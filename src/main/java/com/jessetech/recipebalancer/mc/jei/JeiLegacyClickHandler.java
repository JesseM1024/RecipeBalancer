package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;

import net.minecraft.client.renderer.Rect2i;

import java.util.Optional;

/**
 * 非容器界面的悬停处理器，让 R / U / A 生效（覆盖 JEI 19.8.4 – 19.38.x）。
 *
 * <p>JEI 的 {@code ScreenHelper#getClickableIngredientUnderMouse} 对任意 Screen 都会遍历全局
 * 处理器，而 R/U/A 三个快捷键都建立在它之上（{@code GuiContainerWrapper} →
 * {@code CombinedRecipeFocusSource}）。
 *
 * <p>只实现无工厂重载就够，逐 jar 核对过的事实：
 * <ul>
 *   <li>19.8.4 – 19.22.x：{@code IGlobalGuiHandler} 只有本方法，{@code ScreenHelper} 也只调它；</li>
 *   <li>19.23.0 起：新增工厂重载，但默认实现就是
 *       {@code return getClickableIngredientUnderMouse(mouseX, mouseY);}，
 *       {@code ScreenHelper} 改调工厂重载后仍会转调到这里；</li>
 *   <li>19.39.0 起：{@code IScreenHandler} 自己有了 {@code getClickableIngredientUnderMouse}，
 *       改走 jeiNew 的 {@code JeiBalancerScreenHandler}，不再注册本类。</li>
 * </ul>
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
