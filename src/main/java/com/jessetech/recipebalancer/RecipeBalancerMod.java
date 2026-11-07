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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static final Logger LOGGER = LoggerFactory.getLogger("RecipeBalancer");

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

    /** 启动自检：求解库是否真的在运行期 classpath 上（比点「求解」时才发现更早）。 */
    private static void checkRuntimeDependencies() {
        try {
            Class.forName("org.apache.commons.math3.optim.linear.SimplexSolver");
            LOGGER.info("[RecipeBalancer] commons-math3 可用");
        } catch (Throwable t) {
            LOGGER.error("[RecipeBalancer] commons-math3 不可用，求解会失败", t);
        }
    }

}
