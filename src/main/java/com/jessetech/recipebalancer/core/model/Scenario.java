package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景 = Python 场景 JSON 的内存形态（{@code io.py} schema 同构）。
 *
 * <p>{@code targetProducts} 保持 JSON 顺序；数值查找不依赖顺序。
 */
public record Scenario(List<Recipe> recipes,
                       Map<String, Double> targetProducts,
                       SolveOptions options) {

    public Scenario {
        recipes = List.copyOf(recipes);
        targetProducts = Collections.unmodifiableMap(new LinkedHashMap<>(targetProducts));
        if (options == null) {
            options = SolveOptions.defaults();
        }
    }
}
