package com.jessetech.recipebalancer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecipeBalancer —— 产线配平自动计算器（纯客户端）。
 *
 * 【客户端专用】靠 @Mod 的 dist 参数声明。加载器在专用服务端上会直接跳过这个模组。
 *
 * 本会话（架构阶段）只做环境初始化验证：本类是可编译的最小骨架，
 * 不含任何业务逻辑。快捷键 / 求解 / 渲染等见 docs/ARCHITECTURE.md。
 */
@Mod(value = RecipeBalancerMod.MODID, dist = Dist.CLIENT)
public final class RecipeBalancerMod {

    public static final String MODID = "recipebalancer";
    public static final Logger LOGGER = LoggerFactory.getLogger("RecipeBalancer");

    public RecipeBalancerMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("[RecipeBalancer] skeleton constructed (architecture phase, no features yet)");
    }
}
