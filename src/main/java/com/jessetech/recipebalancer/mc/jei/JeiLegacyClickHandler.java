package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;

import net.minecraft.client.renderer.Rect2i;

import java.util.Optional;

/**
 * 最老的一档 JEI（只有无工厂重载）用的悬停处理器，让 R / U / A 生效。
 *
 * <p>JEI 的 {@code ScreenHelper#getClickableIngredientUnderMouse} 对任意 Screen 都会遍历全局
 * 处理器，而 R/U/A 三个快捷键都建立在它之上（{@code GuiContainerWrapper} →
 * {@code CombinedRecipeFocusSource}）。
 *
 * <p>19.25 起 {@code ScreenHelper} 改用带 {@code IClickableIngredientFactory} 的重载，
 * 那条路走 {@code JeiGlobalClickHandler}（jeiNew 源码集）；本类只在两样都没有时兜底。
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
