package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.ui.Chat;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.Slot;

/**
 * 一级界面（{@link BalancerScreen}）的容器界面基类（1.16.5 版）。
 *
 * <p>只用 {@link ClientEditorContainer} 这个<b>零槽位空容器</b>换取容器界面的身份，
 * 内容仍由子类整屏自绘。为了保持「原来就是普通 Screen」的行为，这里把原版容器界面
 * 里会发网络包 / 画多余元素的行为逐条中和：
 *
 * <ul>
 *   <li>{@link #handleMouseClick} 空实现 —— 空容器没有槽位，同时吞掉「点面板外丢弃」的
 *       {@code slotId=-999} 与快捷栏交换；</li>
 *   <li>{@link #drawGuiContainerForegroundLayer} 空实现 —— 不画容器标题与「物品栏」标签；</li>
 *   <li>{@link #drawGuiContainerBackgroundLayer} 空实现 —— 子类覆盖的是
 *       {@code renderBackground(...)} / 私有 {@code drawBackground(...)}；</li>
 *   <li>{@link #onClose} 不发关闭包 —— 1.16.5 的 {@code Minecraft#displayGuiScreen(newScreen)}
 *       会调用<b>旧屏</b>的 {@code onClose()}，这里再发起关闭就会无限递归 → {@code StackOverflowError}。
 *       落库/草稿等收尾由子类重写 {@code onClose} 完成，关闭动作交给 {@code closeScreen()}。</li>
 * </ul>
 */
public abstract class RbContainerScreen extends ContainerScreen<ClientEditorContainer>
        implements com.jessetech.recipebalancer.mc.ui.Draw.Tooltips {

    protected RbContainerScreen(ClientEditorContainer container) {
        super(container, container.playerInventory(), Chat.empty());
    }

    /**
     * tooltip 入口（1.16.5 的 {@code ContainerScreen#renderTooltip} 是 protected，
     * 绘制辅助类调不到；这里实现成 public 的接口方法，界面代码写法与 1.20 线一致）。
     */
    @Override
    public void renderTooltip(MatrixStack pose, net.minecraft.item.ItemStack stack,
                              int mouseX, int mouseY) {
        super.renderTooltip(pose, stack, mouseX, mouseY);
    }

    @Override
    public void renderComponentTooltip(MatrixStack pose,
                                       java.util.List<net.minecraft.util.text.ITextComponent> lines,
                                       net.minecraft.client.gui.FontRenderer font,
                                       int mouseX, int mouseY) {
        // 1.16.5 的 renderToolTip 收的是「视觉序」列表，这里逐行转换
        java.util.List<net.minecraft.util.IReorderingProcessor> ordered =
                new java.util.ArrayList<>(lines.size());
        for (net.minecraft.util.text.ITextComponent line : lines) {
            ordered.add(line.func_241878_f());
        }
        super.renderToolTip(pose, ordered, mouseX, mouseY, font);
    }

    /** 子类自己整屏自绘背景，这里保持空实现（抽象方法必须实现）。 */
    @Override
    protected void drawGuiContainerBackgroundLayer(MatrixStack matrixStack, float partialTicks,
                                                   int mouseX, int mouseY) {
    }

    /** 不画容器标题与「物品栏」标签：本界面没有原版槽位。 */
    @Override
    protected void drawGuiContainerForegroundLayer(MatrixStack matrixStack, int mouseX, int mouseY) {
    }

    /**
     * 空容器没有槽位，点哪里都不该发 {@code handleInventoryMouseClick}。
     *
     * <p>原版会在这里处理「点面板外丢弃物品」（{@code slotId == -999}）与快捷栏交换，
     * 空实现一并吞掉。1.16.5 的入口叫 {@code handleMouseClick}（高版本是 {@code slotClicked}）。
     */
    @Override
    protected void handleMouseClick(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    /**
     * 只做「被通知关闭」：不调 {@code player.closeContainer()}，也不发起 {@code displayGuiScreen}。
     *
     * <p>1.16.5 里 {@code displayGuiScreen(newScreen)} 会回调旧屏的本方法，
     * 在这里再切屏会无限递归。
     */
    @Override
    public void onClose() {
        // 收尾（落库/草稿/命名/缩放还原）由子类实现
    }
}
