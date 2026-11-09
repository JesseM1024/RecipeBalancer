package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import net.minecraft.item.ItemStack;

/**
 * 悬停槽位 → JEI 配料（1.16.5 / JEI 7.x）。
 *
 * <p>7.x 的 {@code IGuiContainerHandler#getIngredientUnderMouse} 返回的是
 * <b>裸配料对象</b>（{@code ItemStack} / {@code FluidStack} / …），
 * 不再是 9.x 的 {@code IClickableIngredient}。所以这里把界面给的 key
 * 还原成 JEI 认识的对象即可：
 * <ul>
 *   <li>物品：{@link ItemKeyMapper#stack(String)}；</li>
 *   <li>流体/化学品等：交给 {@link JeiIngredientRenderer} 从 JEI 配料表里反查。</li>
 * </ul>
 */
public final class JeiHoverKeys {

    private JeiHoverKeys() {
    }

    /** 把 ingredient key 还原成 JEI 能识别的裸配料对象；解析不了返回 null。 */
    public static Object ingredient(String key) {
        if (key == null || J8.isBlank(key)) {
            return null;
        }
        if (!IngredientKey.isIngredientKey(key)) {
            ItemStack stack = ItemKeyMapper.stack(key);
            return stack.isEmpty() ? null : stack;
        }
        return JeiIngredientRenderer.resolveIngredient(key);
    }

    /** 当前语言下的显示名（用于提示/日志）；解析不了返回 null。 */
    public static String displayName(String key) {
        return JeiIngredientRenderer.displayNameOf(key);
    }

    /** 取界面当前的悬停槽位（没有则 null）。 */
    public static BalancerScreen.HoveredKey hovered(BalancerScreen screen, double mouseX,
                                                    double mouseY) {
        return screen == null ? null : screen.jeiHoveredKey(mouseX, mouseY);
    }
}
