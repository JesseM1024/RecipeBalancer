package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.core.time.TimeUnit;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/** 配方耗时编辑：表达式 + 单位（t/秒/分/时），确定后统一转 tick。 */
public final class TimeScreen extends Screen implements JeiPanelScreen {

    private final Screen parent;
    private final DoubleConsumer callback;
    /** JEI 原配方的参考耗时；为 null 时界面与原来完全一致。 */
    private final RecipePattern.TimeHint timeHint;
    private final int panelHeight;
    private double ticks;
    private TimeUnit unit;
    private EditBox expressionBox;
    private Button unitButton;

    private int panelX = 40;
    private int panelY = 40;
    private int factorTop = 72;
    private static final int PANEL_W = 236;
    private static final int PANEL_H = 164;
    /** 多出一行参考基准耗时后的高度。 */
    private static final int PANEL_H_WITH_HINT = 176;
    /** 快捷系数：两列两行，整个游戏实例共用。 */
    private static final int FACTOR_COUNT = FactorPreferences.COUNT;
    private static final int FACTOR_COL_W = 108;
    private static final int FACTOR_COL_GAP = 2;
    /** 输入框 18 + 浮点值 9 + 间距 4。 */
    private static final int FACTOR_ROW_H = 31;
    private final EditBox[] factorBoxes = new EditBox[FACTOR_COUNT];

    public TimeScreen(Screen parent, double ticks, DoubleConsumer callback) {
        this(parent, ticks, null, callback);
    }

    /**
     * @param timeHint JEI 导入时记录的参考基准耗时；仅用于显示，不改变输入框初值
     */
    public TimeScreen(Screen parent, double ticks, RecipePattern.TimeHint timeHint,
                      DoubleConsumer callback) {
        super(Lang.c("配方耗时"));
        this.parent = parent;
        this.ticks = ticks > 0 ? ticks : 1.0;
        this.callback = callback;
        this.timeHint = timeHint;
        this.panelHeight = timeHint == null ? PANEL_H : PANEL_H_WITH_HINT;
        // 优先使用上次选择；没有记录才自动选单位
        this.unit = UnitPreferences.timeUnit(TimeUnit.bestFor(this.ticks));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - panelHeight) / 2;

        // 窗口 resize 会重建控件，先把当前输入收回偏好里，避免回到上次保存值
        for (int i = 0; i < FACTOR_COUNT; i++) {
            if (factorBoxes[i] != null) {
                FactorPreferences.put(i, factorBoxes[i].getValue());
            }
        }

        expressionBox = new EditBox(font, panelX + 8, panelY + 28, 150, 18,
                Lang.c("耗时表达式"));
        expressionBox.setMaxLength(128);
        expressionBox.setValue(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        addRenderableWidget(expressionBox);

        unitButton = addRenderableWidget(Button.builder(Component.literal(Lang.t(unit.zhLabel())), b -> cycleUnit())
                .bounds(panelX + 164, panelY + 27, 64, 20).build());

        factorTop = panelY + (timeHint == null ? 72 : 84);
        for (int i = 0; i < FACTOR_COUNT; i++) {
            int column = i % 2;
            int row = i / 2;
            int x = panelX + 8 + column * (FACTOR_COL_W + FACTOR_COL_GAP);
            int y = factorTop + row * FACTOR_ROW_H;
            EditBox box = new EditBox(font, x, y, 58, 18, Lang.c("系数"));
            box.setMaxLength(64);
            box.setValue(FactorPreferences.get(i));
            factorBoxes[i] = addRenderableWidget(box);
            int index = i;
            addRenderableWidget(Button.builder(Component.literal("×"), b -> applyFactor(index, true))
                    .bounds(x + 62, y - 1, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("÷"), b -> applyFactor(index, false))
                    .bounds(x + 84, y - 1, 20, 20).build());
        }

        // 取消左、确定右
        addRenderableWidget(Button.builder(Lang.c("取消"), b -> cancel())
                .bounds(panelX + 8, panelY + panelHeight - 28, 104, 20).build());
        addRenderableWidget(Button.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 112, panelY + panelHeight - 28, 104, 20).build());

        setInitialFocus(expressionBox);
        JeiScreenSupport.notifyGuiInit(this);
    }

    private void cycleUnit() {
        double preserved = ticks;
        try {
            preserved = TimeExpression.toTicks(expressionBox.getValue(), unit);
        } catch (RuntimeException ignored) {
            // 当前表达式不合法时只切单位，不清空输入
        }
        unit = unit.next();
        UnitPreferences.setTimeUnit(unit);
        if (preserved > 0) {
            ticks = preserved;
            expressionBox.setValue(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        }
        unitButton.setMessage(Component.literal(Lang.t(unit.zhLabel())));
    }

    private void confirm() {
        double result;
        try {
            result = TimeExpression.toTicks(expressionBox.getValue(), unit);
        } catch (RuntimeException e) {
            // 不另设提示：主预览行本身就是 "表达式无效: …"
            return;
        }
        UnitPreferences.setTimeUnit(unit);
        callback.accept(result);
        minecraft.setScreen(parent);
    }

    /**
     * 应用快捷系数：左键乘、右键除。
     *
     * <p>非法表达式或结果不可用时静默跳过：系数框下方已经实时显示 "= ?"，
     * 不再额外弹提示。
     *
     * @param multiply true 时把当前耗时乘以系数，false 时除以系数
     */
    private void applyFactor(int index, boolean multiply) {
        String raw = factorBoxes[index] == null ? "" : factorBoxes[index].getValue().trim();
        double factor;
        double current;
        try {
            factor = TimeExpression.evaluate(raw);
            current = TimeExpression.toTicks(expressionBox.getValue(), unit);
        } catch (RuntimeException e) {
            return;
        }
        if (!Double.isFinite(factor) || factor <= 0) {
            return;
        }
        double result = multiply ? current * factor : current / factor;
        if (!Double.isFinite(result) || result <= 0) {
            return;
        }
        ticks = result;
        expressionBox.setValue(TimeExpression.formatEditableNumber(unit.fromTicks(result)));
        FactorPreferences.put(index, raw);
        FactorPreferences.save();
    }

    private void cancel() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, panelHeight);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t("配方耗时（支持 + - * / ^ 和括号）"),
                panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        String preview;
        try {
            double result = TimeExpression.toTicks(expressionBox.getValue(), unit);
            preview = "= " + TimeExpression.formatExactTicks(result)
                    + "  (" + TimeExpression.formatTicks(result) + ")";
        } catch (RuntimeException e) {
            preview = Lang.t("表达式无效: %s", Lang.t(String.valueOf(e.getMessage())));
        }
        graphics.drawString(font, preview, panelX + 8, panelY + 52, AeStyle.MUTED, false);

        if (timeHint != null) {
            graphics.drawString(font, Lang.t("参考基准耗时：%s",
                            TimeExpression.formatTicks(timeHint.ticks())),
                    panelX + 8, panelY + 68, AeStyle.MUTED, false);
        }

        // 系数框下方实时显示解析后的浮点值，非法表达式显示 "= ?"
        for (int i = 0; i < FACTOR_COUNT; i++) {
            EditBox box = factorBoxes[i];
            if (box == null) {
                continue;
            }
            String value;
            try {
                double parsed = TimeExpression.evaluate(box.getValue().trim());
                value = Double.isFinite(parsed) && parsed > 0
                        ? "= " + TimeExpression.formatNumber(parsed)
                        : "= ?";
            } catch (RuntimeException e) {
                value = "= ?";
            }
            graphics.drawString(font, value, box.getX(), box.getY() + 20, AeStyle.MUTED, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            confirm();
            return true;
        }
        if (keyCode == 256) {
            cancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 关界面（含确定/取消）时把四个槽位的原始表达式写回实例配置。 */
    @Override
    public void removed() {
        super.removed();
        for (int i = 0; i < FACTOR_COUNT; i++) {
            if (factorBoxes[i] != null) {
                FactorPreferences.put(i, factorBoxes[i].getValue());
            }
        }
        FactorPreferences.save();
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
        return panelHeight;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
