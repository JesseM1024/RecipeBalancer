package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.text.ITextComponent;

/**
 * 本模组的按钮基类：把 1.16.5 的 {@code Button} 形状（x/y/w/h/message/onPress）
 * 落到 1.12.2 的 {@link GuiButton} 上。
 *
 * <p>1.12.2 没有 {@code Button.builder(...)}，也没有 {@code renderWidget}：
 * 绘制入口是 {@code drawButton(Minecraft, int, int, float)}，点击回调要在
 * {@code mousePressed(...)} 里自己调。
 */
public class Button extends GuiButton {

    /** 与 1.16.5 的 {@code Button.IPressable} 同形（参数是本类，便于是 {@code b -> ...}）。 */
    public interface IPressable {
        void onPress(Button button);
    }

    private final IPressable pressable;
    private ITextComponent message;

    public Button(int x, int y, int width, int height, ITextComponent message, IPressable onPress) {
        super(0, x, y, width, height, message == null ? "" : message.getFormattedText());
        this.message = message == null ? Chat.empty() : message;
        this.pressable = onPress;
    }

    public ITextComponent getMessage() {
        return message;
    }

    public void setMessage(ITextComponent message) {
        this.message = message == null ? Chat.empty() : message;
        this.displayString = this.message.getFormattedText();
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (super.mousePressed(mc, mouseX, mouseY)) {
            if (pressable != null) {
                pressable.onPress(this);
            }
            return true;
        }
        return false;
    }

    /** 子类（{@link IconButton}）用：当前是否被悬停。 */
    protected boolean hovered() {
        return hovered;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!visible) {
            return;
        }
        this.hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        Buttons.drawButtonBackground(x, y, width, height,
                46 + getHoverState(hovered) * Buttons.BUTTON_SRC_H);
        int color = 0xFFFFFFFF;
        if (!enabled) {
            color = 0xFFA0A0A0;
        }
        mc.fontRenderer.drawStringWithShadow(displayString,
                x + width / 2 - mc.fontRenderer.getStringWidth(displayString) / 2,
                y + (height - 8) / 2, color);
        mouseDragged(mc, mouseX, mouseY);
    }
}
