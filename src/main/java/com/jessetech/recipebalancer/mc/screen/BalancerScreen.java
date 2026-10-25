package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.report.TextReporter;
import com.jessetech.recipebalancer.core.solve.Balancer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原生主界面（M2）：配方/目标编辑 + 求解 + 文本结果 + 场景保存/载入。
 *
 * <p>契约 C6：本类只做绘制与事件分发；求解在 {@link Balancer}、编辑状态在
 * {@link ScenarioDraft}、报告在 {@link TextReporter}。
 */
public final class BalancerScreen extends Screen {

    private static final int PAD = 8;
    private static final int ROW_H = 22;
    private static final ScenarioStore STORE = new ScenarioStore(
            FMLPaths.CONFIGDIR.get().resolve("recipebalancer").resolve("scenarios"));

    private ScenarioDraft draft = ScenarioDraft.blank();
    private SolveResult result;
    private String scenarioName = "default";
    private String status = "添加配方与目标产物后点「求解」";

    private int selectedRecipe = -1;
    private String selectedTarget;
    private double recipeScroll;
    private double targetScroll;
    private double reportScroll;

    private int leftX;
    private int leftY;
    private int leftW;
    private int leftH;
    private int recipeListY;
    private int recipeListH;
    private int targetListY;
    private int targetListH;
    private int rightX;
    private int rightY;
    private int rightW;
    private int rightH;
    private int reportY;
    private int reportH;

    private List<FormattedCharSequence> reportLines = List.of();
    private Button objectiveButton;
    private Button autoRawButton;
    private Button autoSeedButton;
    private Button autoBootstrapButton;
    private EditBox nameBox;

    public BalancerScreen() {
        super(Component.literal("RecipeBalancer · 产线配平"));
    }

    @Override
    protected void init() {
        leftX = PAD;
        leftY = 30;
        // 窄窗口（GUI 缩放 3 时约 320×240）也要保证右栏至少 120px，不能溢出屏幕
        leftW = Math.max(170, Math.min((width - 3 * PAD) * 45 / 100, width - 3 * PAD - 170));
        leftH = Math.max(110, height - 52 - leftY);
        rightX = leftX + leftW + PAD;
        rightY = leftY;
        rightW = Math.max(120, width - rightX - PAD);
        rightH = leftH;

        recipeListY = leftY + 18;
        recipeListH = Math.max(38, Math.min(leftH * 38 / 100, leftH - 120));
        targetListY = recipeListY + recipeListH + 48;
        targetListH = Math.max(18, leftY + leftH - 28 - targetListY);

        reportY = rightY + 72;
        reportH = Math.max(30, rightY + rightH - reportY - 6);

        recipeScroll = clamp(recipeScroll, 0, Math.max(0, draft.recipeCount() * ROW_H - recipeListH));
        targetScroll = clamp(targetScroll, 0, Math.max(0, draft.targets().size() * ROW_H - targetListH));

        int recipeButtonWidth = Math.max(34, (leftW - 9) / 4);
        int recipeButtonY = recipeListY + recipeListH + 3;
        addRenderableWidget(Button.builder(Component.literal("添加"), b -> openRecipeEditor(-1))
                .bounds(leftX, recipeButtonY, recipeButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("编辑"), b -> openRecipeEditor(selectedRecipe))
                .bounds(leftX + recipeButtonWidth + 3, recipeButtonY, recipeButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("删除"), b -> deleteRecipe())
                .bounds(leftX + 2 * (recipeButtonWidth + 3), recipeButtonY, recipeButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("启/禁"), b -> toggleRecipe())
                .bounds(leftX + 3 * (recipeButtonWidth + 3), recipeButtonY, recipeButtonWidth, 20).build());

        int targetButtonWidth = Math.max(48, (leftW - 6) / 3);
        int targetButtonY = leftY + leftH - 22;
        addRenderableWidget(Button.builder(Component.literal("加目标"), b -> openTargetEditor(null))
                .bounds(leftX, targetButtonY, targetButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("改目标"), b -> openTargetEditor(selectedTarget))
                .bounds(leftX + targetButtonWidth + 3, targetButtonY, targetButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("删目标"), b -> deleteTarget())
                .bounds(leftX + 2 * (targetButtonWidth + 3), targetButtonY, targetButtonWidth, 20).build());

        // 2×2 选项布局：窄窗口（右栏 ~120px）也不会把按钮挤出屏幕
        int optionWidth = Math.max(46, (rightW - 3) / 2);
        objectiveButton = addRenderableWidget(Button.builder(Component.literal("目标"), b -> draft.toggleObjective())
                .bounds(rightX, rightY + 2, optionWidth, 20).build());
        autoRawButton = addRenderableWidget(Button.builder(Component.literal("原料"), b -> draft.toggleAutoDetectRaw())
                .bounds(rightX + optionWidth + 3, rightY + 2, optionWidth, 20).build());
        autoSeedButton = addRenderableWidget(Button.builder(Component.literal("种子"), b -> draft.toggleAutoDetectSeed())
                .bounds(rightX, rightY + 24, optionWidth, 20).build());
        autoBootstrapButton = addRenderableWidget(Button.builder(Component.literal("投料"), b -> draft.toggleAutoBootstrap())
                .bounds(rightX + optionWidth + 3, rightY + 24, optionWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("求解"), b -> solve())
                .bounds(rightX, rightY + 46, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("清空"), b -> clearAll())
                .bounds(rightX + 74, rightY + 46, 70, 20).build());

        nameBox = new EditBox(font, PAD + 44, height - 44, 130, 18, Component.literal("场景名"));
        nameBox.setMaxLength(48);
        nameBox.setValue(scenarioName);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> saveScenario())
                .bounds(PAD + 180, height - 44, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("载入"), b -> loadScenario())
                .bounds(PAD + 236, height - 44, 52, 20).build());

        rebuildReport();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        graphics.fill(0, height - 52, width, height, 0x70000000);
        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, 0x50101010);
        graphics.fill(rightX, rightY, rightX + rightW, rightY + rightH, 0x50101010);

        graphics.drawString(font, "配方 (" + draft.recipeCount() + ")", leftX + 4, leftY + 4,
                0xFFE0A0, false);
        graphics.drawString(font, "目标产物 (" + draft.targets().size() + ")", leftX + 4,
                targetListY - 14, 0xFFE0A0, false);

        drawRecipeRows(graphics, mouseX, mouseY);
        drawTargetRows(graphics, mouseX, mouseY);
        drawReport(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (objectiveButton != null) {
            objectiveButton.setMessage(Component.literal(
                    draft.options().objective() == com.jessetech.recipebalancer.core.model.Objective.MACHINE
                            ? "设备" : "配方"));
            autoRawButton.setMessage(Component.literal("原料" + onOff(draft.options().autoDetectRaw())));
            autoSeedButton.setMessage(Component.literal("种子" + onOff(draft.options().autoDetectSeed())));
            autoBootstrapButton.setMessage(Component.literal("投料" + onOff(draft.options().autoBootstrap())));
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, "场景名", PAD, height - 39, 0xA0A0A0, false);
        graphics.drawString(font, status, PAD, height - 18, 0xFFD080, false);
    }

    private static String onOff(boolean enabled) {
        return enabled ? ":开" : ":关";
    }

    private void drawRecipeRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int total = draft.recipeCount();
        double maxScroll = Math.max(0, total * ROW_H - recipeListH);
        recipeScroll = clamp(recipeScroll, 0, maxScroll);

        graphics.fill(leftX, recipeListY, leftX + leftW, recipeListY + recipeListH, 0x30000000);
        graphics.enableScissor(leftX, recipeListY, leftX + leftW, recipeListY + recipeListH);
        if (total == 0) {
            graphics.drawString(font, "（空）点下方「添加」", leftX + 4, recipeListY + 4,
                    0x808080, false);
        }
        for (int i = 0; i < total; i++) {
            int y = recipeListY - (int) recipeScroll + i * ROW_H;
            if (y + ROW_H < recipeListY || y > recipeListY + recipeListH) {
                continue;
            }
            boolean hover = inside(mouseX, mouseY, leftX, y, leftW, ROW_H);
            if (i == selectedRecipe) {
                graphics.fill(leftX, y, leftX + leftW, y + ROW_H - 1, 0x804A90D9);
            } else if (hover) {
                graphics.fill(leftX, y, leftX + leftW, y + ROW_H - 1, 0x30FFFFFF);
            }
            String text = summary(i);
            int color = draft.isRecipeEnabled(i) ? 0xE0E0E0 : 0x808080;
            graphics.drawString(font, font.plainSubstrByWidth(text, leftW - 8), leftX + 4, y + 7,
                    color, false);
        }
        graphics.disableScissor();
    }

    private void drawTargetRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> keys = new ArrayList<>(draft.targets().keySet());
        double maxScroll = Math.max(0, keys.size() * ROW_H - targetListH);
        targetScroll = clamp(targetScroll, 0, maxScroll);

        graphics.fill(leftX, targetListY, leftX + leftW, targetListY + targetListH, 0x30000000);
        graphics.enableScissor(leftX, targetListY, leftX + leftW, targetListY + targetListH);
        if (keys.isEmpty()) {
            graphics.drawString(font, "（空）点「加目标」", leftX + 4, targetListY + 4, 0x808080, false);
        }
        for (int i = 0; i < keys.size(); i++) {
            int y = targetListY - (int) targetScroll + i * ROW_H;
            if (y + ROW_H < targetListY || y > targetListY + targetListH) {
                continue;
            }
            String item = keys.get(i);
            boolean selected = item.equals(selectedTarget);
            boolean hover = inside(mouseX, mouseY, leftX, y, leftW, ROW_H);
            if (selected) {
                graphics.fill(leftX, y, leftX + leftW, y + ROW_H - 1, 0x804A90D9);
            } else if (hover) {
                graphics.fill(leftX, y, leftX + leftW, y + ROW_H - 1, 0x30FFFFFF);
            }
            String text = item + " × " + formatAmount(draft.targets().get(item)) + "/s";
            graphics.drawString(font, font.plainSubstrByWidth(text, leftW - 8), leftX + 4, y + 7,
                    0xE0E0E0, false);
        }
        graphics.disableScissor();
    }

    private void drawReport(GuiGraphics graphics) {
        int lineHeight = font.lineHeight + 1;
        double maxScroll = Math.max(0, reportLines.size() * lineHeight - reportH);
        reportScroll = clamp(reportScroll, 0, maxScroll);

        graphics.fill(rightX, reportY, rightX + rightW, reportY + reportH, 0x40000000);
        graphics.enableScissor(rightX, reportY, rightX + rightW, reportY + reportH);
        int y = reportY + 4 - (int) reportScroll;
        for (FormattedCharSequence line : reportLines) {
            if (y + font.lineHeight >= reportY && y <= reportY + reportH) {
                graphics.drawString(font, line, rightX + 4, y, 0xD0D0D0, false);
            }
            y += lineHeight;
        }
        graphics.disableScissor();
    }

    private String summary(int index) {
        Recipe recipe = draft.recipes().get(index);
        String prefix = draft.isRecipeEnabled(index) ? "" : "[禁用] ";
        return prefix + "R" + index + " " + recipe.equType() + " "
                + String.format(Locale.ROOT, "%.2fs", recipe.time())
                + "  " + items(recipe.sourceList()) + " -> " + items(recipe.productList());
    }

    private static String items(Map<String, Double> amounts) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, Double> entry : amounts.entrySet()) {
            if (index >= 3) {
                builder.append(" …");
                break;
            }
            if (index > 0) {
                builder.append("+");
            }
            builder.append(entry.getKey()).append("×").append(formatAmount(entry.getValue()));
            index++;
        }
        return builder.toString();
    }

    private void rebuildReport() {
        // 防御：配方被子屏修改后，旧解的速率数组可能和新配方列表长度不一致
        if (result != null && result.recipeRates().size() != draft.recipeCount()) {
            result = null;
        }
        String text;
        if (result == null) {
            text = "尚未求解。\n\n1) 添加配方（原料 / 产物 / 耗时 / 设备）\n"
                    + "2) 添加目标产物\n3) 点击「求解」\n\n"
                    + "快捷键 K 可随时关闭/打开本界面。";
        } else {
            text = TextReporter.format(result, draft.recipes());
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        int wrapWidth = Math.max(40, rightW - 12);
        for (String raw : text.split("\n", -1)) {
            if (raw.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(font.split(Component.literal(raw), wrapWidth));
            }
        }
        reportLines = lines;
        reportScroll = 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (inside(mouseX, mouseY, leftX, recipeListY, leftW, recipeListH)) {
                int index = (int) ((mouseY - recipeListY + recipeScroll) / ROW_H);
                if (index >= 0 && index < draft.recipeCount()) {
                    selectedRecipe = index;
                    return true;
                }
            }
            if (inside(mouseX, mouseY, leftX, targetListY, leftW, targetListH)) {
                int index = (int) ((mouseY - targetListY + targetScroll) / ROW_H);
                List<String> keys = new ArrayList<>(draft.targets().keySet());
                if (index >= 0 && index < keys.size()) {
                    selectedTarget = keys.get(index);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inside(mouseX, mouseY, leftX, recipeListY, leftW, recipeListH)) {
            recipeScroll -= scrollY * ROW_H * 2;
            return true;
        }
        if (inside(mouseX, mouseY, leftX, targetListY, leftW, targetListH)) {
            targetScroll -= scrollY * ROW_H * 2;
            return true;
        }
        if (inside(mouseX, mouseY, rightX, reportY, rightW, reportH)) {
            reportScroll -= scrollY * (font.lineHeight + 1) * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void openRecipeEditor(int index) {
        if (index >= draft.recipeCount()) {
            index = -1;
        }
        result = null; // 编辑回来后旧解必然过期
        rebuildReport();
        minecraft.setScreen(new RecipeEditScreen(this, draft, index));
    }

    private void deleteRecipe() {
        if (selectedRecipe < 0) {
            status = "先选中一条配方";
            return;
        }
        draft.removeRecipe(selectedRecipe);
        if (selectedRecipe >= draft.recipeCount()) {
            selectedRecipe = draft.recipeCount() - 1;
        }
        result = null;
        rebuildReport();
        status = "已删除配方";
    }

    private void toggleRecipe() {
        if (selectedRecipe < 0) {
            status = "先选中一条配方";
            return;
        }
        draft.toggleRecipeEnabled(selectedRecipe);
        status = draft.isRecipeEnabled(selectedRecipe) ? "已启用 R" + selectedRecipe : "已禁用 R" + selectedRecipe;
    }

    private void openTargetEditor(String original) {
        result = null;
        rebuildReport();
        minecraft.setScreen(new TargetEditScreen(this, draft, original));
    }

    private void deleteTarget() {
        if (selectedTarget == null) {
            status = "先选中一个目标";
            return;
        }
        draft.removeTarget(selectedTarget);
        status = "已删除目标: " + selectedTarget;
        selectedTarget = null;
        result = null;
        rebuildReport();
    }

    private void solve() {
        if (draft.recipeCount() == 0) {
            status = "至少添加 1 条配方";
            return;
        }
        if (draft.targets().isEmpty()) {
            status = "至少添加 1 个目标产物";
            return;
        }
        try {
            result = Balancer.solve(draft.toScenario());
            status = result.success() ? "求解完成" : "求解失败: " + result.message();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError：生产环境缺依赖（如 commons-math3 未内嵌）时不要崩游戏，界面给出提示
            result = null;
            status = "求解失败: " + e;
        }
        rebuildReport();
    }

    private void clearAll() {
        draft.clear();
        result = null;
        selectedRecipe = -1;
        selectedTarget = null;
        rebuildReport();
        status = "已清空";
    }

    private void saveScenario() {
        try {
            scenarioName = ScenarioStore.sanitize(nameBox.getValue());
            Path path = STORE.save(scenarioName, draft.toScenario());
            nameBox.setValue(scenarioName);
            status = "已保存: " + path.getFileName();
        } catch (RuntimeException e) {
            status = "保存失败: " + e.getMessage();
        }
    }

    private void loadScenario() {
        String requested = nameBox.getValue();
        try {
            Scenario scenario = STORE.load(requested);
            scenarioName = ScenarioStore.sanitize(requested);
            draft.loadFrom(scenario);
            selectedRecipe = -1;
            selectedTarget = null;
            result = null;
            rebuildReport();
            if (minecraft != null) {
                minecraft.execute(() -> {
                    if (minecraft.screen == this) {
                        rebuildWidgets();
                    }
                });
            }
            status = "已载入: " + scenarioName;
        } catch (RuntimeException e) {
            status = "载入失败: " + e.getMessage();
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatAmount(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e9) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
