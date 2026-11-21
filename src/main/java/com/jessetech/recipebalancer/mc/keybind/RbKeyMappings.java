package com.jessetech.recipebalancer.mc.keybind;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * 快捷键注册与打开界面（K 键，可在「按键绑定」里改）。
 *
 * <p>1.12.2 的差异：{@code KeyBinding} 用 LWJGL2 键码（{@link Keyboard#KEY_K}），
 * 注册用 {@code net.minecraftforge.fml.client.registry.ClientRegistry}，
 * 事件订阅走 {@code MinecraftForge.EVENT_BUS.register(实例)} + {@code @SubscribeEvent}。
 */
public final class RbKeyMappings {

    public static final String CATEGORY = "key.categories.recipebalancer";

    public static final KeyBinding OPEN = new KeyBinding(
            "key.recipebalancer.open", Keyboard.KEY_K, CATEGORY);

    private RbKeyMappings() {
    }

    /** 在 {@code FMLInitializationEvent} 里调用（必须已在客户端）。 */
    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN);
    }

    /** 游戏总线：进入世界后按键打开界面。 */
    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getMinecraft();
            boolean clicked = false;
            while (OPEN.isPressed()) {
                clicked = true;
            }
            if (!clicked || minecraft.player == null || minecraft.currentScreen != null) {
                return; // 主菜单/已有界面时不抢输入；界面内按键交给界面自己处理
            }
            minecraft.displayGuiScreen(new BalancerScreen());
        }
    }
}
