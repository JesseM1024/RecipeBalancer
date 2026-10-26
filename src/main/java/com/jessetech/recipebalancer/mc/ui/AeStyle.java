package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * AE 编码终端的视觉常量与基础绘制（不引用 AE2 资源，避免硬依赖）：
 * 浅灰面板、深色内嵌槽、蓝色标题/强调、细高光边框。
 */
public final class AeStyle {

    public static final int PANEL = 0xFFC6C6C6;
    public static final int HEADER = 0xFF9A9A9A;
    public static final int BORDER_LIGHT = 0xFFFFFFFF;
    public static final int BORDER_DARK = 0xFF4A4A4A;
    public static final int TEXT = 0xFF303030;
    public static final int MUTED = 0xFF555555;
    public static final int ACCENT = 0xFF2E5E8C;
    public static final int ACCENT_LIGHT = 0xFF4A90D9;
    public static final int WARN = 0xFF9E2B2B;
    public static final int SLOT_BORDER = 0xFF8B8B8B;
    public static final int SLOT_FILL = 0xFF373737;
    public static final int SLOT_SHADOW = 0xFF202020;
    public static final int SLOT_HIGHLIGHT = 0xFF5A5A5A;

    private AeStyle() {
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER_DARK);
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width, y + 1, BORDER_LIGHT);
        graphics.fill(x, y + height - 1, x + width, y + height, BORDER_DARK);
    }

    public static void header(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, HEADER);
        graphics.fill(x, y, x + width, y + 1, BORDER_LIGHT);
        graphics.fill(x, y + height - 1, x + width, y + height, BORDER_DARK);
    }

    public static void subPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0x18FFFFFF);
        graphics.fill(x, y, x + width, y + 1, 0x40FFFFFF);
        graphics.fill(x, y + height - 1, x + width, y + height, 0x40000000);
    }

    /** 16px（或指定尺寸）内嵌物品槽。 */
    public static void slot(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, SLOT_BORDER);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_FILL);
        graphics.fill(x + 1, y + 1, x + size - 1, y + 2, SLOT_SHADOW);
        graphics.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, SLOT_HIGHLIGHT);
    }

    public static void slot(GuiGraphics graphics, int x, int y) {
        slot(graphics, x, y, 16);
    }
}
