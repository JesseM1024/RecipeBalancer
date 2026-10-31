package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把模组界面的面板位置告诉 JEI（决定 JEI 面板贴在哪）。
 *
 * <p>19.8.4.110 起就有 {@code IGuiProperties}，所以这段可以直连编译；
 * 需要新 API 的部分（配方页按钮、悬停物品聚焦）放在 {@code src/jeiNew} 里。
 */
public final class JeiGuiProperties {

    /** 每个界面第一次被 JEI 询问位置时记一条日志，用来确认 JEI 确实认这个界面。 */
    private static final Set<Class<?>> LOGGED = ConcurrentHashMap.newKeySet();

    private JeiGuiProperties() {
    }

    /**
     * 报给 JEI 一份界面属性。
     *
     * <p>JEI 会在 {@code ScreenEvent.Opening}（也就是 {@code Screen#init()} 之前）先问一次，
     * 那时 {@code screen.width/height} 还是 0，面板坐标是占位值（guiLeft 可能为负）。
     * 把这种假值报出去，JEI 会把「左侧没有空间」当成结论缓存进
     * {@code ScreenPropertiesCache}，书签列因此要等界面被重新初始化才出现。
     * 所以布局就绪前直接不报（JEI 对 null 是安全的，会当作「未知界面」）。
     */
    public static <T extends Screen & JeiPanelScreen> IGuiProperties of(T screen,
                                                                       JeiPanelScreen panel) {
        if (screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        if (LOGGED.add(screen.getClass())) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 已识别界面：{}",
                    screen.getClass().getSimpleName());
        }
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
