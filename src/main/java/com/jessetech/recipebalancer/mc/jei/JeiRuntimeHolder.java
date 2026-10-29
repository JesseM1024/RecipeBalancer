package com.jessetech.recipebalancer.mc.jei;

import net.neoforged.fml.ModList;

/**
 * 不含任何 JEI 类型的运行时引用（避免未装 JEI 时加载到 JEI 类）。
 * 由 {@link RecipeBalancerJeiPlugin} 写入，{@link JeiScreenSupport} 反射使用。
 *
 * <p>同时提供「JEI 是否装了 / 是否就绪」的判断：只用 {@link ModList} 与普通对象引用，
 * 未装 JEI 时也能安全调用，供界面在按下 JEI 相关按钮前先拦下并提示。
 */
public final class JeiRuntimeHolder {

    private static final String JEI_MOD_ID = "jei";

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
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(JEI_MOD_ID);
        } catch (RuntimeException | LinkageError e) {
            // 非游戏环境（纯 JVM 测试）或 ModList 尚未初始化
            return false;
        }
    }

    /** JEI 是否可用：已安装且插件已拿到 {@code IJeiRuntime}。 */
    public static boolean ready() {
        return runtime != null;
    }
}
