package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.core.time.TimeUnit;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.Buttons;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import com.jessetech.recipebalancer.mc.ui.RbTextField;

import com.jessetech.recipebalancer.mc.ui.Button;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiScreen;

import java.util.function.DoubleConsumer;

/** 配方耗时编辑：表达式 + 单位（t/s/m/h），确定后统一转 tick。 */
public final class TimeScreen extends RbScreen implements JeiPanelScreen {

    private final GuiScreen parent;
    private final DoubleConsumer callback;
    /** JEI 原配方的参考耗时；为 null 时界面与原来完全一致。 */
    private final RecipePattern.TimeHint timeHint;
    private final int panelHeight;
    private double ticks;
    private TimeUnit unit;
    private GuiTextField expressionBox;
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
    private final GuiTextField[] factorBoxes = new GuiTextField[FACTOR_COUNT];
    private final int[] factorX = new int[FACTOR_COUNT];
    private final int[] factorY = new int[FACTOR_COUNT];

    public TimeScreen(GuiScreen parent, double ticks, DoubleConsumer callback) {
        this(parent, ticks, null, callback);
    }

    /**
     * @param timeHint JEI 导入时记录的参考基准耗时；仅用于显示，不改变输入框初值
     */
    public TimeScreen(GuiScreen parent, double ticks, RecipePattern.TimeHint timeHint,
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
                FactorPreferences.put(i, factorBoxes[i].getText());
            }
        }

        expressionBox = new RbTextField(font, panelX + 8, panelY + 28, 150, 18,
                Lang.c("耗时表达式"));
        expressionBox.setMaxStringLength(128);
        expressionBox.setText(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        addButton(expressionBox);

        unitButton = addButton(Buttons.builder(Chat.literal(Lang.t(unit.zhLabel())), b -> cycleUnit())
                .bounds(panelX + 164, panelY + 27, 64, 20).build());

        factorTop = panelY + (timeHint == null ? 72 : 84);
        for (int i = 0; i < FACTOR_COUNT; i++) {
            int column = i % 2;
            int row = i / 2;
            int x = panelX + 8 + column * (FACTOR_COL_W + FACTOR_COL_GAP);
            int y = factorTop + row * FACTOR_ROW_H;
            RbTextField box = new RbTextField(font, x, y, 58, 18, Lang.c("系数"));
            box.setMaxStringLength(64);
            box.setText(FactorPreferences.get(i));
            factorX[i] = x;
            factorY[i] = y;
            factorBoxes[i] = addButton(box);
            int index = i;
            addButton(Buttons.builder(Chat.literal("×"), b -> applyFactor(index, true))
                    .bounds(x + 62, y - 1, 20, 20).build());
            addButton(Buttons.builder(Chat.literal("÷"), b -> applyFactor(index, false))
                    .bounds(x + 84, y - 1, 20, 20).build());
        }

        // 取消左、确定右
        addButton(Buttons.builder(Lang.c("取消"), b -> cancel())
                .bounds(panelX + 8, panelY + panelHeight - 28, 104, 20).build());
        addButton(Buttons.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 112, panelY + panelHeight - 28, 104, 20).build());

        setListener(expressionBox);
    }

    private void cycleUnit() {
        double preserved = ticks;
        try {
            preserved = TimeExpression.toTicks(expressionBox.getText(), unit);
        } catch (RuntimeException ignored) {
            // 当前表达式不合法时只切单位，不清空输入
        }
        unit = unit.next();
        UnitPreferences.setTimeUnit(unit);
        if (preserved > 0) {
            ticks = preserved;
            expressionBox.setText(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        }
        unitButton.setMessage(Chat.literal(Lang.t(unit.zhLabel())));
    }

    private void confirm() {
        double result;
        try {
            result = TimeExpression.toTicks(expressionBox.getText(), unit);
        } catch (RuntimeException e) {
            // 不另设提示：主预览行本身就是 "表达式无效: …"
            return;
        }
        UnitPreferences.setTimeUnit(unit);
        callback.accept(result);
        minecraft.displayGuiScreen(parent);
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
        String raw = factorBoxes[index] == null ? "" : factorBoxes[index].getText().trim();
        double factor;
        double current;
        try {
            factor = TimeExpression.evaluate(raw);
            current = TimeExpression.toTicks(expressionBox.getText(), unit);
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
        expressionBox.setText(TimeExpression.formatEditableNumber(unit.fromTicks(result)));
        FactorPreferences.put(index, raw);
        FactorPreferences.save();
    }

    private void cancel() {
        minecraft.displayGuiScreen(parent);
    }

    @Override
    public void drawBackground(int tint) {
        Draw graphics = new Draw(this);
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, panelHeight);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t("配方耗时（支持 + - * / ^ 和括号）"),
                panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Draw graphics = new Draw(this);
        super.drawScreen(mouseX, mouseY, partialTicks);

        String preview;
        try {
            double result = TimeExpression.toTicks(expressionBox.getText(), unit);
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
            GuiTextField box = factorBoxes[i];
            if (box == null) {
                continue;
            }
            String value;
            try {
                double parsed = TimeExpression.evaluate(box.getText().trim());
                value = Double.isFinite(parsed) && parsed > 0
                        ? "= " + TimeExpression.formatNumber(parsed)
                        : "= ?";
            } catch (RuntimeException e) {
                value = "= ?";
            }
            graphics.drawString(font, value, factorX[i], factorY[i] + 20, AeStyle.MUTED, false);
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

    /**
     * 1.12.2 的 {@code Minecraft#displayGuiScreen(newScreen)} 会回调<b>旧屏</b>的
     * {@code onGuiClosed()}，所以这里只能做收尾、绝不能再切屏，否则会无限递归。
     * 四个槽位的原始表达式也在这里写回实例配置（1.12.2 没有 {@code removed()}）。
     */
    @Override
    public void onGuiClosed() {
        for (int i = 0; i < FACTOR_COUNT; i++) {
            if (factorBoxes[i] != null) {
                FactorPreferences.put(i, factorBoxes[i].getText());
            }
        }
        FactorPreferences.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
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
        return panelHeight;
    }
}
