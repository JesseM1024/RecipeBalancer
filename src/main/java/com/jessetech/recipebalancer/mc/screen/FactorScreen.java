package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.jessetech.recipebalancer.mc.ui.Buttons;
import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;

/** 输入一个缩放系数（支持 + - * / 与括号这类简单表达式）。 */
public final class FactorScreen extends RbScreen implements JeiPanelScreen {

    public interface Callback {
        void accept(double factor);
    }

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 96;

    private final Screen parent;
    private final String title;
    private final Callback callback;
    private TextFieldWidget factorBox;
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

        factorBox = new TextFieldWidget(font, panelX + 8, panelY + 28, PANEL_W - 16, 18,
                Lang.c("系数"));
        factorBox.setMaxStringLength(48);
        factorBox.setText("1");
        addButton(factorBox);

        addButton(Buttons.builder(Lang.c("取消"), b -> minecraft.displayGuiScreen(parent))
                .bounds(panelX + 8, panelY + PANEL_H - 26, 88, 20).build());
        addButton(Buttons.builder(Lang.c("确定"), b -> confirm())
                .bounds(panelX + PANEL_W - 96, panelY + PANEL_H - 26, 88, 20).build());
        setListener(factorBox);
    }

    private void confirm() {
        double factor;
        try {
            factor = TimeExpression.evaluate(factorBox.getText().trim());
        } catch (RuntimeException e) {
            status = Lang.t("系数无效");
            return;
        }
        if (!Double.isFinite(factor) || factor <= 0) {
            status = Lang.t("系数无效");
            return;
        }
        callback.accept(factor);
        minecraft.displayGuiScreen(parent);
    }

    @Override
    public void renderBackground(MatrixStack pose) {
        Draw graphics = new Draw(pose, this);
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
