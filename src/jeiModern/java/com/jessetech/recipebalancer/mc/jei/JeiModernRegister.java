package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.registration.IAdvancedRegistration;

/**
 * JEI 10.x 专属：注册官方「配方页按钮」。
 *
 * <p>本类编译于 {@code src/jeiModern} 源码集（用 JEI 10.81 编译），
 * 主源码集只用字符串 + 反射引用它；JEI 9.x 上没有 {@code addRecipeButtonFactory}，
 * 因此永远不会被加载。
 */
public final class JeiModernRegister {

    private JeiModernRegister() {
    }

    public static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new RbRecipeButtonFactory());
    }
}
