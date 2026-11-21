package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.ui.Draw;

import java.awt.Rectangle;

/**
 * 「配方库已有」蓝色高亮的绘制（1.12.2 / Draw 版）。
 *
 * <p>配色与其它版本线严格一致：填充 {@code 0x40004688}、1px 边框 {@code 0x9F004E96}。
 * 绘制时机在 JEI 把配方（含物品图标）画完之后（{@code GuiScreenEvent.DrawScreenEvent.Post}）。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>。
 */
public final class JeiHighlightOverlay {

    private JeiHighlightOverlay() {
    }

    /** 在槽位矩形上叠一层蓝色高亮；rect 必须是屏幕绝对坐标。 */
    public static void draw(Draw graphics, Rectangle rect) {
        int x = rect.x;
        int y = rect.y;
        int x2 = x + rect.width;
        int y2 = y + rect.height;
        graphics.fill(x, y, x2, y2, JeiCraftableSlots.HIGHLIGHT_FILL);
        graphics.fill(x, y, x2, y + 1, JeiCraftableSlots.HIGHLIGHT_BORDER);
        graphics.fill(x, y2 - 1, x2, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
        graphics.fill(x, y, x + 1, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
        graphics.fill(x2 - 1, y, x2, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
    }
}
