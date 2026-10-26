package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;

/**
 * 手动补发 {@link ScreenEvent.Init.Post}。
 *
 * <p>JEI 只在收到该事件时刷新 GuiProperties 缓存；窗口 resize 后 Minecraft 会重新
 * {@code Screen.init()}，但不会补发 Init.Post，于是 JEI 仍按旧尺寸绘制，表现为
 * “最大化后 JEI 位置不对，进一次二级菜单返回才正常”。
 */
public final class JeiScreenSupport {

    private JeiScreenSupport() {
    }

    public static void notifyGuiInit(Screen screen) {
        NeoForge.EVENT_BUS.post(new ScreenEvent.Init.Post(
                screen,
                new ArrayList<GuiEventListener>(screen.children()),
                listener -> {
                },
                listener -> {
                }));
    }
}
