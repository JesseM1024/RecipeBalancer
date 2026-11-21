package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.IAdvancedGuiHandler;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 一级界面（容器化的 {@link BalancerScreen}）的 JEI 容器处理器（1.12.2 / JEI 4.x）。
 *
 * <p>1.12.2 的注册方式是「handler 自己报出容器类」：
 * {@code IModRegistry#addAdvancedGuiHandlers(...)} + {@link #getGuiContainerClass()}。
 *
 * <p>两条职责：
 * <ul>
 *   <li>{@link #getGuiExtraAreas}：把贴在面板外侧的左右两列图标按钮报成「GUI 之外但仍属于本界面」
 *       的区域，JEI 的物品/书签面板自动让开；</li>
 *   <li>{@link #getIngredientUnderMouse}：鼠标下的槽位还原成<b>裸配料对象</b>
 *       返回给 JEI（4.x 的返回类型就是 {@code Object}），供 R/U/A 使用。</li>
 * </ul>
 */
public final class RbContainerGuiHandler implements IAdvancedGuiHandler<BalancerScreen> {

    @Override
    public Class<BalancerScreen> getGuiContainerClass() {
        return BalancerScreen.class;
    }

    @Override
    public List<Rectangle> getGuiExtraAreas(BalancerScreen screen) {
        List<Rectangle> areas = screen.jeiExtraAreas();
        return areas == null ? new ArrayList<Rectangle>() : areas;
    }

    @Override
    public Object getIngredientUnderMouse(BalancerScreen screen, int mouseX, int mouseY) {
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        if (hovered == null) {
            return null;
        }
        return JeiHoverKeys.ingredient(hovered.key());
    }
}
