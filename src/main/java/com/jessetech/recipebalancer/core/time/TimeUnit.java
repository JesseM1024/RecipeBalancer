package com.jessetech.recipebalancer.core.time;

/** 配方耗时单位：内部统一用 tick（1 秒 = 20 t）。 */
public enum TimeUnit {
    TICK("t", 1.0),
    SECOND("秒", 20.0),
    MINUTE("分", 1200.0),
    HOUR("时", 72000.0);

    private final String label;
    private final double ticksPerUnit;

    TimeUnit(String label, double ticksPerUnit) {
        this.label = label;
        this.ticksPerUnit = ticksPerUnit;
    }

    public String label() {
        return label;
    }

    public double ticksPerUnit() {
        return ticksPerUnit;
    }

    public double toTicks(double value) {
        return value * ticksPerUnit;
    }

    public double fromTicks(double ticks) {
        return ticks / ticksPerUnit;
    }

    public TimeUnit next() {
        TimeUnit[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 展示用：选一个让数值不太小/不太大的单位。 */
    public static TimeUnit bestFor(double ticks) {
        if (ticks >= HOUR.ticksPerUnit) {
            return HOUR;
        }
        if (ticks >= MINUTE.ticksPerUnit) {
            return MINUTE;
        }
        if (ticks >= SECOND.ticksPerUnit) {
            return SECOND;
        }
        return TICK;
    }
}
