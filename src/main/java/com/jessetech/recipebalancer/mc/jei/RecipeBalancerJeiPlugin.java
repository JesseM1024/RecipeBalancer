package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.AmountScreen;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import com.jessetech.recipebalancer.mc.screen.ScenarioListScreen;
import com.jessetech.recipebalancer.mc.screen.TimeScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * 让 JEI 在我们的自定义 Screen（非 ContainerScreen）上渲染：
 * JEI 只自动识别 ContainerScreen，必须为每个屏幕注册 IGuiProperties。
 *
 * <p>另外把 {@link IJeiRuntime} 存入不含 JEI 类型的 {@link JeiRuntimeHolder}，
 * 供 {@link JeiScreenSupport} 反射驱动覆盖层属性缓存（JEI 19.x 无公开刷新 API）。
 */
@JeiPlugin
public final class RecipeBalancerJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("recipebalancer", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.set(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeHolder.set(null);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(BalancerScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(AmountScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(TimeScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(ScenarioListScreen.class,
                screen -> properties(screen, screen));
    }

    private static <T extends Screen & JeiPanelScreen> IGuiProperties properties(
            T screen, JeiPanelScreen panel) {
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
                        : net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
            }

            @Override
            public int screenHeight() {
                return screen.height > 0 ? screen.height
                        : net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
            }
        };
    }
}
