package com.jessetech.recipebalancer.mc.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 方形图标按钮：沿用原版按钮底（与「打开/保存」同款），上面只叠一层纯白像素图标。
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
        // 1.18.2 的 Button 没有 builder()/DEFAULT_NARRATION，直接给 6 参构造器
        super(x, y, size, size, Chat.empty(), onPress);
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

    /** 1.18.2 的可绘制入口叫 renderButton（1.19.3 起改名 renderWidget）。 */
    @Override
    public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        // 原版按钮底（悬停变蓝的那套贴图），只把文字换成图标。
        // 18×18 这种小方块不能走 super：原版把 200×20 贴图左右各切一半硬贴，
        // 会把圆角和上下边框切掉，看起来像贴图被压坏；这里改用九宫格。
        Buttons.drawButtonBackground(pose, x, y, width, height,
                46 + getYImage(isHoveredOrFocused()) * 20);
        int color = stateful && !on ? ICON_OFF : ICON_ON;
        // 1.18.2 的控件坐标是字段（x/y/width/height），没有 getX()/getY()/getWidth()
        icon.draw(new Draw(pose), x + (width - icon.width()) / 2,
                y + (height - icon.height()) / 2, color);
    }
}
