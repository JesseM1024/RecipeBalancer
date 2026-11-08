package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 纯客户端「零槽位空菜单」，只为让 {@link BalancerScreen} 成为容器界面。
 *
 * <p>为什么需要它：JEI 只对 {@code AbstractContainerScreen} 开放容器处理器
 * （额外区域 / 悬停配料），而本模组的一级界面是整屏自绘的。这里给一个没有任何槽位的
 * 空菜单，界面内容与交互完全不变，却能拿到容器界面的生命周期与 JEI 回调。
 *
 * <p><b>纯客户端</b>：菜单类型自造但<b>不注册</b>、从不发送容器包，
 * 服务端未装本模组时照常工作；网络同步永远不会碰到这个类型。
 */
public final class ClientEditorMenu extends AbstractContainerMenu {

    /** 客户端自用容器 id：服务端菜单 id 从 1 开始递增，不会用到这个值。 */
    public static final int CONTAINER_ID = 0x7FFF;

    /**
     * 未注册的哑 {@code MenuType}：只是让 {@code getType()} 有值可返回。
     *
     * <p>1.21.1 的 {@code AbstractContainerMenu#getType()} 在类型为 null 时会抛
     * {@code UnsupportedOperationException}，万一有别的模组/框架顺手取一次就会崩；
     * 这里给一个「自造但<b>不注册</b>」的类型，既不会进入注册表，也不会被网络同步引用。
     */
    private static final MenuType<ClientEditorMenu> TYPE =
            new MenuType<>((containerId, playerInventory) -> new ClientEditorMenu(playerInventory),
                    FeatureFlags.DEFAULT_FLAGS);

    private final Inventory playerInventory;

    public ClientEditorMenu(Inventory playerInventory) {
        super(TYPE, CONTAINER_ID);
        this.playerInventory = playerInventory;
    }

    /** 容器界面基类构造 {@code AbstractContainerScreen} 时需要玩家背包。 */
    Inventory playerInventory() {
        return playerInventory;
    }

    /** 空菜单永远「有效」：不依赖任何方块或实体。 */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** 没有槽位可转移，任何 quickMove 请求都返回空。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** 关闭界面时不做任何事（没有需要返还/同步的槽位）。 */
    @Override
    public void removed(Player player) {
    }
}
