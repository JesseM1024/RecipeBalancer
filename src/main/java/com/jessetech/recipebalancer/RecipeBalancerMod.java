package com.jessetech.recipebalancer;

import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.jei.JeiCapabilities;
import com.jessetech.recipebalancer.mc.jei.JeiHighlightOverlay;
import com.jessetech.recipebalancer.mc.keybind.RbKeyMappings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
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
 * <p>1.20.1 的 {@code @Mod} 没有 {@code dist} 参数，
 * 因此在构造器里检查 {@link FMLEnvironment#dist}：专用服务端直接跳过全部注册。
 *
 * <p>1.20.1 分支说明：本线平台（Forge 1.20.1 / NeoForge 1.20.1）包名同为
 * {@code net.minecraftforge.*}，因此同一份代码与 jar 同时适用于两种加载器；
 * 本分支暂不提供 JEI 快捷功能，进入世界时会输出一次聊天栏提示。
 */
@Mod(RecipeBalancerMod.MODID)
public final class RecipeBalancerMod {

    public static final String MODID = "recipebalancer";
    public static final Logger LOGGER = LoggerFactory.getLogger("RecipeBalancer");

    /** 进入世界后待发送的 JEI 停用提示（在首个客户端 tick 里发，确保聊天栏已就绪）。 */
    private static boolean pendingJeiNotice;

    public RecipeBalancerMod() {
        // 非客户端环境：跳过全部客户端注册（1.20.1 的 @Mod 无 dist 参数）
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        // 本世代构造器不带参数：总线/配置上下文走静态入口取。
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 显式给出事件类型：方法引用经 lambda 擦除后，泛型 addListener 在运行期可能推断不出事件类
        modBus.addListener(RbKeyMappings::register);
        MinecraftForge.EVENT_BUS.addListener(RbKeyMappings::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(RecipeBalancerMod::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(RecipeBalancerMod::onPlayerLoggingIn);
        // JEI 配方页「配方库已有」高亮：延后到屏幕渲染结束再画，避免被物品图标压住
        MinecraftForge.EVENT_BUS.addListener(JeiHighlightOverlay::onRenderPost);
        // 配置：ForgeConfigSpec + 平台自带编辑器（模组列表里的「Config」按钮）
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, GraphLayoutConfig.SPEC);
        LOGGER.info("[RecipeBalancer] 初始化：快捷键 K 打开主界面；{}",
                JeiCapabilities.describe());
        checkRuntimeDependencies();
    }

    /** 进入世界：未装 JEI 时记下待发送提示（此时聊天栏可能还没建好，延到下一个 tick 发）。 */
    private static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        pendingJeiNotice = !JeiCapabilities.installed();
    }

    /** 每 tick：先把提示补发出去，再交给快捷键处理。 */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !pendingJeiNotice) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return; // 世界还没真正进来，等下一 tick
        }
        pendingJeiNotice = false;
        LOGGER.info("[RecipeBalancer] 进入世界：未安装 JEI，提示快捷功能不可用");
        // 聊天栏带模组名前缀，便于辨认来源；正文沿用同一条翻译 key
        player.displayClientMessage(
                Lang.c("[RecipeBalancer] %s", Lang.t("JEI 未安装，快捷功能不可用")), false);
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
