package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.mc.ui.Draw;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 容器版界面基类（{@link RbScreen} 的容器对应物）。
 *
 * <p>存在的理由与 {@link RbScreen} 相同：1.18.2 的 {@code Screen#renderTooltip} 是
 * {@code protected}，{@link Draw} / {@code ItemKeyMapper} / {@code SolveGraphView}
 * 调不到，这里把它提升为 public 并实现 {@link Draw.Tooltips}。
 *
 * <p>另一个职责是<b>中和原版容器行为</b>。本模组的容器界面用的是
 * {@link ClientEditorMenu}（零槽位、纯客户端），任何落到原版槽位逻辑的点击都会变成
 * {@code gameMode.handleInventoryMouseClick(...)} —— 一个指向不存在菜单的网络包。
 * 因此这里统一：
 * <ul>
 *   <li>{@link #slotClicked} 置空：左右键拾取、Shift 快移、快捷栏交换、Q 丢弃全部失效；</li>
 *   <li>{@link #renderLabels} 置空：标题/背包名由各界面自绘；</li>
 *   <li>{@link #onClose} 只切屏，不调 {@code player.closeContainer()}（那是给玩家真实
 *       容器发的关闭包，与本界面无关）。</li>
 * </ul>
 */
public abstract class RbContainerScreen extends AbstractContainerScreen<ClientEditorMenu>
        implements Draw.Tooltips {

    protected RbContainerScreen(Component title) {
        super(new ClientEditorMenu(), playerInventoryOrFallback(), title);
    }

    /**
     * {@code AbstractContainerScreen} 需要一个 {@link Inventory} 取背包标题。
     *
     * <p>1.18.2 的 {@code Inventory} 只有 {@code Inventory(Player)} 一个构造器，
     * 没有玩家就造不出来；而本模组的界面只可能在游戏内打开（快捷键 / JEI 按钮），
     * 因此这里直接给出明确错误，而不是让空指针在构造器里炸得莫名其妙。
     */
    private static Inventory playerInventoryOrFallback() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            return minecraft.player.getInventory();
        }
        throw new IllegalStateException("RecipeBalancer: 没有玩家时无法创建容器界面");
    }

    /** 合成一次绘制入口；子类在 render 里用 {@code draw(pose)} 代替 new Draw(...)。 */
    protected final Draw draw(PoseStack pose) {
        return new Draw(pose, this);
    }

    /**
     * 本界面没有任何真实槽位，任何点击都不允许落到原版槽位逻辑（否则会发点槽包）。
     */
    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
    }

    /** 标题与背包名由界面自绘，容器基类不再绘制。 */
    @Override
    protected void renderLabels(PoseStack pose, int mouseX, int mouseY) {
    }

    /**
     * 关闭界面：<b>不</b>调用 {@code player.closeContainer()}。
     *
     * <p>那个方法会把「玩家当前容器」（背包菜单 id 0）的关闭包发给服务端，
     * 而本界面没有服务端菜单，发这个包既无意义也可能影响其它界面。
     */
    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    /** 由 protected 提升为 public（覆写 Screen 的同签名方法，实现 {@link Draw.Tooltips}）。 */
    @Override
    public void renderTooltip(PoseStack pose, ItemStack stack, int mouseX, int mouseY) {
        super.renderTooltip(pose, stack, mouseX, mouseY);
    }

    @Override
    public void renderComponentTooltip(PoseStack pose, List<Component> lines, Font font,
                                       int mouseX, int mouseY) {
        super.renderComponentTooltip(pose, lines, mouseX, mouseY, font);
    }
}
