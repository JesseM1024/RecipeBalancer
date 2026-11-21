package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

/**
 * 纯客户端「零槽位空容器」，只为让 {@link BalancerScreen} 成为容器界面。
 *
 * <p>为什么需要它：JEI 的容器处理器（额外区域 / 悬停配料）只认 {@code GuiContainer}，
 * 而本模组的一级界面是整屏自绘的。这里给一个没有任何槽位的空容器，
 * 界面内容与交互完全不变，却能拿到容器界面的生命周期与 JEI 回调。
 *
 * <p><b>纯客户端</b>：1.12.2 的 {@code Container} 没有 {@code ContainerType}
 * 也不需要注册，从不发送容器包；服务端未装本模组时照常工作。
 */
public final class ClientEditorContainer extends Container {

    /** 客户端自用容器 id：服务端菜单 id 从 1 开始递增，不会用到这个值。 */
    public static final int CONTAINER_ID = 0x7FFF;

    private final InventoryPlayer playerInventory;

    public ClientEditorContainer(InventoryPlayer playerInventory) {
        this.playerInventory = playerInventory;
        // 1.12.2 的 windowId 在 Container 里是 protected 字段，构造后直接覆盖
        this.windowId = CONTAINER_ID;
    }

    /** 容器界面基类构造 {@code GuiContainer} 时需要玩家背包。 */
    InventoryPlayer playerInventory() {
        return playerInventory;
    }

    /** 空容器永远「有效」：不依赖任何方块或实体。 */
    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }

    /** 没有槽位可转移，任何 shift 点击请求都返回空。 */
    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        return ItemStack.EMPTY;
    }

    /** 关闭界面时不做任何事（没有需要返还/同步的槽位）。 */
    @Override
    public void onContainerClosed(EntityPlayer player) {
    }
}
