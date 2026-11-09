package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.io.WorkspaceSession;
import com.jessetech.recipebalancer.mc.itemmap.ScenarioKeyNormalizer;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.Buttons;
import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 已保存配方集（场景）列表：筛选 / 载入 / 另存 / 删除。 */
public final class ScenarioListScreen extends RbScreen implements JeiPanelScreen {

    private static final int PANEL_W = 264;
    private static final int PANEL_H = 204;
    private static final int ROW_H = 18;

    private final BalancerScreen parent;
    private final ScenarioStore store;
    private final ScenarioDraft draft;
    private final String currentName;
    /** 目录里的全部工作区；names 是筛选后实际显示的列表。 */
    private List<String> allNames = J8.list();
    private List<String> names = J8.list();
    private int selected = -1;
    private double scroll;
    private String status = "";
    private TextFieldWidget nameBox;
    private TextFieldWidget filterBox;

    private int panelX = 20;
    private int panelY = 10;
    private int listY;
    private int listH;

    public ScenarioListScreen(BalancerScreen parent, ScenarioStore store,
                              ScenarioDraft draft, String currentName) {
        super(Lang.c("配方集"));
        this.parent = parent;
        this.store = store;
        this.draft = draft;
        this.currentName = currentName == null ? "" : currentName;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        listY = panelY + 28;
        listH = PANEL_H - 104;
        allNames = new ArrayList<>(store.names());
        scroll = 0;

        // 标题右侧的筛选框：只过滤列表显示，不动磁盘数据
        String query = filterBox == null ? "" : filterBox.getText();
        int titleW = font.getStringWidth(Lang.t("配方集"));
        int filterX = panelX + 8 + titleW + 6;
        filterBox = new TextFieldWidget(font, filterX, panelY + 3,
                Math.max(48, panelX + PANEL_W - 8 - filterX), 14, Lang.c("筛选"));
        filterBox.setMaxStringLength(40);
        filterBox.setResponder(value -> applyFilter());
        filterBox.setText(query);
        addButton(filterBox);

        nameBox = new TextFieldWidget(font, panelX + 8, panelY + PANEL_H - 52, 150, 18,
                Lang.c("配方集名"));
        nameBox.setMaxStringLength(48);
        nameBox.setText(J8.isBlank(currentName) ? WorkspaceSession.DEFAULT_NAME : currentName);
        addButton(nameBox);

        addButton(Buttons.builder(Lang.c("存储为"), b -> saveCurrent())
                .bounds(panelX + 164, panelY + PANEL_H - 53, 44, 20).build());
        addButton(Buttons.builder(Lang.c("删除"), b -> deleteSelected())
                .bounds(panelX + 212, panelY + PANEL_H - 53, 44, 20).build());
        addButton(Buttons.builder(Lang.c("返回"), b -> minecraft.displayGuiScreen(parent))
                .bounds(panelX + 8, panelY + PANEL_H - 27, 80, 20).build());
        // 底行中间：打开工作区文件夹（用于在文件管理器里直接复制/粘贴配方集 json）
        addButton(Buttons.builder(Lang.c("打开文件夹"), b -> openFolder())
                .bounds(panelX + 92, panelY + PANEL_H - 27, 80, 20).build());
        // 右下角：读取选中项（与右键载入等价，方便不做右键操作）
        addButton(Buttons.builder(Lang.c("读取"), b -> loadSelected())
                .bounds(panelX + PANEL_W - 8 - 80, panelY + PANEL_H - 27, 80, 20).build());
        applyFilter();
    }

    /** 按筛选框内容重建可见列表；筛选变化时清掉选中与滚动。 */
    private void applyFilter() {
        String query = filterBox == null
                ? "" : filterBox.getText().trim().toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String name : allNames) {
            if (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(name);
            }
        }
        names = filtered;
        selected = -1;
        scroll = 0;
    }

    /** 磁盘变动后重新读取并保持当前筛选。 */
    private void reloadNames() {
        allNames = new ArrayList<>(store.names());
        applyFilter();
    }

    private void saveCurrent() {
        try {
            String name = WorkspaceSession.nameOf(nameBox.getText());
            store.save(name, ScenarioKeyNormalizer.normalize(draft.toScenario()));
            nameBox.setText(name);
            // 另存后主界面跟随新名字，避免之后继续写回旧工作区
            parent.onWorkspaceNamed(name);
            reloadNames();
            status = Lang.t("已保存: %s", name);
        } catch (RuntimeException e) {
            status = Lang.t("保存失败: %s", e.getMessage());
        }
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= names.size()) {
            status = Lang.t("先选中一个配方集");
            return;
        }
        String name = names.get(selected);
        try {
            store.delete(name);
            reloadNames();
            status = Lang.t("已删除: %s", name);
        } catch (RuntimeException e) {
            status = Lang.t("删除失败: %s", e.getMessage());
        }
    }

    /** 打开配方集文件夹（工作区 json 所在目录），交给系统文件管理器。 */
    private void openFolder() {
        Path directory = store.directory();
        try {
            Files.createDirectories(directory);
            // 1.18.2 的 Util.OS 只有 openFile(File)，没有 openPath(Path)
            Util.getOSType().openFile(directory.toFile());
            status = Lang.t("已打开配方集文件夹");
        } catch (Exception e) {
            status = Lang.t("打开文件夹失败: %s", e.getMessage());
        }
    }

    /** 读取按钮：载入当前选中的配方集。 */
    private void loadSelected() {
        if (selected < 0 || selected >= names.size()) {
            status = Lang.t("先选中一个配方集");
            return;
        }
        load(names.get(selected));
    }

    private void load(String name) {
        try {
            // 工作区允许未填完（目标/配方为空），用草稿宽松读取
            Scenario scenario = store.loadDraft(name);
            draft.loadFrom(ScenarioKeyNormalizer.normalize(scenario));
            parent.onScenarioLoaded(name);
            minecraft.displayGuiScreen(parent);
        } catch (RuntimeException e) {
            status = Lang.t("载入失败: %s", e.getMessage());
        }
    }

    @Override
    public void renderBackground(MatrixStack pose) {
        Draw graphics = new Draw(pose, this);
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t("配方集"), panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float partialTick) {
        Draw graphics = new Draw(pose, this);
        super.render(pose, mouseX, mouseY, partialTick);

        AeStyle.subPanel(graphics, panelX + 4, listY, PANEL_W - 8, listH);
        graphics.enableScissor(panelX + 4, listY, panelX + PANEL_W - 4, listY + listH);
        double maxScroll = Math.max(0, names.size() * ROW_H - listH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (names.isEmpty()) {
            String empty = allNames.isEmpty() ? "（没有已保存的配方集）" : "（没有匹配的配方集）";
            graphics.drawString(font, Lang.t(empty), panelX + 8, listY + 4,
                    AeStyle.MUTED, false);
        }
        for (int i = 0; i < names.size(); i++) {
            int y = listY - (int) scroll + i * ROW_H;
            if (y + ROW_H < listY || y > listY + listH) {
                continue;
            }
            boolean hover = mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                    && mouseY >= y && mouseY < y + ROW_H;
            if (i == selected) {
                graphics.fill(panelX + 4, y, panelX + PANEL_W - 4, y + ROW_H - 1, 0x804A90D9);
            } else if (hover) {
                graphics.fill(panelX + 4, y, panelX + PANEL_W - 4, y + ROW_H - 1, 0x303A6EA5);
            }
            graphics.drawString(font, names.get(i), panelX + 8, y + 5, AeStyle.TEXT, false);
        }
        graphics.disableScissor();

        // 名称框/另存/删除上方的空白行：左边状态，右边常驻操作提示
        int infoY = panelY + PANEL_H - 68;
        String hint = Lang.t("左键选中 · 右键载入");
        int hintW = font.getStringWidth(hint);
        if (!status.isEmpty()) {
            graphics.drawString(font, font.trimStringToWidth(status,
                            Math.max(0, PANEL_W - 16 - hintW - 4)),
                    panelX + 8, infoY, AeStyle.ACCENT, false);
        }
        graphics.drawString(font, hint, panelX + PANEL_W - 8 - hintW, infoY,
                AeStyle.MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= panelX + 4 && mouseX < panelX + PANEL_W - 4
                && mouseY >= listY && mouseY < listY + listH) {
            int index = (int) ((mouseY - listY + scroll) / ROW_H);
            if ((button == 0 || button == 1) && index >= 0 && index < names.size()) {
                selected = index;
                if (button == 1) {
                    // 右键直接载入；左键只是选中，避免误点把当前草稿顶掉
                    load(names.get(index));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 1.18.2：只有纵向滚动，签名比 1.20+ 少一个 scrollX。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= panelX && mouseY >= listY && mouseY < listY + listH) {
            scroll -= delta * ROW_H * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 1.16.5 的 {@code Minecraft#displayGuiScreen(newScreen)} 会回调<b>旧屏</b>的
     * {@code onClose()}，所以这里只能做收尾、绝不能再切屏，否则会无限递归。
     */
    @Override
    public void onClose() {
    }

    /** ESC 返回上一级（按钮里的 displayGuiScreen 同理，都不会递归）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && modifiers == 0) {
            if (minecraft != null) {
                minecraft.displayGuiScreen(parent);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // JEI 面板定位
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
}
