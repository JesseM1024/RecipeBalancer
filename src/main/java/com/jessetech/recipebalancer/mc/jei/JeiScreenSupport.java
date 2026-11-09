package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 刷新 JEI 的 GUI 属性缓存（1.16.5 / JEI 7.x）。
 *
 * <p>JEI 只在收到 {@code GuiScreenEvent.InitGuiEvent.Post} 时更新缓存；窗口 resize 后
 * Minecraft 会重新 {@code init()} 但不补发该事件，于是 JEI 按旧尺寸覆盖绘制。这里同时：
 * <ol>
 *   <li>补发 {@code InitGuiEvent.Post}（1.16.5 的列表元素类型是 {@code Widget}）；</li>
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
            // 再延后一 tick：首次 displayGuiScreen 时 Minecraft.currentScreen 可能尚未切换
            minecraft.execute(() -> {
                if (minecraft.currentScreen != screen) {
                    return;
                }
                postInit(screen);
                refresh(screen);
            });
        }
    }

    private static void postInit(Screen screen) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Post(
                screen,
                widgets(screen),
                widget -> {
                },
                widget -> {
                }));
    }

    /** 1.16.5 的 {@code Screen#buttons} 是 protected，事件却要一份 List&lt;Widget&gt;。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Widget> widgets(Screen screen) {
        try {
            java.lang.reflect.Field field = Screen.class.getDeclaredField("buttons");
            field.setAccessible(true);
            Object value = field.get(screen);
            if (value instanceof java.util.List) {
                return new ArrayList<>((java.util.List<Widget>) value);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 拿不到就发空列表：JEI 只是刷新缓存，空列表不会崩
        }
        return new ArrayList<>();
    }

    private static void refresh(Screen screen) {
        Object runtime = JeiRuntimeHolder.get();
        if (runtime == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.currentScreen != screen) {
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
