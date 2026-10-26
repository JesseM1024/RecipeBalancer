package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.AmountScreen;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.jessetech.recipebalancer.mc.screen.JeiPanelScreen;
import com.jessetech.recipebalancer.mc.screen.ScenarioListScreen;
import com.jessetech.recipebalancer.mc.screen.TargetListScreen;
import com.jessetech.recipebalancer.mc.screen.TimeScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * 让 JEI 在我们的自定义 Screen（非 ContainerScreen）上渲染：
 * JEI 只自动识别 ContainerScreen，必须为每个屏幕注册 IGuiProperties。
 *
 * <p>注意：属性在 Screen.init 之前也可能被 JEI 查询，因此各屏幕的面板字段都有合法默认值，
 * 否则首次打开不会渲染，必须切一次二级界面再返回才出现。
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
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(BalancerScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(AmountScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(TimeScreen.class,
                screen -> properties(screen, screen));
        registration.addGuiScreenHandler(TargetListScreen.class,
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
