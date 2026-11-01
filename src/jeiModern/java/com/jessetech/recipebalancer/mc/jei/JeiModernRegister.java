package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.registration.IAdvancedRegistration;

/**
 * 「配方页按钮」官方 API 的注册（本源码集用 JEI 15.74 编译，不进主源码集的类路径）。
 *
 * <p>这套 API 在本地样本里 15.21.0.148 不存在、15.48.0.179 存在，精确引入版本未验证
 * （离线环境拿不到 15.22–15.47 的 jar），所以不能按版本号判断能不能用。
 *
 * <p>为什么单独一个类：老版本 JEI 没有 {@code IRecipeButtonControllerFactory} /
 * {@code addRecipeButtonFactory}，加载本类会抛 {@code NoClassDefFoundError}。
 * 由 {@link RecipeBalancerJeiPlugin} 反射调用并用 try/catch 包住，
 * 这样缺类只影响按钮这一个功能，其余 JEI 集成照常工作。
 */
public final class JeiModernRegister {

    private JeiModernRegister() {
    }

    public static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new RbRecipeButtonFactory());
    }
}
