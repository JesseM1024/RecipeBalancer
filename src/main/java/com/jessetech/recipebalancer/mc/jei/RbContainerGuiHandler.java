package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import net.minecraft.client.renderer.Rectangle2d;

import java.util.ArrayList;
import java.util.List;

/**
 * 一级界面（容器化后的 {@link BalancerScreen}）的 JEI 容器处理器（1.16.5 / JEI 7.x）。
 *
 * <p>两条职责：
 * <ul>
 *   <li>{@link #getGuiExtraAreas}：把贴在面板外侧的左右两列图标按钮报成「GUI 之外但仍属于本界面」
 *       的区域，JEI 的物品/书签面板会自动让开 —— 取代旧版「把 guiXSize 报大」的 hack；</li>
 *   <li>{@link #getIngredientUnderMouse}：鼠标下的槽位还原成 <b>裸配料对象</b>
 *       返回给 JEI（7.x 的返回类型就是 {@code Object}），供 R/U/A 使用。</li>
 * </ul>
 */
public final class RbContainerGuiHandler implements IGuiContainerHandler<BalancerScreen> {

    @Override
    public List<Rectangle2d> getGuiExtraAreas(BalancerScreen screen) {
        List<Rectangle2d> areas = screen.jeiExtraAreas();
        return areas == null ? new ArrayList<Rectangle2d>() : areas;
    }

    @Override
    public Object getIngredientUnderMouse(BalancerScreen screen, double mouseX, double mouseY) {
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        if (hovered == null) {
            return null;
        }
        return JeiHoverKeys.ingredient(hovered.key());
    }
}
