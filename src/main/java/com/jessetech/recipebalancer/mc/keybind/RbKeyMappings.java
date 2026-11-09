package com.jessetech.recipebalancer.mc.keybind;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 快捷键注册与打开界面（K 键，可在「按键绑定」里改）。
 *
 * <p>1.16.5 的差异：{@code KeyMapping} 在 {@code net.minecraft.client.settings}、
 * 类型用 {@link InputMappings}、注册用 {@code net.minecraftforge.fml.client.registry.ClientRegistry}，
 * 切屏方法是 {@code Minecraft#displayGuiScreen}，键位读取用 {@code isPressed()}
 * （它自带「读完即清」的语义，等价于高版本的 {@code consumeClick()}）。
 */
public final class RbKeyMappings {

    public static final String CATEGORY = "key.categories.recipebalancer";

    public static final KeyBinding OPEN = new KeyBinding(
            "key.recipebalancer.open", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private RbKeyMappings() {
    }

    /** 模组总线：注册键位（1.16.5 没有 RegisterKeyMappingsEvent）。 */
    public static void onClientSetup(FMLClientSetupEvent event) {
        // FMLClientSetupEvent 可能在其它线程派发，注册必须在主线程做
        event.enqueueWork(() -> ClientRegistry.registerKeyBinding(OPEN));
    }

    /** 游戏总线：进入世界后按键打开界面。 */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean clicked = false;
        while (OPEN.isPressed()) {
            clicked = true;
        }
        if (!clicked || minecraft.player == null || minecraft.currentScreen != null) {
            return; // 主菜单/已有界面时不抢输入；Screen 内按键交给 Screen 自己处理
        }
        minecraft.displayGuiScreen(new BalancerScreen());
    }
}
