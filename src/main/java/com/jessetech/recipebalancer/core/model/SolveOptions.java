package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 求解选项，与 Python {@code solve_production} 的关键字参数一一对应。
 *
 * <p>{@code allowedExternalCycInputs} 为空集表示**不限制**（不是"全部禁止"）——
 * 这是 Python {@code detection.detect_deficit_items} 的既有语义（P0-D 相关）。
 */
public final class SolveOptions {

    private final Set<String> rawMaterials;
    private final Map<String, Double> initialInventory;
    private final Set<Integer> disabledRecipes;
    private final Set<String> allowedExternalCycInputs;
    private final Objective objective;
    private final boolean autoDetectRaw;
    private final boolean autoDetectSeed;
    private final boolean autoBootstrap;

    public SolveOptions(Set<String> rawMaterials,
                        Map<String, Double> initialInventory,
                        Set<Integer> disabledRecipes,
                        Set<String> allowedExternalCycInputs,
                        Objective objective,
                        boolean autoDetectRaw,
                        boolean autoDetectSeed,
                        boolean autoBootstrap) {
        this.rawMaterials = Collections.unmodifiableSet(new LinkedHashSet<>(rawMaterials));
        this.initialInventory = Collections.unmodifiableMap(new LinkedHashMap<>(initialInventory));
        this.disabledRecipes = Collections.unmodifiableSet(new LinkedHashSet<>(disabledRecipes));
        this.allowedExternalCycInputs =
                Collections.unmodifiableSet(new LinkedHashSet<>(allowedExternalCycInputs));
        if (objective == null) {
            throw new IllegalArgumentException("objective 不能为 null");
        }
        this.objective = objective;
        this.autoDetectRaw = autoDetectRaw;
        this.autoDetectSeed = autoDetectSeed;
        this.autoBootstrap = autoBootstrap;
    }

    /** 与 Python 默认参数一致：不声明原料/库存/禁用，machine 目标，三个自动开关默认开。 */
    public static SolveOptions defaults() {
        return new SolveOptions(
                Collections.<String>emptySet(),
                Collections.<String, Double>emptyMap(),
                Collections.<Integer>emptySet(),
                Collections.<String>emptySet(),
                Objective.MACHINE, true, true, false);
    }

    public Set<String> rawMaterials() {
        return rawMaterials;
    }

    public Map<String, Double> initialInventory() {
        return initialInventory;
    }

    public Set<Integer> disabledRecipes() {
        return disabledRecipes;
    }

    public Set<String> allowedExternalCycInputs() {
        return allowedExternalCycInputs;
    }

    public Objective objective() {
        return objective;
    }

    public boolean autoDetectRaw() {
        return autoDetectRaw;
    }

    public boolean autoDetectSeed() {
        return autoDetectSeed;
    }

    public boolean autoBootstrap() {
        return autoBootstrap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SolveOptions)) {
            return false;
        }
        SolveOptions other = (SolveOptions) o;
        return autoDetectRaw == other.autoDetectRaw
                && autoDetectSeed == other.autoDetectSeed
                && autoBootstrap == other.autoBootstrap
                && rawMaterials.equals(other.rawMaterials)
                && initialInventory.equals(other.initialInventory)
                && disabledRecipes.equals(other.disabledRecipes)
                && allowedExternalCycInputs.equals(other.allowedExternalCycInputs)
                && objective == other.objective;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawMaterials, initialInventory, disabledRecipes,
                allowedExternalCycInputs, objective, autoDetectRaw, autoDetectSeed, autoBootstrap);
    }

    @Override
    public String toString() {
        return "SolveOptions[rawMaterials=" + rawMaterials + ", initialInventory=" + initialInventory
                + ", disabledRecipes=" + disabledRecipes
                + ", allowedExternalCycInputs=" + allowedExternalCycInputs
                + ", objective=" + objective + ", autoDetectRaw=" + autoDetectRaw
                + ", autoDetectSeed=" + autoDetectSeed + ", autoBootstrap=" + autoBootstrap + "]";
    }
}
