package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 目标产物列表：左侧点击编辑用量，右键删除，空白处「添加目标」。 */
public final class TargetListScreen extends Screen {

    private final BalancerScreen parent;
    private final ScenarioDraft draft;
    private double scroll;
    private String status = "";

    private int panelX;
    private int panelY;
    private static final int PANEL_W = 248;
    private static final int PANEL_H = 186;
    private int listY;
    private int listH;

    public TargetListScreen(BalancerScreen parent, ScenarioDraft draft) {
        super(Component.literal("目标产物"));
        this.parent = parent;
        this.draft = draft;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        listY = panelY + 26;
        listH = PANEL_H - 78;

        addRenderableWidget(Button.builder(Component.literal("添加目标"), b -> addTarget())
                .bounds(panelX + 8, panelY + PANEL_H - 26, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
                .bounds(panelX + PANEL_W - 88, panelY + PANEL_H - 26, 80, 20).build());
    }

    private void addTarget() {
        minecraft.setScreen(new AmountScreen(this, "添加目标产物", "", 1.0, true,
                (key, amount) -> {
                    draft.putTarget(key, amount);
                    parent.onDraftChanged();
                    status = "已添加目标: " + key;
                }));
    }

    private void editTarget(String key, double amount) {
        minecraft.setScreen(new AmountScreen(this, "修改目标产物", key, amount, true,
                (newKey, newAmount) -> {
                    if (!newKey.equals(key)) {
                        draft.removeTarget(key);
                    }
                    draft.putTarget(newKey, newAmount);
                    parent.onDraftChanged();
                    status = "已更新目标: " + newKey;
                }));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, "目标产物（左键编辑 / 右键删除）", panelX + 8, panelY + 6,
                AeStyle.TEXT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
        int rowH = 20;
        double maxScroll = Math.max(0, entries.size() * rowH - listH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        AeStyle.subPanel(graphics, panelX + 4, listY, PANEL_W - 8, listH);
        graphics.enableScissor(panelX + 4, listY, panelX + PANEL_W - 4, listY + listH);
        if (entries.isEmpty()) {
            graphics.drawString(font, "（空）点「添加目标」", panelX + 8, listY + 4,
                    AeStyle.MUTED, false);
        }
        for (int i = 0; i < entries.size(); i++) {
            int y = listY - (int) scroll + i * rowH;
            if (y + rowH < listY || y > listY + listH) {
                continue;
            }
            Map.Entry<String, Double> entry = entries.get(i);
            boolean hover = mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                    && mouseY >= y && mouseY < y + rowH;
            if (hover) {
                graphics.fill(panelX + 4, y, panelX + PANEL_W - 4, y + rowH - 1, 0x303A6EA5);
            }
            AeStyle.slot(graphics, panelX + 6, y + 2);
            ItemKeyMapper.renderIcon(graphics, font, entry.getKey(), panelX + 6, y + 2, null);
            String name = ItemKeyMapper.displayName(entry.getKey());
            graphics.drawString(font, font.plainSubstrByWidth(name, 110), panelX + 26, y + 2,
                    AeStyle.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(entry.getKey(), 110),
                    panelX + 26, y + 11, AeStyle.MUTED, false);
            String amount = TimeExpression.formatNumber(entry.getValue()) + "/s";
            graphics.drawString(font, amount, panelX + PANEL_W - 8 - font.width(amount), y + 5,
                    AeStyle.ACCENT, false);
        }
        graphics.disableScissor();

        if (!status.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, PANEL_W - 16),
                    panelX + 8, panelY + PANEL_H - 40, AeStyle.ACCENT, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                && mouseY >= listY && mouseY < listY + listH) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
            int index = (int) ((mouseY - listY + scroll) / 20);
            if (index >= 0 && index < entries.size()) {
                Map.Entry<String, Double> entry = entries.get(index);
                if (button == 0) {
                    editTarget(entry.getKey(), entry.getValue());
                    return true;
                }
                if (button == 1) {
                    draft.removeTarget(entry.getKey());
                    parent.onDraftChanged();
                    status = "已删除目标: " + entry.getKey();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseY >= listY && mouseY < listY + listH) {
            scroll -= scrollY * 20 * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
}
