package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.ingredients.ITypedIngredient;

/**
 * 非容器界面的悬停处理器，让 R / U / A 生效。
 *
 * <p>JEI 的 {@code GuiScreenHelper} 对任意 Screen 都会遍历全局处理器，而 R/U/A 三个快捷键
 * 都建立在「鼠标下是什么配料」之上。9.x 与 10.x 的这个重载都返回<b>裸配料</b>
 * （{@code ItemStack} / {@code FluidStack} / 化学品对象），由 JEI 自己包成可点击配料；
 * 10.x 新增的工厂重载默认也转调这里，所以一份实现覆盖全区间。
 */
public final class JeiHoverHandler implements IGlobalGuiHandler {

    @Override
    public Object getIngredientUnderMouse(double mouseX, double mouseY) {
        BalancerScreen.HoveredKey hovered = JeiHoverKeys.hovered(mouseX, mouseY);
        if (hovered == null) {
            return null;
        }
        return JeiHoverKeys.typed(hovered.key())
                .map(ITypedIngredient::getIngredient)
                .orElse(null);
    }
}
