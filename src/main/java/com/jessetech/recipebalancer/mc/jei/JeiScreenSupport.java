package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 刷新 JEI 的 GUI 属性缓存。
 *
 * <p>JEI 只在收到 {@code ScreenEvent.Init.Post} 时更新缓存；窗口 resize 后 Minecraft
 * 会重新 {@code init()} 但不补发该事件，于是 JEI 按旧尺寸覆盖绘制。这里同时：
 * <ol>
 *   <li>补发 Init.Post；</li>
 *   <li>直接反射驱动 JEI 内部覆盖层的 {@code getScreenPropertiesUpdater().updateScreen().forceUpdate()}
 *       （JEI 19.x 无公开刷新 API，仅作为事件不生效时的兜底）。</li>
 * </ol>
 */
public final class JeiScreenSupport {

    private JeiScreenSupport() {
    }

    public static void notifyGuiInit(Screen screen) {
        postInit(screen);
        refresh(screen);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            // 再延后一 tick：首次 setScreen 时 init 内 Minecraft.screen 可能尚未切换，
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
        NeoForge.EVENT_BUS.post(new ScreenEvent.Init.Post(
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
            // JEI 版本差异/模块访问受限时退回事件机制，仅告警一次便于排查
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 属性反射刷新失败，退回 Init.Post", e);
        }
    }
}
