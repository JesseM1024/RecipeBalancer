package com.jessetech.recipebalancer.core.time;

/**
 * 目标产物的速率单位。内部（ScenarioDraft）统一存 **每 tick 的数量**；
 * 展示时按单位换算，写场景 JSON 时再乘 20 回每秒钟（保持 Python schema）。
 */
public enum RateUnit {
    TICK("/t", 1.0),
    SECOND("/s", 20.0),
    MINUTE("/m", 1200.0),
    HOUR("/h", 72000.0);

    private final String label;
    private final double ticksPerUnit;

    RateUnit(String label, double ticksPerUnit) {
        this.label = label;
        this.ticksPerUnit = ticksPerUnit;
    }

    public String label() {
        return label;
    }

    /** 每 tick 数量 → 当前单位数量。 */
    public double fromPerTick(double perTick) {
        return perTick * ticksPerUnit;
    }

    /** 当前单位数量 → 每 tick 数量。 */
    public double toPerTick(double value) {
        return value / ticksPerUnit;
    }

    public RateUnit next() {
        RateUnit[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
