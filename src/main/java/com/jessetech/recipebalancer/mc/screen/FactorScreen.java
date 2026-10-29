package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.jei.JeiScreenSupport;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** 输入一个缩放系数（支持 + - * / 与括号这类简单表达式）。 */
public final class FactorScreen extends Screen implements JeiPanelScreen {

    public interface Callback {
        void accept(double factor);
    }

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 96;

    private final Screen parent;
    private final String title;
    private final Callback callback;
    private EditBox factorBox;
    private String status = "";

    private int panelX = 20;
    private int panelY = 10;

    public FactorScreen(Screen parent, String title, Callback callback) {
        super(Lang.c(title));
        this.parent = parent;
        this.title = title;
        this.callback = callback;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        factorBox = new EditBox(font, panelX + 8, panelY + 28, PANEL_W - 16, 18,
                Lang.c("系数"));
        factorBox.setMaxLength(48);
        factorBox.setValue("1");
        addRenderableWidget(factorBox);

        addRenderableWidget(Button.builder(Lang.c("取消"), b -> minecraft.setScreen(parent))
                .bounds(panelX + 8, panelY + PANEL_H - 26, 88, 20).build());
        addRenderableWidget(Button.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 96, panelY + PANEL_H - 26, 88, 20).build());
        setInitialFocus(factorBox);
        JeiScreenSupport.notifyGuiInit(this);
    }

    private void confirm() {
        double factor;
        try {
            factor = TimeExpression.evaluate(factorBox.getValue().trim());
        } catch (RuntimeException e) {
            status = Lang.t("系数无效");
            return;
        }
        if (!Double.isFinite(factor) || factor <= 0) {
            status = Lang.t("系数无效");
            return;
        }
        callback.accept(factor);
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101010);
        AeStyle.panel(graphics, panelX, panelY, PANEL_W, PANEL_H);
        AeStyle.header(graphics, panelX, panelY, PANEL_W, 20);
        graphics.drawString(font, Lang.t(title), panelX + 8, panelY + 6, AeStyle.TEXT, false);
        String hint = status.isEmpty() ? Lang.t("支持 + - * / 和括号") : status;
        graphics.drawString(font, hint, panelX + 8, panelY + 52,
                status.isEmpty() ? AeStyle.MUTED : AeStyle.ACCENT, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            confirm();
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
