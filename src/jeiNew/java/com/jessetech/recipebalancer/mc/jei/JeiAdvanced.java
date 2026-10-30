package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.registration.IAdvancedRegistration;

/**
 * 较新 JEI 才有的「配方页按钮」注册。
 *
 * <p>单独成类是为了让老版本 JEI（缺少
 * {@code IRecipeButtonControllerFactory} / {@code addRecipeButtonFactory}）在加载这个类时
 * 抛出的错误只影响这一个功能——调用方用 try/catch 包住即可，其余 JEI 集照常工作。
 */
final class JeiAdvanced {

    private JeiAdvanced() {
    }

    public static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new JeiRecipeButtonFactory());
    }
}
