package com.jessetech.recipebalancer.mc.screen;

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
    private double ticks;
    private TimeUnit unit;
    private EditBox expressionBox;
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

        expressionBox = new EditBox(font, panelX + 8, panelY + 28, 150, 18,
                Lang.c("耗时表达式"));
        expressionBox.setMaxLength(128);
        expressionBox.setValue(TimeExpression.formatEditableNumber(unit.fromTicks(ticks)));
        addRenderableWidget(expressionBox);

        unitButton = addRenderableWidget(Button.builder(Component.literal(unit.label()), b -> cycleUnit())
                .bounds(panelX + 164, panelY + 27, 64, 20).build());

        // 取消左、确定右
        addRenderableWidget(Button.builder(Lang.c("取消"), b -> cancel())
                .bounds(panelX + 8, panelY + PANEL_H - 28, 104, 20).build());
        addRenderableWidget(Button.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 112, panelY + PANEL_H - 28, 104, 20).build());

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
        unitButton.setMessage(Component.literal(unit.label()));
    }

    private void confirm() {
        try {
            double result = TimeExpression.toTicks(expressionBox.getValue(), unit);
            UnitPreferences.setTimeUnit(unit);
            callback.accept(result);
            minecraft.setScreen(parent);
        } catch (RuntimeException e) {
            // 内层消息也走语言文件（core 抛出的固定文案同时也是语言键）
            status = Lang.t("表达式无效: %s", Lang.t(String.valueOf(e.getMessage())));
        }
    }

    private void cancel() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
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
