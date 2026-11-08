package com.jessetech.recipebalancer.mc.jei;

/**
 * 持有 JEI 的 {@code IJeiRuntime}（以 {@code Object} 存）。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>：未安装 JEI 时它也要能安全加载，
 * 常驻代码（主类、界面、core）只允许通过本类与 {@link JeiCapabilities} 判断 JEI 状态。
 */
public final class JeiRuntimeHolder {

    private static volatile Object runtime;

    private JeiRuntimeHolder() {
    }

    public static void set(Object value) {
        runtime = value;
    }

    public static Object get() {
        return runtime;
    }

    /** JEI 已安装（不一定 runtime 就绪）。 */
    public static boolean installed() {
        return JeiCapabilities.installed();
    }

    /** runtime 已就绪，可以调用 JEI API。 */
    public static boolean ready() {
        return runtime != null;
    }
}
