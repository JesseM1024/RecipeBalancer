package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.client.config.GuiUtils;

/**
 * 按钮工厂（1.12.2 版）。
 *
 * <p>{@code Buttons.builder(...).bounds(...).build()} 的调用形状与 1.16.5/1.18.2 线一致，
 * 只是产物换成本模组自己的 {@link Button}（1.12.2 的 {@code GuiButton} 没有 builder）。
 *
 * <p>另外补上原版缺失的九宫格底图：原版 {@code GuiButton} 只会把 200×20 的按钮贴图
 * 左右各切一半硬贴，宽/高不是 200×20 的整数倍时边角会被裁掉（小按钮看起来像贴图被压扁、缺边）。
 * 这里改用九宫格，四角 1:1、只有中间拉伸，任意尺寸都能保住边框。
 */
public final class Buttons {

    /** widgets.png 里按钮行的源尺寸：200×20。 */
    public static final int BUTTON_SRC_W = 200;
    public static final int BUTTON_SRC_H = 20;
    /** 贴图整张是 256×256（{@code drawModalRectWithCustomSizedTexture} 的默认分母）。 */
    private static final int TEX_SIZE = 256;
    /** 九宫格四角取 4px：够包住原版的圆角与 1px 边框，又不会把渐变挤变形。 */
    private static final int CORNER = 4;

    private static final ResourceLocation WIDGETS =
            new ResourceLocation("textures/gui/widgets.png");

    private Buttons() {
    }

    public static Builder builder(ITextComponent message, Button.IPressable onPress) {
        return new Builder(message, onPress);
    }

    /**
     * 用九宫格画原版按钮底。
     *
     * @param v 按钮行的起始 v（46 + 状态×20，与 {@code GuiButton#getHoverState} 一致）
     */
    public static void drawButtonBackground(int x, int y, int width, int height, int v) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(WIDGETS);
        net.minecraft.client.renderer.GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        int corner = Math.min(CORNER, Math.min(width, height) / 2);
        int midW = width - corner * 2;
        int midH = height - corner * 2;
        int u0 = 0;
        int u1 = BUTTON_SRC_W - corner;
        int v0 = v;
        int v1 = v + BUTTON_SRC_H - corner;
        // 四角：1:1，不缩放
        blit(x, y, u0, v0, corner, corner);
        blit(x + width - corner, y, u1, v0, corner, corner);
        blit(x, y + height - corner, u0, v1, corner, corner);
        blit(x + width - corner, y + height - corner, u1, v1, corner, corner);
        // 四边：只沿一个方向拉伸
        blit(x + corner, y, u0 + corner, v0, midW, corner);
        blit(x + corner, y + height - corner, u0 + corner, v1, midW, corner);
        blit(x, y + corner, u0, v0 + corner, corner, midH);
        blit(x + width - corner, y + corner, u1, v0 + corner, corner, midH);
        // 中间：双向拉伸
        blit(x + corner, y + corner, u0 + corner, v0 + corner, midW, midH);
    }

    private static void blit(int x, int y, int u, int v, int width, int height) {
        Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, width, height, TEX_SIZE, TEX_SIZE);
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
            return new Button(x, y, width, height, message, onPress);
        }
    }

    /** 供 tooltip 复用：1.12.2 的 {@code GuiUtils.drawHoveringText} 需要 zLevel 与屏幕尺寸。 */
    public static void drawHoveringText(java.util.List<String> lines, int mouseX, int mouseY,
                                        net.minecraft.client.gui.FontRenderer font) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiUtils.drawHoveringText(lines, mouseX, mouseY, mc.displayWidth, mc.displayHeight,
                -1, font);
    }
}
