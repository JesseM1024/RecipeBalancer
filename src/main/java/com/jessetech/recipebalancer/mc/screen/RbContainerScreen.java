package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一级界面（{@link BalancerScreen}）的容器界面基类（1.12.2 版）。
 *
 * <p>只用 {@link ClientEditorContainer} 这个<b>零槽位空容器</b>换取容器界面的身份，
 * 内容仍由子类整屏自绘。为了保持「原来就是普通界面」的行为，这里把原版容器界面
 * 里会发网络包 / 画多余元素的行为逐条中和：
 *
 * <ul>
 *   <li>{@code handleMouseClick} 空实现 —— 空容器没有槽位，同时吞掉「点面板外丢弃」的
 *       {@code slotId=-999} 与快捷栏交换；</li>
 *   <li>{@code drawGuiContainerForegroundLayer} 空实现 —— 不画容器标题与「物品栏」标签；</li>
 *   <li>{@code drawGuiContainerBackgroundLayer} 空实现 —— 背景由子类整屏自绘；</li>
 *   <li>{@code onGuiClosed} 不主动切屏 —— 收尾（落库/草稿/命名/缩放还原）由子类完成。</li>
 * </ul>
 */
public abstract class RbContainerScreen extends GuiContainer implements Draw.Tooltips {

    /** 1.16.5 的 {@code Screen#minecraft}。 */
    protected Minecraft minecraft;
    /** 1.16.5 的 {@code Screen#font}。 */
    protected FontRenderer font;
    /** 1.16.5 的 {@code Screen#mouseX/mouseY}：1.12.2 只有参数，这里补成字段供各界面复用。 */
    protected int mouseX;
    protected int mouseY;
    /** 最近一次按键的 1.12.2（LWJGL2）原始键码；子类比较 {@code KeyBinding} 时用它。 */
    protected int lastKeyCode;

    private final List<GuiTextField> textFields = new ArrayList<>();

    protected RbContainerScreen(ClientEditorContainer container) {
        super(container);
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        // 必须先赋值：super 内部会调用 initGui() → init()，子类在那里就要用 font
        this.minecraft = mc;
        this.font = mc == null ? null : mc.fontRenderer;
        super.setWorldAndResolution(mc, width, height);
    }

    @Override
    public final void initGui() {
        textFields.clear();
        buttonList.clear();
        super.initGui();
        init();
        if (!textFields.isEmpty()) {
            // 不默认聚焦任何输入框：点进来就能用键盘，不会把字打进名称框
        }
    }

    /** 子类的初始化入口（1.16.5 的 {@code init()}）。 */
    protected void init() {
    }

    protected <T extends GuiTextField> T addText(T field) {
        textFields.add(field);
        return field;
    }

    protected <T extends GuiButton> T addButton(T button) {
        buttonList.add(button);
        return button;
    }

    /** 1.16.5 的 {@code addButton(TextFieldWidget)} 调用形状（重载按类型分派）。 */
    protected <T extends GuiTextField> T addButton(T field) {
        return addText(field);
    }

    /**
     * 1.16.5 的 {@code setListener(TextFieldWidget)} 只是指定「回车作用于哪个输入框」，
     * <b>不会聚焦</b>；1.12.2 没有这个机制，这里保持空实现以免一进界面就开始吃键盘。
     */
    protected void setListener(GuiTextField field) {
        // 不聚焦，也不抢输入
    }

    protected GuiTextField focusedField() {
        for (GuiTextField field : textFields) {
            if (field.isFocused()) {
                return field;
            }
        }
        return null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField field : textFields) {
            field.drawTextBox();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiTextField field : textFields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
        }
        onMouseClicked(mouseX, mouseY, mouseButton);
    }

    /** 子类的点击入口（1.16.5 的 {@code mouseClicked(double,double,int)}）。 */
    protected boolean onMouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        return false;
    }

    /** 1.12.2 没有滚轮回调，统一在这里读 LWJGL2 的滚轮增量。 */
    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0) {
            onMouseScrolled(wheel > 0 ? 1.0 : -1.0);
        }
    }

    /** 子类的滚轮入口（1.16.5 的 {@code mouseScrolled(double,double,double)}）。 */
    protected boolean onMouseScrolled(double delta) {
        return false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        this.lastKeyCode = keyCode;
        if (keyPressed(RbScreen.KeyCodes.toGlfw(keyCode), 0, RbScreen.KeyCodes.modifiers())) {
            return;
        }
        GuiTextField focused = focusedField();
        if (focused != null && focused.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** 1.16.5 的按键入口（GLFW 键码）。 */
    protected boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField field : textFields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** 子类自己整屏自绘背景，这里保持空实现。 */
    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
    }

    /** 不画容器标题与「物品栏」标签：本界面没有原版槽位。 */
    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
    }

    /** 空容器没有槽位，点哪里都不该发 {@code handleInventoryMouseClick}。 */
    @Override
    protected void handleMouseClick(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    /** 只做「被通知关闭」：不调 {@code player.closeScreen()}，也不发起切屏。 */
    @Override
    public void onGuiClosed() {
    }

    // ------------------------------------------------------------------
    // tooltip（Draw.Tooltips）
    // ------------------------------------------------------------------

    @Override
    public void renderItemTooltip(ItemStack stack, int mouseX, int mouseY) {
        renderToolTip(stack, mouseX, mouseY);
    }

    @Override
    public void renderComponentTooltip(List<ITextComponent> lines, FontRenderer fontRenderer,
                                       int mouseX, int mouseY) {
        List<String> strings = new ArrayList<>(lines.size());
        for (ITextComponent line : lines) {
            strings.add(line.getFormattedText());
        }
        drawHoveringText(strings, mouseX, mouseY, fontRenderer);
    }

    /** 供子类判断滚轮方向（1.12.2 没有 mouseScrolled 回调）。 */
    protected static int scrollDelta() {
        return org.lwjgl.input.Mouse.getEventDWheel();
    }

    /** 与 {@link RbScreen.KeyCodes} 同一套翻译（包内复用）。 */
    static int glfwKey(int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            return 256;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            return 257;
        }
        if (keyCode == Keyboard.KEY_NUMPADENTER) {
            return 335;
        }
        return keyCode;
    }
}
