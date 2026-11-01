package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.Optional;

/**
 * 非容器界面的悬停处理器，让 R / U / A 生效。
 *
 * <p>JEI 的 {@code ScreenHelper#getClickableIngredientUnderMouse} 对任意 Screen 都会遍历全局
 * 处理器，而 R/U/A 三个快捷键都建立在它之上。本地 8 个样本里，这个无工厂重载从 15.0 到 15.74
 * 一直存在（新增的工厂重载在 15.48 样本里才出现，且默认转调本方法），
 * 所以这个类在所有支持版本上都能工作。
 */
public final class JeiLegacyClickHandler implements IGlobalGuiHandler {

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(double mouseX,
                                                                             double mouseY) {
        BalancerScreen.HoveredKey hovered = JeiHoverKeys.hovered(mouseX, mouseY);
        if (hovered == null) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> typed = JeiHoverKeys.typed(hovered.key());
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        Rect2i area = new Rect2i(hovered.x(), hovered.y(), hovered.width(), hovered.height());
        return Optional.of(new Clickable(typed.get(), area));
    }

    /** 老 API 的可点击配料只有「配料 + 屏幕矩形」，没有工厂可用。 */
    private record Clickable(ITypedIngredient<?> typed, Rect2i area)
            implements IClickableIngredient<Object> {

        @Override
        @SuppressWarnings("unchecked")
        public ITypedIngredient<Object> getTypedIngredient() {
            return (ITypedIngredient<Object>) typed;
        }

        @Override
        public Rect2i getArea() {
            return area;
        }
    }
}
