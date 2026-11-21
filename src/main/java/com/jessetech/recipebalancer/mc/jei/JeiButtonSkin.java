package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.ui.Draw;

/**
 * JEI 配方页按钮的图案：13×13，从 JEI 高版本按钮里逐像素采样得到（各版本线同一份）。
 *
 * <p>1.12.2 的 JEI 没有按钮 API，{@link JeiRecipeOverlay} 直接画这份图案 + 悬停白框。
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
    public static void draw(Draw graphics, int x, int y, boolean hover) {
        drawIcon(graphics, x, y);
        if (hover) {
            drawHoverBorder(graphics, x, y);
        }
    }

    /** 只画 13×13 图案。 */
    public static void drawIcon(Draw graphics, int x, int y) {
        for (int row = 0; row < ICON.length; row++) {
            for (int col = 0; col < ICON[row].length; col++) {
                graphics.fill(x + col, y + row, x + col + 1, y + row + 1, ICON[row][col]);
            }
        }
    }

    /** 悬停时的 1px 白框：老版本 JEI 的按钮没有底板，用它提供点击反馈。 */
    public static void drawHoverBorder(Draw graphics, int x, int y) {
        int x2 = x + SIZE;
        int y2 = y + SIZE;
        int white = 0xFFFFFFFF;
        graphics.fill(x, y, x2, y + 1, white);
        graphics.fill(x, y2 - 1, x2, y2, white);
        graphics.fill(x, y, x + 1, y2, white);
        graphics.fill(x2 - 1, y, x2, y2, white);
    }
}
