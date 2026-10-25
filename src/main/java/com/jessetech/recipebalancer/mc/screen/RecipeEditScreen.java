package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方编辑屏：手动物品名 + 数量（M2 录入通道；背包/JEI 录入属 M4）。
 *
 * <p>动态行通过「改模型 → 下一 tick 重建控件」实现，避免在按钮回调里清空
 * {@code renderables} 造成事件遍历 CME。
 */
public final class RecipeEditScreen extends Screen {

    private static final int ROW_H = 22;

    private final Screen parent;
    private final ScenarioDraft draft;
    private final int index;
    private final List<Row> sources = new ArrayList<>();
    private final List<Row> products = new ArrayList<>();

    private EditBox timeBox;
    private EditBox equipBox;
    private String status = "";
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int maxRows = 8;

    public RecipeEditScreen(Screen parent, ScenarioDraft draft, int index) {
        super(Component.literal(index < 0 ? "添加配方" : "编辑配方 R" + index));
        this.parent = parent;
        this.draft = draft;
        this.index = index;

        if (index >= 0) {
            Recipe recipe = draft.recipes().get(index);
            recipe.sourceList().forEach((item, amount) -> sources.add(new Row(item, formatAmount(amount))));
            recipe.productList().forEach((item, amount) -> products.add(new Row(item, formatAmount(amount))));
        }
        if (sources.isEmpty()) {
            sources.add(new Row("", "1"));
        }
        if (products.isEmpty()) {
            products.add(new Row("", "1"));
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(520, width - 16);
        panelH = Math.min(350, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        maxRows = Math.max(2, (panelH - 120) / ROW_H);

        Recipe recipe = index >= 0 ? draft.recipes().get(index) : null;

        timeBox = new EditBox(font, panelX + 56, panelY + 24, 46, 18, Component.literal("耗时"));
        timeBox.setMaxLength(16);
        timeBox.setValue(recipe == null ? "1" : formatAmount(recipe.time()));
        addRenderableWidget(timeBox);

        int equipWidth = Math.max(60, panelW - 170);
        equipBox = new EditBox(font, panelX + 160, panelY + 24, equipWidth, 18, Component.literal("设备"));
        equipBox.setMaxLength(64);
        equipBox.setValue(recipe == null ? "" : recipe.equType());
        addRenderableWidget(equipBox);

        int columnWidth = (panelW - 30) / 2;
        buildColumn(sources, panelX + 10, columnWidth);
        buildColumn(products, panelX + panelW / 2 + 5, columnWidth);

        int bottom = panelY + panelH;
        addRenderableWidget(Button.builder(Component.literal("＋原料"), b -> addRow(sources))
                .bounds(panelX + 10, bottom - 52, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("＋产物"), b -> addRow(products))
                .bounds(panelX + panelW / 2 + 5, bottom - 52, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(panelX + panelW - 150, bottom - 26, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> minecraft.setScreen(parent))
                .bounds(panelX + panelW - 76, bottom - 26, 70, 20).build());
    }

    private void buildColumn(List<Row> rows, int x, int columnWidth) {
        int nameWidth = Math.max(40, columnWidth - 60);
        int visible = Math.min(rows.size(), maxRows);
        for (int i = 0; i < visible; i++) {
            Row row = rows.get(i);
            int y = panelY + 64 + i * ROW_H;

            EditBox nameBox = new EditBox(font, x, y, nameWidth, 18, Component.literal("物品"));
            nameBox.setMaxLength(64);
            nameBox.setValue(row.name);
            nameBox.setResponder(value -> row.name = value);
            addRenderableWidget(nameBox);

            EditBox amountBox = new EditBox(font, x + nameWidth + 2, y, 40, 18,
                    Component.literal("数量"));
            amountBox.setMaxLength(16);
            amountBox.setValue(row.amount);
            amountBox.setResponder(value -> row.amount = value);
            addRenderableWidget(amountBox);

            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                rows.remove(row);
                scheduleRebuild();
            }).bounds(x + nameWidth + 44, y, 18, 18).build());
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0202020);
        graphics.drawString(font, title, panelX + 10, panelY + 8, 0xFFE0A0, false);
        graphics.drawString(font, "耗时(s)", panelX + 10, panelY + 28, 0xA0A0A0, false);
        graphics.drawString(font, "设备类型", panelX + 110, panelY + 28, 0xA0A0A0, false);
        graphics.drawString(font, "原料", panelX + 10, panelY + 50, 0xFFE0A0, false);
        graphics.drawString(font, "产物", panelX + panelW / 2 + 5, panelY + 50, 0xFFE0A0, false);
        if (sources.size() > maxRows || products.size() > maxRows) {
            graphics.drawString(font, "每列最多显示 " + maxRows + " 行", panelX + 10,
                    panelY + panelH - 66, 0xFFB060, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            graphics.drawString(font, status, panelX, panelY + panelH + 4, 0xFF7070, false);
        }
    }

    private void addRow(List<Row> rows) {
        if (rows.size() >= maxRows) {
            status = "本列最多 " + maxRows + " 行";
            return;
        }
        rows.add(new Row("", "1"));
        scheduleRebuild();
    }

    private void scheduleRebuild() {
        if (minecraft != null) {
            minecraft.execute(() -> {
                if (minecraft.screen == this) {
                    rebuildWidgets();
                }
            });
        }
    }

    private void save() {
        try {
            double time = parsePositive(timeBox.getValue(), "耗时");
            String equipment = equipBox.getValue().trim();
            if (equipment.isEmpty()) {
                throw new IllegalArgumentException("设备类型不能为空");
            }
            Map<String, Double> sourceMap = collect(sources, "原料");
            Map<String, Double> productMap = collect(products, "产物");
            if (productMap.isEmpty()) {
                throw new IllegalArgumentException("产物不能为空");
            }
            Recipe recipe = new Recipe(sourceMap, productMap, time, equipment);
            if (index >= 0) {
                draft.replaceRecipe(index, recipe);
            } else {
                draft.addRecipe(recipe);
            }
            minecraft.setScreen(parent);
        } catch (RuntimeException e) {
            status = e.getMessage() == null ? "输入不合法" : e.getMessage();
        }
    }

    private static Map<String, Double> collect(List<Row> rows, String label) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Row row : rows) {
            String item = row.name == null ? "" : row.name.trim();
            if (item.isEmpty()) {
                continue;
            }
            double amount = parsePositive(row.amount, label + "数量");
            result.merge(item, amount, Double::sum);
        }
        return result;
    }

    private static double parsePositive(String raw, String label) {
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "必须是数字");
        }
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(label + "必须为正数");
        }
        return value;
    }

    private static String formatAmount(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e9) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static final class Row {
        private String name;
        private String amount;

        private Row(String name, String amount) {
            this.name = name;
            this.amount = amount;
        }
    }
}
