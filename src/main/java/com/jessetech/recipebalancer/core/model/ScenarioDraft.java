package com.jessetech.recipebalancer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 可编辑场景草稿：界面层唯一的可变状态（契约 C6：UI 只做绘制/事件，编辑语义在这里）。
 *
 * <p>放在 core/ 的理由：删除配方会牵动 {@link SolveOptions#disabledRecipes()} 的下标，
 * 这类状态迁移必须能脱离游戏单测；界面只负责把字段读出来调用本类。
 */
public final class ScenarioDraft {

    /** 场景 JSON/Python 的 target_products 是每秒；草稿内部统一每 tick。 */
    private static final double TICKS_PER_SECOND = 20.0;

    private final List<Recipe> recipes = new ArrayList<>();
    /** 目标产物数量：每 tick 的产出量（不是每秒）。 */
    private final Map<String, Double> targets = new LinkedHashMap<>();
    private SolveOptions options = SolveOptions.defaults();

    public static ScenarioDraft blank() {
        return new ScenarioDraft();
    }

    public static ScenarioDraft from(Scenario scenario) {
        ScenarioDraft draft = new ScenarioDraft();
        draft.loadFrom(scenario);
        return draft;
    }

    public void loadFrom(Scenario scenario) {
        recipes.clear();
        recipes.addAll(scenario.recipes());
        targets.clear();
        scenario.targetProducts().forEach((item, perSecond) ->
                targets.put(item, perSecond / TICKS_PER_SECOND));
        options = scenario.options();
    }

    public void clear() {
        recipes.clear();
        targets.clear();
        options = SolveOptions.defaults();
    }

    public List<Recipe> recipes() {
        return Collections.unmodifiableList(recipes);
    }

    public Map<String, Double> targets() {
        return Collections.unmodifiableMap(targets);
    }

    public SolveOptions options() {
        return options;
    }

    public void setOptions(SolveOptions options) {
        this.options = options;
    }

    public int recipeCount() {
        return recipes.size();
    }

    public void addRecipe(Recipe recipe) {
        recipes.add(recipe);
    }

    public void replaceRecipe(int index, Recipe recipe) {
        recipes.set(index, recipe);
    }

    /** 删除配方并**同步平移禁用下标**（否则禁用会错位到别的配方）。 */
    public void removeRecipe(int index) {
        recipes.remove(index);
        Set<Integer> shifted = new TreeSet<>();
        for (int disabled : options.disabledRecipes()) {
            if (disabled == index) {
                continue;
            }
            shifted.add(disabled > index ? disabled - 1 : disabled);
        }
        options = withDisabled(shifted);
    }

    public boolean isRecipeEnabled(int index) {
        return !options.disabledRecipes().contains(index);
    }

    public void toggleRecipeEnabled(int index) {
        Set<Integer> disabled = new LinkedHashSet<>(options.disabledRecipes());
        if (!disabled.remove(index)) {
            disabled.add(index);
        }
        options = withDisabled(disabled);
    }

    public void putTarget(String item, double amount) {
        if (item == null || item.isBlank()) {
            throw new IllegalArgumentException("目标物品不能为空");
        }
        if (!(amount > 0) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("目标数量必须为正数");
        }
        targets.put(item.trim(), amount);
    }

    public void removeTarget(String item) {
        targets.remove(item);
    }

    public void toggleObjective() {
        setObjective(options.objective() == Objective.MACHINE ? Objective.RECIPES : Objective.MACHINE);
    }

    public void setObjective(Objective objective) {
        options = new SolveOptions(options.rawMaterials(), options.initialInventory(),
                options.disabledRecipes(), options.allowedExternalCycInputs(), objective,
                options.autoDetectRaw(), options.autoDetectSeed(), options.autoBootstrap());
    }

    public void toggleAutoDetectRaw() {
        options = new SolveOptions(options.rawMaterials(), options.initialInventory(),
                options.disabledRecipes(), options.allowedExternalCycInputs(), options.objective(),
                !options.autoDetectRaw(), options.autoDetectSeed(), options.autoBootstrap());
    }

    public void toggleAutoDetectSeed() {
        options = new SolveOptions(options.rawMaterials(), options.initialInventory(),
                options.disabledRecipes(), options.allowedExternalCycInputs(), options.objective(),
                options.autoDetectRaw(), !options.autoDetectSeed(), options.autoBootstrap());
    }

    public void toggleAutoBootstrap() {
        options = new SolveOptions(options.rawMaterials(), options.initialInventory(),
                options.disabledRecipes(), options.allowedExternalCycInputs(), options.objective(),
                options.autoDetectRaw(), options.autoDetectSeed(), !options.autoBootstrap());
    }

    private SolveOptions withDisabled(Set<Integer> disabled) {
        return new SolveOptions(options.rawMaterials(), options.initialInventory(), disabled,
                options.allowedExternalCycInputs(), options.objective(),
                options.autoDetectRaw(), options.autoDetectSeed(), options.autoBootstrap());
    }

    public Scenario toScenario() {
        Map<String, Double> perSecond = new LinkedHashMap<>();
        targets.forEach((item, perTick) -> perSecond.put(item, perTick * TICKS_PER_SECOND));
        return new Scenario(recipes, perSecond, options);
    }
}
