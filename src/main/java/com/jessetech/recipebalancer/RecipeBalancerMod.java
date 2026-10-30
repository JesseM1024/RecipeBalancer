package com.jessetech.recipebalancer;

import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.keybind.RbKeyMappings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecipeBalancer —— 产线配平自动计算器（纯客户端）。
 *
 * <p>{@code dist = Dist.CLIENT}：专用服务端直接跳过本模组；这里可以安全地引用
 * {@code net.minecraft.client.*}。
 */
@Mod(value = RecipeBalancerMod.MODID, dist = Dist.CLIENT)
public final class RecipeBalancerMod {

    public static final String MODID = "recipebalancer";
    public static final Logger LOGGER = LoggerFactory.getLogger("RecipeBalancer");
    /** 低版本 JEI 的提示只发一次。 */
    private static boolean jeiNoticeSent;

    public RecipeBalancerMod(IEventBus modBus, ModContainer container) {
        // 显式给出事件类型：方法引用经 lambda 擦除后，泛型 addListener 在运行期可能推断不出事件类
        modBus.addListener(RegisterKeyMappingsEvent.class, RbKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RbKeyMappings::onClientTick);
        // 配置：ModConfigSpec + NeoForge 自带编辑器（模组列表里的「Config」按钮）
        container.registerConfig(ModConfig.Type.CLIENT, GraphLayoutConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(FMLClientSetupEvent.class, RecipeBalancerMod::onClientSetup);
        LOGGER.info("[RecipeBalancer] M2 初始化：快捷键 K 打开原生主界面");
        checkRuntimeDependencies();
    }


    private static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                RecipeBalancerMod::onPlayerJoin);
    }

    /**
     * 首次进入世界时检查 JEI 能力：低于 19.39 没有配方页按钮 API，
     * 也没有「编码界面悬停物质查配方/用途」所需的接口，直接在聊天栏提示一次。
     */
    private static void onPlayerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (jeiNoticeSent || ModList.get() == null || !ModList.get().isLoaded("jei")) {
            return;
        }
        if (hasJeiButtonApi()) {
            return;
        }
        jeiNoticeSent = true;
        LOGGER.info("[RecipeBalancer] JEI 版本低于 19.39，已提示玩家升级");
        // 前缀标明来源，避免玩家以为是整合包/其它模组的提示
        event.getPlayer().displayClientMessage(
                Component.literal("[Recipe Balancer] ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Lang.c(
                                "当前 JEI 版本过低（需要 19.39 及以上），无法使用配方页的「一键添加配方」按钮，"
                                        + "也无法在编码界面悬停物质查询配方与用途。请升级 JEI。")),
                false);
    }

    /** JEI 是否有配方页按钮 API（19.39 起才有）。 */
    private static boolean hasJeiButtonApi() {
        try {
            Class.forName("mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory");
            return true;
        } catch (Throwable t) {
            return false;
        }
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
