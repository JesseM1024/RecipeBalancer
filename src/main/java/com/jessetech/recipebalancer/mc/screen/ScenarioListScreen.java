package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 已保存配方集（场景）列表：载入 / 另存 / 删除。 */
public final class ScenarioListScreen extends Screen implements JeiPanelScreen {

    private final BalancerScreen parent;
    private final ScenarioStore store;
    private final ScenarioDraft draft;
    private List<String> names = List.of();
    private int selected = -1;
    private double scroll;
    private String status = "";
    private EditBox nameBox;

    private int panelX = 20;
    private int panelY = 10;
    private static final int PANEL_W = 264;
    private static final int PANEL_H = 204;
    private int listY;
    private int listH;

    public ScenarioListScreen(BalancerScreen parent, ScenarioStore store, ScenarioDraft draft) {
        super(Component.literal("配方集"));
        this.parent = parent;
        this.store = store;
        this.draft = draft;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        listY = panelY + 28;
        listH = PANEL_H - 104;
        names = new ArrayList<>(store.names());
        scroll = 0;

        nameBox = new EditBox(font, panelX + 8, panelY + PANEL_H - 52, 150, 18,
                Component.literal("配方集名"));
        nameBox.setMaxLength(48);
        nameBox.setValue("default");
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("另存"), b -> saveCurrent())
                .bounds(panelX + 164, panelY + PANEL_H - 53, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("删除"), b -> deleteSelected())
                .bounds(panelX + 212, panelY + PANEL_H - 53, 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + PANEL_H - 27, 80, 20).build());
        JeiScreenSupport.notifyGuiInit(this);
    }

    private void saveCurrent() {
        try {
            String name = ScenarioStore.sanitize(nameBox.getValue());
            store.save(name, draft.toScenario());
            nameBox.setValue(name);
            names = new ArrayList<>(store.names());
            status = "已保存: " + name;
        } catch (RuntimeException e) {
            status = "保存失败: " + e.getMessage();
        }
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= names.size()) {
            status = "先选中一个配方集";
            return;
        }
        String name = names.get(selected);
        try {
            store.delete(name);
            names = new ArrayList<>(store.names());
            selected = -1;
            status = "已删除: " + name;
        } catch (RuntimeException e) {
            status = "删除失败: " + e.getMessage();
        }
    }

    private void load(String name) {
        try {
            // 工作区允许未填完（目标/配方为空），用草稿宽松读取
            Scenario scenario = store.loadDraft(name);
            draft.loadFrom(scenario);
            parent.onScenarioLoaded(name);
            minecraft.setScreen(parent);
        } catch (RuntimeException e) {
            status = "载入失败: " + e.getMessage();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, "配方集（点击载入）", panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        AeStyle.subPanel(graphics, panelX + 4, listY, PANEL_W - 8, listH);
        graphics.enableScissor(panelX + 4, listY, panelX + PANEL_W - 4, listY + listH);
        int rowH = 18;
        double maxScroll = Math.max(0, names.size() * rowH - listH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (names.isEmpty()) {
            graphics.drawString(font, "（没有已保存的配方集）", panelX + 8, listY + 4,
                    AeStyle.MUTED, false);
        }
        for (int i = 0; i < names.size(); i++) {
            int y = listY - (int) scroll + i * rowH;
            if (y + rowH < listY || y > listY + listH) {
                continue;
            }
            boolean hover = mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                    && mouseY >= y && mouseY < y + rowH;
            if (i == selected) {
                graphics.fill(panelX + 4, y, panelX + PANEL_W - 4, y + rowH - 1, 0x804A90D9);
            } else if (hover) {
                graphics.fill(panelX + 4, y, panelX + PANEL_W - 4, y + rowH - 1, 0x303A6EA5);
            }
            graphics.drawString(font, names.get(i), panelX + 8, y + 5, AeStyle.TEXT, false);
        }
        graphics.disableScissor();

        if (!status.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, PANEL_W - 16),
                    panelX + 8, panelY + PANEL_H - 68, AeStyle.ACCENT, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                && mouseY >= listY && mouseY < listY + listH) {
            int rowH = 18;
            int index = (int) ((mouseY - listY + scroll) / rowH);
            if (button == 0 && index >= 0 && index < names.size()) {
                selected = index;
                load(names.get(index));
                return true;
            }
            if (button == 1 && index >= 0 && index < names.size()) {
                selected = index;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseY >= listY && mouseY < listY + listH) {
            scroll -= scrollY * 18 * 2;
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
    public int jeiGuiLeft() {
        return panelX;
    }

    @Override
    public int jeiGuiTop() {
        return panelY;
    }

    @Override
    public int jeiGuiWidth() {
        return PANEL_W;
    }

    @Override
    public int jeiGuiHeight() {
        return PANEL_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
