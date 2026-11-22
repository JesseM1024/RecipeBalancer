package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * 刷新 JEI 的 GUI 属性缓存。
 *
 * <p><b>为什么不再补发 {@code ScreenEvent.Init.Post}</b>：NeoForge 会在
 * {@code Screen#init} 返回之后自动发一次该事件；本模组此前在 {@code init()} 内部手动再发一次，
 * 等于让 JEI 与所有第三方 GUI 处理器对同一屏幕初始化两遍（而且我们传的 listener  adder 是空实现，
 * 处理器想追加控件会被静默丢弃）。这是本模组对 JEI 全局状态唯一有副作用的操作，
 * 症状正是「JEI 面板/查询偶发失效」。现在只保留「反射强制刷新属性」这一条兜底路径。
 *
 * <p>反射失败只告警一次：JEI 内部结构在版本间会变，每开一次界面刷一条 warn 只会淹没日志。
 */
public final class JeiScreenSupport {

    /** 反射链不可用时只提示一次，之后静默走 NeoForge 的自动事件。 */
    private static boolean warned;

    private JeiScreenSupport() {
    }

    public static void notifyGuiInit(Screen screen) {
        refresh(screen);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            // 再延后一 tick：首次 setScreen 时 Minecraft.screen 可能尚未切换，
            // defer 后属性更新都基于当前屏幕。
            minecraft.execute(() -> {
                if (minecraft.screen != screen) {
                    return;
                }
                refresh(screen);
            });
        }
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

    private static Object call(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            return method == null ? null : method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void refreshOverlay(Object overlay, Screen screen) {
        if (overlay == null) {
            return;
        }
        try {
            Method getUpdater = findMethod(overlay.getClass(), "getScreenPropertiesUpdater", 0);
            if (getUpdater == null) {
                // 该 JEI 版本没有这个内部结构：直接放弃，不刷日志
                return;
            }
            Object updater = getUpdater.invoke(overlay);
            if (updater == null) {
                return;
            }
            Method updateScreen = findMethod(updater.getClass(), "updateScreen", 1);
            if (updateScreen == null) {
                return;
            }
            Object updated = updateScreen.invoke(updater, screen);
            if (updated == null) {
                return;
            }
            Method forceUpdate = findMethod(updated.getClass(), "forceUpdate", 0);
            if (forceUpdate == null) {
                return;
            }
            forceUpdate.invoke(updated);
            RecipeBalancerMod.LOGGER.debug("[RecipeBalancer] JEI 属性刷新: {}x{}",
                    screen.width, screen.height);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                RecipeBalancerMod.LOGGER.warn(
                        "[RecipeBalancer] JEI 属性反射刷新不可用，已停用后续尝试（退回事件机制）", e);
            }
        }
    }

    /**
     * 按名字 + 参数个数找方法，允许包私有方法。
     *
     * <p>JEI 的 {@code GuiPropertiesCache$Updater} 这类内部类，方法本身是 public 但类不可见，
     * {@code Class#getMethod} 在部分构建上取不到，因此统一用 {@code getDeclaredMethods} 遍历。
     */
    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                    continue;
                }
                if (!method.trySetAccessible()) {
                    return null;
                }
                return method;
            }
        }
        return null;
    }
}
