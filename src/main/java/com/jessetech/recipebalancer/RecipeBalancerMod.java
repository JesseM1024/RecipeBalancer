package com.jessetech.recipebalancer;

import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import com.jessetech.recipebalancer.mc.keybind.RbKeyMappings;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RecipeBalancer —— 产线配平自动计算器（纯客户端）。
 *
 * <p>1.18.2 的 {@code @Mod} 没有 {@code dist} 参数（20.2+ 才引入），改为构造时检查
 * {@link FMLEnvironment#dist}：非客户端环境只记一条日志就返回、不注册任何监听 ——
 * 专用服务端无需安装本模组，也不会因缺少客户端类而崩溃。
 *
 * <p>事件总线分工：
 * <ul>
 *   <li>modBus（模组总线）：初始化阶段的事件，如注册快捷键、载入配置；</li>
 *   <li>{@link MinecraftForge#EVENT_BUS}（游戏总线）：运行期反复触发的事件，如客户端 tick。</li>
 * </ul>
 */
@Mod(RecipeBalancerMod.MODID)
public final class RecipeBalancerMod {

    public static final String MODID = "recipebalancer";

    /**
     * 1.16.5 的 MC / Forge 只用 log4j2，运行环境里<b>没有 slf4j</b>：
     * 用 {@code LoggerFactory} 会在类加载（{@code <clinit>}）阶段直接
     * {@code NoClassDefFoundError: org/slf4j/LoggerFactory}，模组连实例都建不起来。
     * 1.18.2 的 slf4j 是 Forge 自带的，所以那条件用 slf4j 没问题；这里必须换 log4j2。
     * 两者都是 {@code {} } 占位符，调用点无需改动。
     */
    public static final Logger LOGGER = LogManager.getLogger("RecipeBalancer");

    public RecipeBalancerMod() {
        // 非客户端环境：跳过全部客户端注册（1.18.2 的 @Mod 无 dist 参数）
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.info("[RecipeBalancer] not a client dist, skipping all client registrations");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 快捷键：1.18.2 没有 RegisterKeyMappingsEvent（1.19 才引入），
        // 走 FMLClientSetupEvent + ClientRegistry.registerKeyBinding（ModBus 事件）。
        modBus.addListener(RbKeyMappings::onClientSetup);
        // 游戏总线：进入世界后按键打开界面。
        // 方法引用在这里推不出泛型事件类型（IEventBus#addListener(Consumer<T>)），
        // 必须写成带显式参数类型的 lambda。
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) ->
                RbKeyMappings.onClientTick(event));

        // 配置：ForgeConfigSpec，落在 config/recipebalancer-client.toml。
        // 1.18.2 没有 NeoForge 自带的配置界面（ConfigurationScreen 为 20.4+ 引入），
        // 亦不注册 ConfigScreenHandler 扩展点：配置仅经 TOML 文件修改。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, GraphLayoutConfig.SPEC);

        LOGGER.info("[RecipeBalancer] 初始化：快捷键 K 打开主界面");
        checkRuntimeDependencies();
    }

    /** 启动自检：求解库是否真的能加载（比点「求解」时才发现更早）。 */
    private static void checkRuntimeDependencies() {
        if (com.jessetech.recipebalancer.core.solve.CommonsMath3LpSolver.available()) {
            LOGGER.info("[RecipeBalancer] commons-math3 可用（jar 内嵌套库）");
        } else {
            LOGGER.error("[RecipeBalancer] commons-math3 不可用，求解会失败: {}",
                    com.jessetech.recipebalancer.core.solve.CommonsMath3LpSolver.failure());
        }
    }

    /**
     * 求解库不可用时打印定位信息：
     * <ul>
     *   <li>模组 jar 的实际位置（确认游戏加载的是哪一个文件）；</li>
     *   <li>模组类的 define 加载器；</li>
     *   <li>同一个 jar 内的 {@code .class} 是否能作为资源被看到 ——
     *       资源能读到却 {@code Class.forName} 失败，说明是类加载/转换层的问题；
     *       资源也读不到，说明 jar 没被加进类加载器（打包/替换时机问题）。</li>
     * </ul>
     */
    private static void diagnoseMath3() {
        try {
            java.security.CodeSource source =
                    RecipeBalancerMod.class.getProtectionDomain().getCodeSource();
            LOGGER.error("[RecipeBalancer] 诊断: 模组位置={}, 类加载器={}",
                    source == null ? "?" : source.getLocation(),
                    RecipeBalancerMod.class.getClassLoader().getClass().getName());
            String resource = "org/apache/commons/math3/optim/linear/SimplexSolver.class";
            ClassLoader loader = RecipeBalancerMod.class.getClassLoader();
            LOGGER.error("[RecipeBalancer] 诊断: loader.getResource={}",
                    loader.getResource(resource));
            LOGGER.error("[RecipeBalancer] 诊断: contextClassLoader.getResource={}",
                    Thread.currentThread().getContextClassLoader().getResource(resource));
        } catch (Throwable t) {
            LOGGER.error("[RecipeBalancer] 诊断失败", t);
        }
    }

}
