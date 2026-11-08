package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 刷新 JEI 的 GUI 属性缓存（1.18.2 / JEI 9.x ~ 10.x）。
 *
 * <p>JEI 只在收到 {@code ScreenEvent.Init.Post} 时更新缓存；窗口 resize 后 Minecraft
 * 会重新 {@code init()} 但不补发该事件，于是 JEI 按旧尺寸覆盖绘制。这里同时：
 * <ol>
 *   <li>补发 Init.Post（1.18.2 的类名是 {@code ScreenEvent.InitScreenEvent.Post}）；</li>
 *   <li>反射驱动 JEI 内部覆盖层的属性刷新作为兜底（各版本内部结构不同，失败只告警一次）。</li>
 * </ol>
 */
public final class JeiScreenSupport {

    /** 反射刷新失败只告警一次，避免每帧刷屏。 */
    private static boolean reflectionWarned;

    private JeiScreenSupport() {
    }

    public static void notifyGuiInit(Screen screen) {
        if (!JeiRuntimeHolder.ready()) {
            return; // 未装 JEI / runtime 未就绪：什么都不用做
        }
        postInit(screen);
        refresh(screen);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            // 再延后一 tick：首次 setScreen 时 Minecraft.screen 可能尚未切换，
            // defer 后 JEI 事件处理/属性更新都基于当前屏幕。
            minecraft.execute(() -> {
                if (minecraft.screen != screen) {
                    return;
                }
                postInit(screen);
                refresh(screen);
            });
        }
    }

    private static void postInit(Screen screen) {
        MinecraftForge.EVENT_BUS.post(new ScreenEvent.InitScreenEvent.Post(
                screen,
                new ArrayList<GuiEventListener>(screen.children()),
                listener -> {
                },
                listener -> {
                }));
    }

    private static void refresh(Screen screen) {
        Object runtime = JeiRuntimeHolder.get();
        if (runtime == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen != screen) {
            return;
        }
        refreshOverlay(call(runtime, "getIngredientListOverlay"), screen);
        refreshOverlay(call(runtime, "getBookmarkOverlay"), screen);
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method reflected = target.getClass().getMethod(method);
            reflected.setAccessible(true);
            return reflected.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void refreshOverlay(Object overlay, Screen screen) {
        if (overlay == null) {
            return;
        }
        try {
            Method getUpdater = overlay.getClass().getMethod("getScreenPropertiesUpdater");
            getUpdater.setAccessible(true);
            Object updater = getUpdater.invoke(overlay);
            if (updater == null) {
                return;
            }
            Method updateScreen = updater.getClass().getMethod("updateScreen", Screen.class);
            // GuiPropertiesCache$Updater 是包私有类，public 方法也要 setAccessible
            updateScreen.setAccessible(true);
            Object updated = updateScreen.invoke(updater, screen);
            if (updated == null) {
                return;
            }
            Method forceUpdate = updated.getClass().getMethod("forceUpdate");
            forceUpdate.setAccessible(true);
            forceUpdate.invoke(updated);
            RecipeBalancerMod.LOGGER.debug("[RecipeBalancer] JEI 属性刷新: {}x{}",
                    screen.width, screen.height);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                RecipeBalancerMod.LOGGER.warn(
                        "[RecipeBalancer] JEI 属性反射刷新失败（仅提示一次），退回 Init.Post 机制: {}",
                        e.toString());
            }
        }
    }
}
