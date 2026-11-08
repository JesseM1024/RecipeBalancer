package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 「编码主界面鼠标下是哪个物质」→ JEI 配料。
 *
 * <p>15.x 全区间都提供 {@code IGlobalGuiHandler#getClickableIngredientUnderMouse(double, double)}，
 * 按住 R/U/A 时由 {@link JeiLegacyClickHandler} 调到这里取配料。
 */
public final class JeiHoverKeys {

    private JeiHoverKeys() {
    }

    /** 当前界面是编码主界面时，返回鼠标下的槽位与其 ingredient key；否则 null。 */
    public static BalancerScreen.HoveredKey hovered(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !(minecraft.screen instanceof BalancerScreen screen)) {
            return null;
        }
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        if (hovered == null || hovered.key() == null || hovered.key().isBlank()) {
            return null;
        }
        return hovered;
    }

    /**
     * 把「鼠标下的槽位」包成 JEI 的可点击配料（容器处理器与全局处理器共用）。
     *
     * <p>15.x 全区间都只有「配料 + 屏幕矩形」这一种老式实现，
     * 更高的工厂重载默认转调它，因此这里不需要按版本分支。
     */
    public static Optional<IClickableIngredient<?>> clickable(BalancerScreen.HoveredKey hovered) {
        if (hovered == null || hovered.key() == null || hovered.key().isBlank()) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> typed = typed(hovered.key());
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        Rect2i area = new Rect2i(hovered.x(), hovered.y(), hovered.width(), hovered.height());
        return Optional.of(new Clickable(typed.get(), area));
    }

    /** 15.x 的可点击配料只有「配料 + 屏幕矩形」。 */
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

    /** 物品走 ItemStack 还原；流体 / 化学品等走模组的 ingredient key 还原。 */
    public static Optional<ITypedIngredient<?>> typed(String key) {
        if (IngredientKey.isIngredientKey(key)) {
            return JeiIngredientRenderer.resolveTyped(key);
        }
        ItemStack stack = ItemKeyMapper.stack(key);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return Optional.empty();
        }
        try {
            // 15.x 的 createTypedIngredient 只有 (type, value) 两参重载
            return runtime.getIngredientManager()
                    .createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
                    .map(typed -> (ITypedIngredient<?>) typed);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
