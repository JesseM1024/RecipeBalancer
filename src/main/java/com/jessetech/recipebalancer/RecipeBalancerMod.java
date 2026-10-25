package com.jessetech.recipebalancer;

import com.jessetech.recipebalancer.mc.keybind.RbKeyMappings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
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

    public RecipeBalancerMod(IEventBus modBus, ModContainer container) {
        // 显式给出事件类型：方法引用经 lambda 擦除后，泛型 addListener 在运行期可能推断不出事件类
        modBus.addListener(RegisterKeyMappingsEvent.class, RbKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RbKeyMappings::onClientTick);
        LOGGER.info("[RecipeBalancer] M2 初始化：快捷键 K 打开原生主界面");
    }
}
