package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.core.time.TimeUnit;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.Buttons;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

import java.util.function.DoubleConsumer;

/** 配方耗时编辑：表达式 + 单位（t/秒/分/时），确定后统一转 tick。 */
public final class TimeScreen extends RbScreen implements JeiPanelScreen {

    private final Screen parent;
    private final DoubleConsumer callback;
    private double ticks;
    private TimeUnit unit;
    private TextFieldWidget expressionBox;
    private Button unitButton;
    private String status = "";

    private int panelX = 40;
    private int panelY = 40;
    private static final int PANEL_W = 236;
    private static final int PANEL_H = 132;

    public TimeScreen(Screen parent, double ticks, DoubleConsumer callback) {
        super(Lang.c("配方耗时"));
        this.parent = parent;
        this.ticks = ticks > 0 ? ticks : 1.0;
        this.callback = callback;
        // 优先使用上次选择；没有记录才自动选单位
        this.unit = UnitPreferences.timeUnit(TimeUnit.bestFor(this.ticks));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        expressionBox = new TextFieldWidget(font, panelX + 8, panelY + 28, 150, 18,
                Lang.c("耗时表达式"));
        expressionBox.setMaxStringLength(128);
        expressionBox.setText(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        addButton(expressionBox);

        unitButton = addButton(Buttons.builder(Chat.literal(unit.label()), b -> cycleUnit())
                .bounds(panelX + 164, panelY + 27, 64, 20).build());

        // 取消左、确定右
        addButton(Buttons.builder(Lang.c("取消"), b -> cancel())
                .bounds(panelX + 8, panelY + PANEL_H - 28, 104, 20).build());
        addButton(Buttons.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 112, panelY + PANEL_H - 28, 104, 20).build());

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
        unitButton.setMessage(Chat.literal(unit.label()));
    }

    private void confirm() {
        try {
            double result = TimeExpression.toTicks(expressionBox.getText(), unit);
            UnitPreferences.setTimeUnit(unit);
            callback.accept(result);
            minecraft.displayGuiScreen(parent);
        } catch (RuntimeException e) {
            // 内层消息也走语言文件（core 抛出的固定文案同时也是语言键）
            status = Lang.t("表达式无效: %s", Lang.t(String.valueOf(e.getMessage())));
        }
    }

    private void cancel() {
        minecraft.displayGuiScreen(parent);
    }

    @Override
    public void renderBackground(MatrixStack pose) {
        Draw graphics = new Draw(pose, this);
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t("配方耗时（支持 + - * / ^ 和括号）"),
                panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float partialTick) {
        Draw graphics = new Draw(pose, this);
        super.render(pose, mouseX, mouseY, partialTick);

        String preview;
        try {
            double result = TimeExpression.toTicks(expressionBox.getText(), unit);
            preview = "= " + TimeExpression.formatExactTicks(result)
                    + "  (" + TimeExpression.formatTicks(result) + ")";
        } catch (RuntimeException e) {
            preview = Lang.t("表达式无效: %s", Lang.t(String.valueOf(e.getMessage())));
        }
        graphics.drawString(font, preview, panelX + 8, panelY + 52, AeStyle.MUTED, false);
        graphics.drawString(font, Lang.t("各配方可独立选单位；内部统一按 t 保存"),
                panelX + 8, panelY + 68, AeStyle.MUTED, false);

        if (!status.isEmpty()) {
            graphics.drawString(font, status, panelX + 8, panelY + 84, AeStyle.WARN, false);
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
     * 1.16.5 的 {@code Minecraft#displayGuiScreen(newScreen)} 会回调<b>旧屏</b>的
     * {@code onClose()}，所以这里只能做收尾、绝不能再切屏，否则会无限递归。
     */
    @Override
    public void onClose() {
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
