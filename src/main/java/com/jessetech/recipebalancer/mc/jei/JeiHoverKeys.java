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
 * 「编码主界面鼠标下是哪个物质」→ JEI 配料。
 *
 * <p>9.x 与 10.x 都提供 {@code IGlobalGuiHandler#getIngredientUnderMouse(double, double)}
 * （返回裸配料），R/U/A 由 {@link JeiHoverHandler} 调到这里取配料。
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
            return Optional.ofNullable(runtime.createTypedIngredient(VanillaTypes.ITEM, stack));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
