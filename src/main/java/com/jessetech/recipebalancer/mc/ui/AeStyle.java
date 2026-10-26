package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 原版（vanilla）物品栏配色：浅灰面板 #C6C6C6、#8B8B8B 内嵌槽、
 * 深色左上阴影 + 白色右下高光；不引用 AE2 资源，避免硬依赖。
 */
public final class AeStyle {

    public static final int SLOT_SIZE = 18;

    public static final int PANEL = 0xFFC6C6C6;
    public static final int HEADER = 0xFFC6C6C6;
    public static final int BORDER_LIGHT = 0xFFFFFFFF;
    public static final int BORDER_DARK = 0xFF373737;
    public static final int TEXT = 0xFF404040;
    public static final int MUTED = 0xFF707070;
    public static final int ACCENT = 0xFF404040;
    public static final int ACCENT_LIGHT = 0xFF4A90D9;
    public static final int WARN = 0xFF9E2B2B;
    public static final int SLOT_BORDER = 0xFF373737;
    public static final int SLOT_FILL = 0xFF8B8B8B;
    public static final int SLOT_SHADOW = 0xFF373737;
    public static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;

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
        graphics.fill(x, y, x + width, y + height, 0x208B8B8B);
        graphics.fill(x, y, x + width, y + 1, 0xFF373737);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFFFFFFFF);
    }

    /** 原版 18px 物品槽：16×16 内区 + 1px 边框。 */
    public static void slot(GuiGraphics graphics, int x, int y) {
        slot(graphics, x, y, SLOT_SIZE);
    }

    public static void slot(GuiGraphics graphics, int x, int y, int size) {
        // 外圈 1px 深色（左上阴影），右下 1px 白色高光；内区 16×16 给物品
        graphics.fill(x, y, x + size, y + size, SLOT_BORDER);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_FILL);
        graphics.fill(x + 1, y + size - 1, x + size, y + size, SLOT_HIGHLIGHT);
        graphics.fill(x + size - 1, y + 1, x + size, y + size, SLOT_HIGHLIGHT);
    }
}
