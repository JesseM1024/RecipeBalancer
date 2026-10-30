package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AmountScreen;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import com.jessetech.recipebalancer.mc.screen.ScenarioListScreen;
import com.jessetech.recipebalancer.mc.screen.TimeScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IScreenHandler;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;


/**
 * 让 JEI 在我们的自定义 Screen（非 ContainerScreen）上渲染：
 * JEI 只自动识别 ContainerScreen，必须为每个屏幕注册 IGuiProperties。
 *
 * <p>本类只引用 <b>19.8.4.110 就存在</b>的 JEI API（编译基线），
 * 更新版本才有的能力（配方页按钮、悬停物品聚焦）放在 {@code src/jeiNew} 源码集，
 * 运行期按类是否存在反射加载，老版本 JEI 自动降级。
 */
@JeiPlugin
public final class RecipeBalancerJeiPlugin implements IModPlugin {

    /** jeiNew 源码集里的类名（用字符串引用，避免主源码集依赖新 API）。 */
    private static final String NEW_BUTTON_REGISTER =
            "com.jessetech.recipebalancer.mc.jei.JeiAdvanced";
    private static final String NEW_SCREEN_HANDLER =
            "com.jessetech.recipebalancer.mc.jei.JeiBalancerScreenHandler";

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("recipebalancer", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.set(jeiRuntime);
        JeiIngredientRenderer renderer = new JeiIngredientRenderer();
        ItemKeyMapper.setIngredientRenderHook(renderer);
        ItemKeyMapper.setIngredientNameHook(renderer);
        AutoRecipeHooks.set(new JeiAutoRecipe());
    }

    @Override
    public void onRuntimeUnavailable() {
        ItemKeyMapper.setIngredientRenderHook(null);
        ItemKeyMapper.setIngredientNameHook(null);
        AutoRecipeHooks.set(null);
        JeiRuntimeHolder.set(null);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // 各段对应不同时期加入的 JEI API：老版本缺哪个就跳过哪个，不能把插件带崩。
        try {
            registerBalancerScreenHandler(registration);
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 主界面悬浮物品不可用: {}", t.toString());
        }
        try {
            registration.addGuiScreenHandler(AmountScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
            registration.addGuiScreenHandler(KeySetScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
            registration.addGuiScreenHandler(
                    TimeScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
            registration.addGuiScreenHandler(ScenarioListScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 面板定位不可用: {}", t.toString());
        }
        try {
            registration.addGhostIngredientHandler(BalancerScreen.class, new JeiGhostHandler());
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 拖拽录入不可用: {}", t.toString());
        }
    }

    /**
     * 主界面处理器：优先用 jeiNew 里带「悬停聚焦」的实现；
     * 老版本 JEI 缺少 {@code IClickableIngredientFactory} 时加载失败，退回只报面板位置。
     */
    @SuppressWarnings("unchecked")
    private static void registerBalancerScreenHandler(IGuiHandlerRegistration registration) {
        try {
            Class<?> type = Class.forName(NEW_SCREEN_HANDLER);
            Object handler = type.getConstructor().newInstance();
            registration.addGuiScreenHandler(BalancerScreen.class,
                    (IScreenHandler<BalancerScreen>) handler);
        } catch (Throwable t) {
            registration.addGuiScreenHandler(BalancerScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
        }
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        // 只有 JEI 19.39+ 才有配方页按钮 API；更低版本不提供该功能，首次进世界会在聊天栏提示
        try {
            Class<?> type = Class.forName(NEW_BUTTON_REGISTER);
            type.getMethod("register", IAdvancedRegistration.class).invoke(null, registration);
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：已注册");
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.info(
                    "[RecipeBalancer] JEI 版本低于 19.39，配方页按钮与悬停查询不可用({})",
                    t.toString());
        }
    }
}
