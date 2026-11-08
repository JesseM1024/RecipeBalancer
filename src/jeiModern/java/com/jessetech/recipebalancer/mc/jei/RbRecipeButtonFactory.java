package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;

/** 给每个「有产物」的 JEI 配方挂一个「编码此配方」按钮。 */
public final class RbRecipeButtonFactory implements IRecipeButtonControllerFactory {

    @Override
    public IIconButtonController createButtonController(IRecipeLayoutDrawable layoutDrawable) {
        if (layoutDrawable == null || !RbRecipeButtonController.hasOutput(layoutDrawable)) {
            return null;
        }
        return new RbRecipeButtonController(layoutDrawable);
    }
}
