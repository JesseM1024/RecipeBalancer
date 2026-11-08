package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.RecipePattern;

/**
 * 编码区右键「自动撰写」需要的 JEI 能力；由 RecipeBalancerJeiPlugin 在运行时安装，
 * 未安装 JEI 时 hook 为 null（界面层不直接引用 JEI 类）。
 */
public final class AutoRecipeHooks {

    public interface Hook {
        /** 生产该物质的配方总数；-1 表示查不到（JEI 里没有该原料）。 */
        int producerCount(String key);

        /** 打开 JEI 里该物质的配方页。 */
        void showRecipes(String key);

        /** 该物质唯一配方转换成的样板；配方不唯一或失败返回 null。 */
        RecipePattern singlePattern(String key);
    }

    private static volatile Hook hook;

    private AutoRecipeHooks() {
    }

    public static void set(Hook value) {
        hook = value;
    }

    public static Hook get() {
        return hook;
    }
}
