package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/**
 * 找到「配方库里已经能自产」的槽位，用于在 JEI 配方页把它们标蓝。
 *
 * <p>只在鼠标悬停配方页按钮时计算（见 {@link JeiLegacyRecipeButton}），
 * 所以这里不做缓存，调用方按 500ms 节流。
 */
final class JeiCraftableSlots {

    private static final int HIGHLIGHT = 0xFF4A90D9;

    private JeiCraftableSlots() {
    }

    /** @return 至少有一个可自产候选的槽位（去重按对象身份） */
    static List<IRecipeSlotView> find(IRecipeSlotsView slots, ProductIndex index) {
        List<IRecipeSlotView> hits = new ArrayList<>();
        if (index == null) {
            return hits;
        }
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.isEmpty()) {
                continue;
            }
            if (isCraftable(slot, index)) {
                hits.add(slot);
            }
        }
        return hits;
    }

    private static boolean isCraftable(IRecipeSlotView slot, ProductIndex index) {
        try {
            for (ITypedIngredient<?> ingredient : slot.getAllIngredients().toList()) {
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                if (mapped != null && index.indexOf(mapped.key()) >= 0) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 某些自定义配料解析不了，当作不可自产
        }
        return false;
    }

    /** 在槽位四周画一圈蓝色描边（与高亮节点同一套配色）。 */
    static void drawHighlight(GuiGraphics graphics, Rect2i rect) {
        int x1 = rect.getX();
        int y1 = rect.getY();
        int x2 = x1 + rect.getWidth();
        int y2 = y1 + rect.getHeight();
        graphics.fill(x1, y1, x2, y1 + 1, HIGHLIGHT);
        graphics.fill(x1, y2 - 1, x2, y2, HIGHLIGHT);
        graphics.fill(x1, y1, x1 + 1, y2, HIGHLIGHT);
        graphics.fill(x2 - 1, y1, x2, y2, HIGHLIGHT);
    }
}
