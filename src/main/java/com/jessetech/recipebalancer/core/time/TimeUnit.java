package com.jessetech.recipebalancer.core.time;

/**
 * 配方耗时单位：内部统一用 tick（1 秒 = 20 t）。
 *
 * <p>展示拆成两个字段：{@link #symbol()} 是语言无关符号（t/s/m/h），
 * 直接拼进 {@link TimeExpression#formatTicks(double)} 的输出；
 * {@link #zhLabel()} 是中文源文本，仅供 {@code Lang.t} 查语言文件用。
 */
public enum TimeUnit {
    TICK("t", "t", 1.0),
    SECOND("s", "秒", 20.0),
    MINUTE("m", "分", 1200.0),
    HOUR("h", "时", 72000.0);

    private final String symbol;
    private final String zhLabel;
    private final double ticksPerUnit;

    TimeUnit(String symbol, String zhLabel, double ticksPerUnit) {
        this.symbol = symbol;
        this.zhLabel = zhLabel;
        this.ticksPerUnit = ticksPerUnit;
    }

    /** 语言无关符号：t / s / m / h。 */
    public String symbol() {
        return symbol;
    }

    /** 中文源文本（语言键）：t / 秒 / 分 / 时。 */
    public String zhLabel() {
        return zhLabel;
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
