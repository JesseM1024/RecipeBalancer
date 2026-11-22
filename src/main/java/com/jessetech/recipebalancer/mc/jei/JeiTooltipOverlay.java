package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 把 RB 的 tooltip 延迟到 JEI 物品面板之后绘制（1.12.2 / JEI 4.x）。
 *
 * <p>JEI 在 {@code GuiScreenEvent.DrawScreenEvent.Post} 里画右侧物品面板，
 * RB 的 tooltip 原本在 {@code drawScreen} 内部绘制，于是被面板整个盖住。
 * 这里用 {@link EventPriority#LOWEST} 监听同一个事件，保证在 JEI（默认优先级）之后画。
 *
 * <p>代价：拖拽跟随图标 / 目标高亮仍然在 {@code drawScreen} 收尾阶段绘制，
 * 会盖住 tooltip 的一小块（16×16 区域），这是刻意保留的取舍。
 */
public final class JeiTooltipOverlay {

    private static boolean registered;

    private JeiTooltipOverlay() {
    }

    /** 幂等注册；由 {@code RecipeBalancerMod} 在 init 阶段调用。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(new Handler());
    }

    private static final class Handler {

        private Handler() {
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (event.getGui() instanceof BalancerScreen) {
                ((BalancerScreen) event.getGui()).drawDeferredTooltips();
            }
        }
    }
}
