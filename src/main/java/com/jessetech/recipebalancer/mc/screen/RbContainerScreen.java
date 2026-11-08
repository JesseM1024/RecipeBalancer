package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/**
 * 一级界面用的容器界面基类（1.20.1 / GuiGraphics 版）。
 *
 * <p>外壳是 {@link AbstractContainerScreen} + {@link ClientEditorMenu}（0 槽位空菜单），
 * 内容仍由子类在 {@link #renderBg} 里整屏自绘；与原版容器行为相关的部分全部中和：
 * <ul>
 *   <li>{@link #slotClicked}：空实现 —— 原版会调用
 *       {@code gameMode.handleInventoryMouseClick(menu.containerId, ...)} 发点槽包，
 *       而本模组没有服务端菜单，绝不能发；</li>
 *   <li>{@link #renderLabels}：空实现 —— 原版会画容器标题与「物品栏」，本界面自绘；</li>
 *   <li>{@link #onClose}：只 {@code setScreen(null)} —— 原版会调用
 *       {@code player.closeContainer()}，那会给玩家的背包菜单发关闭包；</li>
 *   <li>{@link ClientEditorMenu#removed}：空实现。</li>
 * </ul>
 *
 * <p>未覆盖的容器行为都不会产生网络交互：空菜单的 {@code slots} 为空，
 * {@code hoveredSlot} 恒为 null，快捷栏交换/丢弃/拾取路径全部走不到。
 *
 * <p>与 1.18.2 分支的同名类思路一致，只是 1.20.1 用 {@link GuiGraphics} 绘制、
 * 且构造器只需标题（菜单与背包在这里统一创建）。
 */
public abstract class RbContainerScreen extends AbstractContainerScreen<ClientEditorMenu> {

    protected RbContainerScreen(Component title) {
        super(new ClientEditorMenu(playerInventory()), playerInventory(), title);
    }

    /**
     * 容器界面需要一个玩家背包（原版用它取「物品栏」标题与槽位，这里只用于满足构造器）。
     *
     * <p>本模组的界面只可能在游戏内打开（快捷键 / JEI 按钮），没有玩家时直接给出明确错误，
     * 而不是让空指针在构造器里炸得莫名其妙。
     */
    private static Inventory playerInventory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            throw new IllegalStateException("RecipeBalancer: 没有玩家时无法创建容器界面");
        }
        return minecraft.player.getInventory();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        // 见类注释：吞掉原版点槽链路，绝不发送 handleInventoryMouseClick
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题/物品栏由子类自绘，这里不画原版标签
    }

    @Override
    public void onClose() {
        // 不发 player.closeContainer()，只切屏
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
