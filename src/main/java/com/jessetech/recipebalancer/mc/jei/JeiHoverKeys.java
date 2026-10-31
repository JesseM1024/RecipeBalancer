package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 「编码主界面鼠标下是谁」→ JEI 配料，供老/新两套全局处理器共用。
 *
 * <p>老版本与中间版本的 {@code IGlobalGuiHandler} 有两个重载：
 * {@code getClickableIngredientUnderMouse(double, double)} 与
 * {@code getClickableIngredientUnderMouse(IClickableIngredientFactory, double, double)}。
 * 19.25 的 {@code ScreenHelper} 只调用后者，所以两边都要能拿到同一份换算逻辑。
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

    /** 物品走 ItemStack 还原；流体/化学品等走模组的 ingredient key 还原。 */
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
            return runtime.getIngredientManager()
                    .createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
                    .map(typed -> (ITypedIngredient<?>) typed);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
