package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.AmountScreen;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.jessetech.recipebalancer.mc.screen.FactorScreen;
import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import com.jessetech.recipebalancer.mc.screen.ScenarioListScreen;
import com.jessetech.recipebalancer.mc.screen.TimeScreen;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.gui.IGuiProperties;
import mezz.jei.api.gui.IGuiScreenHandler;
import net.minecraft.client.gui.GuiScreen;

/**
 * JEI 4.8.4+ 的注册集合（普通界面面板让位 + 拖拽）。
 *
 * <p>单独成类的原因：{@code IGuiScreenHandler} / {@code IGhostIngredientHandler} /
 * {@code IGuiProperties} 都是 4.8.4 才引入的，低于该版本时加载本类会
 * {@code NoClassDefFoundError}。{@link RecipeBalancerJeiPlugin} 在能力探测通过后才调用，
 * 并把整个调用包在 {@code try/catch (Throwable)} 里。
 */
public final class JeiRegistration {

    private JeiRegistration() {
    }

    public static void register(IModRegistry registry) {
        if (!JeiCapabilities.hasScreenHandlers()) {
            return;
        }
        registry.addGuiScreenHandler(AmountScreen.class, panel());
        registry.addGuiScreenHandler(KeySetScreen.class, panel());
        registry.addGuiScreenHandler(TimeScreen.class, panel());
        registry.addGuiScreenHandler(ScenarioListScreen.class, panel());
        registry.addGuiScreenHandler(FactorScreen.class, panel());
        // SolveScreen 故意不注册：它是纯文本报告，不需要 JEI 面板

        registry.addGhostIngredientHandler(BalancerScreen.class, new JeiGhostHandler());
        registry.addGhostIngredientHandler(KeySetScreen.class, new JeiKeySetGhostHandler());

        // 4.14+ 的全局悬停兜底（方法名是复数，且只有 4.14 起存在）
        if (JeiCapabilities.hasGlobalGuiHandler()) {
            try {
                JeiGlobalRegistration.register(registry);
            } catch (Throwable t) {
                RecipeBalancerMod.LOGGER.warn(
                        "[RecipeBalancer] JEI 全局悬停处理器注册失败（降级为容器处理器）: {}", t.toString());
            }
        }
    }

    /** 普通界面的面板让位：把 {@link JeiPanelScreen} 的面板矩形报给 JEI。 */
    private static <T extends GuiScreen & JeiPanelScreen> IGuiScreenHandler<T> panel() {
        return new IGuiScreenHandler<T>() {
            @Override
            public IGuiProperties apply(T screen) {
                return JeiGuiProperties.of(screen, screen);
            }
        };
    }
}
