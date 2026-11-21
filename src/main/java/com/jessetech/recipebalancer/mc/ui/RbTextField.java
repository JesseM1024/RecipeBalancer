package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 1.16.5 风格输入框的 1.12.2 替身。
 *
 * <p>1.12.2 的 {@link GuiTextField} 构造器是 {@code (id, font, x, y, w, h)}，
 * 没有占位符、也没有 {@code setResponder(...)}。界面代码在 1.16.5 线里到处用
 * {@code new GuiTextField(font, x, y, w, h, placeholder)} + {@code setResponder(...)}，
 * 与其改几十处调用点，不如在这里补一个同形构造器：
 * <ul>
 *   <li>占位符：空文本且未聚焦时画一行灰字；</li>
 *   <li>responder：每次文本变化（键入/点击/程序 setText 之后）回调一次。</li>
 * </ul>
 */
public final class RbTextField extends GuiTextField {

    private static final AtomicInteger IDS = new AtomicInteger(0x1000);

    private final FontRenderer renderer;
    private final int fieldX;
    private final int fieldY;
    private final int fieldHeight;
    private final String placeholder;

    private Consumer<String> responder;
    private String lastNotified;

    public RbTextField(FontRenderer font, int x, int y, int width, int height, String placeholder) {
        super(IDS.incrementAndGet(), font, x, y, width, height);
        this.renderer = font;
        this.fieldX = x;
        this.fieldY = y;
        this.fieldHeight = height;
        this.placeholder = placeholder;
    }

    /** 1.16.5 的占位符参数常常是 {@code ITextComponent}（{@code Lang.c(...)} 的返回值）。 */
    public RbTextField(FontRenderer font, int x, int y, int width, int height,
                       net.minecraft.util.text.ITextComponent placeholder) {
        this(font, x, y, width, height,
                placeholder == null ? null : placeholder.getUnformattedText());
    }

    /** 1.16.5 的 {@code setResponder}：文本变化时回调。 */
    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
        this.lastNotified = getText();
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        notifyResponder();
    }

    @Override
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        boolean changed = super.textboxKeyTyped(typedChar, keyCode);
        notifyResponder();
        return changed;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        boolean focused = super.mouseClicked(mouseX, mouseY, mouseButton);
        notifyResponder();
        return focused;
    }

    @Override
    public void drawTextBox() {
        // 兜底：界面在 setWorldAndResolution 之前构造输入框时 font 可能还是 null，
        // GuiTextField#drawTextBox 内部会直接 NPE，这里干脆不画（下一帧就正常了）。
        if (renderer == null) {
            return;
        }
        super.drawTextBox();
        if (placeholder == null || placeholder.isEmpty()) {
            return;
        }
        if (!getText().isEmpty() || isFocused()) {
            return;
        }
        renderer.drawStringWithShadow(placeholder, fieldX + 4,
                fieldY + (fieldHeight - 8) / 2, 0xFF808080);
    }

    private void notifyResponder() {
        Consumer<String> callback = responder;
        if (callback == null) {
            return;
        }
        String value = getText();
        if (value.equals(lastNotified)) {
            return;
        }
        lastNotified = value;
        callback.accept(value);
    }
}
