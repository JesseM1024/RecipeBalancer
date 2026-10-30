package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 把模组界面的面板位置告诉 JEI（决定 JEI 面板贴在哪）。
 *
 * <p>19.8.4.110 起就有 {@code IGuiProperties}，所以这段可以直连编译；
 * 需要新 API 的部分（配方页按钮、悬停物品聚焦）放在 {@code src/jeiNew} 里。
 */
public final class JeiGuiProperties {

    private JeiGuiProperties() {
    }

    public static <T extends Screen & JeiPanelScreen> IGuiProperties of(T screen,
                                                                       JeiPanelScreen panel) {
        return new IGuiProperties() {
            @Override
            public Class<? extends Screen> screenClass() {
                return screen.getClass();
            }

            @Override
            public int guiLeft() {
                return panel.jeiGuiLeft();
            }

            @Override
            public int guiTop() {
                return panel.jeiGuiTop();
            }

            @Override
            public int guiXSize() {
                return panel.jeiGuiWidth();
            }

            @Override
            public int guiYSize() {
                return panel.jeiGuiHeight();
            }

            @Override
            public int screenWidth() {
                return screen.width > 0 ? screen.width
                        : Minecraft.getInstance().getWindow().getGuiScaledWidth();
            }

            @Override
            public int screenHeight() {
                return screen.height > 0 ? screen.height
                        : Minecraft.getInstance().getWindow().getGuiScaledHeight();
            }
        };
    }
}
