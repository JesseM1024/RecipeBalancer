package com.jessetech.recipebalancer.core.time;

import java.util.List;

/**
 * 配方耗时推断的纯规则表（不依赖 Minecraft / JEI，可直接在 JVM 单测里验证）。
 *
 * <p>规则表独立于读取逻辑的原因：
 * <ul>
 *   <li>「哪些方法名/字段名值得读」「多大的数字算合理」是纯数据，改它不该碰反射代码；</li>
 *   <li>新增一个兼容模组只需在此加一行，不必改 {@code JeiRecipeTime}。</li>
 * </ul>
 */
public final class TimeInferenceRules {

    /**
     * 单次处理耗时的上限：1 小时。
     *
     * <p>超过它的数字几乎不可能是耗时（更像是世界时间、剩余 tick，或与时间无关的计数），
     * 宁可不填也不填错。
     */
    public static final int MAX_TICKS = 72_000;

    /**
     * 通用方法名候选，按语义可靠度从高到低排列。
     *
     * <p>末位的 {@code getDuration}/{@code getTime} 语义较泛（可能读到世界时间、剩余时长），
     * 只有前面的明确命名都落空时才会尝试，且仍要过 {@link #sanitizeTicks} 的值域校验。
     */
    private static final List<String> GENERIC_METHODS = List.of(
            "getProcessingDuration", "getProcessingTime", "getProcessTime",
            "getTicksRequired", "getTotalProcessTime", "getCookingTime",
            "getTotalTime", "getTicks", "getDuration", "getTime");

    /** 字段探测名单，只在开启"反射猜测"时使用；同样按可靠度排序。 */
    private static final List<String> FIELD_NAMES = List.of(
            "processingTime", "processTime", "processtime", "cookingTime",
            "barbecuingTime", "ticksRequired", "totalTime", "duration", "ticks", "time");

    /** 已知模组：包名前缀 + 方法名候选（按顺序尝试）。 */
    public record ModRule(String packagePrefix, List<String> methods) {
    }

    private static final List<ModRule> MOD_RULES = List.of(
            new ModRule("com.simibubi.create",
                    List.of("getProcessingDuration", "getProcessingTime")),
            new ModRule("mekanism",
                    List.of("getTicksRequired")),
            new ModRule("blusunrize.immersiveengineering",
                    List.of("getTotalProcessTime", "getTime")));

    private TimeInferenceRules() {
    }

    public static List<String> genericMethods() {
        return GENERIC_METHODS;
    }

    public static List<String> fieldNames() {
        return FIELD_NAMES;
    }

    /**
     * 已知模组的方法名候选；没有专门规则的包返回空表。
     *
     * <p>用包名前缀而不是精确类名：源模组的类名与包结构在小版本间会漂移，
     * 但根包通常稳定。
     */
    public static List<String> methodsFor(String className) {
        if (className == null) {
            return List.of();
        }
        for (ModRule rule : MOD_RULES) {
            if (className.startsWith(rule.packagePrefix())) {
                return rule.methods();
            }
        }
        return List.of();
    }

    /** 只接受正数且在合理范围内；越界返回 -1（表示"没读到"）。 */
    public static int sanitizeTicks(long ticks) {
        return ticks > 0 && ticks <= MAX_TICKS ? (int) ticks : -1;
    }

    /** 反射只接受这些返回/字段类型，避免把字符串、枚举当成耗时。 */
    public static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || Number.class.isAssignableFrom(type);
    }
}
