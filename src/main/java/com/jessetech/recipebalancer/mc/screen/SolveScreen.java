package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.jessetech.recipebalancer.mc.ui.AeStyle;

import java.util.ArrayList;
import java.util.List;

/** 求解结果二级页：只看报告，避免一级编码界面被文字挤占。 */
public final class SolveScreen extends Screen implements JeiPanelScreen {

    private final Screen parent;
    private final String report;
    private final boolean success;
    private List<FormattedCharSequence> lines = List.of();
    private double scroll;
    private int panelX = 40;
    private int panelY = 40;
    private int panelW = 560;
    private int panelH = 360;
    private int textY;
    private int textH;

    public SolveScreen(Screen parent, String report, boolean success) {
        super(Component.literal("求解结果"));
        this.parent = parent;
        this.report = report;
        this.success = success;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 560);
        panelH = Math.min(height - 16, 360);
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

        addRenderableWidget(Button.builder(Component.literal("返回编码"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
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

        int lineHeight = font.lineHeight + 1;
        double maxScroll = Math.max(0, lines.size() * lineHeight - textH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        AeStyle.subPanel(graphics, panelX + 4, textY, panelW - 8, textH);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
