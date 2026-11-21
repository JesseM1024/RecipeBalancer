package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.mc.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本模组所有界面的基类（1.12.2 版）。
 *
 * <p>1.12.2 与 1.16.5 的界面生命周期差异全部收敛在这里：
 * <ul>
 *   <li>{@code init()} → {@link #initGui()}（子类仍写 {@code init()}）；</li>
 *   <li>{@code render(pose, ...)} → {@link #drawScreen(int, int, float)}；</li>
 *   <li>{@code renderBackground(pose)} → {@link #drawBackground(int)}；</li>
 *   <li>{@code keyPressed(int,int,int)} → 由 {@link #keyTyped(char, int)} 翻译后转发，
 *       子类继续用 GLFW 的 256/257 键码；</li>
 *   <li>{@code minecraft}/{@code font} 字段补齐（1.12.2 叫 {@code mc}/{@code fontRenderer}），
 *       避免几百处改名；</li>
 *   <li>{@code GuiTextField} 没有控件列表：这里维护一份并负责绘制/输入转发。</li>
 * </ul>
 *
 * <p>另外把 tooltip 提升为 public 并直接实现 {@link Draw.Tooltips}，于是绘制辅助类
 * （{@link Draw}、{@code ItemKeyMapper}、{@code SolveGraphView}）仍写
 * {@code graphics.renderTooltip(...)}。
 */
public abstract class RbScreen extends GuiScreen implements Draw.Tooltips {

    /** 1.16.5 的 {@code Screen#minecraft}。 */
    protected Minecraft minecraft;
    /** 1.16.5 的 {@code Screen#font}。 */
    protected FontRenderer font;
    /** 1.16.5 的 {@code Screen#mouseX/mouseY}：1.12.2 只有参数，这里补成字段供各界面复用。 */
    protected int mouseX;
    protected int mouseY;
    /** 最近一次按键的 1.12.2（LWJGL2）原始键码；子类比较 {@code KeyBinding} 时用它。 */
    protected int lastKeyCode;

    private final ITextComponent screenTitle;
    private final List<GuiTextField> textFields = new ArrayList<>();

    protected RbScreen(ITextComponent title) {
        this.screenTitle = title == null ? Chat.empty() : title;
    }

    protected RbScreen() {
        this(Chat.empty());
    }

    public ITextComponent title() {
        return screenTitle;
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        // 必须先赋值：super 内部会调用 initGui() → init()，子类在那里就要用 font
        this.minecraft = mc;
        this.font = mc == null ? null : mc.fontRenderer;
        super.setWorldAndResolution(mc, width, height);
    }

    /** 子类仍写 {@code init()}：这里做桥接并清空上一轮的控件。 */
    @Override
    public final void initGui() {
        textFields.clear();
        buttonList.clear();
        init();
        if (!textFields.isEmpty()) {
            // 不默认聚焦任何输入框：点进来就能用键盘，不会把字打进名称框
        }
    }

    /** 子类的初始化入口（1.16.5 的 {@code init()}）。 */
    protected void init() {
    }

    /** 1.16.5 的 {@code addButton(TextFieldWidget)}：1.12.2 的输入框不在控件列表里。 */
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

    /** 当前获得焦点的输入框（没有则 null）。 */
    protected GuiTextField focusedField() {
        for (GuiTextField field : textFields) {
            if (field.isFocused()) {
                return field;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        drawBackground(0);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField field : textFields) {
            field.drawTextBox();
        }
    }

    /** 子类的背景入口（1.16.5 的 {@code renderBackground(MatrixStack)}）。 */
    @Override
    public void drawBackground(int tint) {
        Gui.drawRect(0, 0, width, height, 0xB0101010);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

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
        if (keyPressed(KeyCodes.toGlfw(keyCode), KeyCodes.scanCode(keyCode), KeyCodes.modifiers())) {
            return;
        }
        GuiTextField focused = focusedField();
        if (focused != null && focused.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 1.16.5 的按键入口：参数是 GLFW 键码（256=ESC、257=ENTER、335=小键盘 ENTER）。
     * 1.12.2 的实际键码由 {@link KeyCodes} 翻译，子类代码不用改。
     */
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

    /** 1.16.5 的 {@code isPauseScreen()}：1.12.2 叫 {@code doesGuiPauseGame()}。 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------
    // tooltip（Draw.Tooltips）
    // ------------------------------------------------------------------

    /**
     * 名字不能叫 {@code renderTooltip}：重混淆会把 MC 的同名方法改成 SRG 名，
     * 实现方法被改名后与接口对不上 → 运行期 {@code AbstractMethodError}。
     */
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

    /** LWJGL2 键码 → GLFW 键码（只覆盖界面真正用到的那些）。 */
    static final class KeyCodes {

        private KeyCodes() {
        }

        static int toGlfw(int keyCode) {
            switch (keyCode) {
                case Keyboard.KEY_ESCAPE:
                    return 256;
                case Keyboard.KEY_RETURN:
                    return 257;
                case Keyboard.KEY_NUMPADENTER:
                    return 335;
                case Keyboard.KEY_TAB:
                    return 258;
                case Keyboard.KEY_BACK:
                    return 259;
                case Keyboard.KEY_DELETE:
                    return 261;
                case Keyboard.KEY_UP:
                    return 265;
                case Keyboard.KEY_DOWN:
                    return 264;
                case Keyboard.KEY_LEFT:
                    return 263;
                case Keyboard.KEY_RIGHT:
                    return 262;
                case Keyboard.KEY_HOME:
                    return 268;
                case Keyboard.KEY_END:
                    return 269;
                default:
                    return keyCode;
            }
        }

        /** 1.12.2 没有 scanCode 的概念。 */
        static int scanCode(int keyCode) {
            return 0;
        }

        /** 与 GLFW 一致的修饰键位：SHIFT=1、CTRL=2、ALT=4。 */
        static int modifiers() {
            int modifiers = 0;
            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
                modifiers |= 1;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
                modifiers |= 2;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
                modifiers |= 4;
            }
            return modifiers;
        }
    }
}
