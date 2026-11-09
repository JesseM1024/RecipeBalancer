package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把模组界面的面板位置告诉 JEI（决定 JEI 面板贴在哪一侧、是否遮挡）。
 *
 * <p>1.18.2 的 {@code IGuiProperties} 是 getter 风格（getGuiLeft/…），
 * 与 1.21.1 的 record 风格（guiLeft/…）不同，这里按 9.x/10.x 实现。
 */
public final class JeiGuiProperties {

    /** 每个界面第一次被 JEI 询问位置时记一条日志，用来确认 JEI 确实认这个界面。 */
    private static final Set<Class<?>> LOGGED = ConcurrentHashMap.newKeySet();

    private JeiGuiProperties() {
    }

    /**
     * 报给 JEI 一份界面属性。
     *
     * <p>JEI 会在界面 {@code init()} 之前先问一次，那时 {@code screen.width/height} 还是 0，
     * 面板坐标是占位值（guiLeft 可能为负）。把这种假值报出去，JEI 会把「左侧没有空间」
     * 当成结论缓存起来，书签列要等界面重新初始化才出现。所以布局就绪前直接返回 null
     * （JEI 对 null 是安全的，会当作「未知界面」）。
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
            public Class<? extends Screen> getScreenClass() {
                return screen.getClass();
            }

            @Override
            public int getGuiLeft() {
                return panel.jeiGuiLeft();
            }

            @Override
            public int getGuiTop() {
                return panel.jeiGuiTop();
            }

            @Override
            public int getGuiXSize() {
                return panel.jeiGuiWidth();
            }

            @Override
            public int getGuiYSize() {
                return panel.jeiGuiHeight();
            }

            @Override
            public int getScreenWidth() {
                return screen.width > 0 ? screen.width
                        : Minecraft.getInstance().getMainWindow().getScaledWidth();
            }

            @Override
            public int getScreenHeight() {
                return screen.height > 0 ? screen.height
                        : Minecraft.getInstance().getMainWindow().getScaledHeight();
            }
        };
    }
}
