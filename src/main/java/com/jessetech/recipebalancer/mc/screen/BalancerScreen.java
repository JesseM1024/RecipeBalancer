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
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
public final class BalancerScreen extends Screen implements JeiPanelScreen {

    private static final int SLOT = 18;
    private static final int GRID_COLS = 3;
    private static final int OUTPUT_COLS = 2;
    private static final int LIB_COLS = 9;
    private static final int TARGET_COLS = 9;
    private static final int MAX_TARGETS = 32;
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 4;
    private int targetRows = 1;
    private int libRows = 1;
    private int visibleRows = 1;
    private int targetScrollRow;
    private static final long DELETE_WINDOW_MS = 500L;

    private static final ScenarioStore STORE = new ScenarioStore(
            FMLPaths.CONFIGDIR.get().resolve("recipebalancer").resolve("scenarios"));

    private final ScenarioDraft draft = loadAutosave();
    private final RecipePattern pattern;
    private final boolean fromJei;
    private int editingIndex = -1;
    private String carriedKey = "";
    private String status = Lang.t("左键取物品→点格子放置；中键改量；右键配方库两次删除");
    private String scenarioName = "default";

    private int libraryScroll;
    private int encodingScrollRow;
    private int lastRightClickIndex = -1;
    private long lastRightClickTime;

    // 默认值让 JEI 在首次 Screen init 之前查询属性也能得到合法尺寸
    private int panelX = 4;
    private int panelY = 4;
    private int panelW = 172;
    private int panelH = 200;
    private int topY;
    private int paramsY;
    private int targetLabelY;
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
    private EditBox nameBox;

    public BalancerScreen() {
        this(new RecipePattern(), false);
    }

    /** JEI 配方加号：带一个预填的编码草稿打开终端。 */
    public BalancerScreen(RecipePattern pattern) {
        this(pattern, true);
    }

    private BalancerScreen(RecipePattern pattern, boolean fromJei) {
        super(Lang.c("RecipeBalancer · 配方编码终端"));
        this.pattern = pattern;
        this.fromJei = fromJei;
        if (fromJei) {
            status = Lang.t("已从 JEI 导入编码；请检查设备/产物后点「写入」");
        }
    }

    @Override
    protected void init() {
        // 宽度由内容推算：9 格列表/背包、编码（3+2 列+设备列）、参数按钮行取最大值
        int gridWidth = 9 * SLOT;
        int paramsWidth = 4 * (30 + 2) + 36;
        int encodingWidth = GRID_COLS * SLOT + 18 + OUTPUT_COLS * SLOT + 6 + 42;
        int requiredWidth = Math.max(gridWidth, Math.max(paramsWidth, encodingWidth)) + 8;
        panelW = Math.min(requiredWidth, width - 8);
        panelH = height - 8;
        // 配平面板保持屏幕正中；JEI 自己填充 guiRight 之外的剩余空间
        panelX = (width - panelW) / 2;
        panelY = 4;

        // 固定区域：工作区名/参数/目标/配方库/编码/背包/状态
        // 编码最多 4 行；剩余高度全部给配方库
        int fixed = 168;
        int available = Math.max(3 * SLOT, panelH - fixed);
        targetRows = 2;
        visibleRows = 4;
        libRows = 1;
        // 优先保证编码 4 行；不够时先减配方库，再减目标行
        while ((targetRows + visibleRows + libRows) * SLOT > available) {
            if (libRows > 1) {
                libRows--;
            } else if (targetRows > 1) {
                targetRows--;
            } else {
                break;
            }
        }
        if ((targetRows + visibleRows + libRows) * SLOT > available) {
            // 极小窗口（GUI scale 3）兜底：压缩编码可视行
            visibleRows = Math.max(1,
                    (available - (targetRows + libRows) * SLOT) / SLOT);
        }
        int remaining = available - (targetRows + visibleRows + libRows) * SLOT;
        while (remaining >= SLOT && libRows < 14) {
            libRows++;
            remaining -= SLOT;
        }

        topY = panelY + 3;
        paramsY = topY + 20;
        targetLabelY = paramsY + 20;
        targetY = targetLabelY + 9;
        libraryLabelY = targetY + targetRows * SLOT + 2;
        libraryY = libraryLabelY + 9;
        encodingLabelY = libraryY + libRows * SLOT + 2;
        encodingY = encodingLabelY + 9;
        inventoryLabelY = encodingY + visibleRows * SLOT + 2;
        inventoryY = inventoryLabelY + 9;
        statusY = panelY + panelH - 9;

        inputX = panelX + 4;
        outputX = inputX + GRID_COLS * SLOT + 18;
        equipmentX = outputX + OUTPUT_COLS * SLOT + 6;
        inventoryX = panelX + 4;

        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - libRows)));
        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));
        targetScrollRow = Math.max(0, Math.min(targetScrollRow, maxTargetScroll()));

        nameBox = new EditBox(font, panelX + 30, topY, panelW - 108, 16,
                Lang.c("工作区名"));
        nameBox.setMaxLength(48);
        nameBox.setValue(scenarioName);
        addRenderableWidget(nameBox);
        // 载入/存储保持一行，仅压缩按钮高度到 14
        int storeX = panelX + panelW - 4 - 34;
        int loadX = storeX - 36;
        addRenderableWidget(Button.builder(Lang.c("载入"), b -> loadWorkspace())
                .bounds(loadX, topY + 1, 34, 14).build());
        addRenderableWidget(Button.builder(Lang.c("存储"), b -> storeWorkspace())
                .bounds(storeX, topY + 1, 34, 14).build());

        int optionW = 30;
        objectiveButton = addRenderableWidget(Button.builder(Lang.c("目标:设备"),
                        b -> draft.toggleObjective())
                .bounds(panelX + 4, paramsY, optionW, 18).build());
        rawButton = addRenderableWidget(Button.builder(Lang.c("原料:开"),
                        b -> draft.toggleAutoDetectRaw())
                .bounds(panelX + 4 + optionW + 2, paramsY, optionW, 18).build());
        seedButton = addRenderableWidget(Button.builder(Lang.c("种子:开"),
                        b -> draft.toggleAutoDetectSeed())
                .bounds(panelX + 4 + 2 * (optionW + 2), paramsY, optionW, 18).build());
        bootstrapButton = addRenderableWidget(Button.builder(Lang.c("投料:开"),
                        b -> draft.toggleAutoBootstrap())
                .bounds(panelX + 4 + 3 * (optionW + 2), paramsY, optionW, 18).build());
        addRenderableWidget(Button.builder(Lang.c("求解"), b -> solveNow())
                .bounds(panelX + 4 + 4 * (optionW + 2), paramsY, 36, 18).build());

        // 写入/新建恢复上下排列；高度压缩到 14，正好能塞进耗时下方
        addRenderableWidget(Button.builder(Lang.c("写入"),
                        b -> commitPattern(true))
                .bounds(equipmentX, encodingY + 38, 40, 14).build());
        addRenderableWidget(Button.builder(Lang.c("新建"),
                        b -> newPattern())
                .bounds(equipmentX, encodingY + 54, 40, 14).build());

        // resize 后 Minecraft 不会补发 Init.Post，JEI 会一直用旧属性；手动通知
        JeiScreenSupport.notifyGuiInit(this);
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
            status = Lang.t("自动保存失败: %s", e.getMessage());
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

        graphics.drawString(font, Lang.t("工作区"), panelX + 4, topY + 4, AeStyle.MUTED, false);

        graphics.drawString(font, Lang.t("目标 (%s/%s · /t)",
                        draft.targets().size(), MAX_TARGETS), panelX + 4, targetLabelY,
                AeStyle.MUTED, false);
        drawTargets(graphics, mouseX, mouseY);

        graphics.drawString(font, Lang.t("配方库 (%s)", draft.recipeCount()), panelX + 4,
                libraryLabelY, AeStyle.MUTED, false);
        drawLibrary(graphics, mouseX, mouseY);

        if (editingIndex >= 0) {
            String tag = "R" + editingIndex;
            graphics.drawString(font, tag, panelX + panelW - 4 - font.width(tag),
                    encodingLabelY, AeStyle.ACCENT, false);
        }
        drawEncoding(graphics, mouseX, mouseY);

        graphics.drawString(font, Lang.t("背包"), panelX + 4, inventoryLabelY, AeStyle.MUTED, false);
        drawInventory(graphics, mouseX, mouseY);

        graphics.drawString(font, font.plainSubstrByWidth(status, panelW - 8), panelX + 4,
                statusY, AeStyle.ACCENT, false);
    }

    private void drawTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
        targetScrollRow = Math.max(0, Math.min(targetScrollRow, maxTargetScroll()));
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                int index = (targetScrollRow + row) * TARGET_COLS + col;
                int x = panelX + 4 + col * SLOT;
                int y = targetY + row * SLOT;
                AeStyle.slot(graphics, x, y);
                if (index < entries.size()) {
                    Map.Entry<String, Double> entry = entries.get(index);
                    // 草稿内部是每 tick，一级目标统一显示每 t
                    ItemKeyMapper.renderIcon(graphics, font, entry.getKey(), x, y,
                            amountText(entry.getValue()));
                }
            }
        }
    }

    private void drawLibrary(GuiGraphics graphics, int mouseX, int mouseY) {
        int total = draft.recipeCount();
        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - libRows)));
        graphics.fill(panelX + 2, libraryY - 2, panelX + 4 + LIB_COLS * SLOT,
                libraryY + libRows * SLOT, 0x30000000);
        if (total == 0) {
            graphics.drawString(font, Lang.t("（空）在下方编码后点「写入」"),
                    panelX + 6, libraryY + 4,
                    AeStyle.MUTED, false);
            return;
        }
        for (int row = 0; row < libRows; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int index = (libraryScroll + row) * LIB_COLS + col;
                if (index >= total) {
                    continue;
                }
                int x = panelX + 4 + col * SLOT;
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
        graphics.drawString(font, Lang.t("原料 %s", pattern.inputCount()),
                inputX, encodingLabelY, AeStyle.ACCENT, false);
        graphics.drawString(font, Lang.t("产物 %s", pattern.outputCount()),
                outputX, encodingLabelY, AeStyle.ACCENT, false);
        graphics.drawString(font, "→", inputX + GRID_COLS * SLOT + 4, encodingY + 18,
                AeStyle.MUTED, false);

        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));
        for (int row = 0; row < visibleRows; row++) {
            int gridRow = encodingScrollRow + row;
            int y = encodingY + row * SLOT;
            for (int col = 0; col < GRID_COLS; col++) {
                int index = gridRow * GRID_COLS + col;
                int inX = inputX + col * SLOT;
                if (index < RecipePattern.MAX_INPUTS) {
                    AeStyle.slot(graphics, inX, y);
                    if (pattern.inputKey(index) != null) {
                        ItemKeyMapper.renderIcon(graphics, font, pattern.inputKey(index), inX, y,
                                amountText(pattern.inputAmount(index)));
                    }
                }
            }
            for (int col = 0; col < OUTPUT_COLS; col++) {
                int index = gridRow * OUTPUT_COLS + col;
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
            int barH = visibleRows * SLOT;
            graphics.fill(barX, barY, barX + 3, barY + barH, 0xFF6E6E6E);
            int thumbH = Math.max(8, barH * visibleRows / (visibleRows + maxScroll));
            int thumbY = barY + (barH - thumbH) * encodingScrollRow / maxScroll;
            graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, AeStyle.ACCENT_LIGHT);
        }

        // 设备/耗时标签在各自槽位的“后方”（右侧），时间值画在槽位内部
        AeStyle.slot(graphics, equipmentX, encodingY);
        graphics.drawString(font, Lang.t("设备"), equipmentX + SLOT + 2, encodingY + 5,
                AeStyle.MUTED, false);
        if (!pattern.equipmentKey().isBlank()) {
            ItemKeyMapper.renderIcon(graphics, font, pattern.equipmentKey(), equipmentX,
                    encodingY, null);
        }

        int timeY = encodingY + 20;
        AeStyle.slot(graphics, equipmentX, timeY);
        graphics.drawString(font, Lang.t("耗时"), equipmentX + SLOT + 2, timeY + 5,
                AeStyle.MUTED, false);

        // 一级耗时也用紧凑后缀（μ/m/k/M/G/T/P/E），并抬高 z 防止被槽位盖住
        String time = TimeExpression.formatCompact(pattern.timeTicks()) + "t";
        int textWidth = font.width(time);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 200);
        if (textWidth <= SLOT) {
            graphics.drawString(font, time, equipmentX + (SLOT - textWidth) / 2,
                    timeY + 5, AeStyle.TEXT, false);
        } else {
            // 0.001t 这类较长值缩小到槽内，不溢出、不丢失
            float scale = Math.min(0.75f, (SLOT - 1.0f) / textWidth);
            pose.translate(equipmentX + 0.5, timeY + 4.5, 0);
            pose.scale(scale, scale, 1.0f);
            graphics.drawString(font, time, 0, 0, AeStyle.TEXT, false);
        }
        pose.popPose();
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
                    graphics.renderItem(stack, x + 1, y + 1);
                    graphics.renderItemDecorations(font, stack, x + 1, y + 1);
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
        // 一级只显示紧凑值（μ/m/k/M/G/T）；精确值在二级编辑框
        return TimeExpression.formatCompact(amount);
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
        objectiveButton.setMessage(Lang.c(
                draft.options().objective() == Objective.MACHINE ? "设备" : "配方"));
        rawButton.setMessage(Lang.c("原料%s", onOff(draft.options().autoDetectRaw())));
        seedButton.setMessage(Lang.c("种子%s", onOff(draft.options().autoDetectSeed())));
        bootstrapButton.setMessage(Lang.c("投料%s", onOff(draft.options().autoBootstrap())));
    }

    private static String onOff(boolean value) {
        return value ? "+" : "-";
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
            lines.add(Lang.c("R%s · %s", library, ItemKeyMapper.displayName(product)));
            lines.add(Lang.c("产物: %s", summary(recipe.productList()))
                    .withStyle(style -> style.withColor(0xFF90FF90)));
            lines.add(Lang.c("原料: %s", summary(recipe.sourceList()))
                    .withStyle(style -> style.withColor(0xFF90C0FF)));
            lines.add(Lang.c("设备: %s · 耗时 %s", recipe.equType(),
                            TimeExpression.formatTicks(recipe.time() * 20.0))
                    .withStyle(style -> style.withColor(0xFFC0C0C0)));
            lines.add(Lang.c("左键载入编辑 · 右键两次删除")
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
                graphics.renderTooltip(font, Lang.c("设备标记：左键放置手持物品 / 中键自定义"),
                        mouseX, mouseY);
            } else {
                ItemKeyMapper.renderTooltip(graphics, font, pattern.equipmentKey(), mouseX, mouseY);
            }
            return;
        }
        if (inside(mouseX, mouseY, equipmentX, encodingY + 20, SLOT, SLOT)) {
            List<Component> lines = List.of(
                    Lang.c("耗时 %s", TimeExpression.formatTicks(pattern.timeTicks())),
                    Lang.c("精确: %s", TimeExpression.formatExactTicks(pattern.timeTicks()))
                            .withStyle(style -> style.withColor(0xFF808080)),
                    Lang.c("左键/中键编辑，支持 + - * / ^").withStyle(style ->
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
                graphics.renderTooltip(font, Lang.c("空目标槽：左键从手持标记 / 中键自定义"),
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
        for (int row = 0; row < libRows; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int x = panelX + 4 + col * SLOT;
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
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                int x = panelX + 4 + col * SLOT;
                int y = targetY + row * SLOT;
                if (inside(mouseX, mouseY, x, y, SLOT, SLOT)) {
                    return (targetScrollRow + row) * TARGET_COLS + col;
                }
            }
        }
        return -1;
    }

    private int maxTargetScroll() {
        int usedRows = (Math.max(1, draft.targets().size()) + TARGET_COLS - 1) / TARGET_COLS;
        int maxRows = Math.min(4, usedRows + (draft.targets().size() < MAX_TARGETS ? 1 : 0));
        return Math.max(0, maxRows - targetRows);
    }

    private int maxEncodingScroll() {
        int maxRow = Math.max(pattern.maxInputIndex() / GRID_COLS,
                pattern.maxOutputIndex() / OUTPUT_COLS);
        int maxRows = (RecipePattern.MAX_INPUTS + GRID_COLS - 1) / GRID_COLS;
        // 多留一行空格：最后一格填满后仍能滚到下一行继续添加
        int rows = maxRow < 0 ? 1 : Math.min(maxRows,
                Math.max(maxRow + 2, visibleRows));
        return Math.max(0, rows - visibleRows);
    }

    /** [0=输入,1=输出, index]；不在可见格子上返回 null。 */
    private int[] encodingSlotAt(double mouseX, double mouseY) {
        for (int row = 0; row < visibleRows; row++) {
            int y = encodingY + row * SLOT;
            for (int col = 0; col < GRID_COLS; col++) {
                int index = (encodingScrollRow + row) * GRID_COLS + col;
                if (index < RecipePattern.MAX_INPUTS
                        && inside(mouseX, mouseY, inputX + col * SLOT, y, SLOT, SLOT)) {
                    return new int[]{0, index};
                }
            }
            for (int col = 0; col < OUTPUT_COLS; col++) {
                int index = (encodingScrollRow + row) * OUTPUT_COLS + col;
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
                status = Lang.t("已删除目标: %s", key);
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
                    if (draft.targets().size() >= MAX_TARGETS) {
                        status = Lang.t("目标最多 %s 个", MAX_TARGETS);
                        return;
                    }
                    // 默认 1/t；ScenarioDraft 内部即每 tick
                    draft.putTarget(carriedKey, 1.0);
                    onDraftChanged();
                    status = Lang.t("已添加目标: %s 1/t", carriedKey);
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
        boolean adding = key == null || key.isBlank();
        if (adding && draft.targets().size() >= MAX_TARGETS) {
            status = Lang.t("目标最多 %s 个", MAX_TARGETS);
            return;
        }
        // amount 是每 tick；新目标默认 1/t
        double initialPerTick = adding ? 1.0 : amount;
        minecraft.setScreen(new AmountScreen(this, "目标产物", key, initialPerTick, true, true,
                (newKey, perTick) -> {
                    if (!newKey.equals(key)) {
                        draft.removeTarget(key);
                    }
                    if (draft.targets().size() >= MAX_TARGETS
                            && !draft.targets().containsKey(newKey)) {
                        status = Lang.t("目标最多 %s 个", MAX_TARGETS);
                        return;
                    }
                    draft.putTarget(newKey, perTick);
                    onDraftChanged();
                    status = Lang.t("目标: %s × %s/t", newKey,
                            TimeExpression.formatNumber(perTick));
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
                status = Lang.t("再次右键删除 R%s", index);
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
                status = Lang.t("已标记 %s", ItemKeyMapper.displayName(carriedKey));
            } else if (key != null) {
                if (input) {
                    pattern.removeInput(index);
                } else {
                    pattern.removeOutput(index);
                }
                status = Lang.t("已移除格子");
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
                    status = Lang.t(input ? "原料 %s × %s" : "产物 %s × %s",
                            newKey, TimeExpression.formatNumber(newAmount));
                }));
    }

    private void handleEquipmentClick(int button) {
        if (button == 0 && !carriedKey.isEmpty()) {
            pattern.setEquipmentKey(carriedKey);
            status = Lang.t("设备: %s", ItemKeyMapper.displayName(carriedKey));
            return;
        }
        minecraft.setScreen(new AmountScreen(this, "设备标记", pattern.equipmentKey(), 1.0, false,
                (key, ignored) -> {
                    pattern.setEquipmentKey(key);
                    status = Lang.t("设备: %s", ItemKeyMapper.displayName(key));
                }));
    }

    private void handleInventoryClick(int index, int button) {
        if (button == 0) {
            ItemStack stack = minecraft.player == null ? ItemStack.EMPTY
                    : minecraft.player.getInventory().getItem(index);
            if (!stack.isEmpty()) {
                carriedKey = ItemKeyMapper.of(stack);
                status = Lang.t("手持: %s", stack.getHoverName().getString());
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
            status = Lang.t("耗时: %s", TimeExpression.formatTicks(ticks));
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseY >= targetY
                && mouseY < targetY + targetRows * SLOT) {
            targetScrollRow = Math.max(0, Math.min(
                    targetScrollRow + (scrollY < 0 ? 1 : -1), maxTargetScroll()));
            return true;
        }
        if (mouseX >= panelX && mouseY >= libraryY - 2
                && mouseY < libraryY + libRows * SLOT) {
            libraryScroll = Math.max(0, Math.min(libraryScroll + (scrollY < 0 ? 1 : -1),
                    Math.max(0, recipeRows() - libRows)));
            return true;
        }
        if (mouseX >= inputX && mouseX < panelX + panelW - 4
                && mouseY >= encodingY && mouseY < encodingY + visibleRows * SLOT) {
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
                ? Lang.t("R%s 已载入；超过 256/16 的部分未显示", index)
                : Lang.t("R%s 已载入编码区", index);
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
            status = (showStatus ? Lang.t("已写入 R%s", editingIndex) : Lang.t("已保存编码"))
                    + (pattern.truncated() ? Lang.t("（超过 9 格的部分未写入）") : "");
            return true;
        } catch (RuntimeException e) {
            if (showStatus) {
                status = Lang.t("写入失败: %s", e.getMessage());
            }
            return false;
        }
    }

    private void newPattern() {
        pattern.clear();
        editingIndex = -1;
        carriedKey = "";
        encodingScrollRow = 0;
        status = Lang.t("新建配方编码");
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
        status = Lang.t("已删除 R%s", index);
    }

    private void solveNow() {
        if (pattern.hasContent() && !commitPattern(false)) {
            status = Lang.t("编码未完成：至少要有产物、设备和正耗时");
            return;
        }
        if (draft.recipeCount() == 0) {
            status = Lang.t("至少写入 1 条配方");
            return;
        }
        if (draft.targets().isEmpty()) {
            status = Lang.t("至少添加 1 个目标产物");
            return;
        }
        try {
            SolveResult result = Balancer.solve(draft.toScenario());
            persistDraft();
            // 报告里把 stack|base64 替换成“物品名 [效果/时长]”，不改变磁盘数据
            String report = Lang.report(ItemKeyMapper.prettyText(
                    TextReporter.formatPerTick(result, draft.recipes())));
            minecraft.setScreen(new SolveScreen(this, report, result,
                    draft.recipes(), new java.util.LinkedHashMap<>(draft.targets()),
                    draft.options().autoBootstrap(), result.success()));
        } catch (RuntimeException | LinkageError e) {
            RecipeBalancerMod.LOGGER.error("[RecipeBalancer] 求解失败", e);
            status = Lang.t("求解失败: %s", e);
        }
    }

    private void loadWorkspace() {
        minecraft.setScreen(new ScenarioListScreen(this, STORE, draft));
    }

    private void storeWorkspace() {
        try {
            scenarioName = ScenarioStore.sanitize(nameBox.getValue());
            nameBox.setValue(scenarioName);
            STORE.save(scenarioName, draft.toScenario());
            persistDraft();
            status = Lang.t("已存储工作区: %s", scenarioName);
        } catch (RuntimeException e) {
            status = Lang.t("存储失败: %s", e.getMessage());
        }
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
        libraryScroll = 0;
        targetScrollRow = 0;
        encodingScrollRow = 0;
        status = Lang.t("已载入工作区: %s", name);
        persistDraft();
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // ------------------------------------------------------------------
    // JEI ghost 拖拽 / 配方加号
    // ------------------------------------------------------------------

    public enum GhostKind {
        INPUT, OUTPUT, EQUIPMENT, TARGET
    }

    public record GhostSlot(GhostKind kind, int index, int x, int y) {
    }

    /** 当前可见、可接收 ghost 的槽位（屏幕绝对坐标）。 */
    public java.util.List<GhostSlot> ghostSlots() {
        java.util.List<GhostSlot> result = new java.util.ArrayList<>();
        for (int row = 0; row < visibleRows; row++) {
            int y = encodingY + row * SLOT;
            for (int col = 0; col < GRID_COLS; col++) {
                int index = (encodingScrollRow + row) * GRID_COLS + col;
                if (index < RecipePattern.MAX_INPUTS) {
                    result.add(new GhostSlot(GhostKind.INPUT, index, inputX + col * SLOT, y));
                }
            }
            for (int col = 0; col < OUTPUT_COLS; col++) {
                int index = (encodingScrollRow + row) * OUTPUT_COLS + col;
                if (index < RecipePattern.MAX_OUTPUTS) {
                    result.add(new GhostSlot(GhostKind.OUTPUT, index, outputX + col * SLOT, y));
                }
            }
        }
        result.add(new GhostSlot(GhostKind.EQUIPMENT, 0, equipmentX, encodingY));
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                int index = (targetScrollRow + row) * TARGET_COLS + col;
                result.add(new GhostSlot(GhostKind.TARGET, index,
                        panelX + 4 + col * SLOT, targetY + row * SLOT));
            }
        }
        return result;
    }

    public record HoveredKey(String key, int x, int y, int width, int height) {
    }

    /** JEI R/U：鼠标下的 ingredient key 与屏幕矩形（物品/流体/化学品通用）。 */
    public HoveredKey jeiHoveredKey(double mouseX, double mouseY) {
        int[] slot = encodingSlotAt(mouseX, mouseY);
        if (slot != null) {
            String key = slot[0] == 0 ? pattern.inputKey(slot[1]) : pattern.outputKey(slot[1]);
            if (key != null) {
                int cols = slot[0] == 0 ? GRID_COLS : OUTPUT_COLS;
                int row = slot[1] / cols - encodingScrollRow;
                int col = slot[1] % cols;
                return hovered(key, (slot[0] == 0 ? inputX : outputX) + col * SLOT,
                        encodingY + row * SLOT);
            }
        }
        if (inside(mouseX, mouseY, equipmentX, encodingY, SLOT, SLOT)
                && !pattern.equipmentKey().isBlank()) {
            return hovered(pattern.equipmentKey(), equipmentX, encodingY);
        }
        int target = targetSlotAt(mouseX, mouseY);
        if (target >= 0) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
            if (target < entries.size()) {
                int row = target / TARGET_COLS - targetScrollRow;
                int col = target % TARGET_COLS;
                return hovered(entries.get(target).getKey(),
                        panelX + 4 + col * SLOT, targetY + row * SLOT);
            }
        }
        int library = libraryIndexAt(mouseX, mouseY);
        if (library >= 0) {
            String product = draft.recipes().get(library).productList().keySet()
                    .stream().findFirst().orElse("");
            if (!product.isEmpty()) {
                int row = library / LIB_COLS - libraryScroll;
                int col = library % LIB_COLS;
                return hovered(product, panelX + 4 + col * SLOT, libraryY + row * SLOT);
            }
        }
        int inventory = inventoryIndexAt(mouseX, mouseY);
        if (inventory >= 0 && minecraft != null && minecraft.player != null) {
            net.minecraft.world.item.ItemStack stack = minecraft.player.getInventory()
                    .getItem(inventory);
            if (!stack.isEmpty()) {
                int row = inventory < 9 ? 3 : (inventory - 9) / 9;
                int col = inventory < 9 ? inventory : (inventory - 9) % 9;
                return new HoveredKey(ItemKeyMapper.of(stack), inventoryX + col * SLOT,
                        inventoryY + row * SLOT, SLOT, SLOT);
            }
        }
        return null;
    }

    private static HoveredKey hovered(String key, int x, int y) {
        return key == null || key.isBlank() ? null : new HoveredKey(key, x, y, SLOT, SLOT);
    }

    public void applyGhost(GhostKind kind, int index, String key) {
        applyGhost(kind, index, key, 1.0);
    }

    public void applyGhost(GhostKind kind, int index, String key, double amount) {
        if (key == null || key.isBlank()) {
            return;
        }
        double value = amount > 0 ? amount : 1.0;
        switch (kind) {
            case INPUT -> pattern.putInput(index, key, value);
            case OUTPUT -> pattern.putOutput(index, key, value);
            case EQUIPMENT -> pattern.setEquipmentKey(key);
            case TARGET -> {
                if (draft.targets().size() >= MAX_TARGETS && !draft.targets().containsKey(key)) {
                    status = Lang.t("目标最多 %s 个", MAX_TARGETS);
                    return;
                }
                draft.putTarget(key, value);
                onDraftChanged();
            }
        }
        status = "JEI ghost: " + ItemKeyMapper.displayName(key)
                + " × " + TimeExpression.formatNumber(value);
    }

    // JEI 非 ContainerScreen 渲染（RecipeBalancerJeiPlugin 使用）
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
