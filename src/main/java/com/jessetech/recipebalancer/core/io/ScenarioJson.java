package com.jessetech.recipebalancer.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.SolveOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 场景 JSON 读写；字段名与 Python {@code io.py} 一字不差（跨项目互导的根基，验收 A2）。
 *
 * <p>校验规则逐条对应 {@code validate_recipe_list} / {@code parse_options}：
 * recipes 非空、四个必需键、sourceList 可为空而 productList 非空、数量与耗时为正、
 * options 只允许已知键、空的白名单 = 不限制。
 */
public final class ScenarioJson {

    private static final List<String> REQUIRED_RECIPE_KEYS =
            List.of("sourceList", "productList", "time", "equType");

    private static final Set<String> KNOWN_OPTIONS = Set.of(
            "raw_materials", "initial_inventory", "disabled_recipes",
            "allowed_external_cyc_inputs", "objective",
            "auto_detect_raw", "auto_detect_seed", "auto_bootstrap");

    private ScenarioJson() {
    }

    public static Scenario load(Path path) {
        try {
            return load(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取场景失败: " + path, e);
        }
    }

    public static Scenario load(String json) {
        return parse(json, true);
    }

    /**
     * 宽松读取：用于**自动保存的草稿**。允许 recipes / target_products 为空
     * （用户可能还没填完就按 K 退出），但每条已存在的配方仍按严格规则校验。
     */
    public static Scenario loadDraft(Path path) {
        try {
            return loadDraft(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取草稿失败: " + path, e);
        }
    }

    public static Scenario loadDraft(String json) {
        return parse(json, false);
    }

    private static Scenario parse(String json, boolean completeScenario) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new SchemaException("场景 JSON 解析失败", e);
        }
        if (!root.isJsonObject()) {
            throw new SchemaException("场景必须是 JSON 对象");
        }
        JsonObject object = root.getAsJsonObject();

        JsonElement recipesElement = object.get("recipes");
        JsonElement targetsElement = object.get("target_products");
        if (recipesElement == null) {
            if (completeScenario) {
                throw new SchemaException("场景缺少必需键: recipes");
            }
            recipesElement = new JsonArray();
        }
        if (targetsElement == null) {
            if (completeScenario) {
                throw new SchemaException("场景缺少必需键: target_products");
            }
            targetsElement = new JsonObject();
        }
        if (!targetsElement.isJsonObject()) {
            throw new SchemaException("target_products 必须为对象");
        }
        if (completeScenario && targetsElement.getAsJsonObject().isEmpty()) {
            throw new SchemaException("target_products 不能为空");
        }

        List<Recipe> recipes = parseRecipes(recipesElement, completeScenario);
        Map<String, Double> targets = parseNumberMap(targetsElement.getAsJsonObject(), "target_products");

        JsonObject optionsJson;
        if (!object.has("options")) {
            optionsJson = new JsonObject();
        } else if (object.get("options").isJsonObject()) {
            optionsJson = object.getAsJsonObject("options");
        } else {
            throw new SchemaException("options 必须是 JSON 对象");
        }
        SolveOptions options = parseOptions(optionsJson);

        return new Scenario(recipes, targets, options);
    }

    private static List<Recipe> parseRecipes(JsonElement element, boolean requireNonEmpty) {
        if (!element.isJsonArray()) {
            if (requireNonEmpty) {
                throw new SchemaException("recipes 必须为非空列表");
            }
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            if (requireNonEmpty) {
                throw new SchemaException("recipes 必须为非空列表");
            }
            return List.of();
        }

        List<Recipe> recipes = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new SchemaException("配方 [" + index + "] 必须为 dict");
            }
            JsonObject recipe = array.get(index).getAsJsonObject();
            for (String key : REQUIRED_RECIPE_KEYS) {
                if (!recipe.has(key)) {
                    throw new SchemaException("配方 [" + index + "] 缺少键: " + key);
                }
            }

            Map<String, Double> sources = parseAmounts(recipe.get("sourceList"), index, "sourceList");
            Map<String, Double> products = parseAmounts(recipe.get("productList"), index, "productList");
            if (products.isEmpty()) {
                throw new SchemaException("配方 [" + index + "].productList 不能为空");
            }

            JsonElement time = recipe.get("time");
            double timeSeconds = time != null && isNumber(time) ? time.getAsDouble() : Double.NaN;
            if (!Double.isFinite(timeSeconds) || timeSeconds <= 0) {
                throw new SchemaException("配方 [" + index + "].time 必须为正数");
            }
            JsonElement equType = recipe.get("equType");
            if (!isString(equType) || equType.getAsString().isEmpty()) {
                throw new SchemaException("配方 [" + index + "].equType 必须为非空字符串");
            }

            recipes.add(new Recipe(sources, products, timeSeconds, equType.getAsString()));
        }
        return recipes;
    }

    private static Map<String, Double> parseAmounts(JsonElement element, int index, String key) {
        if (element == null || !element.isJsonObject()) {
            throw new SchemaException("配方 [" + index + "]." + key + " 必须为 dict（物品名 -> 数量）");
        }
        Map<String, Double> amounts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String item = entry.getKey();
            if (item.isEmpty()) {
                throw new SchemaException("配方 [" + index + "]." + key + " 存在非法物品名: " + item);
            }
            double amount = isNumber(entry.getValue())
                    ? entry.getValue().getAsDouble() : Double.NaN;
            if (!Double.isFinite(amount) || amount <= 0) {
                throw new SchemaException("配方 [" + index + "]." + key + "[" + item + "] 数量必须为正数");
            }
            amounts.put(item, amount);
        }
        return amounts;
    }

    private static SolveOptions parseOptions(JsonObject options) {
        Set<String> unknown = new TreeSet<>(options.keySet());
        unknown.removeAll(KNOWN_OPTIONS);
        if (!unknown.isEmpty()) {
            throw new SchemaException("未知 options 键: " + unknown);
        }

        Set<String> rawMaterials = readStringSet(options, "raw_materials");
        Map<String, Double> initialInventory = Map.of();
        if (options.has("initial_inventory")) {
            JsonElement element = options.get("initial_inventory");
            if (!element.isJsonObject()) {
                throw new SchemaException("options.initial_inventory 必须为对象");
            }
            initialInventory = parseNumberMap(element.getAsJsonObject(), "options.initial_inventory");
        }
        Set<Integer> disabled = readIntSet(options, "disabled_recipes");
        Set<String> allowedCyclic = readStringSet(options, "allowed_external_cyc_inputs");
        Objective objective = Objective.MACHINE;
        if (options.has("objective")) {
            JsonElement element = options.get("objective");
            if (!isString(element)) {
                throw new SchemaException("options.objective 必须为字符串");
            }
            objective = Objective.fromJson(element.getAsString());
        }

        boolean autoDetectRaw = readBoolean(options, "auto_detect_raw", true);
        boolean autoDetectSeed = readBoolean(options, "auto_detect_seed", true);
        boolean autoBootstrap = readBoolean(options, "auto_bootstrap", false);

        return new SolveOptions(rawMaterials, initialInventory, disabled, allowedCyclic,
                objective, autoDetectRaw, autoDetectSeed, autoBootstrap);
    }

    private static Set<String> readStringSet(JsonObject options, String key) {
        if (!options.has(key)) {
            return Set.of();
        }
        JsonElement element = options.get(key);
        if (!element.isJsonArray()) {
            throw new SchemaException("options." + key + " 必须为数组");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!isString(value)) {
                throw new SchemaException("options." + key + " 必须为字符串数组");
            }
            values.add(value.getAsString());
        }
        return values;
    }

    private static Set<Integer> readIntSet(JsonObject options, String key) {
        if (!options.has(key)) {
            return Set.of();
        }
        JsonElement element = options.get(key);
        if (!element.isJsonArray()) {
            throw new SchemaException("options." + key + " 必须为数组");
        }
        Set<Integer> values = new LinkedHashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!isNumber(value)) {
                throw new SchemaException("options." + key + " 必须为整数数组");
            }
            values.add(value.getAsInt());
        }
        return values;
    }

    private static boolean readBoolean(JsonObject options, String key, boolean fallback) {
        if (!options.has(key)) {
            return fallback;
        }
        JsonElement element = options.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new SchemaException("options." + key + " 必须为布尔值");
        }
        return element.getAsBoolean();
    }

    private static Map<String, Double> parseNumberMap(JsonObject object, String field) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!isNumber(entry.getValue())) {
                throw new SchemaException(field + "[" + entry.getKey() + "] 必须为数字");
            }
            double value = entry.getValue().getAsDouble();
            if (!Double.isFinite(value)) {
                throw new SchemaException(field + "[" + entry.getKey() + "] 必须为有限双精度数");
            }
            values.put(entry.getKey(), value);
        }
        return values;
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    /** 输出与 Python {@code save_scenario} 同构的 JSON（字段名与顺序一致，集合排序后输出）。 */
    public static String dump(Scenario scenario) {
        JsonObject root = new JsonObject();

        JsonArray recipes = new JsonArray();
        for (Recipe recipe : scenario.recipes()) {
            JsonObject item = new JsonObject();
            item.add("sourceList", numberObject(recipe.sourceList()));
            item.add("productList", numberObject(recipe.productList()));
            item.add("time", new JsonPrimitive(recipe.time()));
            item.add("equType", new JsonPrimitive(recipe.equType()));
            recipes.add(item);
        }
        root.add("recipes", recipes);
        root.add("target_products", numberObject(scenario.targetProducts()));

        SolveOptions options = scenario.options();
        JsonObject optionsJson = new JsonObject();
        optionsJson.add("raw_materials", stringArray(options.rawMaterials()));
        optionsJson.add("initial_inventory", numberObject(options.initialInventory()));
        JsonArray disabled = new JsonArray();
        for (int index : new TreeSet<>(options.disabledRecipes())) {
            disabled.add(index);
        }
        optionsJson.add("disabled_recipes", disabled);
        optionsJson.add("allowed_external_cyc_inputs", stringArray(options.allowedExternalCycInputs()));
        optionsJson.add("objective", new JsonPrimitive(options.objective().jsonName()));
        optionsJson.add("auto_detect_raw", new JsonPrimitive(options.autoDetectRaw()));
        optionsJson.add("auto_detect_seed", new JsonPrimitive(options.autoDetectSeed()));
        optionsJson.add("auto_bootstrap", new JsonPrimitive(options.autoBootstrap()));
        root.add("options", optionsJson);

        return root.toString();
    }

    private static JsonObject numberObject(Map<String, Double> values) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            object.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
        }
        return object;
    }

    private static JsonArray stringArray(Set<String> values) {
        JsonArray array = new JsonArray();
        for (String value : new TreeSet<>(values)) {
            array.add(value);
        }
        return array;
    }
}
