package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 找出「配方库里已经有配方」的槽位。
 *
 * <p>判定口径与 main 分支一致：只看原料与产物槽位，用 {@link ProductIndex#produces(String)} 判断。
 * 配色与绘制在 {@link JeiHighlightOverlay}（与 main 同一组颜色与 overlay 渲染方式）。
 */
public final class JeiCraftableSlots {

    private JeiCraftableSlots() {
    }

    /** 找出该配方里「至少有一种候选物质能由配方库自产」的槽位（只看原料与产物）。 */
    public static List<IRecipeSlotView> find(IRecipeSlotsView slots, ProductIndex index) {
        List<IRecipeSlotView> result = new ArrayList<>();
        if (index == null) {
            return result;
        }
        // 15.x 的 IRecipeSlotView 没有 getRole()，按角色分别取槽位视图等价
        collect(slots, RecipeIngredientRole.INPUT, index, result);
        collect(slots, RecipeIngredientRole.OUTPUT, index, result);
        return result;
    }

    private static void collect(IRecipeSlotsView slots, RecipeIngredientRole role,
                                ProductIndex index, List<IRecipeSlotView> out) {
        for (IRecipeSlotView slot : slots.getSlotViews(role)) {
            if (slot.isEmpty()) {
                continue;
            }
            if (isCraftable(slot, index)) {
                out.add(slot);
            }
        }
    }

    private static boolean isCraftable(IRecipeSlotView slot, ProductIndex index) {
        try {
            for (ITypedIngredient<?> ingredient : slot.getAllIngredients().toList()) {
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                if (mapped != null && index.produces(mapped.key())) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 某些自定义配料解析不了，当作不可自产
        }
        return false;
    }
}
