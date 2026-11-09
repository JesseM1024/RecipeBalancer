package com.jessetech.recipebalancer.mc.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.math.MathHelper;

/**
 * 按钮工厂。
 *
 * <p>{@code Button.builder(...).bounds(x, y, w, h).build()} 是 1.19+ 的写法；
 * 1.18.2 只能 {@code new Button(x, y, w, h, message, onPress)}。
 * 本类的 {@link Builder} 保持与 1.20 线同样的调用形状，
 * 界面代码只需把 {@code Button.builder} 换成 {@link Buttons#builder}。
 *
 * <p>另外补上 1.18.2 缺失的九宫格底图：原版 {@code renderButton} 只会把
 * 200×20 的按钮贴图左右各切一半硬贴，宽/高不是 200×20 的整数倍时边角会被裁掉
 * （屏幕上的小按钮看起来像贴图被压扁、缺边）。这里改用九宫格，四角 1:1、
 * 只有中间拉伸，任意尺寸都能保住边框。
 */
public final class Buttons {

    /** widgets.png 里按钮行的源尺寸：200×20。 */
    private static final int BUTTON_SRC_W = 200;
    private static final int BUTTON_SRC_H = 20;
    /** 贴图整张是 256×256（AbstractGui.blit 的默认分母）。 */
    private static final int TEX_SIZE = 256;
    /** 九宫格四角取 4px：够包住原版的圆角与 1px 边框，又不会把渐变挤变形。 */
    private static final int CORNER = 4;

    private Buttons() {
    }

    public static Builder builder(ITextComponent message, Button.IPressable onPress) {
        return new Builder(message, onPress);
    }

    /**
     * 用九宫格画原版按钮底。
     *
     * @param v 按钮行的起始 v（46 + 状态×20，与 {@code AbstractWidget#getYImage} 一致）
     */
    public static void drawButtonBackground(MatrixStack pose, int x, int y, int width, int height, int v) {
        Minecraft.getInstance().getTextureManager().bindTexture(Widget.WIDGETS_LOCATION);
        RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
        int corner = Math.min(CORNER, Math.min(width, height) / 2);
        int midW = width - corner * 2;
        int midH = height - corner * 2;
        int u0 = 0;
        int u1 = BUTTON_SRC_W - corner;
        int v0 = v;
        int v1 = v + BUTTON_SRC_H - corner;
        // 四角：1:1，不缩放
        blit(pose, x, y, u0, v0, corner, corner);
        blit(pose, x + width - corner, y, u1, v0, corner, corner);
        blit(pose, x, y + height - corner, u0, v1, corner, corner);
        blit(pose, x + width - corner, y + height - corner, u1, v1, corner, corner);
        // 四边：只沿一个方向拉伸
        blit(pose, x + corner, y, u0 + corner, v0, midW, corner);
        blit(pose, x + corner, y + height - corner, u0 + corner, v1, midW, corner);
        blit(pose, x, y + corner, u0, v0 + corner, corner, midH);
        blit(pose, x + width - corner, y + corner, u1, v0 + corner, corner, midH);
        // 中间：双向拉伸
        blit(pose, x + corner, y + corner, u0 + corner, v0 + corner, midW, midH);
    }

    private static void blit(MatrixStack pose, int x, int y, int u, int v, int width, int height) {
        AbstractGui.blit(pose, x, y, 0, u, v, width, height, TEX_SIZE, TEX_SIZE);
    }

    /** 与 {@code Button.Builder} 同名同形的极简建造器。 */
    public static final class Builder {

        private final ITextComponent message;
        private final Button.IPressable onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(ITextComponent message, Button.IPressable onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new TexturedButton(x, y, width, height, message, onPress);
        }
    }

    /**
     * 九宫格底图 + 居中文字的按钮。
     *
     * <p>只重写底图与文字这两步，交互（点击/键盘/朗读）全部沿用原版 {@link Button}。
     */
    private static final class TexturedButton extends Button {

        private TexturedButton(int x, int y, int width, int height, ITextComponent message,
                               IPressable onPress) {
            super(x, y, width, height, message, onPress);
        }

        @Override
        public void renderWidget(MatrixStack pose, int mouseX, int mouseY, float partialTick) {
            drawButtonBackground(pose, x, y, width, height,
                    46 + getYImage(isHovered()) * BUTTON_SRC_H);
            AbstractGui.drawCenteredString(pose, Minecraft.getInstance().fontRenderer, getMessage(),
                    x + width / 2, y + (height - 8) / 2,
                    getFGColor() | MathHelper.ceil(alpha * 255.0f) << 24);
            if (isHovered()) {
                renderToolTip(pose, mouseX, mouseY);
            }
        }
    }
}
