package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.graph.SolveGraphView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 求解结果二级页：只看报告，避免一级编码界面被文字挤占。 */
public final class SolveScreen extends Screen implements JeiPanelScreen {

    private final Screen parent;
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
    private Button layoutButton;

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

        int wrapWidth = Math.max(80, panelW - 20);
        List<FormattedCharSequence> built = new ArrayList<>();
        for (String raw : report.split("\n", -1)) {
            if (raw.isEmpty()) {
                built.add(FormattedCharSequence.EMPTY);
            } else {
                built.addAll(font.split(Component.literal(raw), wrapWidth));
            }
        }
        lines = built;
        graph = success && result != null
                ? new SolveGraphView(result, recipes, targets, inventoryEnabled) : null;

        addRenderableWidget(Button.builder(Lang.c("返回编码"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
        if (graph != null) {
            toggleButton = addRenderableWidget(Button.builder(toggleLabel(), b -> toggleView())
                    .bounds(panelX + 112, panelY + panelH - 26, 100, 20).build());
            layoutButton = addRenderableWidget(Button.builder(layoutLabel(), b -> toggleLayout())
                    .bounds(panelX + 216, panelY + panelH - 26, 110, 20).build());
            layoutButton.visible = graphMode;
        }
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
        if (!graphMode) {
            scroll = 0;
        } else if (graph != null) {
            graph.resetCamera(panelW - 8, textH);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xD0101010);
        AeStyle.panel(graphics, panelX, panelY, panelW, panelH);
        AeStyle.header(graphics, panelX, panelY, panelW, 20);
        graphics.drawString(font, Lang.t(success ? "求解成功" : "求解失败"),
                panelX + 8, panelY + 6,
                success ? 0xFF1E6B2E : AeStyle.WARN, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

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

    private void renderGraph(GuiGraphics graphics, int mouseX, int mouseY) {
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (graphMode && graph != null) {
            if (inGraphArea(mouseX, mouseY)) {
                graph.zoomAt(mouseX - (panelX + 4), mouseY - textY,
                        Math.pow(1.18, scrollY));
            }
            return true;
        }
        scroll -= scrollY * (font.lineHeight + 1) * 3;
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
