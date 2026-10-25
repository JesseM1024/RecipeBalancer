package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 求解选项，与 Python {@code solve_production} 的关键字参数一一对应。
 *
 * <p>{@code allowedExternalCycInputs} 为空集表示**不限制**（不是"全部禁止"）——
 * 这是 Python {@code detection.detect_deficit_items} 的既有语义（P0-D 相关）。
 */
public record SolveOptions(Set<String> rawMaterials,
                           Map<String, Double> initialInventory,
                           Set<Integer> disabledRecipes,
                           Set<String> allowedExternalCycInputs,
                           Objective objective,
                           boolean autoDetectRaw,
                           boolean autoDetectSeed,
                           boolean autoBootstrap) {

    public SolveOptions {
        rawMaterials = Collections.unmodifiableSet(new LinkedHashSet<>(rawMaterials));
        initialInventory = Collections.unmodifiableMap(new LinkedHashMap<>(initialInventory));
        disabledRecipes = Collections.unmodifiableSet(new LinkedHashSet<>(disabledRecipes));
        allowedExternalCycInputs = Collections.unmodifiableSet(new LinkedHashSet<>(allowedExternalCycInputs));
        if (objective == null) {
            throw new IllegalArgumentException("objective 不能为 null");
        }
    }

    /** 与 Python 默认参数一致：不声明原料/库存/禁用，machine 目标，三个自动开关默认开。 */
    public static SolveOptions defaults() {
        return new SolveOptions(Set.of(), Map.of(), Set.of(), Set.of(),
                Objective.MACHINE, true, true, false);
    }
}
