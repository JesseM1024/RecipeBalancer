package com.jessetech.recipebalancer.mc.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 1.18.2 的绘制入口（1.20+ {@code GuiGraphics} 的替身）。
 *
 * <p>1.20 起 MC 把「PoseStack + 一组绘制方法」收成了 {@code GuiGraphics}；
 * 1.18.2 还没有这个类，绘制方法散在 {@link GuiComponent}、{@link Font}、
 * {@code ItemRenderer} 上。本类只提供上层真正用到的那一小撮方法，
 * <b>签名与 1.20 的 {@code GuiGraphics} 保持一致</b> —— 于是界面代码里
 * 上百处 {@code graphics.fill} / {@code graphics.drawString} 一行都不用改，
 * 只需要把参数类型换成 {@link Draw}，并在 render 方法开头
 * {@code Draw graphics = new Draw(pose, this);} 合成一次。
 *
 * <p>以后若再移植到别的版本，这一层是唯一的渲染差异点。
 */
public final class Draw {

    /**
     * tooltip 绘制入口。
     *
     * <p>1.18.2 的 {@code Screen#renderTooltip} 是 {@code protected}，外部类调不到，
     * 因此由界面自己实现并把 {@code this} 传进来（见 {@code RbScreen}）。
     */
    public interface Tooltips {
        void renderTooltip(PoseStack pose, ItemStack stack, int mouseX, int mouseY);

        void renderComponentTooltip(PoseStack pose, List<Component> lines, Font font,
                                    int mouseX, int mouseY);
    }

    private final PoseStack pose;
    private final Tooltips tooltips;

    public Draw(PoseStack pose) {
        this(pose, null);
    }

    public Draw(PoseStack pose, Tooltips tooltips) {
        this.pose = pose;
        this.tooltips = tooltips;
    }

    /** 供确实需要直接操作矩阵的地方使用。 */
    public PoseStack pose() {
        return pose;
    }

    // ------------------------------------------------------------------
    // 基础绘制原语
    // ------------------------------------------------------------------

    public void fill(int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(pose, x1, y1, x2, y2, color);
    }

    public void drawString(Font font, String text, int x, int y, int color) {
        font.drawShadow(pose, text, x, y, color);
    }

    /**
     * ★ 陷阱 ★ 1.18.2 的 {@code Font#drawShadow(..., boolean)} 那个 boolean 是
     * <b>透明（see-through）</b>，不是「是否画阴影」——阴影是硬编码在 drawShadow 里的。
     * 要关闭阴影只能用 {@code Font#draw(...)}（1.19+ 才把它改成 dropShadow 参数）。
     */
    public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(pose, text, x, y, color);
        } else {
            font.draw(pose, text, x, y, color);
        }
    }

    public void drawString(Font font, Component text, int x, int y, int color) {
        font.drawShadow(pose, text.getVisualOrderText(), x, y, color);
    }

    public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        // 1.18.2 的 drawShadow 没有 boolean 重载：带阴影走 drawShadow，不带阴影走 draw
        if (shadow) {
            font.drawShadow(pose, text.getVisualOrderText(), x, y, color);
        } else {
            font.draw(pose, text.getVisualOrderText(), x, y, color);
        }
    }

    public void drawString(Font font, FormattedCharSequence text, int x, int y, int color,
                           boolean shadow) {
        if (shadow) {
            font.drawShadow(pose, text, x, y, color);
        } else {
            font.draw(pose, text, x, y, color);
        }
    }

    /**
     * 16×16 物品图标（绝对 GUI 坐标）。
     *
     * <p>走本类的 {@link #renderItemPosed} 而不是原版 {@code renderGuiItem}：
     * 两者的最终变换完全一致，但后者只认 {@link RenderSystem#getModelViewStack()}、
     * 完全忽略 PoseStack —— 在流程图那种「相机缩放/平移」的自绘坐标系里会把图标
     * 画到屏幕固定位置（左上角/随机处）。
     */
    public void renderItem(ItemStack stack, int x, int y) {
        renderItemPosed(stack, x, y);
    }

    /**
     * 在当前 PoseStack 变换下画 16×16 物品。
     *
     * <p>变换与 1.18.2 的 {@code ItemRenderer#renderGuiItem} 等价
     * （translate(x+8, y+8, 100) + scale(16, -16, 16)），
     * 区别只在于这里<b>吃 PoseStack</b>，因此可以随所在坐标系缩放平移。
     */
    public void renderItemPosed(ItemStack stack, float x, float y) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getItemRenderer()
                .getModel(stack, minecraft.level, minecraft.player, 0);
        boolean flat = !model.usesBlockLight();
        pose.pushPose();
        pose.translate(x + 8.0f, y + 8.0f, 100.0f);
        // ★ 与 GuiGraphics.renderItem 一致：Y 翻转只用 mulPoseMatrix（只改位姿矩阵）。
        // 写成 scale(16, -16, 16) 会顺带更新法线矩阵（PoseStack.scale 会同步 inverse-scale
        // 法线），方块的明暗面因此错位，表现为「顶面暗、侧面亮」。
        // 1.20.1 分支踩过同一个坑，这里照搬其修法。
        pose.mulPoseMatrix(Matrix4f.createScaleMatrix(1.0f, -1.0f, 1.0f));
        pose.scale(16.0f, 16.0f, 16.0f);
        // 与 renderGuiItem 完全一致的状态设置：像素风不过滤、开混合、清掉上一轮的颜色
        minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableDepthTest();
        if (flat) {
            Lighting.setupForFlatItems();
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        minecraft.getItemRenderer().render(stack, ItemTransforms.TransformType.GUI, false, pose,
                buffers, 15728880, OverlayTexture.NO_OVERLAY, model);
        buffers.endBatch();
        if (flat) {
            Lighting.setupFor3DItems();
        }
        pose.popPose();
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
    }

    // ------------------------------------------------------------------
    // tooltip
    // ------------------------------------------------------------------

    public void renderTooltip(Font font, ItemStack stack, int mouseX, int mouseY) {
        if (tooltips != null) {
            tooltips.renderTooltip(pose, stack, mouseX, mouseY);
        }
    }

    public void renderTooltip(Font font, Component line, int mouseX, int mouseY) {
        renderComponentTooltip(font, List.of(line), mouseX, mouseY);
    }

    public void renderComponentTooltip(Font font, List<Component> lines, int mouseX, int mouseY) {
        if (tooltips != null) {
            tooltips.renderComponentTooltip(pose, lines, font, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------
    // 批缓冲 / 裁剪
    // ------------------------------------------------------------------

    /**
     * 物品渲染用的批缓冲（1.20 的 {@code GuiGraphics#bufferSource()}）。
     *
     * <p>只用于把 {@link ItemStack} 交给 {@code ItemRenderer.render(...)}；
     * 自定义顶点请用 {@link #batch()}。
     */
    public MultiBufferSource bufferSource() {
        return Minecraft.getInstance().renderBuffers().bufferSource();
    }

    /** 提交物品渲染缓冲（1.20 的同名方法）。 */
    public void flush() {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    }

    /**
     * 开启一段自定义顶点批（替代 1.20 的 {@code bufferSource().getBuffer(RenderType.gui())}）。
     *
     * <p>1.18.2 没有 {@code RenderType.gui()}，GUI 上的裸四边形一律走
     * {@link Tesselator} 的立即模式；因此本批是<b>独占</b>的：
     * 期间不能夹 {@code graphics.fill}（它会重开同一个 builder）。
     * 写完必须调 {@link Batch#end()}。
     */
    public Batch batch() {
        return new Batch();
    }

    /** 一段 GUI 四边形批：{@code addVertex(...)} + {@code setColor(...)} 与 1.20 写法一致。 */
    public static final class Batch {

        private final BufferBuilder builder;

        private Batch() {
            this.builder = Tesselator.getInstance().getBuilder();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            // disableTexture 在 1.18.2 里同时把 shader 切成 position_color
            RenderSystem.disableTexture();
            // 1.20 的 RenderType.gui() 是 NO_CULL：GUI 上随手画的四边形绕序不统一，
            // 开着背面剔除会让一半方向的线段整块消失。
            RenderSystem.disableCull();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        }

        public Batch addVertex(Matrix4f matrix, float x, float y, float z) {
            builder.vertex(matrix, x, y, z);
            return this;
        }

        /** ARGB；写完一个顶点即结束该顶点（1.18.2 需要显式 endVertex）。 */
        public Batch setColor(int argb) {
            builder.color(argb);
            builder.endVertex();
            return this;
        }

        /** 提交并恢复 GUI 纹理/剔除状态。 */
        public void end() {
            builder.end();
            BufferUploader.end(builder);
            RenderSystem.enableCull();
            RenderSystem.enableTexture();
            RenderSystem.disableBlend();
        }
    }

    /**
     * 开启裁剪区（GUI 坐标）。
     *
     * <p>1.18.2 的 {@code GuiComponent#enableScissor} 是 {@code protected static}，
     * 这里按相同公式自己换算成窗口像素坐标。
     */
    public void enableScissor(int x1, int y1, int x2, int y2) {
        Window window = Minecraft.getInstance().getWindow();
        int height = window.getHeight();
        double scale = window.getGuiScale();
        double dx1 = x1 * scale;
        double dy1 = height - y2 * scale;
        double dx2 = x2 * scale;
        double dy2 = height - y1 * scale;
        RenderSystem.enableScissor((int) dx1, (int) dy1,
                Math.max(0, (int) (dx2 - dx1)), Math.max(0, (int) (dy2 - dy1)));
    }

    public void disableScissor() {
        RenderSystem.disableScissor();
    }
}
