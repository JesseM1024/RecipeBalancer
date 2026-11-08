package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/**
 * 一级界面（{@link BalancerScreen}）的容器界面基类。
 *
 * <p>只用 {@link ClientEditorMenu} 这个<b>零槽位空菜单</b>换取容器界面的身份，
 * 内容仍由子类整屏自绘。为了保持「原来就是普通 Screen」的行为，这里把原版容器界面
 * 里会发网络包 / 画多余元素的行为逐条中和：
 *
 * <ul>
 *   <li>{@link #slotClicked} 空实现 —— 空菜单没有槽位，同时吞掉「点面板外丢弃」的
 *       {@code slotId=-999} 与快捷栏交换；</li>
 *   <li>{@link #renderLabels} 空实现 —— 不画容器标题与「物品栏」标签；</li>
 *   <li>{@link #renderBg} 空实现 —— 子类覆盖的是 {@code renderBackground(...)}（整屏自绘）；</li>
 *   <li>{@link #onClose} 只关界面 —— 不调用 {@code player.closeContainer()}，
 *       避免向服务端发无意义的关闭包。</li>
 * </ul>
 */
public abstract class RbContainerScreen extends AbstractContainerScreen<ClientEditorMenu> {

    protected RbContainerScreen(ClientEditorMenu menu) {
        super(menu, menu.playerInventory(), Component.empty());
    }

    /** 子类用 {@code renderBackground(...)} 整屏自绘，这里保持空实现（抽象方法必须实现）。 */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    /** 不画容器标题与「物品栏」标签：本界面没有原版槽位。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /**
     * 空菜单没有槽位，点哪里都不该发 {@code handleInventoryMouseClick}。
     *
     * <p>原版会在这里处理「点面板外丢弃物品」（{@code slotId == -999}）与快捷栏交换，
     * 空实现一并吞掉。
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    /** 关界面只切屏：本界面没有服务端菜单，不能调 {@code player.closeContainer()}。 */
    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
