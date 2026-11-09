package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rectangle2d;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 全局悬停处理器（1.16.5 / JEI 7.x），让 R / U / A 生效。
 *
 * <p>7.x 的 {@code IGlobalGuiHandler#getIngredientUnderMouse} 返回<b>裸配料对象</b>；
 * 一级界面还有容器处理器这条主路径，本类只是兜底：
 * 若某个 JEI 构建裁剪了容器侧回调，R/U/A 依然可用。
 */
public final class JeiHoverHandler implements IGlobalGuiHandler {

    @Override
    public Object getIngredientUnderMouse(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.currentScreen instanceof BalancerScreen)) {
            return null;
        }
        BalancerScreen screen = (BalancerScreen) minecraft.currentScreen;
        BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
        return hovered == null ? null : JeiHoverKeys.ingredient(hovered.key());
    }

    @Override
    public Collection<Rectangle2d> getGuiExtraAreas() {
        // 额外区域由容器处理器负责；全局处理器不声明区域
        return new ArrayList<>();
    }
}
