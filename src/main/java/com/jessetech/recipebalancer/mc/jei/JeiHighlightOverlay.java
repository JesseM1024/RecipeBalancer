package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.Rectangle2d;

/**
 * 「配方库已有」蓝色高亮的绘制（1.18.2 / MatrixStack 版）。
 *
 * <p>配色与 1.20.1 的 {@code JeiHighlightOverlay} 严格一致：
 * 填充 {@code 0x40004688}、1px 边框 {@code 0x9F004E96}。
 * 绘制时机都在 JEI 把配方（含物品图标）画完之后：
 * <ul>
 *   <li>9.x ~ 10.6 的叠加层：{@code GuiScreenEvent.DrawGuiScreenEvent.Post}；</li>
 *   <li>10.81+ 的官方按钮：{@code IIconButtonController#drawExtras}。</li>
 * </ul>
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>。
 */
public final class JeiHighlightOverlay {

    private JeiHighlightOverlay() {
    }

    /** 在槽位矩形上叠一层蓝色高亮；rect 必须是屏幕绝对坐标。 */
    public static void draw(MatrixStack pose, Rectangle2d rect) {
        int x = rect.getX();
        int y = rect.getY();
        int x2 = x + rect.getWidth();
        int y2 = y + rect.getHeight();
        AbstractGui.fill(pose, x, y, x2, y2, JeiCraftableSlots.HIGHLIGHT_FILL);
        AbstractGui.fill(pose, x, y, x2, y + 1, JeiCraftableSlots.HIGHLIGHT_BORDER);
        AbstractGui.fill(pose, x, y2 - 1, x2, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
        AbstractGui.fill(pose, x, y, x + 1, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
        AbstractGui.fill(pose, x2 - 1, y, x2, y2, JeiCraftableSlots.HIGHLIGHT_BORDER);
    }
}
