package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.GuiGraphics;

/**
 * JEI 配方页按钮的图案：13×13，从 JEI 高版本按钮里逐像素采样得到。
 *
 * <p>尺寸跟 JEI 自带的侧边按钮（转移按钮 13×13）一致：
 * <ul>
 *   <li>19.39+ 由 JEI 画按钮底板，我们只提供这个图案（{@code JeiRecipeButtonFactory}）；</li>
 *   <li>19.39 之前没有底板可借，{@link JeiLegacyRecipeButton} 直接画图案 + 悬停白框。</li>
 * </ul>
 * 早期版本曾把 JEI 的 20×20 底板也逐像素画出来，但那层底板的半透明阴影被压成了不透明灰块，
 * 看起来像多了一大块背景，因此改为只画图案。
 */
public final class JeiButtonSkin {

    /** 图案尺寸，与 JEI 配方页侧边按钮一致。 */
    public static final int SIZE = 13;

    /** 从参考图逐像素采样出的 13×13 图案（每格 1px 完全一致复制）。 */
    private static final int[][] ICON = {
            {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC3C3C3, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFF929292, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFF929292, 0xFF979797, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF979797, 0xFF909090, 0xFF8E8E8E, 0xFF797979, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF8D8D8D, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9B9B9B, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF919191, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFFFFFFFF, 0xFF909090, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF979797, 0xFFFFFFFF, 0xFF8E8E8E, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9B9B9B, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFFFFFFFF, 0xFF979797, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF929292, 0xFFFFFFFF, 0xFF979797, 0xFF7C7C7C, 0xFF000000},
            {0xFF000000, 0xFFBFBFBF, 0xFF949494, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF949494, 0xFF7E7E7E, 0xFF000000},
            {0xFF000000, 0xFFBFBFBF, 0xFF949494, 0xFF979797, 0xFF949494, 0xFF9B9B9B, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF949494, 0xFF979797, 0xFF949494, 0xFF7D7D7D, 0xFF000000},
            {0xFF000000, 0xFF909090, 0xFF7B7B7B, 0xFF797979, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7E7E7E, 0xFF7B7B7B, 0xFF797979, 0xFF000000},
            {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000}
    };

    private JeiButtonSkin() {
    }

    /** 画一个完整的 13×13 按钮（图案 + 悬停白框）。 */
    public static void draw(GuiGraphics graphics, int x, int y, boolean hover) {
        drawIcon(graphics, x, y);
        if (hover) {
            drawHoverBorder(graphics, x, y);
        }
    }

    /** 只画 13×13 图案（新 JEI 的 IDrawable 与老 JEI 的自绘按钮共用这份像素表）。 */
    public static void drawIcon(GuiGraphics graphics, int x, int y) {
        for (int row = 0; row < ICON.length; row++) {
            for (int col = 0; col < ICON[row].length; col++) {
                graphics.fill(x + col, y + row, x + col + 1, y + row + 1, ICON[row][col]);
            }
        }
    }

    /** 悬停时的 1px 白框：老版本 JEI 的按钮没有底板，用它提供点击反馈。 */
    public static void drawHoverBorder(GuiGraphics graphics, int x, int y) {
        int x2 = x + SIZE;
        int y2 = y + SIZE;
        int white = 0xFFFFFFFF;
        graphics.fill(x, y, x2, y + 1, white);
        graphics.fill(x, y2 - 1, x2, y2, white);
        graphics.fill(x, y, x + 1, y2, white);
        graphics.fill(x2 - 1, y, x2, y2, white);
    }
}
