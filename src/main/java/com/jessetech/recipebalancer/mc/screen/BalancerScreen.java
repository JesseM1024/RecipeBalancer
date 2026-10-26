package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.report.TextReporter;
import com.jessetech.recipebalancer.core.solve.Balancer;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M3 主界面：仿 AE 样板编码终端的紧凑布局。
 *
 * <p>上方配方库、中央编码区（3×3 原料 / 3×3 产物 / 设备 / 耗时）、下方玩家背包。
 * 左键从背包拿起物品、点格子放置/删除；中键改用量或自定义物品；右键配方库两次删除。
 * 求解进入二级结果页，避免挤占一级界面。
 */
public final class BalancerScreen extends Screen {

    private static final int SLOT = 16;
    private static final int GRID_COLS = 3;
    private static final int VISIBLE_ROWS = 3;
    private static final int LIB_COLS = 4;
    private static final int LIB_ROWS = 2;
    private static final int TARGET_SLOTS = 6;
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 4;
    private static final long DELETE_WINDOW_MS = 500L;

    private static final ScenarioStore STORE = new ScenarioStore(
            FMLPaths.CONFIGDIR.get().resolve("recipebalancer").resolve("scenarios"));

    private final ScenarioDraft draft = loadAutosave();
    private final RecipePattern pattern = new RecipePattern();
    private int editingIndex = -1;
    private String carriedKey = "";
    private String status = "左键取物品→点格子放置；中键改量；右键配方库两次删除";
    private String scenarioName = "default";

    private int libraryScroll;
    private int encodingScrollRow;
    private int lastRightClickIndex = -1;
    private long lastRightClickTime;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int topY;
    private int targetY;
    private int libraryLabelY;
    private int libraryY;
    private int encodingLabelY;
    private int encodingY;
    private int inventoryLabelY;
    private int inventoryY;
    private int statusY;
    private int inputX;
    private int outputX;
    private int equipmentX;
    private int inventoryX;

    private Button objectiveButton;
    private Button rawButton;
    private Button seedButton;
    private Button bootstrapButton;

    public BalancerScreen() {
        super(Component.literal("RecipeBalancer · 配方编码终端"));
    }

    @Override
    protected void init() {
        panelW = Math.min(330, width - 8);
        panelH = Math.min(238, height - 8);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        topY = panelY + 4;
        targetY = topY + 17;
        libraryLabelY = targetY + 17;
        libraryY = libraryLabelY + 10;
        encodingLabelY = libraryY + LIB_ROWS * SLOT + 4;
        encodingY = encodingLabelY + 10;
        inventoryLabelY = encodingY + VISIBLE_ROWS * SLOT + 4;
        inventoryY = inventoryLabelY + 10;
        statusY = panelY + panelH - 10;

        inputX = panelX + 4;
        outputX = inputX + GRID_COLS * SLOT + 18;
        equipmentX = outputX + GRID_COLS * SLOT + 6;
        inventoryX = panelX + 4;

        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - LIB_ROWS)));
        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));

        int topButtonW = 38;
        int topButtonX = panelX + panelW - 4 - 3 * (topButtonW + 2);
        addRenderableWidget(Button.builder(Component.literal("目标"), b -> openTargets())
                .bounds(topButtonX, topY, topButtonW, 16).build());
        addRenderableWidget(Button.builder(Component.literal("载入"), b -> openScenarios())
                .bounds(topButtonX + topButtonW + 2, topY, topButtonW, 16).build());
        addRenderableWidget(Button.builder(Component.literal("求解"), b -> solveNow())
                .bounds(topButtonX + 2 * (topButtonW + 2), topY, topButtonW, 16).build());

        addRenderableWidget(Button.builder(Component.literal("写入"),
                        b -> commitPattern(true))
                .bounds(equipmentX + 48, encodingY, 38, 16).build());
        addRenderableWidget(Button.builder(Component.literal("新建"),
                        b -> newPattern())
                .bounds(equipmentX + 48, encodingY + 20, 38, 16).build());

        int optionW = 58;
        objectiveButton = addRenderableWidget(Button.builder(Component.literal("目标:设备"),
                        b -> draft.toggleObjective())
                .bounds(panelX + 156, inventoryY, optionW, 16).build());
        rawButton = addRenderableWidget(Button.builder(Component.literal("原料:开"),
                        b -> draft.toggleAutoDetectRaw())
                .bounds(panelX + 156 + optionW + 3, inventoryY, optionW, 16).build());
        seedButton = addRenderableWidget(Button.builder(Component.literal("种子:开"),
                        b -> draft.toggleAutoDetectSeed())
                .bounds(panelX + 156, inventoryY + 18, optionW, 16).build());
        bootstrapButton = addRenderableWidget(Button.builder(Component.literal("投料:开"),
                        b -> draft.toggleAutoBootstrap())
                .bounds(panelX + 156 + optionW + 3, inventoryY + 18, optionW, 16).build());
    }

    private static ScenarioDraft loadAutosave() {
        try {
            return ScenarioDraft.from(STORE.loadDraft("autosave"));
        } catch (RuntimeException e) {
            return ScenarioDraft.blank();
        }
    }

    private void persistDraft() {
        try {
            STORE.save("autosave", draft.toScenario());
        } catch (RuntimeException e) {
            status = "自动保存失败: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        AeStyle.panel(graphics, panelX, panelY, panelW, panelH);
        AeStyle.header(graphics, panelX, panelY, panelW, 16);

        graphics.drawString(font, "配方编码终端", panelX + 4, topY + 4, AeStyle.TEXT, false);

        graphics.drawString(font, "目标", panelX + 4, targetY + 4, AeStyle.MUTED, false);
        drawTargets(graphics, mouseX, mouseY);

        graphics.drawString(font, "配方库 (" + draft.recipeCount() + ")", panelX + 4,
                libraryLabelY, AeStyle.MUTED, false);
        drawLibrary(graphics, mouseX, mouseY);

        if (editingIndex >= 0) {
            String tag = "R" + editingIndex;
            graphics.drawString(font, tag, panelX + panelW - 4 - font.width(tag),
                    encodingLabelY, AeStyle.ACCENT, false);
        }
        drawEncoding(graphics, mouseX, mouseY);

        graphics.drawString(font, "背包", panelX + 4, inventoryLabelY, AeStyle.MUTED, false);
        drawInventory(graphics, mouseX, mouseY);

        graphics.drawString(font, font.plainSubstrByWidth(status, panelW - 8), panelX + 4,
                statusY, AeStyle.ACCENT, false);
    }

    private void drawTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
        for (int i = 0; i < TARGET_SLOTS; i++) {
            int x = panelX + 30 + i * (SLOT + 1);
            int y = targetY;
            AeStyle.slot(graphics, x, y);
            if (i < entries.size()) {
                Map.Entry<String, Double> entry = entries.get(i);
                ItemKeyMapper.renderIcon(graphics, font, entry.getKey(), x, y,
                        amountText(entry.getValue()));
            }
        }
        if (entries.size() > TARGET_SLOTS) {
            graphics.drawString(font, "+" + (entries.size() - TARGET_SLOTS),
                    panelX + 30 + TARGET_SLOTS * (SLOT + 1), targetY + 4, AeStyle.MUTED, false);
        }
    }

    private void drawLibrary(GuiGraphics graphics, int mouseX, int mouseY) {
        int total = draft.recipeCount();
        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - LIB_ROWS)));
        graphics.fill(panelX + 2, libraryY - 2, panelX + 8 + LIB_COLS * (SLOT + 1),
                libraryY + LIB_ROWS * SLOT, 0x30000000);
        if (total == 0) {
            graphics.drawString(font, "（空）在下方编码后点「写入」", panelX + 6, libraryY + 4,
                    AeStyle.MUTED, false);
            return;
        }
        for (int row = 0; row < LIB_ROWS; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int index = (libraryScroll + row) * LIB_COLS + col;
                if (index >= total) {
                    continue;
                }
                int x = panelX + 4 + col * (SLOT + 1);
                int y = libraryY + row * SLOT;
                AeStyle.slot(graphics, x, y);
                if (index == editingIndex) {
                    graphics.fill(x - 1, y - 1, x + SLOT + 1, y, AeStyle.ACCENT_LIGHT);
                    graphics.fill(x - 1, y + SLOT, x + SLOT + 1, y + SLOT + 1,
                            AeStyle.ACCENT_LIGHT);
                }
                Recipe recipe = draft.recipes().get(index);
                String productKey = recipe.productList().keySet().stream().findFirst().orElse("");
                if (productKey.isEmpty()) {
                    graphics.drawString(font, "?", x + 5, y + 4, 0xFFFF8080, false);
                } else {
                    ItemKeyMapper.renderIcon(graphics, font, productKey, x, y, null);
                }
                if (!recipe.equType().isBlank()) {
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    pose.translate(x + 10, y + 10, 0);
                    pose.scale(0.5f, 0.5f, 1.0f);
                    ItemKeyMapper.renderIcon(graphics, font, recipe.equType(), 0, 0, null);
                    pose.popPose();
                }
            }
        }
    }

    private void drawEncoding(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "原料 " + pattern.inputCount(), inputX, encodingLabelY,
                AeStyle.ACCENT, false);
        graphics.drawString(font, "产物 " + pattern.outputCount(), outputX, encodingLabelY,
                AeStyle.ACCENT, false);
        graphics.drawString(font, "→", inputX + GRID_COLS * SLOT + 4, encodingY + 18,
                AeStyle.MUTED, false);

        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int gridRow = encodingScrollRow + row;
            for (int col = 0; col < GRID_COLS; col++) {
                int index = gridRow * GRID_COLS + col;
                int y = encodingY + row * SLOT;

                int inX = inputX + col * SLOT;
                if (index < RecipePattern.MAX_INPUTS) {
                    AeStyle.slot(graphics, inX, y);
                    if (pattern.inputKey(index) != null) {
                        ItemKeyMapper.renderIcon(graphics, font, pattern.inputKey(index), inX, y,
                                amountText(pattern.inputAmount(index)));
                    }
                }

                int outX = outputX + col * SLOT;
                if (index < RecipePattern.MAX_OUTPUTS) {
                    AeStyle.slot(graphics, outX, y);
                    if (pattern.outputKey(index) != null) {
                        ItemKeyMapper.renderIcon(graphics, font, pattern.outputKey(index), outX, y,
                                amountText(pattern.outputAmount(index)));
                    }
                }
            }
        }

        int maxScroll = maxEncodingScroll();
        if (maxScroll > 0) {
            int barX = panelX + panelW - 7;
            int barY = encodingY;
            int barH = VISIBLE_ROWS * SLOT;
            graphics.fill(barX, barY, barX + 3, barY + barH, 0xFF6E6E6E);
            int thumbH = Math.max(8, barH * VISIBLE_ROWS / (VISIBLE_ROWS + maxScroll));
            int thumbY = barY + (barH - thumbH) * encodingScrollRow / maxScroll;
            graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, AeStyle.ACCENT_LIGHT);
        }

        graphics.drawString(font, "设备", equipmentX + 18, encodingY + 4, AeStyle.MUTED, false);
        AeStyle.slot(graphics, equipmentX, encodingY);
        if (!pattern.equipmentKey().isBlank()) {
            ItemKeyMapper.renderIcon(graphics, font, pattern.equipmentKey(), equipmentX,
                    encodingY, null);
        }

        graphics.drawString(font, "耗时", equipmentX + 18, encodingY + 24, AeStyle.MUTED, false);
        int timeY = encodingY + 20;
        AeStyle.slot(graphics, equipmentX, timeY);
        String time = TimeExpression.formatNumber(pattern.timeTicks()) + "t";
        if (font.width(time) > SLOT) {
            time = TimeExpression.formatNumber(pattern.timeTicks() / 20.0) + "s";
        }
        graphics.drawString(font, font.plainSubstrByWidth(time, SLOT), equipmentX + 1,
                timeY + 4, 0xFFFFD080, false);
    }

    private void drawInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        Inventory inventory = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getInventory();
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int x = inventoryX + col * SLOT;
                int y = inventoryY + row * SLOT;
                AeStyle.slot(graphics, x, y);
                if (inventory == null) {
                    continue;
                }
                int index = row < 3 ? 9 + row * 9 + col : col;
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, x, y);
                    graphics.renderItemDecorations(font, stack, x, y);
                }
            }
        }
        graphics.fill(inventoryX, inventoryY + 3 * SLOT - 1,
                inventoryX + INV_COLS * SLOT, inventoryY + 3 * SLOT, 0x40FFFFFF);
    }

    private static String amountText(double amount) {
        if (Math.abs(amount - 1.0) < 1e-9) {
            return "";
        }
        return TimeExpression.formatNumber(amount);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateOptionLabels();
        super.render(graphics, mouseX, mouseY, partialTick);

        if (!carriedKey.isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, carriedKey, mouseX - 8, mouseY - 8, null);
        }
        drawTooltips(graphics, mouseX, mouseY);
    }

    private void updateOptionLabels() {
        objectiveButton.setMessage(Component.literal(draft.options().objective() == Objective.MACHINE
                ? "目标:设备" : "目标:配方"));
        rawButton.setMessage(Component.literal("原料:" + onOff(draft.options().autoDetectRaw())));
        seedButton.setMessage(Component.literal("种子:" + onOff(draft.options().autoDetectSeed())));
        bootstrapButton.setMessage(Component.literal("投料:" + onOff(draft.options().autoBootstrap())));
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        Inventory inventory = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getInventory();
        int invIndex = inventoryIndexAt(mouseX, mouseY);
        if (inventory != null && invIndex >= 0) {
            ItemStack stack = inventory.getItem(invIndex);
            if (!stack.isEmpty()) {
                graphics.renderTooltip(font, stack, mouseX, mouseY);
                return;
            }
        }

        int library = libraryIndexAt(mouseX, mouseY);
        if (library >= 0) {
            Recipe recipe = draft.recipes().get(library);
            String product = recipe.productList().keySet().stream().findFirst().orElse("?");
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("R" + library + " · " + ItemKeyMapper.displayName(product)));
            lines.add(Component.literal("产物: " + summary(recipe.productList()))
                    .withStyle(style -> style.withColor(0xFF90FF90)));
            lines.add(Component.literal("原料: " + summary(recipe.sourceList()))
                    .withStyle(style -> style.withColor(0xFF90C0FF)));
            lines.add(Component.literal("设备: " + recipe.equType()
                    + " · 耗时 " + TimeExpression.formatTicks(recipe.time() * 20.0))
                    .withStyle(style -> style.withColor(0xFFC0C0C0)));
            lines.add(Component.literal("左键载入编辑 · 右键两次删除")
                    .withStyle(style -> style.withColor(0xFF808080)));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        int[] slot = encodingSlotAt(mouseX, mouseY);
        if (slot != null) {
            String key = slot[0] == 0 ? pattern.inputKey(slot[1]) : pattern.outputKey(slot[1]);
            double amount = slot[0] == 0 ? pattern.inputAmount(slot[1]) : pattern.outputAmount(slot[1]);
            if (key != null) {
                ItemKeyMapper.renderTooltip(graphics, font, key, mouseX, mouseY);
                return;
            }
        }

        if (inside(mouseX, mouseY, equipmentX, encodingY, SLOT, SLOT)) {
            if (pattern.equipmentKey().isBlank()) {
                graphics.renderTooltip(font, Component.literal("设备标记：左键放置手持物品 / 中键自定义"),
                        mouseX, mouseY);
            } else {
                ItemKeyMapper.renderTooltip(graphics, font, pattern.equipmentKey(), mouseX, mouseY);
            }
            return;
        }
        if (inside(mouseX, mouseY, equipmentX, encodingY + 20, SLOT, SLOT)) {
            List<Component> lines = List.of(
                    Component.literal("耗时 " + TimeExpression.formatTicks(pattern.timeTicks())),
                    Component.literal("精确: " + TimeExpression.formatExactTicks(pattern.timeTicks()))
                            .withStyle(style -> style.withColor(0xFF808080)),
                    Component.literal("左键/中键编辑，支持 + - * / ^").withStyle(style ->
                            style.withColor(0xFF808080)));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        int target = targetSlotAt(mouseX, mouseY);
        if (target >= 0) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
            if (target < entries.size()) {
                Map.Entry<String, Double> entry = entries.get(target);
                ItemKeyMapper.renderTooltip(graphics, font, entry.getKey(), mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, Component.literal("空目标槽：左键从手持标记 / 中键自定义"),
                        mouseX, mouseY);
            }
        }
    }

    private static String summary(Map<String, Double> amounts) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Double> entry : amounts.entrySet()) {
            if (count++ > 0) {
                builder.append(" + ");
            }
            if (count > 4) {
                builder.append("…");
                break;
            }
            builder.append(ItemKeyMapper.displayName(entry.getKey()))
                    .append("×").append(TimeExpression.formatNumber(entry.getValue()));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 命中测试
    // ------------------------------------------------------------------

    private int recipeRows() {
        return (draft.recipeCount() + LIB_COLS - 1) / LIB_COLS;
    }

    private int libraryIndexAt(double mouseX, double mouseY) {
        for (int row = 0; row < LIB_ROWS; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int x = panelX + 4 + col * (SLOT + 1);
                int y = libraryY + row * SLOT;
                if (inside(mouseX, mouseY, x, y, SLOT, SLOT)) {
                    int index = (libraryScroll + row) * LIB_COLS + col;
                    return index < draft.recipeCount() ? index : -1;
                }
            }
        }
        return -1;
    }

    private int targetSlotAt(double mouseX, double mouseY) {
        for (int i = 0; i < TARGET_SLOTS; i++) {
            int x = panelX + 30 + i * (SLOT + 1);
            if (inside(mouseX, mouseY, x, targetY, SLOT, SLOT)) {
                return i;
            }
        }
        return -1;
    }

    private int maxEncodingScroll() {
        int last = Math.max(pattern.maxInputIndex(), pattern.maxOutputIndex());
        int maxRows = (RecipePattern.MAX_INPUTS + GRID_COLS - 1) / GRID_COLS;
        // 多留一行空格：最后一格填满后仍能滚到下一行继续添加
        int rows = last < 0 ? 1 : Math.min(maxRows, last / GRID_COLS + 2);
        return Math.max(0, rows - VISIBLE_ROWS);
    }

    /** [0=输入,1=输出, index]；不在可见格子上返回 null。 */
    private int[] encodingSlotAt(double mouseX, double mouseY) {
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int index = (encodingScrollRow + row) * GRID_COLS + col;
                int y = encodingY + row * SLOT;
                if (index < RecipePattern.MAX_INPUTS
                        && inside(mouseX, mouseY, inputX + col * SLOT, y, SLOT, SLOT)) {
                    return new int[]{0, index};
                }
                if (index < RecipePattern.MAX_OUTPUTS
                        && inside(mouseX, mouseY, outputX + col * SLOT, y, SLOT, SLOT)) {
                    return new int[]{1, index};
                }
            }
        }
        return null;
    }

    private int inventoryIndexAt(double mouseX, double mouseY) {
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int x = inventoryX + col * SLOT;
                int y = inventoryY + row * SLOT;
                if (inside(mouseX, mouseY, x, y, SLOT, SLOT)) {
                    return row < 3 ? 9 + row * 9 + col : col;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int target = targetSlotAt(mouseX, mouseY);
        if (target >= 0) {
            handleTargetClick(target, button);
            return true;
        }

        int library = libraryIndexAt(mouseX, mouseY);
        if (library >= 0) {
            handleLibraryClick(library, button);
            return true;
        }

        int[] slot = encodingSlotAt(mouseX, mouseY);
        if (slot != null) {
            handleEncodingClick(slot[0], slot[1], button);
            return true;
        }

        if (inside(mouseX, mouseY, equipmentX, encodingY, SLOT, SLOT)) {
            handleEquipmentClick(button);
            return true;
        }
        if (inside(mouseX, mouseY, equipmentX, encodingY + 20, SLOT, SLOT)) {
            openTimeEditor();
            return true;
        }

        int inventory = inventoryIndexAt(mouseX, mouseY);
        if (inventory >= 0) {
            handleInventoryClick(inventory, button);
            return true;
        }

        if (button == 0) {
            carriedKey = "";
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleTargetClick(int slot, int button) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
        if (button == 1) {
            if (slot < entries.size()) {
                String key = entries.get(slot).getKey();
                draft.removeTarget(key);
                onDraftChanged();
                status = "已删除目标: " + key;
            }
            return;
        }
        if (button == 2) {
            String key = slot < entries.size() ? entries.get(slot).getKey() : "";
            double amount = slot < entries.size() ? entries.get(slot).getValue() : 1.0;
            openTargetAmount(key, amount);
            return;
        }
        if (button == 0) {
            if (slot >= entries.size()) {
                if (!carriedKey.isEmpty()) {
                    draft.putTarget(carriedKey, 1.0);
                    onDraftChanged();
                    status = "已添加目标: " + carriedKey;
                } else {
                    openTargetAmount("", 1.0);
                }
            } else {
                Map.Entry<String, Double> entry = entries.get(slot);
                openTargetAmount(entry.getKey(), entry.getValue());
            }
        }
    }

    private void openTargetAmount(String key, double amount) {
        minecraft.setScreen(new AmountScreen(this, "目标产物", key, amount, true,
                (newKey, newAmount) -> {
                    if (!newKey.equals(key)) {
                        draft.removeTarget(key);
                    }
                    draft.putTarget(newKey, newAmount);
                    onDraftChanged();
                    status = "目标: " + newKey + " × " + TimeExpression.formatNumber(newAmount) + "/s";
                }));
    }

    private void handleLibraryClick(int index, int button) {
        if (button == 0) {
            loadPattern(index);
            return;
        }
        if (button == 1) {
            long now = System.currentTimeMillis();
            if (lastRightClickIndex == index && now - lastRightClickTime <= DELETE_WINDOW_MS) {
                deleteRecipe(index);
                lastRightClickIndex = -1;
            } else {
                lastRightClickIndex = index;
                lastRightClickTime = now;
                status = "再次右键删除 R" + index;
            }
        }
    }

    private void handleEncodingClick(int side, int index, int button) {
        String key = side == 0 ? pattern.inputKey(index) : pattern.outputKey(index);
        double amount = side == 0 ? pattern.inputAmount(index) : pattern.outputAmount(index);
        boolean input = side == 0;
        if (button == 0) {
            if (!carriedKey.isEmpty()) {
                if (input) {
                    pattern.putInput(index, carriedKey, 1.0);
                } else {
                    pattern.putOutput(index, carriedKey, 1.0);
                }
                status = "已标记 " + ItemKeyMapper.displayName(carriedKey);
            } else if (key != null) {
                if (input) {
                    pattern.removeInput(index);
                } else {
                    pattern.removeOutput(index);
                }
                status = "已移除格子";
            } else {
                // QoL：空手左键空格直接开始自定义（不再要求中键）
                openPatternAmount(input, index, "", 1.0);
            }
            return;
        }
        if (button == 2) {
            openPatternAmount(input, index, key == null ? "" : key, amount);
        }
    }

    private void openPatternAmount(boolean input, int index, String key, double amount) {
        String originalKey = key == null ? "" : key;
        double initialAmount = amount > 0 ? amount : 1.0;
        minecraft.setScreen(new AmountScreen(this, input ? "原料用量" : "产物用量", originalKey,
                initialAmount, true, (newKey, newAmount) -> {
                    if (newKey.equals(originalKey)) {
                        if (input) {
                            pattern.setInputAmount(index, newAmount);
                        } else {
                            pattern.setOutputAmount(index, newAmount);
                        }
                    } else if (input) {
                        pattern.putInput(index, newKey, newAmount);
                    } else {
                        pattern.putOutput(index, newKey, newAmount);
                    }
                    status = (input ? "原料 " : "产物 ") + newKey + " × "
                            + TimeExpression.formatNumber(newAmount);
                }));
    }

    private void handleEquipmentClick(int button) {
        if (button == 0 && !carriedKey.isEmpty()) {
            pattern.setEquipmentKey(carriedKey);
            status = "设备: " + ItemKeyMapper.displayName(carriedKey);
            return;
        }
        minecraft.setScreen(new AmountScreen(this, "设备标记", pattern.equipmentKey(), 1.0, false,
                (key, ignored) -> {
                    pattern.setEquipmentKey(key);
                    status = "设备: " + ItemKeyMapper.displayName(key);
                }));
    }

    private void handleInventoryClick(int index, int button) {
        if (button == 0) {
            ItemStack stack = minecraft.player == null ? ItemStack.EMPTY
                    : minecraft.player.getInventory().getItem(index);
            if (!stack.isEmpty()) {
                carriedKey = ItemKeyMapper.of(stack);
                status = "手持: " + stack.getHoverName().getString();
            } else {
                carriedKey = "";
            }
        } else if (button == 1) {
            carriedKey = "";
        }
    }

    private void openTimeEditor() {
        minecraft.setScreen(new TimeScreen(this, pattern.timeTicks(), ticks -> {
            pattern.setTimeTicks(ticks);
            status = "耗时: " + TimeExpression.formatTicks(ticks);
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseY >= libraryY - 2
                && mouseY < libraryY + LIB_ROWS * SLOT) {
            libraryScroll = Math.max(0, Math.min(libraryScroll + (scrollY < 0 ? 1 : -1),
                    Math.max(0, recipeRows() - LIB_ROWS)));
            return true;
        }
        if (mouseX >= inputX && mouseX < panelX + panelW - 4
                && mouseY >= encodingY && mouseY < encodingY + VISIBLE_ROWS * SLOT) {
            encodingScrollRow = Math.max(0, Math.min(
                    encodingScrollRow + (scrollY < 0 ? 1 : -1), maxEncodingScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // 配方库/编码操作
    // ------------------------------------------------------------------

    private void loadPattern(int index) {
        Recipe recipe = draft.recipes().get(index);
        pattern.load(recipe);
        editingIndex = index;
        carriedKey = "";
        encodingScrollRow = 0;
        status = pattern.truncated()
                ? "R" + index + " 已载入；超过 256/16 的部分未显示"
                : "R" + index + " 已载入编码区";
    }

    private boolean commitPattern(boolean showStatus) {
        try {
            Recipe recipe = pattern.toRecipe();
            if (editingIndex >= 0 && editingIndex < draft.recipeCount()) {
                draft.replaceRecipe(editingIndex, recipe);
            } else {
                draft.addRecipe(recipe);
                editingIndex = draft.recipeCount() - 1;
            }
            persistDraft();
            status = (showStatus ? "已写入 R" + editingIndex : "已保存编码")
                    + (pattern.truncated() ? "（超过 9 格的部分未写入）" : "");
            return true;
        } catch (RuntimeException e) {
            if (showStatus) {
                status = "写入失败: " + e.getMessage();
            }
            return false;
        }
    }

    private void newPattern() {
        pattern.clear();
        editingIndex = -1;
        carriedKey = "";
        encodingScrollRow = 0;
        status = "新建配方编码";
    }

    private void deleteRecipe(int index) {
        draft.removeRecipe(index);
        if (editingIndex == index) {
            editingIndex = -1;
            pattern.clear();
        } else if (editingIndex > index) {
            editingIndex--;
        }
        persistDraft();
        status = "已删除 R" + index;
    }

    private void solveNow() {
        if (pattern.hasContent() && !commitPattern(false)) {
            status = "编码未完成：至少要有产物、设备和正耗时";
            return;
        }
        if (draft.recipeCount() == 0) {
            status = "至少写入 1 条配方";
            return;
        }
        if (draft.targets().isEmpty()) {
            status = "至少添加 1 个目标产物";
            return;
        }
        try {
            SolveResult result = Balancer.solve(draft.toScenario());
            persistDraft();
            minecraft.setScreen(new SolveScreen(this, TextReporter.format(result, draft.recipes()),
                    result.success()));
        } catch (RuntimeException | LinkageError e) {
            RecipeBalancerMod.LOGGER.error("[RecipeBalancer] 求解失败", e);
            status = "求解失败: " + e;
        }
    }

    void openTargets() {
        minecraft.setScreen(new TargetListScreen(this, draft));
    }

    private void openScenarios() {
        minecraft.setScreen(new ScenarioListScreen(this, STORE, draft));
    }

    @Override
    public void onClose() {
        // 关界面前把编码区未写入的内容落库，避免 K 关闭丢配方
        if (pattern.hasContent()) {
            commitPattern(false);
        }
        persistDraft();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modifiers == 0 && !(getFocused() instanceof net.minecraft.client.gui.components.EditBox)
                && com.jessetech.recipebalancer.mc.keybind.RbKeyMappings.OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 子屏回调
    // ------------------------------------------------------------------

    void onDraftChanged() {
        persistDraft();
    }

    void onScenarioLoaded(String name) {
        scenarioName = name;
        pattern.clear();
        editingIndex = -1;
        carriedKey = "";
        status = "已载入配方集: " + name;
        persistDraft();
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // JEI 非 ContainerScreen 渲染（RecipeBalancerJeiPlugin 使用）
    public int jeiGuiLeft() {
        return panelX;
    }

    public int jeiGuiTop() {
        return panelY;
    }

    public int jeiGuiWidth() {
        return panelW;
    }

    public int jeiGuiHeight() {
        return panelH;
    }
}
