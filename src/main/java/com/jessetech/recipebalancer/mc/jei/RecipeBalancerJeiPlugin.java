package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.JEIPlugin;

/**
 * RecipeBalancer 的 JEI 插件入口（1.12.2 / JEI 4.7.8 ~ 4.24）。
 *
 * <p>4.x 与 7.x 的入口差异：
 * <ul>
 *   <li>注解是 {@code @JEIPlugin}（大写 I），没有 {@code getPluginUid()}；</li>
 *   <li>注册入口是 {@code register(IModRegistry)}，不是 {@code registerGuiHandlers(...)}；</li>
 *   <li>容器处理器、普通界面处理器、拖拽处理器全部挂在同一个 registry 上；</li>
 *   <li>配料注册表（{@code IIngredientRegistry}）只能从这里拿，运行期要一直持有。</li>
 * </ul>
 *
 * <p>本类只引用 4.7.9 就存在的 API（{@code IModPlugin} / {@code IModRegistry} /
 * {@code IAdvancedGuiHandler}）。4.8.4+ 的能力注册全部委派给 {@link JeiRegistration}，
 * 低于该版本时加载它会 {@code NoClassDefFoundError}，这里用 {@code catch (Throwable)} 兜住，
 * 于是 4.7.8 的实例也能启动、只是少了拖拽与普通界面的面板让位。
 */
@JEIPlugin
public final class RecipeBalancerJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        // 配料注册表：1.12.2 的 IJeiRuntime 没有 getIngredientManager()，只能在这里拿
        try {
            JeiRuntimeHolder.setIngredientRegistry(registry.getIngredientRegistry());
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 配料注册表不可用: {}",
                    t.toString());
        }

        // 主路径：一级界面是容器界面，走 4.x 全版本都有的 IAdvancedGuiHandler
        try {
            registry.addAdvancedGuiHandlers(new RbContainerGuiHandler());
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 容器处理器：已注册（一级界面）");
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 容器处理器注册失败: {}",
                    t.toString());
        }

        // 4.8.4+：普通界面的面板让位 + 拖拽录入 + 4.14+ 的全局悬停兜底
        try {
            JeiRegistration.register(registry);
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 面板定位/拖拽录入：已注册");
        } catch (Throwable t) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 4.8.4+ 能力注册失败（降级为容器处理器）: {}",
                    t.toString());
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.set(jeiRuntime);
        // 旧 runtime 的对象已失效，清掉 key 缓存与反查缓存
        JeiIngredientMapper.clearCache();
        JeiIngredientRenderer.clearCache();
        JeiReflect.clear();
        JeiIngredientRenderer renderer = new JeiIngredientRenderer();
        ItemKeyMapper.setIngredientRenderHook(renderer);
        ItemKeyMapper.setIngredientNameHook(renderer);
        AutoRecipeHooks.set(new JeiAutoRecipe());
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] {}", JeiCapabilities.describe());
        try {
            // 4.x 没有官方按钮 API：用客户端叠加层在配方页画「编码此配方」按钮
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
                "com.jessetech.recipebalancer.mc.jei.JeiRecipeOverlay",
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
}
