package com.jessetech.recipebalancer.mc.keybind;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** 快捷键注册与打开界面（K 键，可在「按键绑定」里改）。 */
public final class RbKeyMappings {

    public static final String CATEGORY = "key.categories.recipebalancer";

    public static final KeyMapping OPEN = new KeyMapping(
            "key.recipebalancer.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private RbKeyMappings() {
    }

    /**
     * 模组总线：注册键位。
     *
     * <p>1.18.2 没有 {@code RegisterKeyMappingsEvent}（1.19 才引入），
     * 只能在 {@link FMLClientSetupEvent} 里用 {@link ClientRegistry} 注册。
     */
    public static void onClientSetup(FMLClientSetupEvent event) {
        // FMLClientSetupEvent 可能在其它线程派发，注册必须在主线程做
        event.enqueueWork(() -> ClientRegistry.registerKeyBinding(OPEN));
    }

    /** 游戏总线：进入世界后按键打开界面（1.18.2 的 TickEvent 用 phase 区分 start/end）。 */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean clicked = false;
        while (OPEN.consumeClick()) {
            clicked = true;
        }
        if (!clicked || minecraft.player == null || minecraft.screen != null) {
            return; // 主菜单/已有界面时不抢输入；Screen 内按键交给 Screen 自己处理
        }
        minecraft.setScreen(new BalancerScreen());
    }
}
