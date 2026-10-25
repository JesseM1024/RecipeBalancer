package com.jessetech.recipebalancer.core.detect;

import com.jessetech.recipebalancer.core.model.Recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单配方自催化检测（Python {@code auto_detect_seed_inventory}）：A -> 2A。
 *
 * <p>物品同时出现在某配方的 sourceList 与 productList 时，
 * seed = 该物品在各配方消耗量的最小值。不区分净增（1→2）与净耗（5→2）（P2-L 忠实保留）。
 */
public final class SeedDetector {

    private SeedDetector() {
    }

    public static Map<String, Double> detect(List<Recipe> recipes) {
        Map<String, Double> seed = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            for (String item : recipe.productList().keySet()) {
                if (!recipe.consumes(item)) {
                    continue;
                }
                double needed = recipe.sourceList().get(item);
                Double previous = seed.get(item);
                if (previous == null) {
                    seed.put(item, needed);
                } else {
                    seed.put(item, Math.min(previous, needed));
                }
            }
        }
        return seed;
    }
}
