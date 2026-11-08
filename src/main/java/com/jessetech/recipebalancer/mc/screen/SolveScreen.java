package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.Buttons;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import com.jessetech.recipebalancer.mc.ui.graph.SolveGraphView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 求解结果二级页：只看报告，避免一级编码界面被文字挤占。 */
public final class SolveScreen extends RbScreen implements JeiPanelScreen {

    private final Screen parent;
    /** 注册名口径的报告（ingredient|/stack| 已被替换成显示名，普通物品仍是 registry id）。 */
    private final String report;
    private final boolean success;
    private final SolveResult result;
    private final List<Recipe> recipes;
    private final Map<String, Double> targets;
    private final boolean inventoryEnabled;
    private List<FormattedCharSequence> lines = List.of();
    private SolveGraphView graph;
    private boolean graphMode;
    private boolean graphDragging;
    private boolean graphNodeDragging;
    private double lastGraphMouseX;
    private double lastGraphMouseY;
    private double scroll;
    private int panelX = 40;
    private int panelY = 40;
    private int panelW = 560;
    private int panelH = 360;
    private int textY;
    private int textH;
    private Button toggleButton;
    private Button namesButton;
    private Button layoutButton;

    /** 名称显示口径：false = 显示名（默认，如「煤炭」），true = 注册名（如 minecraft:coal）。 */
    private boolean showRegistryNames;
    /** 显示名口径的报告，切到显示名时惰性生成后缓存。 */
    private String displayReport;

    /** 报告里的注册名 token：{@code namespace:path}；只可能是整合包里真实存在的物品/机器。 */
    private static final Pattern REGISTRY_ID =
            Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    // ---- 文本页段落标题配色（只作用于本页渲染，报告文本本身不变） ----
    /** 深绿：外部原料 / 循环亏损 / 亏损环 / 原料输入。 */
    private static final int TITLE_GREEN = 0xFF1E6B2E;
    /** 深红：启动库存类段落。 */
    private static final int TITLE_RED = 0xFF9E2B2B;
    /** 深褐（黄色色相）：副产物产出。 */
    private static final int TITLE_BROWN = 0xFF6B4E16;

    private static final String[] GREEN_TITLES = {
            ">>自动检测外部原料",
            ">>循环亏损物质（检测结果）",
            ">>亏损环涉及的物质",
            ">>原料输入",
            ">>原料输入（/t）",
    };
    private static final String[] RED_TITLES = {
            ">>自增殖配方启动库存",
            ">>完美循环启动投料（最小集合）",
    };
    private static final String[] BROWN_TITLES = {
            ">>副产物产出（/t）",
            ">>副产物产出",
    };

    private static Map<String, Integer> titleColors = Map.of();
    private static String titleColorsLanguage;

    public SolveScreen(Screen parent, String report, boolean success) {
        this(parent, report, null, List.of(), Map.of(), false, success);
    }

    public SolveScreen(Screen parent, String report, SolveResult result,
                       List<Recipe> recipes, Map<String, Double> targets,
                       boolean inventoryEnabled, boolean success) {
        super(Lang.c("求解结果"));
        this.parent = parent;
        this.report = report;
        this.result = result;
        this.recipes = recipes == null ? List.of() : recipes;
        this.targets = targets == null ? Map.of() : Map.copyOf(targets);
        this.inventoryEnabled = inventoryEnabled;
        this.success = success;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 760);
        panelH = Math.max(240, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        textY = panelY + 26;
        textH = panelH - 58;

        rebuildLines();
        graph = success && result != null
                ? new SolveGraphView(result, recipes, targets, inventoryEnabled) : null;

        addRenderableWidget(Buttons.builder(Lang.c("返回编码"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
        namesButton = addRenderableWidget(Buttons.builder(namesLabel(), b -> toggleNames())
                .bounds(panelX + 216, panelY + panelH - 26, 110, 20).build());
        namesButton.visible = !graphMode;
        if (graph != null) {
            toggleButton = addRenderableWidget(Buttons.builder(toggleLabel(), b -> toggleView())
                    .bounds(panelX + 112, panelY + panelH - 26, 100, 20).build());
            layoutButton = addRenderableWidget(Buttons.builder(layoutLabel(), b -> toggleLayout())
                    .bounds(panelX + 216, panelY + panelH - 26, 110, 20).build());
            layoutButton.visible = graphMode;
        }
    }

    /** 按当前名称口径把报告拆成可绘制的行；切换名称时整页重排。 */
    private void rebuildLines() {
        int wrapWidth = Math.max(80, panelW - 20);
        List<FormattedCharSequence> built = new ArrayList<>();
        for (String raw : currentReport().split("\n", -1)) {
            if (raw.isEmpty()) {
                built.add(FormattedCharSequence.EMPTY);
            } else {
                built.addAll(font.split(styled(raw), wrapWidth));
            }
        }
        lines = built;
    }

    private String currentReport() {
        if (showRegistryNames) {
            return report;
        }
        if (displayReport == null) {
            displayReport = toDisplayNames(report);
        }
        return displayReport;
    }

    /**
     * 注册名换显示名：只处理整合包里真实存在的物品/机器（{@code minecraft:coal} → 「煤炭」）。
     * 已由 {@code prettyText} 换成显示名的 {@code ingredient|…}/{@code stack|…} 不会再次出现，
     * 因此 JEI 写的流体/化学品/药水不参与切换。
     *
     * <p>配平器允许自定义物质（如手输 {@code MgCl2}，整合包里没有这个名字），
     * 这类 key 查不到对应物品，切换按钮对它们无效，原样显示。
     */
    private static String toDisplayNames(String text) {
        Matcher matcher = REGISTRY_ID.matcher(text);
        StringBuilder output = new StringBuilder(text.length() + 64);
        while (matcher.find()) {
            String id = matcher.group();
            String name = ItemKeyMapper.exists(id) ? ItemKeyMapper.reportName(id) : id;
            matcher.appendReplacement(output, Matcher.quoteReplacement(name));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private void toggleNames() {
        showRegistryNames = !showRegistryNames;
        if (namesButton != null) {
            namesButton.setMessage(namesLabel());
        }
        rebuildLines();
    }

    private Component namesLabel() {
        return Lang.c(showRegistryNames ? "名称: 注册名" : "名称: 显示名");
    }

    /** 段落标题按 {@link #titleColor(String)} 上色，其余行保持默认正文色。 */
    private Component styled(String raw) {
        int color = titleColor(raw);
        return color == 0
                ? Chat.literal(raw)
                : Chat.literal(raw).withStyle(Style.EMPTY.withColor(color));
    }

    /**
     * 标题行在段落上属于哪一类的查表：键是当前语言下的标题文本（中文源文本或语言文件的译文），
     * 因此中英文共用同一套规则；语言切换后按 {@link Lang#languageCode()} 重建。
     */
    private static int titleColor(String line) {
        String title = line.trim();
        if (!title.startsWith(">>")) {
            return 0;
        }
        String language = Lang.languageCode();
        if (!language.equals(titleColorsLanguage)) {
            Map<String, Integer> built = new HashMap<>();
            for (String key : GREEN_TITLES) {
                built.put(Lang.t(key), TITLE_GREEN);
            }
            for (String key : RED_TITLES) {
                built.put(Lang.t(key), TITLE_RED);
            }
            for (String key : BROWN_TITLES) {
                built.put(Lang.t(key), TITLE_BROWN);
            }
            titleColors = Map.copyOf(built);
            titleColorsLanguage = language;
        }
        return titleColors.getOrDefault(title, 0);
    }

    private Component toggleLabel() {
        return Lang.c(graphMode ? "文本输出" : "流程图");
    }

    /** 布局按钮：显示当前模式，点一下在分层 / 弹性之间切换。 */
    private Component layoutLabel() {
        boolean force = graph != null
                && graph.layoutMode() == SolveGraphView.LayoutMode.FORCE;
        return Lang.c(force ? "布局: 弹性" : "布局: 分层");
    }

    private void toggleLayout() {
        if (graph == null) {
            return;
        }
        graph.toggleLayoutMode();
        if (layoutButton != null) {
            layoutButton.setMessage(layoutLabel());
        }
        graph.resetCamera(panelW - 8, textH);
    }

    private void toggleView() {
        graphMode = !graphMode;
        if (toggleButton != null) {
            toggleButton.setMessage(toggleLabel());
        }
        if (layoutButton != null) {
            layoutButton.visible = graphMode;
        }
        if (namesButton != null) {
            namesButton.visible = !graphMode;
        }
        if (!graphMode) {
            scroll = 0;
        } else if (graph != null) {
            graph.resetCamera(panelW - 8, textH);
        }
    }

    @Override
    public void renderBackground(PoseStack pose) {
        Draw graphics = new Draw(pose, this);
        graphics.fill(0, 0, width, height, 0xD0101010);
        AeStyle.panel(graphics, panelX, panelY, panelW, panelH);
        AeStyle.header(graphics, panelX, panelY, panelW, 20);
        graphics.drawString(font, Lang.t(success ? "求解成功" : "求解失败"),
                panelX + 8, panelY + 6,
                success ? 0xFF1E6B2E : AeStyle.WARN, false);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        Draw graphics = new Draw(pose, this);
        super.render(pose, mouseX, mouseY, partialTick);

        AeStyle.subPanel(graphics, panelX + 4, textY, panelW - 8, textH);
        if (graphMode && graph != null) {
            renderGraph(graphics, mouseX, mouseY);
            return;
        }
        graphNodeDragging = false;

        int lineHeight = font.lineHeight + 1;
        double maxScroll = Math.max(0, lines.size() * lineHeight - textH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        graphics.enableScissor(panelX + 4, textY, panelX + panelW - 4, textY + textH);
        int y = textY + 2 - (int) scroll;
        for (FormattedCharSequence line : lines) {
            if (y + font.lineHeight >= textY && y <= textY + textH) {
                graphics.drawString(font, line, panelX + 8, y, AeStyle.TEXT, false);
            }
            y += lineHeight;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            graphics.drawString(font, Lang.t("滚轮滚动"),
                    panelX + panelW - 64, panelY + panelH - 21, AeStyle.MUTED, false);
        }
    }

    private void renderGraph(Draw graphics, int mouseX, int mouseY) {
        graph.render(graphics, font, panelX + 4, textY, panelW - 8, textH);
        if (!graphNodeDragging) {
            graph.renderTooltip(graphics, font, mouseX, mouseY);
        }
    }

    private boolean inGraphArea(double mouseX, double mouseY) {
        return mouseX >= panelX + 4 && mouseX < panelX + panelW - 4
                && mouseY >= textY && mouseY < textY + textH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (graphMode && graph != null && button == 0 && inGraphArea(mouseX, mouseY)) {
            if (graph.beginNodeDrag(mouseX, mouseY)) {
                graphNodeDragging = true;
                graphDragging = false;
            } else {
                // 左键点空白只平移，不取消选中（拖动画面时选中高亮保持）
                graphDragging = true;
                graphNodeDragging = false;
                lastGraphMouseX = mouseX;
                lastGraphMouseY = mouseY;
            }
            return true;
        }
        if (graphMode && graph != null && button == 1 && inGraphArea(mouseX, mouseY)) {
            // 右键点空白处取消选中；点节点则保留
            graph.clearSelectionOnRightClick(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (graphNodeDragging && graph != null) {
            graph.dragNodeTo(mouseX, mouseY);
            return true;
        }
        if (graphDragging && graph != null) {
            graph.panBy(mouseX - lastGraphMouseX, mouseY - lastGraphMouseY);
            lastGraphMouseX = mouseX;
            lastGraphMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (graphNodeDragging && graph != null) {
            graph.endNodeDrag();
            graphNodeDragging = false;
            return true;
        }
        if (graphDragging) {
            graphDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 1.18.2：只有纵向滚动，签名比 1.20+ 少一个 scrollX。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (graphMode && graph != null) {
            if (inGraphArea(mouseX, mouseY)) {
                graph.zoomAt(mouseX - (panelX + 4), mouseY - textY,
                        Math.pow(1.18, delta));
            }
            return true;
        }
        scroll -= delta * (font.lineHeight + 1) * 3;
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // JEI 面板定位：结果页也要报面板区域，否则 JEI 不认为它「有 GUI」
    @Override
    public int jeiGuiLeft() {
        return panelX;
    }

    @Override
    public int jeiGuiTop() {
        return panelY;
    }

    @Override
    public int jeiGuiWidth() {
        return panelW;
    }

    @Override
    public int jeiGuiHeight() {
        return panelH;
    }
}
