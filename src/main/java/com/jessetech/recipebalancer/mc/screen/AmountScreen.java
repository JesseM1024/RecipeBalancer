package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通用“物品 + 用量”编辑屏（AE Select Amount 的简化版）。
 *
 * <p>用于：配方原料/产物用量、目标产物、设备标记。
 * 物品栏可以输入任意 registry id（整合包没有也能标记，界面用彩色占位格）。
 */
public final class AmountScreen extends Screen implements JeiPanelScreen {

    public interface Callback {
        void accept(String itemKey, double amount);
    }

    private final Screen parent;
    private final String title;
    private final boolean amountEditable;
    private final Callback callback;
    private String itemKey;
    private double amount;
    private String status = "";
    private EditBox keyBox;
    private EditBox amountBox;

    public AmountScreen(Screen parent, String title, String itemKey, double amount,
                        boolean amountEditable, Callback callback) {
        super(Component.literal(title));
        this.parent = parent;
        this.title = title;
        this.itemKey = ItemKeyMapper.normalize(itemKey);
        this.amount = amount;
        this.amountEditable = amountEditable;
        this.callback = callback;
    }

    private int panelX = 40;
    private int panelY = 40;
    private int panelW = 220;
    private int panelH = 168;

    @Override
    protected void init() {
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        keyBox = new EditBox(font, panelX + 8, panelY + 28, panelW - 16, 18,
                Component.literal("物品注册名"));
        keyBox.setMaxLength(128);
        keyBox.setValue(itemKey);
        addRenderableWidget(keyBox);

        if (amountEditable) {
            amountBox = new EditBox(font, panelX + 8, panelY + 52, 112, 18,
                    Component.literal("用量"));
            amountBox.setMaxLength(64);
            amountBox.setValue(TimeExpression.formatEditableNumber(amount));
            addRenderableWidget(amountBox);
        }

        int buttonY = panelY + 76;
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

        addRenderableWidget(Button.builder(Component.literal("确定"), b -> confirm())
                .bounds(panelX + 8, panelY + panelH - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> cancel())
                .bounds(panelX + panelW - 108, panelY + panelH - 26, 100, 20).build());

        if (amountEditable && amountBox != null) {
            setInitialFocus(amountBox);
        } else {
            setInitialFocus(keyBox);
        }
        JeiScreenSupport.notifyGuiInit(this);
    }

    private void adjust(double delta) {
        if (!amountEditable || amountBox == null) {
            return;
        }
        double current;
        try {
            current = Double.parseDouble(amountBox.getValue().trim());
        } catch (NumberFormatException e) {
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
                value = Double.parseDouble(amountBox.getValue().trim());
            } catch (NumberFormatException e) {
                status = "用量必须是数字";
                return;
            }
            if (!(value > 0) || !Double.isFinite(value)) {
                status = "用量必须为正数";
                return;
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

        graphics.drawString(font, "物品 / 注册名", panelX + 8, panelY + 16, AeStyle.MUTED, false);
        if (amountEditable) {
            graphics.drawString(font, "用量", panelX + 126, panelY + 40, AeStyle.MUTED, false);
        }

        String previewKey = ItemKeyMapper.normalize(keyBox.getValue());
        if (!previewKey.isEmpty()) {
            AeStyle.slot(graphics, panelX + 126, panelY + 52);
            ItemKeyMapper.renderIcon(graphics, font, previewKey, panelX + 126, panelY + 52, null);
            String name = ItemKeyMapper.displayName(previewKey);
            graphics.drawString(font, font.plainSubstrByWidth(name, panelW - 154),
                    panelX + 146, panelY + 52, AeStyle.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(previewKey, panelW - 154),
                    panelX + 146, panelY + 64, AeStyle.MUTED, false);
        }

        if (!status.isEmpty()) {
            graphics.drawString(font, status, panelX + 8, panelY + panelH - 38,
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
