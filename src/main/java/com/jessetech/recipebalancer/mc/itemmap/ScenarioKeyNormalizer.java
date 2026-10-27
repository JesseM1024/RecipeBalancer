package com.jessetech.recipebalancer.mc.itemmap;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非物品 ingredient 的身份归一化。
 *
 * <p>JEI 的 key 末尾带显示名；同一化学品在中文/英文环境会得到不同字符串。
 * 求解前把目标与配方中的同身份 key 统一成同一个代表 key，避免出现
 * “目标有定义、配方产物对不上”的假不可行。
 */
public final class ScenarioKeyNormalizer {

    private ScenarioKeyNormalizer() {
    }

    public static Scenario normalize(Scenario scenario) {
        Map<String, String> representatives = new LinkedHashMap<>();
        // 目标优先作为显示名代表，让结果图使用玩家看到的目标名称。
        for (String key : scenario.targetProducts().keySet()) {
            representatives.putIfAbsent(identity(key), key);
        }
        for (Recipe recipe : scenario.recipes()) {
            for (String key : recipe.sourceList().keySet()) {
                representatives.putIfAbsent(identity(key), key);
            }
            for (String key : recipe.productList().keySet()) {
                representatives.putIfAbsent(identity(key), key);
            }
        }

        List<Recipe> recipes = scenario.recipes().stream()
                .map(recipe -> new Recipe(
                        remap(recipe.sourceList(), representatives),
                        remap(recipe.productList(), representatives),
                        recipe.time(), recipe.equType()))
                .toList();
        Map<String, Double> targets = remap(scenario.targetProducts(), representatives);
        return new Scenario(recipes, targets, scenario.options());
    }

    public static String identity(String key) {
        return IngredientKey.identity(key);
    }

    /**
     * 新增目标时优先复用配方中已有 key，保证目标与产物字符串一致。
     * 若目标已存在同身份 key，则复用目标 key，避免重复目标。
     */
    public static String preferredTargetKey(String key, Collection<Recipe> recipes,
                                            Map<String, Double> targets) {
        String wanted = identity(key);
        for (String existing : targets.keySet()) {
            if (identity(existing).equals(wanted)) {
                return existing;
            }
        }
        for (Recipe recipe : recipes) {
            for (String candidate : recipe.sourceList().keySet()) {
                if (identity(candidate).equals(wanted)) {
                    return candidate;
                }
            }
            for (String candidate : recipe.productList().keySet()) {
                if (identity(candidate).equals(wanted)) {
                    return candidate;
                }
            }
        }
        return key;
    }

    private static Map<String, Double> remap(Map<String, Double> source,
                                             Map<String, String> representatives) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            String key = representatives.getOrDefault(
                    identity(entry.getKey()), entry.getKey());
            result.merge(key, entry.getValue(), Double::sum);
        }
        return result;
    }
}
