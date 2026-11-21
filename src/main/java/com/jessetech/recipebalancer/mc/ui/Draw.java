package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.Matrix4f;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.12.2 的绘制入口（替代 1.16.5 的 {@code MatrixStack} 版）。
 *
 * <p>这一代没有 {@code MatrixStack}：位移/缩放直接作用在 GL 矩阵上
 * （{@link GlStateManager#pushMatrix()} / {@code translate} / {@code scale}），
 * 因此 {@link #pose()} 返回的是一个<b>无状态的薄壳</b>，只把调用转发给 GlStateManager。
 * 界面代码里 {@code pose.push()/translate()/pop()} 的写法可以原样保留。
 *
 * <p>其余原语与 1.16.5 版同名同参：
 * <ul>
 *   <li>{@code fill} → {@link Gui#drawRect}；</li>
 *   <li>文字 → {@link FontRenderer#drawStringWithShadow} / {@code drawString}；</li>
 *   <li>物品 → {@code RenderItem#renderItemAndEffectIntoGUI}；</li>
 *   <li>tooltip 由界面（{@code RbScreen}）实现 {@link Tooltips} 转交原版。</li>
 * </ul>
 */
public final class Draw {

    /**
     * tooltip 绘制入口。
     *
     * <p>1.12.2 的 {@code GuiScreen#renderToolTip} 是 {@code protected}，外部类调不到，
     * 因此由界面自己实现并把 {@code this} 传进来（见 {@code RbScreen}）。
     */
    public interface Tooltips {
        /**
         * 物品 tooltip。
         *
         * <p><b>名字刻意不叫 renderTooltip</b>：重混淆会把 MC 的同名方法改成 SRG 名，
         * 实现方法被改名后与接口对不上 → 运行期 {@code AbstractMethodError}。
         */
        void renderItemTooltip(ItemStack stack, int mouseX, int mouseY);

        void renderComponentTooltip(List<ITextComponent> lines, FontRenderer font,
                                    int mouseX, int mouseY);
    }

    /** 全局唯一的位姿薄壳（1.12.2 的变换是全局状态，不需要每屏一个实例）。 */
    private static final Pose POSE = new Pose();

    /** 位姿薄壳：只转发到 {@link GlStateManager} 的矩阵栈。 */
    public static final class Pose {

        private static final Matrix4f IDENTITY = new Matrix4f();

        private Pose() {
        }

        public void push() {
            GlStateManager.pushMatrix();
        }

        public void pop() {
            GlStateManager.popMatrix();
        }

        public void translate(double x, double y, double z) {
            GlStateManager.translate(x, y, z);
        }

        public void scale(float x, float y, float z) {
            GlStateManager.scale(x, y, z);
        }

        /**
         * 1.12.2 的顶点批直接用当前 GL 矩阵，不需要把矩阵传进去；
         * 保留这个方法是为了让调用方少改一行。
         */
        public Matrix4f matrix() {
            return IDENTITY;
        }
    }

    private final Tooltips tooltips;

    public Draw() {
        this(null);
    }

    public Draw(Tooltips tooltips) {
        this.tooltips = tooltips;
    }

    /** 供确实需要直接操作矩阵的地方使用。 */
    public Pose pose() {
        return POSE;
    }

    // ------------------------------------------------------------------
    // 基础绘制原语
    // ------------------------------------------------------------------

    public void fill(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
    }

    public void drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(text, x, y, color);
        } else {
            font.drawString(text, x, y, color);
        }
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color) {
        font.drawStringWithShadow(text.getFormattedText(), x, y, color);
    }

    public void drawString(FontRenderer font, ITextComponent text, int x, int y, int color,
                           boolean shadow) {
        drawString(font, text.getFormattedText(), x, y, color, shadow);
    }

    /** 16×16 物品图标（绝对 GUI 坐标，不支持位姿缩放）。 */
    public void renderItem(ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // 先冲刷本类的四边形批：物品是另一套 Tessellator 通道，混着画会把方块盖掉
        flush();
        Minecraft minecraft = Minecraft.getMinecraft();
        // 1.12.2 的 GUI 物品需要标准物品光照，否则图标是「全亮平涂」，没有明暗
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        minecraft.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    /**
     * 在当前 GL 变换下画 16×16 物品。
     *
     * <p>1.12.2 的 {@code RenderItem#renderItemModelIntoGUI} 内部自己压栈并平移到
     * 传入的 (x, y)，所以这里先把坐标系搬到调用方要求的位置，再以 (0,0) 调用，
     * 于是外层的缩放/平移（流程图相机）能正确作用在物品上。
     */
    public void renderItemPosed(ItemStack stack, float x, float y) {
        if (stack.isEmpty()) {
            return;
        }
        flush();
        Minecraft minecraft = Minecraft.getMinecraft();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        minecraft.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
    }

    /** 数量角标 / 耐久条（1.12.2 的 {@code renderItemOverlayIntoGUI}）。 */
    public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
        flush();
        Minecraft.getMinecraft().getRenderItem()
                .renderItemOverlayIntoGUI(font, stack, x, y, null);
    }

    // ------------------------------------------------------------------
    // tooltip
    // ------------------------------------------------------------------

    public void renderTooltip(FontRenderer font, ItemStack stack, int mouseX, int mouseY) {
        if (tooltips != null) {
            tooltips.renderItemTooltip(stack, mouseX, mouseY);
        }
    }

    public void renderTooltip(FontRenderer font, ITextComponent line, int mouseX, int mouseY) {
        List<ITextComponent> lines = new ArrayList<>(1);
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
        tooltips.renderComponentTooltip(expand(font, lines), font, mouseX, mouseY);
    }

    /**
     * 把 tooltip 展开成「每个元素一行」：拆掉组件里的 {@code \n} 并按宽度折行。
     *
     * <p>1.12.2 的 {@code drawHoveringText} 只认「行列表」，组件内部的换行符会被当成
     * 普通字符画成一个方块；所以换行必须在进入渲染前处理掉。
     */
    private static List<ITextComponent> expand(FontRenderer font, List<ITextComponent> lines) {
        List<ITextComponent> out = new ArrayList<>(lines.size() + 4);
        for (ITextComponent line : lines) {
            for (ITextComponent composed : composeLines(line)) {
                if (font.getStringWidth(composed.getUnformattedText()) <= TOOLTIP_MAX_WIDTH) {
                    out.add(composed);
                } else {
                    // 超长行才退化：只保留首段样式，按宽度折行
                    out.addAll(wrap(font, composed.getUnformattedText(), firstStyle(composed)));
                }
            }
        }
        return out;
    }

    /** 把一个「可能带子组件与换行符」的组件拆成多行，并逐段保留颜色。 */
    private static List<ITextComponent> composeLines(ITextComponent root) {
        List<List<Run>> lines = new ArrayList<>(2);
        lines.add(new ArrayList<Run>());
        collectRuns(root, null, lines);
        List<ITextComponent> out = new ArrayList<>(lines.size());
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
    private static void collectRuns(ITextComponent component, Style parent,
                                    List<List<Run>> lines) {
        Style style = merged(parent, component.getStyle());
        String text = component.getUnformattedComponentText();
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    lines.add(new ArrayList<Run>());
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

    /** 1.12.2 的 {@link Style} 没有 merge：子样式非空就以子样式为准（颜色都在叶子上）。 */
    private static Style merged(Style parent, Style own) {
        if (own == null || own.isEmpty()) {
            return parent;
        }
        return own;
    }

    private static ITextComponent compose(List<Run> runs) {
        ITextComponent line = Chat.empty();
        for (Run run : runs) {
            line.appendSibling(Chat.styled(run.text, run.style));
        }
        return line;
    }

    /** 取首个片段的样式（超长行折行时兜底用）。 */
    private static Style firstStyle(ITextComponent component) {
        Style style = component.getStyle();
        for (ITextComponent child : component.getSiblings()) {
            Style childStyle = firstStyle(child);
            if (childStyle != null && !childStyle.isEmpty()) {
                return childStyle;
            }
        }
        return style;
    }

    /** 一段带样式的文本。 */
    private static final class Run {

        private final String text;
        private final Style style;

        Run(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    /**
     * 按像素宽度贪心折行：优先在空格处断，超长单词按字符断。
     *
     * <p>折行必须带上原始 {@link Style}：界面里灰/黄/绿的提示是逐组件带颜色的，
     * 若这里统一重建字面量，颜色会全部变白。
     */
    private static List<ITextComponent> wrap(FontRenderer font, String text, Style style) {
        List<ITextComponent> out = new ArrayList<>(2);
        if (font.getStringWidth(text) <= TOOLTIP_MAX_WIDTH) {
            out.add(Chat.styled(text, style));
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
                out.add(Chat.styled(current.toString(), style));
                current.setLength(0);
            }
            // 单个词本身就超宽（长 ID / 无空格的中文）：按字符切
            while (font.getStringWidth(word) > TOOLTIP_MAX_WIDTH) {
                int cut = 1;
                while (cut < word.length()
                        && font.getStringWidth(word.substring(0, cut + 1)) <= TOOLTIP_MAX_WIDTH) {
                    cut++;
                }
                out.add(Chat.styled(word.substring(0, cut), style));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (current.length() > 0) {
            out.add(Chat.styled(current.toString(), style));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 批缓冲 / 裁剪
    // ------------------------------------------------------------------

    /** 1.12.2 的物品渲染是立即模式，没有需要显式提交的缓冲。 */
    public void flush() {
        // 空实现：保留调用形状
    }

    /**
     * 开启一段自定义顶点批（替代 1.20 的 {@code bufferSource().getBuffer(RenderType.gui())}）。
     *
     * <p>1.12.2 没有 {@code RenderType.gui()}，GUI 上的裸四边形一律走
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
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            GlStateManager.disableAlpha();
            GlStateManager.disableTexture2D();
            // GUI 上随手画的四边形绕序不统一，开背面剔除会让一半方向的线段整块消失
            GlStateManager.disableCull();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        }

        /** 1.12.2 的顶点位置直接乘当前 GL 矩阵，{@code matrix} 仅为保持调用形状。 */
        public Batch addVertex(Matrix4f matrix, float x, float y, float z) {
            builder.pos(x, y, z);
            return this;
        }

        /** ARGB；写完一个顶点即结束该顶点（1.12.2 需要显式 endVertex）。 */
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
            GlStateManager.enableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.disableBlend();
        }
    }

    /**
     * 开启裁剪区（GUI 坐标）。
     *
     * <p>1.12.2 的 {@code Gui#enableScissor} 不存在，这里按相同公式自己换算成
     * 窗口像素坐标（注意 1.12.2 是 LWJGL2，直接调用 {@link GL11#glScissor}）。
     */
    public void enableScissor(int x1, int y1, int x2, int y2) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(minecraft);
        double scale = resolution.getScaleFactor();
        int height = minecraft.displayHeight;
        double dx1 = x1 * scale;
        double dy1 = height - y2 * scale;
        double dx2 = x2 * scale;
        double dy2 = height - y1 * scale;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) dx1, (int) dy1,
                Math.max(0, (int) (dx2 - dx1)), Math.max(0, (int) (dy2 - dy1)));
    }

    public void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
