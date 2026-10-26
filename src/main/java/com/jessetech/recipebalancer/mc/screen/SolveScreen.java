package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.graph.SolveGraphView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 求解结果二级页：只看报告，避免一级编码界面被文字挤占。 */
public final class SolveScreen extends Screen implements JeiPanelScreen {

    private final Screen parent;
    private final String report;
    private final boolean success;
    private final SolveResult result;
    private final List<Recipe> recipes;
    private final Set<String> targets;
    private final boolean inventoryEnabled;
    private List<FormattedCharSequence> lines = List.of();
    private SolveGraphView graph;
    private boolean graphMode;
    private boolean graphDragging;
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

    public SolveScreen(Screen parent, String report, boolean success) {
        this(parent, report, null, List.of(), Set.of(), false, success);
    }

    public SolveScreen(Screen parent, String report, SolveResult result,
                       List<Recipe> recipes, Set<String> targets,
                       boolean inventoryEnabled, boolean success) {
        super(Component.literal("求解结果"));
        this.parent = parent;
        this.report = report;
        this.result = result;
        this.recipes = recipes == null ? List.of() : recipes;
        this.targets = targets == null ? Set.of() : Set.copyOf(targets);
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

        addRenderableWidget(Button.builder(Component.literal("返回编码"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
        if (graph != null) {
            toggleButton = addRenderableWidget(Button.builder(toggleLabel(), b -> toggleView())
                    .bounds(panelX + 112, panelY + panelH - 26, 100, 20).build());
        }
    }

    private Component toggleLabel() {
        return Component.literal(graphMode ? "文本输出" : "流程图");
    }

    private void toggleView() {
        graphMode = !graphMode;
        if (toggleButton != null) {
            toggleButton.setMessage(toggleLabel());
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
        graphics.drawString(font, success ? "求解成功" : "求解失败", panelX + 8, panelY + 6,
                success ? 0xFF1E6B2E : AeStyle.WARN, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        AeStyle.subPanel(graphics, panelX + 4, textY, panelW - 8, textH);
        if (graphMode && graph != null) {
            renderGraph(graphics);
            return;
        }

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
            graphics.drawString(font, "滚轮滚动", panelX + panelW - 64, panelY + panelH - 21,
                    AeStyle.MUTED, false);
        }
    }

    private void renderGraph(GuiGraphics graphics) {
        graph.render(graphics, font, panelX + 4, textY, panelW - 8, textH);
    }

    private boolean inGraphArea(double mouseX, double mouseY) {
        return mouseX >= panelX + 4 && mouseX < panelX + panelW - 4
                && mouseY >= textY && mouseY < textY + textH;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (graphMode && graph != null && button == 0 && inGraphArea(mouseX, mouseY)) {
            graphDragging = true;
            lastGraphMouseX = mouseX;
            lastGraphMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
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
