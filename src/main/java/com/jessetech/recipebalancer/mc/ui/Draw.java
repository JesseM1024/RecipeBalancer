package com.jessetech.recipebalancer.mc.ui;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IReorderingProcessor;
import net.minecraft.util.text.ITextComponent;

import java.util.List;

/**
 * 1.16.5 的绘制入口（1.20+ {@code GuiGraphics} 的替身，1.18.2 版的降级对应物）。
 *
 * <p>1.16.5 的绘制环境比 1.18.2 更早一代：
 * <ul>
 *   <li>位姿是 {@link MatrixStack}（不是 {@code PoseStack}）；</li>
 *   <li>静态绘制在 {@link AbstractGui#fill}，文字在 {@link FontRenderer}；</li>
 *   <li>物品是 {@code ItemRenderer#renderItemAndEffectIntoGUI} / {@code renderItem(..., MatrixStack, IRenderTypeBuffer, ...)}；</li>
 *   <li>GUI 顶点批走 {@link Tessellator} 的立即模式（没有 1.18 的 {@code RenderType.gui()}）。</li>
 * </ul>
 *
 * <p>本类只提供上层真正用到的那一小撮方法，<b>签名与 1.18.2 的分支保持一致</b>
 * （仅把 {@code PoseStack→MatrixStack}、{@code Font→FontRenderer}、{@code Component→ITextComponent}），
 * 于是界面代码里的 {@code graphics.fill / drawString / renderItem} 调用基本不用改。
 */
public final class Draw {

    /**
     * tooltip 绘制入口。
     *
     * <p>1.16.5 的 {@code Screen#renderTooltip} 是 {@code protected}，外部类调不到，
     * 因此由界面自己实现并把 {@code this} 传进来（见 {@code RbScreen}）。
     */
    public interface Tooltips {
        /**
         * 物品 tooltip。
         *
         * <p><b>名字刻意不叫 renderTooltip</b>：1.16.5 的 {@code ContainerScreen#renderTooltip}
         * 是 MC 方法，ForgeGradle 重混淆会把它改名成 SRG（{@code func_230457_a_}），
         * 而本接口是自己的类、不会被改名 —— 同名会导致实现方法被改名后与接口对不上，
         * 运行期就是 {@code AbstractMethodError}。换个名字两边都保持原样。
         */
        void renderItemTooltip(MatrixStack pose, ItemStack stack, int mouseX, int mouseY);

        void renderComponentTooltip(MatrixStack pose, List<ITextComponent> lines,
                                    FontRenderer font, int mouseX, int mouseY);
    }

    private final MatrixStack pose;
    private final Tooltips tooltips;

    public Draw(MatrixStack pose) {
        this(pose, null);
    }

    public Draw(MatrixStack pose, Tooltips tooltips) {
        this.pose = pose;
        this.tooltips = tooltips;
    }

    /** 供确实需要直接操作矩阵的地方使用。 */
    public MatrixStack pose() {
        return pose;
    }

    // ------------------------------------------------------------------
    // 基础绘制原语
    // ------------------------------------------------------------------

    public void fill(int x1, int y1, int x2, int y2, int color) {
        AbstractGui.fill(pose, x1, y1, x2, y2, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(pose, text, x, y, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(pose, text, x, y, color);
        } else {
            font.drawString(pose, text, x, y, color);
        }
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        font.drawTextWithShadow(pose, text, x, y, color);
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color,
                           boolean shadow) {
        if (shadow) {
            font.drawTextWithShadow(pose, text, x, y, color);
        } else {
            font.drawText(pose, text, x, y, color);
        }
    }

    public void drawString(FontRenderer font, IReorderingProcessor text, int x, int y, int color,
                           boolean shadow) {
        if (shadow) {
            font.drawTextWithShadow(pose, text, x, y, color);
        } else {
            // 1.16.5 的无阴影处理器重载是 func_238422_b_（结果页正文用得到）
            font.func_238422_b_(pose, text, x, y, color);
        }
    }

    /** 16×16 物品图标（绝对 GUI 坐标，不支持位姿缩放）。 */
    public void renderItem(ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer().renderItemAndEffectIntoGUI(stack, x, y);
    }

    /**
     * 在当前 {@link MatrixStack} 变换下画 16×16 物品。
     *
     * <p>状态序列照抄 1.16.5 的 {@code ItemRenderer#renderItemModelIntoGUI}：
     * 绑定方块图集、关模糊、开混合、按 {@code isSideLit()} 切 GUI 平/立体光照，
     * 结束再恢复。区别只在于这里的位移/缩放作用在<b>调用方的矩阵</b>上，
     * 因此能随流程图那种相机缩放平移。
     */
    public void renderItemPosed(ItemStack stack, float x, float y) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer renderer = minecraft.getItemRenderer();
        IBakedModel model = renderer.getItemModelWithOverrides(stack, minecraft.world,
                minecraft.player);
        boolean flat = !model.isSideLit();
        pose.push();
        pose.translate(x + 8.0f, y + 8.0f, 100.0f);
        // 与官方一致：先翻转 Y，再放大到 16×16
        pose.scale(1.0f, -1.0f, 1.0f);
        pose.scale(16.0f, 16.0f, 16.0f);

        minecraft.getTextureManager().bindTexture(AtlasTexture.LOCATION_BLOCKS_TEXTURE);
        minecraft.getTextureManager().getTexture(AtlasTexture.LOCATION_BLOCKS_TEXTURE)
                .setBlurMipmapDirect(false, false);
        RenderSystem.enableRescaleNormal();
        RenderSystem.enableAlphaTest();
        RenderSystem.defaultAlphaFunc();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
        if (flat) {
            RenderHelper.setupGuiFlatDiffuseLighting();
        }
        IRenderTypeBuffer.Impl buffers = minecraft.getRenderTypeBuffers().getBufferSource();
        renderer.renderItem(stack, ItemCameraTransforms.TransformType.GUI, false, pose,
                buffers, 15728880, OverlayTexture.NO_OVERLAY, model);
        buffers.finish();
        RenderSystem.enableDepthTest();
        if (flat) {
            RenderHelper.setupGui3DDiffuseLighting();
        }
        RenderSystem.disableAlphaTest();
        RenderSystem.disableRescaleNormal();
        pose.pop();
    }

    /** 数量角标 / 耐久条（1.16.5 的 {@code renderItemOverlayIntoGUI}）。 */
    public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
        Minecraft.getInstance().getItemRenderer()
                .renderItemOverlayIntoGUI(font, stack, x, y, null);
    }

    // ------------------------------------------------------------------
    // tooltip
    // ------------------------------------------------------------------

    public void renderTooltip(FontRenderer font, ItemStack stack, int mouseX, int mouseY) {
        if (tooltips != null) {
            tooltips.renderItemTooltip(pose, stack, mouseX, mouseY);
        }
    }

    public void renderTooltip(FontRenderer font, ITextComponent line, int mouseX, int mouseY) {
        java.util.List<ITextComponent> lines = new java.util.ArrayList<>(1);
        lines.add(line);
        renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /** tooltip 单行最大宽度（像素）：超过就自动折行，避免长英文提示跑到屏幕外。 */
    private static final int TOOLTIP_MAX_WIDTH = 220;

    public void renderComponentTooltip(FontRenderer font, List<ITextComponent> lines,
                                       int mouseX, int mouseY) {
        if (tooltips == null) {
            return;
        }
        tooltips.renderComponentTooltip(pose, expand(font, lines), font, mouseX, mouseY);
    }

    /**
     * 把 tooltip 展开成「每个元素一行」：拆掉组件里的 {@code \n} 并按宽度折行。
     *
     * <p>1.16.5 的 {@code renderToolTip} 只认「行列表」，组件内部的换行符会被当成
     * 普通字符画成一个方块；所以换行必须在进入渲染前处理掉。
     */
    private static List<ITextComponent> expand(FontRenderer font, List<ITextComponent> lines) {
        List<ITextComponent> out = new java.util.ArrayList<>(lines.size() + 4);
        for (ITextComponent line : lines) {
            for (ITextComponent composed : composeLines(line)) {
                if (font.getStringWidth(composed.getString()) <= TOOLTIP_MAX_WIDTH) {
                    out.add(composed);
                } else {
                    // 超长行才退化：只保留首段样式，按宽度折行
                    out.addAll(wrap(font, composed.getString(), firstStyle(composed)));
                }
            }
        }
        return out;
    }

    /**
     * 把一个「可能带子组件与换行符」的组件拆成多行，并<b>逐段保留颜色</b>。
     *
     * <p>界面里的提示是 {@code Chat.empty().appendSibling(灰).appendSibling(黄)...} 拼出来的，
     * 直接取 {@code getString()} 再重建字面量会把所有颜色抹掉（1.18.2/1.20.1 看到的是彩色）。
     * 这里沿组件树收集「文本 + 有效样式」的片段，再按 {@code \n} 分行重组。
     */
    private static List<ITextComponent> composeLines(ITextComponent root) {
        List<List<Run>> lines = new java.util.ArrayList<>(2);
        lines.add(new java.util.ArrayList<Run>());
        collectRuns(root, net.minecraft.util.text.Style.EMPTY, lines);
        List<ITextComponent> out = new java.util.ArrayList<>(lines.size());
        int last = lines.size() - 1;
        while (last >= 0 && lines.get(last).isEmpty()) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            out.add(compose(lines.get(i)));
        }
        return out;
    }

    /** 组件树 → 文本片段（含换行切分）。 */
    private static void collectRuns(ITextComponent component, net.minecraft.util.text.Style parent,
                                    List<List<Run>> lines) {
        net.minecraft.util.text.Style style = merged(parent, component.getStyle());
        String text = component.getUnformattedComponentText();
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    lines.add(new java.util.ArrayList<Run>());
                }
                if (!parts[i].isEmpty()) {
                    lines.get(lines.size() - 1).add(new Run(parts[i], style));
                }
            }
        }
        for (ITextComponent child : component.getSiblings()) {
            collectRuns(child, style, lines);
        }
    }

    /** 空字段不覆盖，等价于逐段叠加样式。 */
    private static net.minecraft.util.text.Style merged(net.minecraft.util.text.Style parent,
                                                        net.minecraft.util.text.Style own) {
        if (own == null || own == net.minecraft.util.text.Style.EMPTY) {
            return parent;
        }
        return Chat.literal("").mergeStyle(parent).mergeStyle(own).getStyle();
    }

    private static ITextComponent compose(List<Run> runs) {
        net.minecraft.util.text.IFormattableTextComponent line = Chat.empty();
        for (Run run : runs) {
            line.appendSibling(Chat.literal(run.text).mergeStyle(run.style));
        }
        return line;
    }

    /** 取首个片段的样式（超长行折行时兜底用）。 */
    private static net.minecraft.util.text.Style firstStyle(ITextComponent component) {
        net.minecraft.util.text.Style style = component.getStyle();
        for (ITextComponent child : component.getSiblings()) {
            net.minecraft.util.text.Style childStyle = firstStyle(child);
            if (childStyle != null && childStyle != net.minecraft.util.text.Style.EMPTY) {
                return childStyle;
            }
        }
        return style;
    }

    /** 一段带样式的文本。 */
    private static final class Run {

        private final String text;
        private final net.minecraft.util.text.Style style;

        Run(String text, net.minecraft.util.text.Style style) {
            this.text = text;
            this.style = style;
        }
    }

    /**
     * 按像素宽度贪心折行：优先在空格处断，超长单词按字符断。
     *
     * <p>折行必须带上原始 {@code Style}：1.18.2 / 1.20.1 的颜色是逐组件带过来的，
     * 若这里统一 {@code Chat.literal(...)}，界面里灰/黄/绿的提示会全部变白。
     */
    private static List<ITextComponent> wrap(FontRenderer font, String text,
                                            net.minecraft.util.text.Style style) {
        List<ITextComponent> out = new java.util.ArrayList<>(2);
        if (font.getStringWidth(text) <= TOOLTIP_MAX_WIDTH) {
            out.add(styled(text, style));
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (font.getStringWidth(candidate) <= TOOLTIP_MAX_WIDTH) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                out.add(styled(current.toString(), style));
                current.setLength(0);
            }
            // 单个词本身就超宽（长 ID / 无空格的中文）：按字符切
            while (font.getStringWidth(word) > TOOLTIP_MAX_WIDTH) {
                int cut = 1;
                while (cut < word.length()
                        && font.getStringWidth(word.substring(0, cut + 1)) <= TOOLTIP_MAX_WIDTH) {
                    cut++;
                }
                out.add(styled(word.substring(0, cut), style));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (current.length() > 0) {
            out.add(styled(current.toString(), style));
        }
        return out;
    }

    /** 生成带样式的组件（保留原行的颜色/粗体等）。 */
    private static ITextComponent styled(String text, net.minecraft.util.text.Style style) {
        return Chat.literal(text).mergeStyle(style);
    }

    // ------------------------------------------------------------------
    // 批缓冲 / 裁剪
    // ------------------------------------------------------------------

    /** 物品渲染用的批缓冲（1.16.5 的 {@code RenderTypeBuffers#getBufferSource()}）。 */
    public IRenderTypeBuffer.Impl bufferSource() {
        return Minecraft.getInstance().getRenderTypeBuffers().getBufferSource();
    }

    /** 提交物品渲染缓冲。 */
    public void flush() {
        Minecraft.getInstance().getRenderTypeBuffers().getBufferSource().finish();
    }

    /**
     * 开启一段自定义顶点批（替代 1.20 的 {@code bufferSource().getBuffer(RenderType.gui())}）。
     *
     * <p>1.16.5 没有 {@code RenderType.gui()}，GUI 上的裸四边形一律走
     * {@link Tessellator} 的立即模式；因此本批是<b>独占</b>的：
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
            this.builder = Tessellator.getInstance().getBuffer();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableAlphaTest();
            RenderSystem.disableTexture();
            // GUI 上随手画的四边形绕序不统一，开背面剔除会让一半方向的线段整块消失
            RenderSystem.disableCull();
            RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
            builder.begin(org.lwjgl.opengl.GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        }

        public Batch addVertex(Matrix4f matrix, float x, float y, float z) {
            builder.pos(matrix, x, y, z);
            return this;
        }

        /** ARGB；写完一个顶点即结束该顶点（1.16.5 需要显式 endVertex）。 */
        public Batch setColor(int argb) {
            builder.color(((argb >> 16) & 0xFF) / 255.0f,
                    ((argb >> 8) & 0xFF) / 255.0f,
                    (argb & 0xFF) / 255.0f,
                    ((argb >> 24) & 0xFF) / 255.0f);
            builder.endVertex();
            return this;
        }

        /** 提交并恢复 GUI 纹理/剔除状态。 */
        public void end() {
            Tessellator.getInstance().draw();
            RenderSystem.enableCull();
            RenderSystem.enableTexture();
            RenderSystem.enableAlphaTest();
            RenderSystem.disableBlend();
        }
    }

    /**
     * 开启裁剪区（GUI 坐标）。
     *
     * <p>1.16.5 的 {@code AbstractGui#enableScissor} 是 {@code protected static}，
     * 这里按相同公式自己换算成窗口像素坐标。
     */
    public void enableScissor(int x1, int y1, int x2, int y2) {
        int height = Minecraft.getInstance().getMainWindow().getHeight();
        double scale = Minecraft.getInstance().getMainWindow().getGuiScaleFactor();
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
