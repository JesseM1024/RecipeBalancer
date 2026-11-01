package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;

import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.List;

/**
 * 「配方库里已有配方的物质」在 JEI 配方页上的高亮。
 *
 * <p>高版本（19.39+）在 {@code IRecipeButtonController#drawExtras} 里画，
 * 低版本在 {@code IRecipeCategoryDecorator#draw} 里画 —— 两处都用这里的选择规则与配色，
 * 避免判定口径分叉。渲染本身不依赖容器界面，低版本同样可用。
 */
public final class JeiCraftableSlots {

    /** 深蓝、低不透明度的「已有配方」高亮。 */
    public static final int HIGHLIGHT_FILL = 0x40004688;
    public static final int HIGHLIGHT_BORDER = 0x9F004E96;

    private JeiCraftableSlots() {
    }

    /**
     * 找出该配方里「至少有一种候选物质能由配方库自产」的槽位（只看原料与产物）。
     */
    public static List<IRecipeSlotView> find(IRecipeSlotsView slots, ProductIndex index) {
        List<IRecipeSlotView> result = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.isEmpty()) {
                continue;
            }
            RecipeIngredientRole role = slot.getRole();
            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            for (ITypedIngredient<?> ingredient : slot.getAllIngredients().toList()) {
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                if (mapped != null && index.produces(mapped.key())) {
                    result.add(slot);
                    break;
                }
            }
        }
        return result;
    }

    /** 在槽位矩形上叠一层蓝色高亮；rect 必须与当前绘制坐标系同一空间。 */
    public static void drawHighlight(GuiGraphics graphics, Rect2i rect) {
        int x = rect.getX();
        int y = rect.getY();
        int x2 = x + rect.getWidth();
        int y2 = y + rect.getHeight();
        overlayFill(graphics, x, y, x2, y2, HIGHLIGHT_FILL);
        overlayFill(graphics, x, y, x2, y + 1, HIGHLIGHT_BORDER);
        overlayFill(graphics, x, y2 - 1, x2, y2, HIGHLIGHT_BORDER);
        overlayFill(graphics, x, y, x + 1, y2, HIGHLIGHT_BORDER);
        overlayFill(graphics, x2 - 1, y, x2, y2, HIGHLIGHT_BORDER);
    }

    /** guiOverlay：不吃深度，保证盖在物品图标之上（和 JEI 自带高亮一致）。 */
    private static void overlayFill(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fillGradient(RenderType.guiOverlay(), x1, y1, x2, y2, color, color, 0);
    }
}
