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
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * RecipeBalancer 的 JEI 插件入口（1.18.2 / JEI 9.x ~ 10.x）。
 *
 * <p>JEI 只自动识别 {@code AbstractContainerScreen}，本模组全是自定义 {@code Screen}，
 * 因此要逐个注册 {@code IGuiProperties} 告诉 JEI 面板该贴在哪一侧。
 *
 * <p><b>版本策略</b>：本类只使用 JEI 9.4.1 就存在的 API（编译基线），
 * 更高版本才有的能力（配方页按钮 API、类别装饰器）由 {@link JeiCapabilities} 探测，
 * 实现类放在 {@code src/jeiModern} 源码集里用 10.81 编译、运行期按能力反射加载；
 * 9.x 没有这些 API，对应功能整体不可用（日志会明确说明）。
 */
@JeiPlugin
public final class RecipeBalancerJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            new ResourceLocation("recipebalancer", "jei_plugin");

    /** jeiModern 源码集里的类名（字符串引用，避免主源码集依赖新 API）。 */
    private static final String MODERN_REGISTER =
            "com.jessetech.recipebalancer.mc.jei.JeiModernRegister";

    /** 官方配方页按钮是否已成功注册（true 时叠加层让位）。 */
    private static volatile boolean officialRecipeButtons;

    static boolean officialRecipeButtons() {
        return officialRecipeButtons;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.set(jeiRuntime);
        // 旧 runtime 的配料对象已失效，清掉 key 缓存与反查缓存
        JeiIngredientMapper.clearCache();
        JeiIngredientRenderer.clearCache();
        JeiIngredientRenderer renderer = new JeiIngredientRenderer();
        ItemKeyMapper.setIngredientRenderHook(renderer);
        ItemKeyMapper.setIngredientNameHook(renderer);
        AutoRecipeHooks.set(new JeiAutoRecipe());
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] {}", JeiCapabilities.describe());
        try {
            // 9.x 没有官方按钮 API：用客户端叠加层在配方页画「编码此配方」按钮；
            // 10.x 有官方 API 时本层会自动让位（见 JeiRecipeOverlay#active）。
            JeiRecipeOverlay.install();
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 配方页叠加按钮安装失败: {}",
                    t.toString());
        }
        warmUpHelperClasses();
    }

    /**
     * 预热点击/导入路径才会用到的类。
     *
     * <p>这些类平时不会被触碰（例如 {@code JeiPatternImporter} 只在右键「自动撰写」时第一次加载）。
     * 若 jar 打包缺类，异常会延迟到玩家点击时才爆，而且发生在渲染线程。
     * 启动阶段主动加载一次，问题会立刻以清晰的错误日志暴露。
     */
    private static void warmUpHelperClasses() {
        String[] names = {
                "com.jessetech.recipebalancer.mc.jei.JeiPatternImporter",
                "com.jessetech.recipebalancer.mc.jei.JeiIngredientMapper",
                "com.jessetech.recipebalancer.mc.jei.JeiAutoRecipe",
                "com.jessetech.recipebalancer.mc.jei.JeiBookmarkReader",
                "com.jessetech.recipebalancer.mc.jei.JeiHoverKeys",
        };
        for (String name : names) {
            try {
                Class.forName(name, false, RecipeBalancerJeiPlugin.class.getClassLoader());
            } catch (Throwable t) {
                RecipeBalancerMod.LOGGER.error(
                        "[RecipeBalancer] JEI 辅助类加载失败：{}（相关功能不可用；"
                                + "若 jar 在游戏运行中被替换过，请重启游戏）", name, t);
            }
        }
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // 各段独立 try/catch：任何一段的 API 差异都不能把整个插件带崩。
        try {
            registerScreenHandlers(registration);
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 面板定位不可用: {}", t.toString());
        }
        try {
            // 主路径：一级界面已是容器界面，走 JEI 只对容器开放的 IGuiContainerHandler
            // （额外区域 + 悬停配料，9.x 起一直可用）。
            registration.addGuiContainerHandler(BalancerScreen.class,
                    new RbContainerGuiHandler());
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 容器处理器：已注册（一级界面）");
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 容器处理器注册失败: {}", t.toString());
        }
        try {
            // 兜底：若某个 JEI 构建不支持/裁掉了容器侧悬停回调，全局处理器仍能让 R/U/A 工作。
            // 代价只是同一个界面上被查询两次（无害），换来跨版本稳定。
            registration.addGlobalGuiHandler(new JeiHoverHandler());
            RecipeBalancerMod.LOGGER.info(
                    "[RecipeBalancer] JEI 悬停查询：全局处理器兜底已注册（工厂 API={}）",
                    JeiCapabilities.supportsClickableFactory());
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 悬停查询不可用: {}",
                    t.toString());
        }
        try {
            registration.addGhostIngredientHandler(BalancerScreen.class, new JeiGhostHandler());
            // 物料清单编辑屏（无限外部供应 / 循环亏损补充）：拖进网格即追加
            registration.addGhostIngredientHandler(KeySetScreen.class,
                    new JeiKeySetGhostHandler());
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 拖拽录入不可用: {}", t.toString());
        }
    }

    private static void registerScreenHandlers(IGuiHandlerRegistration registration) {
        // 一级界面已改为容器界面，改由 addGuiContainerHandler 注册（见 registerGuiHandlers），
        // 这里只处理仍是普通 Screen 的二级界面。
        registration.addGuiScreenHandler(AmountScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
        registration.addGuiScreenHandler(KeySetScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
        registration.addGuiScreenHandler(TimeScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
        registration.addGuiScreenHandler(ScenarioListScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
        // 求解结果页 / 系数输入页同样要实现 IGuiProperties，否则 JEI 不认为
        // 这些界面「有 GUI」，左侧书签面板与物品面板都不会渲染。
        registration.addGuiScreenHandler(SolveScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
        registration.addGuiScreenHandler(FactorScreen.class,
                screen -> JeiGuiProperties.of(screen, screen));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        // 配方页按钮：10.x 走官方 API；9.x（或官方注册失败）由客户端叠加层提供，
        // 两条路只会启用一条，避免同一页出现两个按钮。
        if (JeiCapabilities.supportsRecipeButtons() && registerModernButtons(registration)) {
            officialRecipeButtons = true;
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：已注册（按钮 API，{}）",
                    JeiCapabilities.version());
            return;
        }
        officialRecipeButtons = false;
        RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] JEI 配方页按钮：官方 API 不可用（{}），改用客户端叠加层",
                JeiCapabilities.version());
    }

    /**
     * 官方按钮 API；类或方法不存在时返回 false（9.x）。
     *
     * <p>实现类在 {@code src/jeiModern} 源码集（用 JEI 10.81 编译），用字符串引用，
     * 这样主源码集不依赖新 API，老版本加载本类也不会触发 {@code NoClassDefFoundError}。
     */
    private static boolean registerModernButtons(IAdvancedRegistration registration) {
        try {
            Class<?> type = Class.forName(MODERN_REGISTER);
            type.getMethod("register", IAdvancedRegistration.class).invoke(null, registration);
            return true;
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.debug(
                    "[RecipeBalancer] JEI 按钮 API 可用但注册失败: {}", t.toString());
            return false;
        }
    }
}
