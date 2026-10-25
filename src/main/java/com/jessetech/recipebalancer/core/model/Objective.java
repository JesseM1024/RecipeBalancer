package com.jessetech.recipebalancer.core.model;

/** 求解目标，对应 Python {@code objective="machine"|"recipes"}。 */
public enum Objective {
    /** 最小化机器并行总量（权重 = 单次耗时）。 */
    MACHINE("machine"),
    /** 最小化配方活动总量（权重 = 1）。 */
    RECIPES("recipes");

    private final String jsonName;

    Objective(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * @throws IllegalArgumentException 未知取值（与 Python 抛 ValueError 的语义一致）
     */
    public static Objective fromJson(String value) {
        for (Objective objective : values()) {
            if (objective.jsonName.equals(value)) {
                return objective;
            }
        }
        throw new IllegalArgumentException("未知 objective: " + value);
    }
}
