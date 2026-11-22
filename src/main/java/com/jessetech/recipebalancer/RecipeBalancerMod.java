package com.jessetech.recipebalancer;

import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import com.jessetech.recipebalancer.mc.io.GamePaths;
import com.jessetech.recipebalancer.mc.jei.JeiVersionNotice;
import com.jessetech.recipebalancer.mc.keybind.RbKeyMappings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * RecipeBalancer —— 产线配平自动计算器（纯客户端）。
 *
 * <p>1.12.2 的入口形态：
 * <ul>
 *   <li>{@code @Mod(clientSideOnly = true)}：专用服务端不加载本模组；</li>
 *   <li>初始化分 {@code FMLPreInitializationEvent}（配置 / 求解库目录）与
 *       {@code FMLInitializationEvent}（键位 / 事件订阅）；</li>
 *   <li>事件总线只有 {@link MinecraftForge#EVENT_BUS} 一条（没有 1.13+ 的 mod 总线）。</li>
 * </ul>
 *
 * <p>{@code dependencies = "after:jei@[4.7.8,)"}：JEI 是<b>可选</b>前置，
 * 低于 4.8.5.160 时仍能加载（缺能力只降级），首次进入世界会在聊天栏提示升级。
 */
@Mod(modid = RecipeBalancerMod.MODID,
        name = "RecipeBalancer",
        version = RecipeBalancerMod.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        clientSideOnly = true,
        dependencies = "after:jei@[4.7.8,)")
public final class RecipeBalancerMod {

    public static final String MODID = "recipebalancer";

    /** 与 gradle.properties 的 mod_version 保持一致（1.12.2 的 @Mod 只能写常量）。 */
    public static final String VERSION = "0.3.1-1.12.2";

    /**
     * 1.12.2 的 MC / Forge 只用 log4j2，运行环境里<b>没有 slf4j</b>：
     * 用 {@code LoggerFactory} 会在类加载阶段直接
     * {@code NoClassDefFoundError: org/slf4j/LoggerFactory}。
     */
    public static final Logger LOGGER = LogManager.getLogger("RecipeBalancer");

    private static boolean ready;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 目录解析：1.12.2 没有 FMLPaths，统一由 GamePaths 收敛
        GamePaths.init(event.getModConfigurationDirectory());

        // 配置：config/recipebalancer-client.cfg（键名与其它版本线一致）
        GraphLayoutConfig.load(new File(event.getModConfigurationDirectory(),
                "recipebalancer-client.cfg"));

        // 求解库的解压目录：固定放在实例内（<gamedir>/RecipeBalancer/lib），
        // 不在系统临时目录或用户其它位置留文件。
        com.jessetech.recipebalancer.core.solve.CommonsMath3LpSolver.setLibraryDir(
                GamePaths.gameDir().resolve("RecipeBalancer").resolve("lib"));
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        RbKeyMappings.register();
        MinecraftForge.EVENT_BUS.register(new RbKeyMappings.Handler());
        MinecraftForge.EVENT_BUS.register(new JeiVersionNotice());
        // tooltip 延迟到 JEI 物品面板之后：否则配方库的悬停提示会被右侧面板盖住
        com.jessetech.recipebalancer.mc.jei.JeiTooltipOverlay.register();

        LOGGER.info("[RecipeBalancer] 初始化：快捷键 K 打开主界面");
        checkRuntimeDependencies();
        ready = true;
    }

    public static boolean ready() {
        return ready;
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
     *   <li>同一个 jar 内的 {@code .class} 是否能作为资源被看到。</li>
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
