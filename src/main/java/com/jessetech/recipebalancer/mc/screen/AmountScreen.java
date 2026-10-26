package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.RateUnit;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通用“物品 + 用量”编辑屏。
 *
 * <p>配方原料/产物/设备用无量纲数量；目标产物用速率（/t、/s、/m、/h），
 * 内部回调统一返回**每 tick 数量**（与 ScenarioDraft 一致）。
 */
public final class AmountScreen extends Screen implements JeiPanelScreen {

    public interface Callback {
        void accept(String itemKey, double amount);
    }

    private final Screen parent;
    private final String title;
    private final boolean amountEditable;
    private final boolean rateMode;
    private final Callback callback;
    private final String itemKey;
    private final boolean nonItem;
    private String status = "";
    private RateUnit rateUnit;
    private EditBox keyBox;
    private EditBox amountBox;
    private Button unitButton;

    public AmountScreen(Screen parent, String title, String itemKey, double amount,
                        boolean amountEditable, Callback callback) {
        this(parent, title, itemKey, amount, amountEditable, false, callback);
    }

    /** rateMode=true：amount 为每 tick 数量，界面显示速率单位。 */
    public AmountScreen(Screen parent, String title, String itemKey, double amount,
                        boolean amountEditable, boolean rateMode, Callback callback) {
        super(Component.literal(title));
        this.parent = parent;
        this.title = title;
        this.itemKey = ItemKeyMapper.normalize(itemKey);
        this.nonItem = IngredientKey.isIngredientKey(this.itemKey);
        this.amountEditable = amountEditable;
        this.rateMode = rateMode;
        this.callback = callback;
        // 记住上次速率单位；没有记录时目标默认 /t
        this.rateUnit = UnitPreferences.rateUnit(RateUnit.TICK);
        this.amount = rateMode ? rateUnit.fromPerTick(amount) : amount;
    }

    private double amount;

    private int panelX = 40;
    private int panelY = 40;
    private int panelW = 236;
    private int panelH = 208;

    @Override
    protected void init() {
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        // 图标槽在注册名输入框左侧；真名/注册名放在它们下方
        keyBox = new EditBox(font, panelX + 30, panelY + 32, panelW - 38, 18,
                Component.literal("物品注册名"));
        // 组件物品（药水等）的 key 是 base64，可能远超 128
        keyBox.setMaxLength(4096);
        keyBox.setValue(itemKey);
        addRenderableWidget(keyBox);

        if (amountEditable) {
            int amountWidth = rateMode ? 104 : 112;
            amountBox = new EditBox(font, panelX + 8, panelY + 90, amountWidth, 18,
                    Component.literal("用量"));
            amountBox.setMaxLength(64);
            amountBox.setValue(TimeExpression.formatEditableNumber(amount));
            addRenderableWidget(amountBox);
            if (rateMode) {
                unitButton = addRenderableWidget(Button.builder(
                                Component.literal(rateLabel()), b -> cycleRateUnit())
                        .bounds(panelX + 116, panelY + 89, 60, 20).build());
            }
        }

        int buttonY = panelY + 114;
        int buttonW = 48;
        int gap = 3;
        addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjust(1))
                .bounds(panelX + 8, buttonY, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> adjust(10))
                .bounds(panelX + 8 + (buttonW + gap), buttonY, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+100"), b -> adjust(100))
                .bounds(panelX + 8 + 2 * (buttonW + gap), buttonY, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+1000"), b -> adjust(1000))
                .bounds(panelX + 8 + 3 * (buttonW + gap), buttonY, buttonW, 20).build());

        if (amountEditable) {
            addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjust(-1))
                    .bounds(panelX + 8, buttonY + 24, buttonW, 20).build());
            addRenderableWidget(Button.builder(Component.literal("-10"), b -> adjust(-10))
                    .bounds(panelX + 8 + (buttonW + gap), buttonY + 24, buttonW, 20).build());
            addRenderableWidget(Button.builder(Component.literal("-100"), b -> adjust(-100))
                    .bounds(panelX + 8 + 2 * (buttonW + gap), buttonY + 24, buttonW, 20).build());
            addRenderableWidget(Button.builder(Component.literal("-1000"), b -> adjust(-1000))
                    .bounds(panelX + 8 + 3 * (buttonW + gap), buttonY + 24, buttonW, 20).build());
        }

        // 取消左、确定右
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> cancel())
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("确定"), b -> confirm())
                .bounds(panelX + panelW - 108, panelY + panelH - 26, 100, 20).build());

        if (amountEditable && amountBox != null) {
            setInitialFocus(amountBox);
        } else {
            setInitialFocus(keyBox);
        }
        JeiScreenSupport.notifyGuiInit(this);
    }

    private void cycleRateUnit() {
        if (!rateMode) {
            return;
        }
        double perTick = amount;
        try {
            perTick = rateUnit.toPerTick(TimeExpression.evaluate(amountBox.getValue().trim()));
        } catch (RuntimeException ignored) {
            // 当前输入不合法时只切换单位显示
        }
        rateUnit = rateUnit.next();
        UnitPreferences.setRateUnit(rateUnit);
        amount = rateUnit.fromPerTick(perTick);
        amountBox.setValue(TimeExpression.formatEditableNumber(amount));
        unitButton.setMessage(Component.literal(rateLabel()));
    }

    private String rateLabel() {
        return (nonItem ? "B" : "") + rateUnit.label();
    }

    private void adjust(double delta) {
        if (!amountEditable || amountBox == null) {
            return;
        }
        double current;
        try {
            // 用量框也支持 + - * / ^ 表达式；± 按钮基于求值结果继续调整
            current = TimeExpression.evaluate(amountBox.getValue().trim());
        } catch (RuntimeException e) {
            current = amount;
        }
        current = Math.max(0.0, current + delta);
        amountBox.setValue(TimeExpression.formatEditableNumber(current));
    }

    private void confirm() {
        String key = ItemKeyMapper.normalize(keyBox.getValue());
        if (key.isEmpty()) {
            status = "物品注册名不能为空";
            return;
        }
        double value = 1.0;
        if (amountEditable) {
            try {
                value = TimeExpression.evaluate(amountBox.getValue().trim());
            } catch (RuntimeException e) {
                status = "表达式无效: " + e.getMessage();
                return;
            }
            if (!(value > 0) || !Double.isFinite(value)) {
                status = "用量必须为正数";
                return;
            }
            if (rateMode) {
                UnitPreferences.setRateUnit(rateUnit);
                value = rateUnit.toPerTick(value);
            }
        }
        callback.accept(key, value);
        minecraft.setScreen(parent);
    }

    private void cancel() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, panelW, panelH);
        AeStyle.header(graphics, panelX, panelY, panelW, 20);
        graphics.drawString(font, title, panelX + 8, panelY + 6, AeStyle.TEXT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, "物品 / 注册名", panelX + 8, panelY + 22, AeStyle.MUTED, false);
        AeStyle.slot(graphics, panelX + 8, panelY + 32);

        String previewKey = ItemKeyMapper.normalize(keyBox.getValue());
        if (!previewKey.isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, previewKey, panelX + 8, panelY + 32, null);
            String name = ItemKeyMapper.displayName(previewKey);
            graphics.drawString(font, font.plainSubstrByWidth(name, panelW - 16),
                    panelX + 8, panelY + 54, AeStyle.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(previewKey, panelW - 16),
                    panelX + 8, panelY + 65, AeStyle.MUTED, false);
        }

        if (amountEditable) {
            String amountLabel = rateMode ? "速率" : "用量";
            if (nonItem) {
                amountLabel += "（B）";
            }
            graphics.drawString(font, amountLabel, panelX + 8, panelY + 79,
                    AeStyle.MUTED, false);
        }

        if (!status.isEmpty()) {
            graphics.drawString(font, status, panelX + 8, panelY + panelH - 44,
                    AeStyle.WARN, false);
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
        return panelW;
    }

    @Override
    public int jeiGuiHeight() {
        return panelH;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
