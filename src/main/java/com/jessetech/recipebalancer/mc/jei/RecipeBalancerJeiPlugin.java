package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AmountScreen;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import com.jessetech.recipebalancer.mc.screen.FactorScreen;
import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import com.jessetech.recipebalancer.mc.screen.ScenarioListScreen;
import com.jessetech.recipebalancer.mc.screen.SolveScreen;
import com.jessetech.recipebalancer.mc.screen.TimeScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;

import java.util.List;


/**
 * RecipeBalancer 的 JEI 插件入口（1.21.1 / JEI 19.x）。
 *
 * <p>一级界面（配方编码主界面）已容器化，走 JEI 对 {@code AbstractContainerScreen} 开放的
 * {@link RbContainerGuiHandler}（额外区域 + 悬停配料）；其余二级界面仍是自定义 {@code Screen}，
 * 逐个注册 {@code IGuiProperties} 告诉 JEI 面板该贴在哪一侧。
 *
 * <p>本类只引用 <b>19.8.4.110 就存在</b>的 JEI API（编译基线），
 * 更新版本才有的能力（配方页按钮 API）放在 {@code src/jeiNew} 源码集，
 * 运行期按类是否存在反射加载。
 *
 * <p>配方页按钮有两条实现：
 * <ul>
 *   <li>19.39+：{@code IRecipeButtonControllerFactory}（jeiNew 源码集）；</li>
 *   <li>19.39 之前：没有按钮 API，退回 {@link JeiLegacyRecipeButton}（装饰器自绘，
 *       材质与高版本完全一致），按 {@code getAllRecipeTypes()} 覆盖所有配方类型。</li>
 * </ul>
 */
@JeiPlugin
public final class RecipeBalancerJeiPlugin implements IModPlugin {

    /** jeiNew 源码集里的类名（用字符串引用，避免主源码集依赖新 API）。 */
    private static final String NEW_BUTTON_REGISTER =
            "com.jessetech.recipebalancer.mc.jei.JeiAdvanced";

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
        // 一级界面已是容器界面，走容器处理器（额外区域 + 悬停配料）。
        try {
            registerBalancerContainerHandler(registration);
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 容器处理器不可用: {}", t.toString());
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
            // 求解结果页 / 系数输入页同样要实现 IGuiProperties，否则 JEI 不认为
            // 这些界面「有 GUI」，左侧书签面板与物品面板都不会渲染。
            registration.addGuiScreenHandler(SolveScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
            registration.addGuiScreenHandler(FactorScreen.class,
                    screen -> JeiGuiProperties.of(screen, screen));
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 面板定位不可用: {}", t.toString());
        }
        // 容器处理器为主路径，全局处理器保留兜底（19.8.4 起全区间可用）。
        if (registerGlobalClickHandler(registration)) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 悬停查询：容器处理器 + 全局兜底");
        } else {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 悬停查询不可用");
        }
        try {
            registration.addGhostIngredientHandler(BalancerScreen.class, new JeiGhostHandler());
            // 物料清单编辑屏（无限外部供应 / 循环亏损补充）：拖进网格即追加
            registration.addGhostIngredientHandler(KeySetScreen.class, new JeiKeySetGhostHandler());
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 拖拽录入不可用: {}", t.toString());
        }
    }

    /**
     * 一级界面的处理器：容器化之后走 JEI 只对 {@code AbstractContainerScreen} 开放的接口。
     *
     * <p>额外区域（左右图标列）与悬停配料都由 {@link RbContainerGuiHandler} 提供；
     * 面板属性由 JEI 自带的 {@code AbstractContainerScreen} 默认处理器按
     * {@code leftPos/topPos/imageWidth/imageHeight} 推导，不需要再单独注册 {@code IGuiProperties}。
     */
    private static void registerBalancerContainerHandler(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(BalancerScreen.class, new RbContainerGuiHandler());
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 容器处理器：已注册（一级界面）");
    }

    /**
     * 悬停兜底：注册全局处理器。
     *
     * <p>只需实现无工厂重载 {@link JeiLegacyClickHandler#getClickableIngredientUnderMouse(double, double)}：
     * 19.8.4–19.22 只有它；19.23 新加的工厂重载默认就是转调它，19.70 的容器处理器同理。
     * 容器处理器为主路径，这里保留一份，某个 JEI 构建裁剪回调时 R/U/A 仍然可用。
     */
    private static boolean registerGlobalClickHandler(IGuiHandlerRegistration registration) {
        try {
            registration.addGlobalGuiHandler(new JeiLegacyClickHandler());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        if (registerRecipeButtonApi(registration)) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：已注册（按钮 API）");
            return;
        }
        // 19.39 起才有 IRecipeButtonControllerFactory；更低版本退回装饰器自绘
        try {
            int types = registerLegacyDecorators(registration);
            RecipeBalancerMod.LOGGER.info(
                    "[RecipeBalancer] JEI 版本低于 19.39，配方页按钮改用装饰器，覆盖 {} 个配方类型",
                    types);
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.info(
                    "[RecipeBalancer] JEI 配方页按钮不可用({})", t.toString());
        }
    }

    /** 19.39+ 的按钮 API；类或方法不存在时返回 false（老版本 JEI）。 */
    private static boolean registerRecipeButtonApi(IAdvancedRegistration registration) {
        try {
            Class<?> type = Class.forName(NEW_BUTTON_REGISTER);
            type.getMethod("register", IAdvancedRegistration.class).invoke(null, registration);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 老版本 JEI：为<b>所有</b>已注册配方类型（含模组类别）挂上同一个装饰器，
     * 由它在配方上自绘按钮。{@code registerAdvanced} 在所有插件注册完类别之后调用，
     * 所以这里能拿到完整的类型表。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int registerLegacyDecorators(IAdvancedRegistration registration) {
        List<RecipeType<?>> types = registration.getJeiHelpers()
                .getAllRecipeTypes()
                .toList();
        for (RecipeType<?> type : types) {
            registration.addRecipeCategoryDecorator((RecipeType) type,
                    (IRecipeCategoryDecorator) JeiLegacyRecipeButton.INSTANCE);
        }
        return types.size();
    }
}
