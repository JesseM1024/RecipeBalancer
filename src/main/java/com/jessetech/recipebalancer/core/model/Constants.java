package com.jessetech.recipebalancer.core.model;

/**
 * 数值常量，逐字面量对应 Python {@code models.py}（架构契约 C7：禁止"顺手优化"）。
 *
 * <p>任何常量偏移都会使金标准对拍失真；调整必须作为独立变更立项并重跑全部对拍。
 */
public final class Constants {

    /** 外部输入 big-M 惩罚（P0-C：尺度敏感）。 */
    public static final double EXTERNAL_PENALTY = 1e6;

    /** 原料检测：矩阵行内正项阈值。 */
    public static final double EPS_RAW = 1e-12;

    /** 配方速率视为 0 的阈值。 */
    public static final double EPS_RATE = 1e-10;

    /** 循环亏损 / bootstrap 模拟阈值。 */
    public static final double EPS_DEFICIT = 1e-9;

    /** 外部输入 / 净物流阈值。 */
    public static final double EPS_FLOW = 1e-8;

    /** 精确求最小反馈顶点集的规模上限（超过则贪心回退）。 */
    public static final int MAX_EXACT_FVS = 12;

    private Constants() {
    }
}
