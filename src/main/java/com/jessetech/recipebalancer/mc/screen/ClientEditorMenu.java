package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 纯客户端的「空菜单」：本模组的一级界面只是把界面类型升级成
 * {@code AbstractContainerScreen} 以拿到 JEI 的容器 API，<b>并不真的持有物品</b>。
 *
 * <p>为什么不注册真正的菜单类型：本模组是纯客户端模组，多人游戏里服务端可能
 * 没有安装它。真正的 {@code MenuType} + 槽位同步要求服务端也注册对应的菜单，
 * 否则点击包会被服务端丢弃、界面状态与服务端不同步。这里改用：
 * <ul>
 *   <li>{@code MenuType} 传 {@code null}（客户端渲染不需要它，也绝不注册）；</li>
 *   <li>{@code slots} 为空表 —— 编码区/背包全部由界面自绘，不经过原版槽位；</li>
 *   <li>{@code containerId} 取 {@link #CLIENT_CONTAINER_ID}，与服务端菜单 id（0 起）错开，
 *       即使某条路径意外触发也不会误关玩家的背包菜单。</li>
 * </ul>
 */
public final class ClientEditorMenu extends AbstractContainerMenu {

    /** 客户端专用容器 id：与服务端菜单 id 不冲突，且永不参与网络同步。 */
    public static final int CLIENT_CONTAINER_ID = 0x7FFF;

    public ClientEditorMenu() {
        // 不需要 MenuType：本菜单只在客户端存在，不参与任何同步
        super((MenuType<?>) null, CLIENT_CONTAINER_ID);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
