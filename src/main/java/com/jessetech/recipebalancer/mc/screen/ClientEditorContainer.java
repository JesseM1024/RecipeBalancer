package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeContainerType;

/**
 * 纯客户端「零槽位空容器」，只为让 {@link BalancerScreen} 成为容器界面。
 *
 * <p>为什么需要它：JEI 只对 {@code ContainerScreen} 开放容器处理器
 * （额外区域 / 悬停配料），而本模组的一级界面是整屏自绘的。这里给一个没有任何槽位的
 * 空容器，界面内容与交互完全不变，却能拿到容器界面的生命周期与 JEI 回调。
 *
 * <p><b>纯客户端</b>：容器类型自造但<b>不注册</b>、从不发送容器包，
 * 服务端未装本模组时照常工作；网络同步永远不会碰到这个类型。
 *
 * <p>1.16.5 与 1.18.2 的差异：{@code MenuType} 叫 {@link ContainerType}，
 * 且构造器是 {@code protected}，只能借 Forge 的
 * {@link IForgeContainerType#create} 造一个不注册的实例。
 */
public final class ClientEditorContainer extends Container {

    /** 客户端自用容器 id：服务端菜单 id 从 1 开始递增，不会用到这个值。 */
    public static final int CONTAINER_ID = 0x7FFF;

    /**
     * 未注册的哑 {@code ContainerType}：只是让 {@code getType()} 有值可返回。
     *
     * <p>1.16.5 的 {@code Container#getType()} 在类型为 null 时会抛
     * {@code UnsupportedOperationException}，万一有别的模组顺手取一次就会崩；
     * 这里给一个「自造但不注册」的类型，既不会进入注册表，也不会被网络同步引用。
     */
    private static final ContainerType<ClientEditorContainer> TYPE =
            IForgeContainerType.create((windowId, playerInventory, data) ->
                    new ClientEditorContainer(playerInventory));

    private final PlayerInventory playerInventory;

    public ClientEditorContainer(PlayerInventory playerInventory) {
        super(TYPE, CONTAINER_ID);
        this.playerInventory = playerInventory;
    }

    /** 容器界面基类构造 {@code ContainerScreen} 时需要玩家背包。 */
    PlayerInventory playerInventory() {
        return playerInventory;
    }

    /** 空容器永远「有效」：不依赖任何方块或实体。 */
    @Override
    public boolean canInteractWith(PlayerEntity player) {
        return true;
    }

    /** 没有槽位可转移，任何 shift 点击请求都返回空。 */
    @Override
    public ItemStack transferStackInSlot(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }

    /** 关闭界面时不做任何事（没有需要返还/同步的槽位）。 */
    @Override
    public void onContainerClosed(PlayerEntity player) {
    }
}
