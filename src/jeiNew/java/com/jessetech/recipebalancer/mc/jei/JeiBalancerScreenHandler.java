package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.handlers.IScreenHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 主界面：告诉 JEI 面板位置，并让「悬停物品」能把 JEI 的配方列表聚焦到该物品。
 *
 * <p>用到 {@code IClickableIngredientFactory}（19.3x 才加入），所以放在 <b>jeiNew</b>
 * 源码集里，按更新版本的 API 编译；运行期由主源码集反射加载，老 JEI 上加载失败就退回
 * 只报面板位置的简化实现。
 */
public final class JeiBalancerScreenHandler implements IScreenHandler<BalancerScreen> {

    @Override
    public IGuiProperties apply(BalancerScreen screen) {
        return JeiGuiProperties.of(screen, screen);
    }

    @Override
    public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
            IClickableIngredientFactory factory, BalancerScreen screen,
            double mouseX, double mouseY) {
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        if (hovered == null) {
            return Optional.empty();
        }
        Rect2i area = new Rect2i(hovered.x(), hovered.y(),
                hovered.width(), hovered.height());
        if (IngredientKey.isIngredientKey(hovered.key())) {
            Optional<? extends ITypedIngredient<?>> typed =
                    JeiIngredientRenderer.resolveTyped(hovered.key());
            if (typed.isEmpty()) {
                return Optional.empty();
            }
            return factory.createBuilder(typed.get()).buildWithArea(area);
        }
        ItemStack stack = ItemKeyMapper.stack(hovered.key());
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return factory.createBuilder(stack).buildWithArea(area);
    }
}
