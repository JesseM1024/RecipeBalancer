package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 「配方库里已经有配方」的槽位判定（与 1.20.1 / main 同一口径）。
 *
 * <p>只看原料与产物槽位：槽位的任意一种候选配料能由当前工作区的配方库产出，
 * 就算「可自产」。颜色与绘制在 {@link JeiHighlightOverlay}（与 1.20.1 同一组配色）。
 */
public final class JeiCraftableSlots {

    /** 蓝色高亮（与 1.20.1 严格一致）。 */
    public static final int HIGHLIGHT_FILL = 0x40004688;
    public static final int HIGHLIGHT_BORDER = 0x9F004E96;

    private JeiCraftableSlots() {
    }

    /** 该槽位是否「配方库能自产」：任意候选配料命中配方库产物即可。 */
    public static boolean craftable(List<ITypedIngredient<?>> ingredients, ProductIndex index) {
        if (index == null || ingredients == null || ingredients.isEmpty()) {
            return false;
        }
        for (ITypedIngredient<?> ingredient : ingredients) {
            try {
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                if (mapped != null && index.produces(mapped.key())) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 某些自定义配料解析不了，当作不可自产
            }
        }
        return false;
    }

    /** 只关心原料与产物槽位（与 1.20.1 的 {@code find} 一致）。 */
    public static boolean isInputOrOutput(RecipeIngredientRole role) {
        return role == RecipeIngredientRole.INPUT || role == RecipeIngredientRole.OUTPUT;
    }

    /** 从 {@link JeiSlots} 的结果里挑出可自产、且有矩形的槽位。 */
    public static List<JeiSlots.Slot> filter(List<JeiSlots.Slot> slots, ProductIndex index) {
        List<JeiSlots.Slot> result = new ArrayList<>();
        for (JeiSlots.Slot slot : slots) {
            if (!slot.hasRect() || !isInputOrOutput(slot.role())) {
                continue;
            }
            if (craftable(slot.ingredients(), index)) {
                result.add(slot);
            }
        }
        return result;
    }
}
