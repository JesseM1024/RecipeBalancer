package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.runtime.IClickableIngredient;

import java.util.Optional;

/**
 * 全局悬停处理器，让 R / U / A 生效。
 *
 * <p>JEI 的 {@code ScreenHelper#getClickableIngredientUnderMouse} 对任意 Screen 都会遍历全局
 * 处理器，而 R/U/A 三个快捷键都建立在它之上。只实现无工厂重载就够，逐 jar 核对过的事实：
 * <ul>
 *   <li>19.8.4 – 19.22.x：{@code IGlobalGuiHandler} 只有本方法，{@code ScreenHelper} 也只调它；</li>
 *   <li>19.23.0 起：新增工厂重载，但默认实现就是
 *       {@code return getClickableIngredientUnderMouse(mouseX, mouseY);}，
 *       {@code ScreenHelper} 改调工厂重载后仍会转调到这里。</li>
 * </ul>
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
