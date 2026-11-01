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
 * <p>配色与判定口径与 main 分支完全一致：深蓝填充 + 更亮一点的描边，
 * 用 {@code RenderType.guiOverlay()} 画在物品图标之上（不吃深度），
 * 并且只高亮原料/产物槽位里「配方库能自产」的物质。
 */
public final class JeiCraftableSlots {

    /** 深蓝、低不透明度的「已有配方」高亮。 */
    public static final int HIGHLIGHT_FILL = 0x40004688;
    public static final int HIGHLIGHT_BORDER = 0x9F004E96;

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
