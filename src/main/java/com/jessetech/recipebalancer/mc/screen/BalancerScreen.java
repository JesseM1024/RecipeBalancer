package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.report.TextReporter;
import com.jessetech.recipebalancer.core.solve.Balancer;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.io.WorkspaceSession;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.itemmap.ScenarioKeyNormalizer;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.IconButton;
import com.jessetech.recipebalancer.mc.ui.PixelIcon;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /** 左右两列图标按钮：18×18 方形，与面板外缘留 2px 缝。 */
    private static final int SIDE_GAP = 2;
    private static final int SIDE_PAD = SLOT + SIDE_GAP;
    private static final int GRID_COLS = 3;
    private static final int OUTPUT_COLS = 2;
    private static final int LIB_COLS = 9;
    private static final int TARGET_COLS = 9;
    /** 编码区中间的缩放小按钮：2 列 × 3 行 + 底部一格自定义（无间隙，尽量省宽度）。 */
    private static final int MULTI_W = 13;
    private static final int MULTI_H = 10;
    private static final int MULTI_GAP = 0;
    private static final double[] MULTI_FACTORS = {2, 3, 5};
    /** 右侧设备/耗时/写入列宽度。 */
    private static final int EQUIP_W = 40;
    private static final int MAX_TARGETS = 32;
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 4;
    private int targetRows = 1;
    private int libRows = 1;
    private int visibleRows = 1;
    private int targetScrollRow;
    /** 配方库筛选：文本 + 原料/产物/设备三个开关格。 */
    private static final int LIB_FILTER_CELL = 30;
    private static final int LIB_FILTER_BOX = 66;
    private static final String[] LIB_FILTER_LABELS = {"原料", "产物", "设备"};
    private final boolean[] libFilterFields = {true, true, true};
    private final List<Integer> libraryView = new ArrayList<>();
    private String libraryQuery = "";
    private static final long DELETE_WINDOW_MS = 500L;

    private static final ScenarioStore STORE = WorkspaceSession.store();

    private final ScenarioDraft draft = loadAutosave();
    private final RecipePattern pattern;
    private final boolean fromJei;
    private int editingIndex = -1;
    private String carriedKey = "";
    private String status = Lang.t("左键取物品→点格子放置；中键改量；右键配方库两次删除");
    /** 当前工作区名；跨界面/重启保留，新建工作区时回到默认值。 */
    private String scenarioName = WorkspaceSession.currentName();

    /**
     * 延后绘制的物品图标。
     *
     * <p>原版 {@code GuiGraphics.renderItem} 每画一件物品就 {@code flush()} 一次，
     * 配方库满屏时一帧要 flush 200+ 次；这里先把物品排队，同一帧内容画完后
     * 按「平面 / 立体光照」分组一次性提交，渲染结果与原版逐件绘制完全一致。
     */
    private record PendingItem(ItemStack stack, BakedModel model, boolean flat,
                               float x, float y, float z, float scale) {
    }

    private final List<PendingItem> pendingItems = new ArrayList<>();

    /** 槽位底图（边框/内区/高光）预计算：5 个 int 一个矩形，布局/滚动变化时重建。 */
    private int[] slotChrome = new int[0];
    private int slotChromeLibScroll = -1;
    private int slotChromeLibTotal = -1;
    private int slotChromeEncRow = -1;

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
    private int contentX;
    private int targetLabelY;
    private int targetY;
    private int libraryLabelY;
    private int libraryY;
    private int libraryFilterY;
    private int libFilterBarX;
    private int encodingLabelY;
    private int encodingY;
    private int inventoryLabelY;
    private int inventoryY;
    private int statusY;
    private int inputX;
    private int multiX;
    private int multiY;
    private int outputX;
    private int equipmentX;
    private int inventoryX;

    private IconButton objectiveButton;
    private IconButton rawButton;
    private IconButton seedButton;
    private IconButton bootstrapButton;
    private IconButton solveButton;
    private IconButton rawListButton;
    private IconButton externalInputButton;
    private EditBox nameBox;
    private EditBox libraryFilterBox;

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
        // 面板只包住内容；左右两列图标按钮贴在面板外侧，不占面板底色
        int sideW = SLOT;
        int sideGap = SIDE_GAP;
        int gridWidth = 9 * SLOT;
        int multiBlockW = 2 * MULTI_W + MULTI_GAP;
        // 编码区总宽度压到和 9 格一致（162），这样面板左右边距对称、9 格区域居中
        int encodingWidth = GRID_COLS * SLOT + 1 + multiBlockW + 1
                + OUTPUT_COLS * SLOT + 4 + EQUIP_W;
        int requiredWidth = Math.max(gridWidth, encodingWidth) + 8;
        panelW = Math.min(requiredWidth, width - 8);
        panelH = height - 8;
        // 配平面板保持屏幕正中；JEI 自己填充 guiRight 之外的剩余空间
        panelX = (width - panelW) / 2;
        panelY = 4;

        // 固定区域：工作区名/目标/配方库/配方库筛选行/编码/背包/状态
        // 编码最多 4 行；剩余高度全部给配方库
        int fixed = 166;
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
        targetLabelY = topY + 22;
        targetY = targetLabelY + 9;
        libraryLabelY = targetY + targetRows * SLOT + 2;
        libraryY = libraryLabelY + 9;
        libraryFilterY = libraryY + libRows * SLOT + 2;
        encodingLabelY = libraryFilterY + 16;
        encodingY = encodingLabelY + 9;
        inventoryLabelY = encodingY + visibleRows * SLOT + 2;
        inventoryY = inventoryLabelY + 9;
        statusY = panelY + panelH - 9;

        contentX = panelX + 4;
        libFilterBarX = contentX + LIB_FILTER_BOX + 2;
        inputX = contentX;
        // 原料与产物之间插一列缩放按钮（2 列 3 行 + 底部自定义）
        multiX = inputX + GRID_COLS * SLOT + 1;
        multiY = encodingY + 16;
        outputX = multiX + multiBlockW + 1;
        equipmentX = outputX + OUTPUT_COLS * SLOT + 4;
        inventoryX = contentX;

        refreshLibraryView();
        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - libRows)));
        buildSlotChrome();
        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));
        targetScrollRow = Math.max(0, Math.min(targetScrollRow, maxTargetScroll()));

        // 名称框顶满左侧（不再画“工作区”标签），右侧依次是 存取/保存/新建
        int buttonW = 30;
        int gap = 2;
        int newX = panelX + panelW - 4 - buttonW;
        int saveX = newX - gap - buttonW;
        int openX = saveX - gap - buttonW;
        String typedName = nameBox == null ? scenarioName : nameBox.getValue();

        nameBox = new EditBox(font, panelX + 4, topY, Math.max(40, openX - 4 - panelX - 4), 16,
                Lang.c("工作区名"));
        nameBox.setMaxLength(48);
        nameBox.setValue(typedName);
        addRenderableWidget(nameBox);

        // 配方库筛选：左边关键字，右边原料/产物/设备三格开关
        libraryFilterBox = new EditBox(font, contentX, libraryFilterY, LIB_FILTER_BOX, 14,
                Lang.c("筛选"));
        libraryFilterBox.setMaxLength(40);
        libraryFilterBox.setHint(Lang.c("筛选"));
        libraryFilterBox.setValue(libraryQuery);
        libraryFilterBox.setResponder(value -> {
            libraryQuery = value;
            libraryScroll = 0;
            refreshLibraryView();
        });
        addRenderableWidget(libraryFilterBox);

        addRenderableWidget(Button.builder(Lang.c("存取"), b -> openWorkspaceList())
                .bounds(openX, topY + 1, buttonW, 14).build());
        addRenderableWidget(Button.builder(Lang.c("保存"), b -> storeWorkspace())
                .bounds(saveX, topY + 1, buttonW, 14).build());
        addRenderableWidget(Button.builder(Lang.c("新建"), b -> newWorkspace())
                .bounds(newX, topY + 1, buttonW, 14).build());

        // 左列：四个开关，贴在面板左外侧，从目标产物那一行起逐行向下；右列：求解
        int sideX = panelX - SIDE_PAD;
        int sideStep = sideW + 2;
        objectiveButton = addRenderableWidget(new IconButton(sideX, targetY, sideW,
                PixelIcon.MACHINE, false,
                () -> Lang.c("最小化目标: %s（左键切换）",
                        Lang.t(draft.options().objective() == Objective.MACHINE ? "设备" : "配方")),
                b -> draft.toggleObjective()));
        rawButton = addRenderableWidget(new IconButton(sideX, targetY + sideStep, sideW,
                PixelIcon.INGOT, true,
                () -> onOffLine("自动探测外部原料", draft.options().autoDetectRaw(), false),
                b -> draft.toggleAutoDetectRaw()));
        seedButton = addRenderableWidget(new IconButton(sideX, targetY + 2 * sideStep, sideW,
                PixelIcon.CRYSTAL, true,
                () -> onOffLine("自增殖种子检测", draft.options().autoDetectSeed(), false),
                b -> draft.toggleAutoDetectSeed()));
        bootstrapButton = addRenderableWidget(new IconButton(sideX, targetY + 3 * sideStep, sideW,
                PixelIcon.FEED, true,
                () -> onOffLine("循环启动物料库存检测", draft.options().autoBootstrap(), false),
                b -> draft.toggleAutoBootstrap()));
        solveButton = addRenderableWidget(new IconButton(panelX + panelW + sideGap, targetY,
                sideW, PixelIcon.SOLVE, false,
                this::solveTooltip,
                b -> solveNow()));
        // 求解下方：raw_materials / allowed_external_cyc_inputs（只看物品种类）
        rawListButton = addRenderableWidget(new IconButton(panelX + panelW + sideGap,
                targetY + sideStep, sideW, PixelIcon.RAW_LIST, false,
                () -> twoLineTooltip("无限外部供应物质",
                        "集合中的物质可无限供应，算法可以选择是否使用以跳过部分工业链条。"
                                + "若自动探测外部原料关闭，则所有原料需要完整手动定义"),
                b -> openRawMaterials()));
        externalInputButton = addRenderableWidget(new IconButton(panelX + panelW + sideGap,
                targetY + 2 * sideStep, sideW, PixelIcon.EXTERNAL_INPUT, false,
                () -> twoLineTooltip("循环亏损补充物质",
                        "集合为空时算法自动检测循环亏损物质并尝试进行补充，"
                                + "集合内有定义时，算法将集合中的物质视作严格白名单尝试补足循环亏损"),
                b -> openExternalInputs()));

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
            return ScenarioDraft.from(ScenarioKeyNormalizer.normalize(
                    STORE.loadDraft("autosave")));
        } catch (RuntimeException e) {
            return ScenarioDraft.blank();
        }
    }

    private boolean persistDraft() {
        // 配方增删后同步刷新配方库视图（筛选条件不变）
        refreshLibraryView();
        try {
            STORE.save("autosave", ScenarioKeyNormalizer.normalize(draft.toScenario()));
            return true;
        } catch (RuntimeException e) {
            status = Lang.t("自动保存失败: %s", e.getMessage());
            return false;
        }
    }

    /** Scenario 的目标是每秒；流程图 tooltip 使用每 tick。 */
    private static Map<String, Double> perTickTargets(Scenario scenario) {
        Map<String, Double> result = new LinkedHashMap<>();
        scenario.targetProducts().forEach(
                (key, perSecond) -> result.put(key, perSecond / 20.0));
        return result;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101010);
        AeStyle.panel(graphics, panelX, panelY, panelW, panelH);
        AeStyle.header(graphics, panelX, panelY, panelW, 16);

        // 配方库底板 + 全部槽位底图：一次批量提交，只有图标/文字走逐格绘制
        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - libRows)));
        graphics.fill(contentX - 2, libraryY - 2, contentX + LIB_COLS * SLOT,
                libraryY + libRows * SLOT, 0x30000000);
        drawSlotChrome(graphics);

        graphics.drawString(font, Lang.t("目标 (%s/%s · /t)",
                        draft.targets().size(), MAX_TARGETS), contentX, targetLabelY,
                AeStyle.MUTED, false);
        drawTargets(graphics, mouseX, mouseY);

        String libraryCount = libraryQuery.isBlank() ? String.valueOf(draft.recipeCount())
                : libraryView.size() + "/" + draft.recipeCount();
        graphics.drawString(font, Lang.t("配方库 (%s)", libraryCount), contentX,
                libraryLabelY, AeStyle.MUTED, false);
        drawLibrary(graphics, mouseX, mouseY);
        drawLibraryFilter(graphics, mouseX, mouseY);

        if (editingIndex >= 0) {
            String tag = "R" + editingIndex;
            graphics.drawString(font, tag, panelX + panelW - 4 - font.width(tag),
                    encodingLabelY, AeStyle.ACCENT, false);
        }
        drawEncoding(graphics, mouseX, mouseY);

        graphics.drawString(font, Lang.t("背包"), contentX, inventoryLabelY, AeStyle.MUTED, false);
        drawInventory(graphics, mouseX, mouseY);

        graphics.drawString(font, font.plainSubstrByWidth(status, panelW - 8), panelX + 4,
                statusY, AeStyle.ACCENT, false);

        // 内容画完后再统一提交排队的物品图标（按光照分组，各只 flush 一次）
        drawPendingItems(graphics);
    }

    /**
     * 把所有槽位底图（边框 + 内区 + 高光）按当前布局预计算成矩形列表。
     *
     * <p>槽位位置只跟布局有关，滚动/选中/悬停都不改变它，所以每次 init 算一次即可；
     * 每帧只提交一次顶点，不再逐格调用 4 次 {@code GuiGraphics.fill}。
     */
    private void buildSlotChrome() {
        int slots = targetRows * TARGET_COLS + libRows * LIB_COLS
                + visibleRows * (GRID_COLS + OUTPUT_COLS) + 2 + INV_ROWS * INV_COLS;
        int[] data = new int[slots * 4 * 5];
        int n = 0;
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                n = addSlotQuads(data, n, contentX + col * SLOT, targetY + row * SLOT);
            }
        }
        // 配方库：只有真实配方所在格才画槽（和逐格绘制时一致）
        int libTotal = libraryView.size();
        for (int row = 0; row < libRows; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                if ((libraryScroll + row) * LIB_COLS + col >= libTotal) {
                    continue;
                }
                n = addSlotQuads(data, n, contentX + col * SLOT, libraryY + row * SLOT);
            }
        }
        // 编码区：输入/产物各自到上限为止（随滚动行变化）
        for (int row = 0; row < visibleRows; row++) {
            int y = encodingY + row * SLOT;
            for (int col = 0; col < GRID_COLS; col++) {
                if ((encodingScrollRow + row) * GRID_COLS + col < RecipePattern.MAX_INPUTS) {
                    n = addSlotQuads(data, n, inputX + col * SLOT, y);
                }
            }
            for (int col = 0; col < OUTPUT_COLS; col++) {
                if ((encodingScrollRow + row) * OUTPUT_COLS + col < RecipePattern.MAX_OUTPUTS) {
                    n = addSlotQuads(data, n, outputX + col * SLOT, y);
                }
            }
        }
        n = addSlotQuads(data, n, equipmentX, encodingY);
        n = addSlotQuads(data, n, equipmentX, encodingY + 20);
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                n = addSlotQuads(data, n, inventoryX + col * SLOT, inventoryY + row * SLOT);
            }
        }
        slotChrome = java.util.Arrays.copyOf(data, n);
        slotChromeLibScroll = libraryScroll;
        slotChromeLibTotal = libTotal;
        slotChromeEncRow = encodingScrollRow;
    }

    /** 一个 18px 槽的 4 层矩形：深色外框、内区、右下高光两条。 */
    private static int addSlotQuads(int[] data, int n, int x, int y) {
        int size = SLOT;
        n = addQuad(data, n, x, y, x + size, y + size, AeStyle.SLOT_BORDER);
        n = addQuad(data, n, x + 1, y + 1, x + size - 1, y + size - 1, AeStyle.SLOT_FILL);
        n = addQuad(data, n, x + 1, y + size - 1, x + size, y + size, AeStyle.SLOT_HIGHLIGHT);
        n = addQuad(data, n, x + size - 1, y + 1, x + size, y + size, AeStyle.SLOT_HIGHLIGHT);
        return n;
    }

    private static int addQuad(int[] data, int n, int x1, int y1, int x2, int y2, int color) {
        data[n] = x1;
        data[n + 1] = y1;
        data[n + 2] = x2;
        data[n + 3] = y2;
        data[n + 4] = color;
        return n + 5;
    }

    /** 一次性把预计算的槽位底图写进 GUI 批量缓冲（顶点顺序与 GuiGraphics.fill 一致）。 */
    private void drawSlotChrome(GuiGraphics graphics) {
        int libTotal = libraryView.size();
        if (slotChrome.length == 0 || slotChromeLibScroll != libraryScroll
                || slotChromeLibTotal != libTotal || slotChromeEncRow != encodingScrollRow) {
            buildSlotChrome();
        }
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f matrix = graphics.pose().last().pose();
        for (int i = 0; i < slotChrome.length; i += 5) {
            int x1 = slotChrome[i];
            int y1 = slotChrome[i + 1];
            int x2 = slotChrome[i + 2];
            int y2 = slotChrome[i + 3];
            int color = slotChrome[i + 4];
            consumer.addVertex(matrix, x1, y1, 0.0f).setColor(color);
            consumer.addVertex(matrix, x1, y2, 0.0f).setColor(color);
            consumer.addVertex(matrix, x2, y2, 0.0f).setColor(color);
            consumer.addVertex(matrix, x2, y1, 0.0f).setColor(color);
        }
    }

    /**
     * 槽内图标：物品排队延后绘制，非物品（JEI 化学品/流体、占位字母）仍即时绘制。
     *
     * <p>数量文字仍旧立刻画（它抬到 z=200，深度上永远压在图标上方），
     * 所以延后绘制图标不会让数字被盖住。
     */
    private void renderSlotIcon(GuiGraphics graphics, Font font, String key, int x, int y,
                                String amount) {
        ItemStack stack = ItemKeyMapper.stack(key);
        if (stack.isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, key, x, y, amount);
            return;
        }
        queueItem(stack, x + 1, y + 1, 0, 1.0f);
        ItemKeyMapper.renderAmountOverlay(graphics, font, amount, x, y);
    }

    /** 把物品图标排进延后队列（位置/缩放/额外 z 都与原版逐件绘制的变换一致）。 */
    private void queueItem(ItemStack stack, float x, float y, float z, float scale) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getItemRenderer()
                .getModel(stack, minecraft.level, minecraft.player, 0);
        pendingItems.add(new PendingItem(stack, model, !model.usesBlockLight(),
                x, y, z, scale));
    }

    /** 本轮内容画完后统一提交：平面光照组先画并 flush，立体光照组再画一次。 */
    private void drawPendingItems(GuiGraphics graphics) {
        if (pendingItems.isEmpty()) {
            return;
        }
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        boolean hasFlat = false;
        boolean hasSolid = false;
        for (PendingItem item : pendingItems) {
            if (item.flat()) {
                hasFlat = true;
            } else {
                hasSolid = true;
            }
        }
        if (hasFlat) {
            Lighting.setupForFlatItems();
            for (PendingItem item : pendingItems) {
                if (item.flat()) {
                    drawPending(graphics, renderer, item);
                }
            }
            graphics.flush();
            Lighting.setupFor3DItems();
        }
        if (hasSolid) {
            for (PendingItem item : pendingItems) {
                if (!item.flat()) {
                    drawPending(graphics, renderer, item);
                }
            }
            graphics.flush();
        }
        pendingItems.clear();
    }

    private void drawPending(GuiGraphics graphics, ItemRenderer renderer, PendingItem item) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(item.x(), item.y(), item.z());
        if (item.scale() != 1.0f) {
            pose.scale(item.scale(), item.scale(), 1.0f);
        }
        pose.translate(8.0f, 8.0f, 150.0f);
        pose.scale(16.0f, -16.0f, 16.0f);
        renderer.render(item.stack(), ItemDisplayContext.GUI, false, pose,
                graphics.bufferSource(), 15728880, OverlayTexture.NO_OVERLAY, item.model());
        pose.popPose();
    }

    private void drawTargets(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(draft.targets().entrySet());
        targetScrollRow = Math.max(0, Math.min(targetScrollRow, maxTargetScroll()));
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                int index = (targetScrollRow + row) * TARGET_COLS + col;
                int x = contentX + col * SLOT;
                int y = targetY + row * SLOT;
                if (index < entries.size()) {
                    Map.Entry<String, Double> entry = entries.get(index);
                    // 草稿内部是每 tick，一级目标统一显示每 t
                    renderSlotIcon(graphics, font, entry.getKey(), x, y,
                            amountText(entry.getValue()));
                }
            }
        }
    }

    private void drawLibrary(GuiGraphics graphics, int mouseX, int mouseY) {
        int total = libraryView.size();
        libraryScroll = Math.max(0, Math.min(libraryScroll,
                Math.max(0, recipeRows() - libRows)));
        if (draft.recipeCount() == 0) {
            graphics.drawString(font, Lang.t("（空）在下方编码后点「写入」"),
                    panelX + 6, libraryY + 4,
                    AeStyle.MUTED, false);
            return;
        }
        if (total == 0) {
            graphics.drawString(font, Lang.t("（没有匹配的配方）"),
                    panelX + 6, libraryY + 4, AeStyle.MUTED, false);
            return;
        }
        // 边框要最后画：相邻格子的 slot 会盖掉当前格右侧/下侧的 1px 边
        List<int[]> marks = new ArrayList<>();
        boolean deleteArmed = lastRightClickIndex >= 0
                && System.currentTimeMillis() - lastRightClickTime <= DELETE_WINDOW_MS;
        for (int row = 0; row < libRows; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int index = (libraryScroll + row) * LIB_COLS + col;
                if (index >= total) {
                    continue;
                }
                int x = contentX + col * SLOT;
                int y = libraryY + row * SLOT;
                int recipeIndex = libraryView.get(index);
                // 右键待删除时红底，否则当前编辑项蓝底；边框统一在循环后补
                if (deleteArmed && recipeIndex == lastRightClickIndex) {
                    graphics.fill(x, y, x + SLOT, y + SLOT, 0x66D94A4A);
                    marks.add(new int[]{x, y, 0xFFD94A4A});
                } else if (recipeIndex == editingIndex) {
                    graphics.fill(x, y, x + SLOT, y + SLOT, 0x664A90D9);
                    marks.add(new int[]{x, y, 0xFF4A90D9});
                }
                Recipe recipe = draft.recipes().get(recipeIndex);
                String productKey = recipe.productList().keySet().stream().findFirst().orElse("");
                if (productKey.isEmpty()) {
                    graphics.drawString(font, "?", x + 5, y + 4, 0xFFFF8080, false);
                } else {
                    renderSlotIcon(graphics, font, productKey, x, y, null);
                }
                if (!recipe.equType().isBlank()) {
                    ItemStack equipment = ItemKeyMapper.stack(recipe.equType());
                    if (equipment.isEmpty()) {
                        // 非物品设备（理论少见）仍按原变换即时绘制
                        PoseStack pose = graphics.pose();
                        pose.pushPose();
                        pose.translate(x + 10, y + 10, 0);
                        pose.scale(0.5f, 0.5f, 1.0f);
                        ItemKeyMapper.renderIcon(graphics, font, recipe.equType(), 0, 0, null);
                        pose.popPose();
                    } else {
                        // 原版这里是 translate(x+10,y+10) + scale(0.5) 后再 renderItem(1,1)，
                        // 折合到延后队列的坐标就是 x+10.5 / y+10.5
                        queueItem(equipment, x + 10.5f, y + 10.5f, 0, 0.5f);
                    }
                }
            }
        }
        for (int[] mark : marks) {
            drawLibraryBorder(graphics, mark[0], mark[1], mark[2]);
        }
    }

    /** 配方库格子四边 1px 边框（蓝=当前编辑，红=待删除）。 */
    private static void drawLibraryBorder(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x - 1, y - 1, x + SLOT + 1, y, color);
        graphics.fill(x - 1, y + SLOT, x + SLOT + 1, y + SLOT + 1, color);
        graphics.fill(x - 1, y - 1, x, y + SLOT + 1, color);
        graphics.fill(x + SLOT, y - 1, x + SLOT + 1, y + SLOT + 1, color);
    }

    /** 配方库筛选行右侧的三格开关：原料 / 产物 / 设备；启用格用蓝色底一眼可辨。 */
    private void drawLibraryFilter(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < LIB_FILTER_LABELS.length; i++) {
            int x = libFilterBarX + i * (LIB_FILTER_CELL + 1);
            int y = libraryFilterY;
            boolean on = libFilterFields[i];
            boolean hover = mouseX >= x && mouseX < x + LIB_FILTER_CELL
                    && mouseY >= y && mouseY < y + 14;
            int fill = on ? AeStyle.ACCENT_LIGHT : (hover ? 0xFFCFCFCF : 0xFFB8B8B8);
            graphics.fill(x, y, x + LIB_FILTER_CELL, y + 14, AeStyle.SLOT_BORDER);
            graphics.fill(x + 1, y + 1, x + LIB_FILTER_CELL - 1, y + 13, fill);
            String label = Lang.t(LIB_FILTER_LABELS[i]);
            graphics.drawString(font, label,
                    x + (LIB_FILTER_CELL - font.width(label)) / 2, y + 3,
                    on ? 0xFFFFFFFF : AeStyle.TEXT, false);
        }
    }

    /** 配方库筛选：真名或注册名包含关键字即命中；三格全关时按全选处理。 */
    private void refreshLibraryView() {
        String query = libraryQuery.trim().toLowerCase(Locale.ROOT);
        boolean allFields = !libFilterFields[0] && !libFilterFields[1] && !libFilterFields[2];
        libraryView.clear();
        for (int index = 0; index < draft.recipeCount(); index++) {
            Recipe recipe = draft.recipes().get(index);
            if (query.isEmpty() || matchesLibraryFilter(recipe, query, allFields)) {
                libraryView.add(index);
            }
        }
        // 配方库内容可能刚变过：JEI 侧的产物索引缓存一起失效
        ProductIndex.invalidate();
    }

    private boolean matchesLibraryFilter(Recipe recipe, String query, boolean allFields) {
        if ((allFields || libFilterFields[0]) && matchesKeys(recipe.sourceList().keySet(), query)) {
            return true;
        }
        if ((allFields || libFilterFields[1]) && matchesKeys(recipe.productList().keySet(), query)) {
            return true;
        }
        return (allFields || libFilterFields[2]) && !recipe.equType().isBlank()
                && matchesKey(recipe.equType(), query);
    }

    private static boolean matchesKeys(Iterable<String> keys, String query) {
        for (String key : keys) {
            if (matchesKey(key, query)) {
                return true;
            }
        }
        return false;
    }

    /** 真名与注册名都参与匹配（大小写不敏感）。 */
    private static boolean matchesKey(String key, String query) {
        return ItemKeyMapper.displayName(key).toLowerCase(Locale.ROOT).contains(query)
                || ItemKeyMapper.registryName(key).toLowerCase(Locale.ROOT).contains(query);
    }

    private int libraryFilterFieldAt(double mouseX, double mouseY) {
        if (mouseY < libraryFilterY || mouseY >= libraryFilterY + 14) {
            return -1;
        }
        for (int i = 0; i < LIB_FILTER_LABELS.length; i++) {
            int x = libFilterBarX + i * (LIB_FILTER_CELL + 1);
            if (mouseX >= x && mouseX < x + LIB_FILTER_CELL) {
                return i;
            }
        }
        return -1;
    }

    private void toggleLibraryField(int field) {
        libFilterFields[field] = !libFilterFields[field];
        libraryScroll = 0;
        refreshLibraryView();
    }

    /**
     * 编码区中间的缩放按钮：左列 ÷2/÷3/÷5，右列 ×2/×3/×5，底部一整行自定义系数。
     * 效果是同时缩放原料与产物两侧的量（比例不变）。
     */
    private void drawMultiButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        int blockW = 2 * MULTI_W + MULTI_GAP;
        // 顶部小箭头，代替原来的「→」
        graphics.drawString(font, "→", multiX + blockW / 2 - 4, encodingY + 4,
                AeStyle.MUTED, false);
        for (int i = 0; i < 6; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = multiX + col * (MULTI_W + MULTI_GAP);
            int y = multiY + row * (MULTI_H + MULTI_GAP);
            String label = (col == 0 ? "÷" : "×") + (int) MULTI_FACTORS[row];
            drawMiniButton(graphics, x, y, MULTI_W, MULTI_H, label,
                    inside(mouseX, mouseY, x, y, MULTI_W, MULTI_H));
        }
        int customY = multiY + 3 * (MULTI_H + MULTI_GAP);
        drawMiniButton(graphics, multiX, customY, blockW, MULTI_H, "×n",
                inside(mouseX, mouseY, multiX, customY, blockW, MULTI_H));
    }

    private void drawMiniButton(GuiGraphics graphics, int x, int y, int w, int h,
                                String label, boolean hover) {
        graphics.fill(x, y, x + w, y + h, AeStyle.SLOT_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                hover ? 0xFFC4C4C4 : 0xFF9E9E9E);
        // 小按钮字缩到 0.75 倍，13px 宽也能完整放下「÷5」「×5」
        float scale = 0.75f;
        float textW = font.width(label) * scale;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + w / 2.0f - textW / 2.0f, y + (h - 8 * scale) / 2.0f, 0);
        pose.scale(scale, scale, 1.0f);
        graphics.drawString(font, label, 0, 0, AeStyle.TEXT, false);
        pose.popPose();
    }

    /** 命中：0..2 = ÷2/÷3/÷5，3..5 = ×2/×3/×5，6 = 自定义，-1 = 未命中。 */
    private int multiButtonAt(double mouseX, double mouseY) {
        int blockW = 2 * MULTI_W + MULTI_GAP;
        for (int i = 0; i < 6; i++) {
            int x = multiX + (i % 2) * (MULTI_W + MULTI_GAP);
            int y = multiY + (i / 2) * (MULTI_H + MULTI_GAP);
            if (inside(mouseX, mouseY, x, y, MULTI_W, MULTI_H)) {
                return i;
            }
        }
        int customY = multiY + 3 * (MULTI_H + MULTI_GAP);
        if (inside(mouseX, mouseY, multiX, customY, blockW, MULTI_H)) {
            return 6;
        }
        return -1;
    }

    /** 同时按系数缩放原料与产物（保留比例，不改物品）。 */
    private void scalePattern(double factor) {
        if (!Double.isFinite(factor) || factor <= 0) {
            return;
        }
        for (int i = 0; i < RecipePattern.MAX_INPUTS; i++) {
            if (pattern.inputKey(i) != null) {
                pattern.setInputAmount(i, pattern.inputAmount(i) * factor);
            }
        }
        for (int i = 0; i < RecipePattern.MAX_OUTPUTS; i++) {
            if (pattern.outputKey(i) != null) {
                pattern.setOutputAmount(i, pattern.outputAmount(i) * factor);
            }
        }
        status = Lang.t("已按系数 %s 缩放样板", TimeExpression.formatEditableNumber(factor));
    }

    private void drawEncoding(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Lang.t("原料 %s", pattern.inputCount()),
                inputX, encodingLabelY, AeStyle.ACCENT, false);
        graphics.drawString(font, Lang.t("产物 %s", pattern.outputCount()),
                outputX, encodingLabelY, AeStyle.ACCENT, false);
        drawMultiButtons(graphics, mouseX, mouseY);

        encodingScrollRow = Math.max(0, Math.min(encodingScrollRow, maxEncodingScroll()));
        for (int row = 0; row < visibleRows; row++) {
            int gridRow = encodingScrollRow + row;
            int y = encodingY + row * SLOT;
            for (int col = 0; col < GRID_COLS; col++) {
                int index = gridRow * GRID_COLS + col;
                int inX = inputX + col * SLOT;
                if (index < RecipePattern.MAX_INPUTS) {
                    if (pattern.inputKey(index) != null) {
                        renderSlotIcon(graphics, font, pattern.inputKey(index), inX, y,
                                amountText(pattern.inputAmount(index)));
                    }
                }
            }
            for (int col = 0; col < OUTPUT_COLS; col++) {
                int index = gridRow * OUTPUT_COLS + col;
                int outX = outputX + col * SLOT;
                if (index < RecipePattern.MAX_OUTPUTS) {
                    if (pattern.outputKey(index) != null) {
                        renderSlotIcon(graphics, font, pattern.outputKey(index), outX, y,
                                amountText(pattern.outputAmount(index)));
                    }
                }
            }
        }

        int maxScroll = maxEncodingScroll();
        if (maxScroll > 0) {
            // 放在产物与设备列之间的空隙里，避免压到设备/耗时标签
            int barX = equipmentX - 4;
            int barY = encodingY;
            int barH = visibleRows * SLOT;
            graphics.fill(barX, barY, barX + 3, barY + barH, 0xFF6E6E6E);
            int thumbH = Math.max(8, barH * visibleRows / (visibleRows + maxScroll));
            int thumbY = barY + (barH - thumbH) * encodingScrollRow / maxScroll;
            graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, AeStyle.ACCENT_LIGHT);
        }

        // 设备/耗时标签在各自槽位的“后方”（右侧），时间值画在槽位内部
        graphics.drawString(font, Lang.t("设备"), equipmentX + SLOT + 2, encodingY + 5,
                AeStyle.MUTED, false);
        if (!pattern.equipmentKey().isBlank()) {
            renderSlotIcon(graphics, font, pattern.equipmentKey(), equipmentX, encodingY, null);
        }

        int timeY = encodingY + 20;
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
                if (inventory == null) {
                    continue;
                }
                int index = row < 3 ? 9 + row * 9 + col : col;
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    // 图标延后批量绘制；数量/耐久在 z=200，先画也不会被图标盖住
                    queueItem(stack, x + 1, y + 1, 0, 1.0f);
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
        updateOptionStates();
        super.render(graphics, mouseX, mouseY, partialTick);

        if (!carriedKey.isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, carriedKey, mouseX - 8, mouseY - 8, null);
        }
        drawTooltips(graphics, mouseX, mouseY);
    }

    /** 图标按钮没有文字，开关状态用图标明暗表示（提示语里也带当前状态）。 */
    private void updateOptionStates() {
        rawButton.setOn(draft.options().autoDetectRaw());
        seedButton.setOn(draft.options().autoDetectSeed());
        bootstrapButton.setOn(draft.options().autoBootstrap());
    }

    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (drawIconButtonTooltip(graphics, mouseX, mouseY)) {
            return;
        }
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

    /** 求解按钮提示：标题 +（必要时）黄色无解警告 + 当前参数一览。 */
    private Component solveTooltip() {
        SolveOptions options = draft.options();
        MutableComponent tip = Component.empty().append(Lang.c("求解并查看结果"));
        if (!options.autoDetectRaw() && options.rawMaterials().isEmpty()) {
            tip.append("\n").append(Lang.c("未启动自动探测原料且无无限物质输入，显然无解")
                    .withStyle(ChatFormatting.YELLOW));
        }
        appendTipLine(tip, Lang.t("最小化目标: %s",
                Lang.t(options.objective() == Objective.MACHINE ? "设备" : "配方")));
        appendTip(tip, onOffLine("自动探测外部原料", options.autoDetectRaw(), true));
        appendTip(tip, onOffLine("自增殖种子检测", options.autoDetectSeed(), true));
        appendTip(tip, onOffLine("循环启动物料库存检测", options.autoBootstrap(), true));
        appendTipLine(tip, Lang.t("无限外部供应物质: %s 种", options.rawMaterials().size()));
        appendTipLine(tip,
                Lang.t("循环亏损补充物质: %s 种", options.allowedExternalCycInputs().size()));
        int targets = draft.targets().size();
        if (targets == 0) {
            appendTip(tip, Lang.c("目标: %s 种，显然无解", targets)
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            appendTipLine(tip, Lang.t("目标: %s 种", targets));
        }
        int recipes = draft.recipeCount();
        if (recipes == 0) {
            appendTip(tip, Lang.c("配方库: %s 条，显然无解", recipes)
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            appendTipLine(tip, Lang.t("配方库: %s 条", recipes));
        }
        return tip;
    }

    private static void appendTipLine(MutableComponent tip, String text) {
        appendTip(tip, Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private static void appendTip(MutableComponent tip, Component line) {
        tip.append("\n").append(line);
    }

    /** 图标按钮提示：第一行标题，第二行灰色说明（自动换行）。 */
    private static Component twoLineTooltip(String titleKey, String descKey) {
        return Component.empty()
                .append(Lang.c(titleKey))
                .append("\n")
                .append(Lang.c(descKey).withStyle(ChatFormatting.GRAY));
    }

    /**
     * 「标签: 开/关」一行：开=绿色、关=红色。
     *
     * @param grayLabel true 时标签也用灰色（用于求解按钮的参数总览）
     */
    private static Component onOffLine(String labelKey, boolean value, boolean grayLabel) {
        MutableComponent line = Component.empty();
        if (grayLabel) {
            line.append(Component.literal(Lang.t(labelKey)).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        } else {
            line.append(Lang.c(labelKey)).append(Component.literal(": "));
        }
        return line.append(Lang.c(value ? "开" : "关")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /** 图标按钮的说明：悬停时在鼠标处显示。 */
    private boolean drawIconButtonTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        IconButton[] buttons = {objectiveButton, rawButton, seedButton, bootstrapButton,
                solveButton, rawListButton, externalInputButton};
        for (IconButton button : buttons) {
            if (button != null && button.isHovered()) {
                graphics.renderComponentTooltip(font, List.of(button.tooltipText()),
                        mouseX, mouseY);
                return true;
            }
        }
        return false;
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
        return (libraryView.size() + LIB_COLS - 1) / LIB_COLS;
    }

    private int libraryIndexAt(double mouseX, double mouseY) {
        for (int row = 0; row < libRows; row++) {
            for (int col = 0; col < LIB_COLS; col++) {
                int x = contentX + col * SLOT;
                int y = libraryY + row * SLOT;
                if (inside(mouseX, mouseY, x, y, SLOT, SLOT)) {
                    int index = (libraryScroll + row) * LIB_COLS + col;
                    return index < libraryView.size() ? libraryView.get(index) : -1;
                }
            }
        }
        return -1;
    }

    private int targetSlotAt(double mouseX, double mouseY) {
        for (int row = 0; row < targetRows; row++) {
            for (int col = 0; col < TARGET_COLS; col++) {
                int x = contentX + col * SLOT;
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
        int multi = multiButtonAt(mouseX, mouseY);
        if (multi >= 0 && button == 0) {
            if (multi < 6) {
                // 行 = 系数（2/3/5），列 0 = 除、列 1 = 乘
                double factor = MULTI_FACTORS[multi / 2];
                scalePattern(multi % 2 == 0 ? 1.0 / factor : factor);
            } else {
                minecraft.setScreen(new FactorScreen(this, "系数", this::scalePattern));
            }
            return true;
        }

        int filterField = libraryFilterFieldAt(mouseX, mouseY);
        if (filterField >= 0 && button == 0) {
            toggleLibraryField(filterField);
            return true;
        }

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
                    String targetKey = ScenarioKeyNormalizer.preferredTargetKey(
                            carriedKey, draft.recipes(), draft.targets());
                    draft.putTarget(targetKey, 1.0);
                    onDraftChanged();
                    status = Lang.t("已添加目标: %s 1/t", targetKey);
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
        minecraft.setScreen(            new AmountScreen(this, "目标产物", key, initialPerTick, true, true,
                (newKey, perTick) -> {
                String targetKey = ScenarioKeyNormalizer.preferredTargetKey(
                        newKey, draft.recipes(), draft.targets());
                if (!targetKey.equals(key)) {
                    draft.removeTarget(key);
                }
                if (draft.targets().size() >= MAX_TARGETS
                        && !draft.targets().containsKey(targetKey)) {
                    status = Lang.t("目标最多 %s 个", MAX_TARGETS);
                    return;
                }
                draft.putTarget(targetKey, perTick);
                onDraftChanged();
                status = Lang.t("目标: %s × %s/t", targetKey,
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
        if (button == 1) {
            // 右键：该物质在 JEI 里只有一种配方就自动撰写并写入配方集，
            // 有多种（如红石粉：合成 + 各种机器处理）只打开 JEI 配方页
            if (key != null) {
                autoWriteFromJei(key);
            }
            return;
        }
        if (button == 2) {
            openPatternAmount(input, index, key == null ? "" : key, amount);
        }
    }

    /** 右键物质：单一配方自动新建并写入，多配方只打开 JEI 配方页。 */
    private void autoWriteFromJei(String key) {
        AutoRecipeHooks.Hook hook = AutoRecipeHooks.get();
        if (hook == null) {
            status = Lang.t("JEI 不可用");
            return;
        }
        int count;
        RecipePattern built = null;
        try {
            count = hook.producerCount(key);
            if (count == 1) {
                built = hook.singlePattern(key);
            }
        } catch (RuntimeException | LinkageError e) {
            status = Lang.t("JEI 不可用");
            return;
        }
        if (count <= 0) {
            hook.showRecipes(key);
            status = Lang.t("没找到该物质的配方");
            return;
        }
        if (built == null) {
            hook.showRecipes(key);
            status = Lang.t("该物质有 %s 种配方，已在 JEI 中打开", count);
            return;
        }
        newPattern();
        pattern.copyFrom(built);
        if (commitPattern(true)) {
            status = Lang.t("已自动写入 %s 的配方", ItemKeyMapper.displayName(key));
            // 写入成功给一次按钮点击音，和点「写入」的反馈一致
            playClickSound();
        }
    }

    /** 原版按钮点击音（与「写入」按钮按下时一致）。 */
    private void playClickSound() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
            // 目标与配方可能来自不同语言的 JEI 页面；求解前统一非物品身份。
            Scenario scenario = ScenarioKeyNormalizer.normalize(draft.toScenario());
            SolveResult result = Balancer.solve(scenario);
            STORE.save("autosave", scenario);
            // 报告里把 stack|base64 替换成“物品名 [效果/时长]”，不改变磁盘数据
            String report = Lang.report(ItemKeyMapper.prettyText(
                    TextReporter.formatPerTick(result, scenario.recipes())));
            minecraft.setScreen(new SolveScreen(this, report, result,
                    scenario.recipes(), perTickTargets(scenario),
                    draft.options().autoBootstrap(), result.success()));
        } catch (RuntimeException | LinkageError e) {
            RecipeBalancerMod.LOGGER.error("[RecipeBalancer] 求解失败", e);
            status = Lang.t("求解失败: %s", e);
        }
    }

    /** 无限外部供应物质（raw_materials）：只维护物品种类。 */
    private void openRawMaterials() {
        minecraft.setScreen(new KeySetScreen(this, "无限外部供应物质",
                draft.options().rawMaterials(), keys -> {
                    draft.setRawMaterials(keys);
                    persistDraft();
                }));
    }

    /** 循环亏损补充物质（allowed_external_cyc_inputs）：只维护物品种类。 */
    private void openExternalInputs() {
        minecraft.setScreen(new KeySetScreen(this, "循环亏损补充物质",
                draft.options().allowedExternalCycInputs(), keys -> {
                    draft.setAllowedExternalCycInputs(keys);
                    persistDraft();
                }));
    }

    /** 存取：打开工作区列表（筛选 / 载入 / 另存 / 删除）。 */
    private void openWorkspaceList() {
        minecraft.setScreen(new ScenarioListScreen(this, STORE, draft, scenarioName));
    }

    /** 保存：把当前草稿写进当前工作区文件。 */
    private void storeWorkspace() {
        try {
            onWorkspaceNamed(WorkspaceSession.nameOf(nameBox.getValue()));
            STORE.save(scenarioName, ScenarioKeyNormalizer.normalize(draft.toScenario()));
            persistDraft();
            status = Lang.t("已保存工作区: %s", scenarioName);
        } catch (RuntimeException e) {
            status = Lang.t("保存失败: %s", e.getMessage());
        }
    }

    /** 新建：清空全部数据，工作区名回到默认值。 */
    private void newWorkspace() {
        draft.clear();
        pattern.clear();
        editingIndex = -1;
        carriedKey = "";
        libraryScroll = 0;
        targetScrollRow = 0;
        encodingScrollRow = 0;
        // 新建工作区默认打开「循环启动物料库存检测」
        if (!draft.options().autoBootstrap()) {
            draft.toggleAutoBootstrap();
        }
        // 写一份空草稿覆盖 autosave，重开界面不会再读回旧数据
        boolean saved = persistDraft();
        onWorkspaceNamed(uniqueWorkspaceName(WorkspaceSession.DEFAULT_NAME));
        if (saved) {
            status = Lang.t("已新建工作区: %s", scenarioName);
        }
    }

    /** 新建工作区名：默认名已被占用时依次尝试 -1、-2…，避免覆盖已有配方集。 */
    private String uniqueWorkspaceName(String base) {
        try {
            if (!STORE.exists(base)) {
                return base;
            }
            for (int i = 1; i < 1000; i++) {
                String candidate = base + "-" + i;
                if (!STORE.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (RuntimeException e) {
            // 目录读不了就退回默认名，保存时仍会给出失败提示
        }
        return base;
    }

    @Override
    public void onClose() {
        // 关界面前把编码区未写入的内容落库，避免 K 关闭丢配方
        if (pattern.hasContent()) {
            commitPattern(false);
        }
        persistDraft();
        if (nameBox != null) {
            onWorkspaceNamed(WorkspaceSession.nameOf(nameBox.getValue()));
        }
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
        onWorkspaceNamed(name);
        pattern.clear();
        editingIndex = -1;
        carriedKey = "";
        libraryScroll = 0;
        targetScrollRow = 0;
        encodingScrollRow = 0;
        status = Lang.t("已载入工作区: %s", name);
        persistDraft();
    }

    /** 工作区改名（载入/另存/新建）：同步输入框并记住，重开界面时原样恢复。 */
    void onWorkspaceNamed(String name) {
        scenarioName = WorkspaceSession.nameOf(name);
        if (nameBox != null) {
            nameBox.setValue(scenarioName);
        }
        WorkspaceSession.saveName(scenarioName);
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
                        contentX + col * SLOT, targetY + row * SLOT));
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
                        contentX + col * SLOT, targetY + row * SLOT);
            }
        }
        int library = libraryIndexAt(mouseX, mouseY);
        if (library >= 0) {
            String product = draft.recipes().get(library).productList().keySet()
                    .stream().findFirst().orElse("");
            if (!product.isEmpty()) {
                int row = library / LIB_COLS - libraryScroll;
                int col = library % LIB_COLS;
                return hovered(product, contentX + col * SLOT, libraryY + row * SLOT);
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
    // 左右两列图标按钮贴在面板外侧，算进 GUI 范围，避免被 JEI 物品/书签面板盖住
    @Override
    public int jeiGuiLeft() {
        return panelX - SIDE_PAD;
    }

    @Override
    public int jeiGuiTop() {
        return panelY;
    }

    @Override
    public int jeiGuiWidth() {
        return panelW + 2 * SIDE_PAD;
    }

    @Override
    public int jeiGuiHeight() {
        return panelH;
    }
}
