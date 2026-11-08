package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;
import java.util.Optional;

/**
 * 一级界面（已容器化）的 JEI 容器处理器。
 *
 * <p>相比原来的「{@code addGuiScreenHandler} + 虚报 GUI 宽度 + 全局悬停处理器」，
 * 容器处理器是 JEI 对容器界面开放的正规入口：
 * <ul>
 *   <li>{@link #getGuiExtraAreas}：左右两列图标按钮贴在面板外侧，报成额外区域后，
 *       JEI 的物品/书签面板会把它们让开，不再靠「把 guiXSize 报大」这种 hack；</li>
 *   <li>{@link #getClickableIngredientUnderMouse}：鼠标下的配料（物品/流体/化学品），
 *       支撑 R / U / A。JEI 传进来的是<b>绝对屏幕坐标</b>（已核实 15.3/15.74 的实现），
 *       正好与 {@link BalancerScreen#jeiHoveredKey} 的入参一致。</li>
 * </ul>
 *
 * <p>全局处理器 {@link JeiLegacyClickHandler} 仍保留注册作为兜底：若某个 JEI 构建
 * 裁剪/改动了容器侧回调，R/U/A 依然可用。
 */
public final class RbContainerGuiHandler implements IGuiContainerHandler<BalancerScreen> {

    @Override
    public List<Rect2i> getGuiExtraAreas(BalancerScreen screen) {
        return screen.jeiExtraAreas();
    }

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(BalancerScreen screen,
                                                                             double mouseX,
                                                                             double mouseY) {
        return JeiHoverKeys.clickable(screen.jeiHoveredKey(mouseX, mouseY));
    }
}
