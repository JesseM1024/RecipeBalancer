package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;
import java.util.Optional;

/**
 * 一级界面（容器化后的 {@link BalancerScreen}）的 JEI 容器处理器。
 *
 * <p>两条职责：
 * <ul>
 *   <li>{@link #getGuiExtraAreas}：把贴在面板外侧的左右两列图标按钮报成「GUI 之外但仍属于本界面」
 *       的区域，JEI 的物品/书签面板会自动让开 —— 取代旧版「把 guiXSize 报大」的 hack；</li>
 *   <li>{@link #getClickableIngredientUnderMouse}：鼠标下的槽位包成可点击配料，供 R/U/A 使用。</li>
 * </ul>
 *
 * <p>19.8.4 的接口只有老式重载；19.70 的工厂重载默认实现就是转调它，因此不需要按版本分支。
 */
public final class RbContainerGuiHandler implements IGuiContainerHandler<BalancerScreen> {

    @Override
    public List<Rect2i> getGuiExtraAreas(BalancerScreen screen) {
        return screen.jeiExtraAreas();
    }

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
            BalancerScreen screen, double mouseX, double mouseY) {
        return JeiHoverKeys.clickable(screen.jeiHoveredKey(mouseX, mouseY));
    }
}
