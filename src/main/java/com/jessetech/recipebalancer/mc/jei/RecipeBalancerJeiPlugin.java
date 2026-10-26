package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * 让 JEI 在我们的自定义 Screen 上渲染：JEI 只自动识别 ContainerScreen，
 * 非容器屏幕必须注册 {@link IGuiProperties}（面板位置/尺寸），否则右侧物品面板不出现。
 *
 * <p>装了 JEI 才会加载本类；未装时本类不被引用（compileOnly 软依赖）。
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
        registration.addGuiScreenHandler(BalancerScreen.class, screen -> new IGuiProperties() {
            @Override
            public Class<? extends Screen> screenClass() {
                return BalancerScreen.class;
            }

            @Override
            public int guiLeft() {
                return screen.jeiGuiLeft();
            }

            @Override
            public int guiTop() {
                return screen.jeiGuiTop();
            }

            @Override
            public int guiXSize() {
                return screen.jeiGuiWidth();
            }

            @Override
            public int guiYSize() {
                return screen.jeiGuiHeight();
            }

            @Override
            public int screenWidth() {
                return screen.width;
            }

            @Override
            public int screenHeight() {
                return screen.height;
            }
        });
    }
}
