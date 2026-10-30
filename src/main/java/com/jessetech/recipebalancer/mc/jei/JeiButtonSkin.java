package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.GuiGraphics;

/**
 * JEI 配方页按钮的外观：20×20 底图 + 居中的 13×13 图案。
 *
 * <p>底图是从 JEI 自带的 {@code button_enabled_v2.png / button_highlight_v2.png}
 * 逐像素抄下来的灰度表（每行 20 个 16 进制字节），所以和 JEI 原生按钮（例如 AE2 的
 * 「编写样板」）像素完全一致，又不依赖图集 id（避免出现紫黑块）。
 */
public final class JeiButtonSkin {

    /** JEI 原生配方按钮尺寸。 */
    public static final int SIZE = 20;
    /** 13×13 图案在 20×20 按钮里的居中偏移。 */
    public static final int ICON_OFFSET = (SIZE - 13) / 2;

    /** button_enabled_v2.png 的灰度（每行 20 字节）。 */
    private static final String[] FRAME_ENABLED = {
            "0000000000000000000000000000000000000000",
            "00AAAAAAAAA6A6AAAAA6A6AAAAAAAAAAAAAA6E00",
            "00AA6E6E7474706E6E6E6E706E7070746C695300",
            "00AA696C6E6E6E6E6E6E6E6E706E6E6E6E6C5600",
            "00A66E6E6C6E6E7074706C6E777070746C695300",
            "00AA6E746C6C6C6E6E6E6E6C6E6E6E6C6C6E5800",
            "00A66C6C70746C6C6E6E6C6C696E6E6C6E6C5600",
            "00AA746E6E6E6E70746E6E6E6E6E6E6C6C6E5600",
            "00AA696E706C6E6E6E6E706E70746E696C6E5600",
            "00AA696C6E6C696C6E7077706E6E6E6E6E694F00",
            "00AA6E6E6E6E6E6E6E6E6E6E746E6E6C6C6C5600",
            "00AA6E746E6C6E6E6C6E6E6C74706E6E70745800",
            "00AA706E6E6E6C696C6E6C6C6E6E706E6E6C4F00",
            "00AA6E6E6E706C64696E6E6C6C6C6C64696E5600",
            "00AA70776C6C6C6E6C696C6E6E6C707770705600",
            "00AA6E6E747470706C6E707774706E6E70745600",
            "00A67074706E6C6C696C6E6E6E6E6E7074705800",
            "00A67074706E6C6C696C6E6E6E6E6E7074705800",
            "006C565356535653565656565658565658565300",
            "0000000000000000000000000000000000000000"
    };

    /** button_highlight_v2.png 的灰度（悬停时整体替换，等效于 JEI 叠加高亮层）。 */
    private static final String[] FRAME_HIGHLIGHT = {
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "FFABABABABA7A7ABABA7A7ABABABABABABAB6EFF",
            "FFAB6E6E7476716E6E6E6E716E7171766C6951FF",
            "FFAB696C6E6E6E6E6E6E6E6E716E6E6E6E6C55FF",
            "FFA76E6E6C6E6E7174716C6E797171766C6951FF",
            "FFAB6E746C6C6C6E6E6E6E6C6E6E6E6C6C6E57FF",
            "FFA76C6C71746C6C6E6E6C6C696E6E6C6E6C55FF",
            "FFAB746E6E6E6E71746E6E6E6E6E6E6C6C6E55FF",
            "FFAB696E716C6E6E6E6E716E71766E696C6E55FF",
            "FFAB696C6E6C696C6E7176716E6E6E6E6E694EFF",
            "FFAB6E6E6E6E6E6E6E6E6E6E746E6E6C6C6C55FF",
            "FFAB6E766E6C6E6E6C6E6E6C74716E6E717457FF",
            "FFAB716E6E6E6C696C6E6C6C6E6E716E6E6C4EFF",
            "FFAB6E6E6E716C64696E6E6C6C6C6C64696E55FF",
            "FFAB71796C6C6C6E6C696C6E6E6C7179717155FF",
            "FFAB6E6E747671716C6E717676716E6E717455FF",
            "FFA77174716E6C6C696C6E6E6E6E6E71767157FF",
            "FFA77174716E6C6C696C6E6E6E6E6E71767157FF",
            "FF6C5551555155515555555555575555575551FF",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
    };

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

    private static final int[][] FRAME_ENABLED_PIXELS = parse(FRAME_ENABLED);
    private static final int[][] FRAME_HIGHLIGHT_PIXELS = parse(FRAME_HIGHLIGHT);

    private JeiButtonSkin() {
    }

    /** 画一个完整的 20×20 按钮（底图 + 居中图案）。 */
    public static void draw(GuiGraphics graphics, int x, int y, boolean hover) {
        blit(graphics, hover ? FRAME_HIGHLIGHT_PIXELS : FRAME_ENABLED_PIXELS, x, y);
        drawIcon(graphics, x + ICON_OFFSET, y + ICON_OFFSET);
    }

    /** 只画 13×13 图案（新 JEI 的 IDrawable 复用这份像素表）。 */
    public static void drawIcon(GuiGraphics graphics, int x, int y) {
        for (int row = 0; row < ICON.length; row++) {
            for (int col = 0; col < ICON[row].length; col++) {
                graphics.fill(x + col, y + row, x + col + 1, y + row + 1, ICON[row][col]);
            }
        }
    }

    private static void blit(GuiGraphics graphics, int[][] pixels, int x, int y) {
        for (int row = 0; row < pixels.length; row++) {
            for (int col = 0; col < pixels[row].length; col++) {
                graphics.fill(x + col, y + row, x + col + 1, y + row + 1, pixels[row][col]);
            }
        }
    }

    private static int[][] parse(String[] hexRows) {
        int[][] pixels = new int[hexRows.length][SIZE];
        for (int row = 0; row < hexRows.length; row++) {
            String line = hexRows[row];
            for (int col = 0; col < SIZE; col++) {
                int gray = Integer.parseInt(line.substring(col * 2, col * 2 + 2), 16);
                pixels[row][col] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
        }
        return pixels;
    }
}
