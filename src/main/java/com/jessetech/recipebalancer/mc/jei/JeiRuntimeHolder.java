package com.jessetech.recipebalancer.mc.jei;

/**
 * 不含任何 JEI 类型的运行时引用（避免未装 JEI 时加载到 JEI 类）。
 * 由 {@link RecipeBalancerJeiPlugin} 写入，{@link JeiScreenSupport} 反射使用。
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
}
