package com.jessetech.recipebalancer.core.time;

/**
 * 配方耗时单位：内部统一用 tick（1 秒 = 20 t）。
 *
 * <p>{@link #label()} 返回语言无关的符号（t/s/m/h），用于拼装"200 t"这类展示文本；
 * 界面按钮上的可读名称用 {@link #zhLabel()} 作为语言键，交给 {@code Lang} 翻译，
 * 这样英文环境下不会漏出中文"秒/分/时"。
 */
public enum TimeUnit {
    TICK("t", "t", 1.0),
    SECOND("s", "秒", 20.0),
    MINUTE("m", "分", 1200.0),
    HOUR("h", "时", 72000.0);

    private final String symbol;
    /** 中文源文本，同时充当语言文件的键。 */
    private final String zhLabel;
    private final double ticksPerUnit;

    TimeUnit(String symbol, String zhLabel, double ticksPerUnit) {
        this.symbol = symbol;
        this.zhLabel = zhLabel;
        this.ticksPerUnit = ticksPerUnit;
    }

    /** 语言无关符号，用于格式化后的数字后缀。 */
    public String label() {
        return symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** 界面按钮用的中文源文本（语言键）。 */
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
