package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.runtime.IClickableIngredient;

import java.util.Optional;

/**
 * 全局悬停处理器，让 R / U / A 生效。
 *
 * <p>JEI 的 {@code ScreenHelper#getClickableIngredientUnderMouse} 对任意 Screen 都会遍历全局
 * 处理器，而 R/U/A 三个快捷键都建立在它之上。本地 8 个样本里，这个无工厂重载从 15.0 到 15.74
 * 一直存在（新增的工厂重载在 15.48 样本里才出现，且默认转调本方法），
 * 所以这个类在所有支持版本上都能工作。
 *
 * <p>一级界面容器化后主路径换成了 {@link RbContainerGuiHandler}（JEI 对容器界面开放的回调），
 * 本类保留为兜底：若某个 JEI 构建裁剪/改动了容器侧回调，R/U/A 依然可用。
 * 同一个界面上被查询两次是无害的 —— JEI 取第一个命中的结果。
 */
public final class JeiLegacyClickHandler implements IGlobalGuiHandler {

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(double mouseX,
                                                                             double mouseY) {
        return JeiHoverKeys.clickable(JeiHoverKeys.hovered(mouseX, mouseY));
    }
}
