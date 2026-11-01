package com.jessetech.recipebalancer.mc.jei;

/**
 * 持有 JEI 的 {@code IJeiRuntime}（用 {@link Object} 存，避免未装 JEI 时加载到 JEI 类）。
 *
 * <p>由 {@link RecipeBalancerJeiPlugin} 在 {@code onRuntimeAvailable} 写入；
 * 只有 {@code mc.jei} 包内「JEI 已安装才会加载」的类才允许把它转成 {@code IJeiRuntime}。
 * 界面/主类等常驻代码只能通过 {@link #installed()} / {@link #ready()} 判断状态。
 */
public final class JeiRuntimeHolder {

    private static volatile Object runtime;

    private JeiRuntimeHolder() {
    }

    static void set(Object value) {
        runtime = value;
    }

    static Object get() {
        return runtime;
    }

    /** JEI 是否已安装（不代表 runtime 已就绪）。 */
    public static boolean installed() {
        return JeiCapabilities.installed();
    }

    /** JEI 是否可用：已安装且插件已拿到 runtime。 */
    public static boolean ready() {
        return runtime != null;
    }
}
