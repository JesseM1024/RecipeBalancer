package com.jessetech.recipebalancer.mc.screen;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * 纯客户端的「空菜单」：0 个 {@code Slot}，只为了让一级界面成为容器界面。
 *
 * <p><b>为什么需要它</b>：JEI 的悬停配料、额外区域等回调只对
 * {@code AbstractContainerScreen} 开放（{@code IGuiContainerHandler}）。本模组是纯客户端模组，
 * 不能注册服务端菜单，所以这里造一个「外壳」：
 * <ul>
 *   <li>{@code MenuType} 传 {@code null}、不注册 {@code MenuType}/{@code MenuScreens}，
 *       服务端完全不知道它的存在；</li>
 *   <li>{@code containerId} 取 {@link #CONTAINER_ID}（服务端容器 id 从 1 递增，远达不到该值），
 *       避免与服务端下发的菜单 id 撞车；</li>
 *   <li>0 个槽位，任何槽位点击路径都会被 {@link RbContainerScreen#slotClicked} 吞掉，
 *       不会发出 {@code handleInventoryMouseClick}；</li>
 *   <li>{@link #removed(Player)} 空实现，关闭界面时不做任何事。</li>
 * </ul>
 *
 * <p>多人游戏、服务端未装本模组时照常工作：本菜单从不同步任何数据。
 */
public final class ClientEditorMenu extends AbstractContainerMenu {

    /** 客户端自用容器 id：服务端菜单 id 从 1 开始递增，不会用到这个值。 */
    public static final int CONTAINER_ID = 0x7FFF;

    /**
     * 未注册的哑 {@code MenuType}：只是让 {@code getType()} 有值可返回。
     *
     * <p>1.20.1 的 {@code AbstractContainerMenu#getType()} 在类型为 null 时会抛
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

    /** 创建时传入的玩家背包（容器界面用它取背包标题，实际渲染由界面自绘）。 */
    public Inventory playerInventory() {
        return playerInventory;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        // 客户端空菜单：关闭时不需要做任何事
    }
}
