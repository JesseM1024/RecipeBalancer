package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.Collections;
import java.util.List;

/**
 * 一级界面（配方编码终端）的 JEI 容器处理器。
 *
 * <p>界面已改为 {@code AbstractContainerScreen}（空菜单、纯客户端），于是 JEI 会把它当容器界面，
 * 走这套只对容器开放的 API：
 * <ul>
 *   <li>{@link #getGuiExtraAreas}：左右两列图标按钮在面板之外，报成额外区域，
 *       JEI 的物品/书签面板会自动让开；</li>
 *   <li>{@link #getIngredientUnderMouse}：槽位悬停 → R/U/A 查看配方，
 *       返回裸配料（9.x/10.x 一致契约），由 JEI 自己包装成可点击配料。</li>
 * </ul>
 *
 * <p>注意：JEI 9.x 的「配方页转移按钮」由<b>服务端</b>处理器执行，
 * 纯客户端、服务端未装本模组时无法使用；本处理器只覆盖客户端侧回调。
 */
public final class RbContainerGuiHandler implements IGuiContainerHandler<BalancerScreen> {

    @Override
    public List<Rect2i> getGuiExtraAreas(BalancerScreen screen) {
        return screen == null ? Collections.emptyList() : screen.jeiExtraAreas();
    }

    @Override
    public Object getIngredientUnderMouse(BalancerScreen screen, double mouseX, double mouseY) {
        if (screen == null) {
            return null;
        }
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        if (hovered == null || hovered.key() == null || J8.isBlank(hovered.key())) {
            return null;
        }
        return JeiHoverKeys.typed(hovered.key())
                .map(ITypedIngredient::getIngredient)
                .orElse(null);
    }
}
