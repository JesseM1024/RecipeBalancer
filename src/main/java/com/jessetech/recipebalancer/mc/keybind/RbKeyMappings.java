package com.jessetech.recipebalancer.mc.keybind;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** 快捷键注册与打开界面（K 键，可在「按键绑定」里改）。 */
public final class RbKeyMappings {

    public static final String CATEGORY = "key.categories.recipebalancer";

    public static final KeyMapping OPEN = new KeyMapping(
            "key.recipebalancer.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private RbKeyMappings() {
    }

    /** 模组总线：注册键位。 */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    /** 游戏总线：进入世界后按键打开界面。 */
    public static void onClientTick(ClientTickEvent.Post event) {
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
