package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;

import net.minecraft.client.renderer.Rect2i;

import java.util.Optional;

/**
 * 带 {@code IClickableIngredientFactory} 的悬停处理器（JEI 19.2x 起）。
 *
 * <p>关键点：19.25 的 {@code ScreenHelper#getPluginsIngredientUnderMouse} 调用的是
 * {@code getClickableIngredientUnderMouse(factory, mouseX, mouseY)}，无工厂的老重载已经
 * 不被调用；只实现老重载时 R/U/A 依旧无效。
 *
 * <p>同理，{@code IScreenHandler#getClickableIngredientUnderMouse} 在这版还不存在，
 * 所以不能指望 {@code JeiBalancerScreenHandler}；两件事在注册时分别判定（见插件）。
 */
public final class JeiGlobalClickHandler implements IGlobalGuiHandler {

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
            IClickableIngredientFactory factory, double mouseX, double mouseY) {
        BalancerScreen.HoveredKey hovered = JeiHoverKeys.hovered(mouseX, mouseY);
        if (hovered == null) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> typed = JeiHoverKeys.typed(hovered.key());
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        Rect2i area = new Rect2i(hovered.x(), hovered.y(), hovered.width(), hovered.height());
        return build(factory, typed.get(), area);
    }

    /** 通配符配料没法让 {@code createBuilder} 推导 T，这里统一退化到原生类型。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<IClickableIngredient<?>> build(IClickableIngredientFactory factory,
                                                           ITypedIngredient<?> typed,
                                                           Rect2i area) {
        Optional<IClickableIngredient<?>> built = (Optional<IClickableIngredient<?>>) (Optional<?>)
                factory.createBuilder((ITypedIngredient) typed).buildWithArea(area);
        return built;
    }
}
