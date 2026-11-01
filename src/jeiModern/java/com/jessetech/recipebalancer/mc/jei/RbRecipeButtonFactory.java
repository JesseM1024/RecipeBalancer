package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;

/**
 * 每个配方 layout 生成一个「编码此配方」按钮控制器（仅在 JEI 提供该组按钮 API 时加载）。
 *
 * <p>按钮的位置、底板、悬停反馈全部交给 JEI 处理（与 JEI 自己的书签/转移按钮同一列），
 * 所以不会再出现「装饰器自绘按钮被 JEI 按钮盖住」的问题。
 */
final class RbRecipeButtonFactory implements IRecipeButtonControllerFactory {

    @Override
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        return new RbRecipeButtonController(layout);
    }
}
