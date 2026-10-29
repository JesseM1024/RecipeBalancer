package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 方形图标按钮：沿用原版按钮底（与「存取/保存」同款），上面只叠一层纯白像素图标。
 *
 * <p>开关类按钮关闭时图标变淡；提示文字由界面在渲染末尾统一画，
 * 避免被同帧其它 widget 覆盖。
 */
public final class IconButton extends Button {

    private static final int ICON_ON = 0xFFFFFFFF;
    private static final int ICON_OFF = 0x80FFFFFF;

    private final PixelIcon icon;
    private final Supplier<Component> tooltip;
    private final boolean stateful;
    private boolean on = true;

    /**
     * @param stateful true = 开关按钮（关闭时图标变淡）；false = 动作/模式按钮（图标恒亮）
     */
    public IconButton(int x, int y, int size, PixelIcon icon, boolean stateful,
                      Supplier<Component> tooltip, OnPress onPress) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.stateful = stateful;
        this.tooltip = tooltip;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    public Component tooltipText() {
        return tooltip.get();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 原版按钮底（悬停变蓝的那套贴图），只把文字换成图标
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        int color = stateful && !on ? ICON_OFF : ICON_ON;
        icon.draw(graphics, getX() + (getWidth() - icon.width()) / 2,
                getY() + (getHeight() - icon.height()) / 2, color);
    }
}
