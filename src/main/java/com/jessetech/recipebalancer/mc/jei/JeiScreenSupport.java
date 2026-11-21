package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 刷新 JEI 的 GUI 属性缓存（1.12.2 / JEI 4.x）。
 *
 * <p>JEI 只在收到 {@code GuiScreenEvent.InitGuiEvent.Post} 时更新缓存；窗口 resize 后
 * Minecraft 会重新 {@code initGui()} 但不补发该事件，于是 JEI 按旧尺寸覆盖绘制。这里同时：
 * <ol>
 *   <li>补发 {@code InitGuiEvent.Post}（1.12.2 的构造器是 {@code (GuiScreen, List<GuiButton>)}）；</li>
 *   <li>反射驱动 JEI 内部覆盖层的属性刷新作为兜底（各版本内部结构不同，失败只告警一次）。</li>
 * </ol>
 */
public final class JeiScreenSupport {

    /** 反射刷新失败只告警一次，避免每帧刷屏。 */
    private static boolean reflectionWarned;

    private JeiScreenSupport() {
    }

    public static void notifyGuiInit(GuiScreen screen) {
        if (screen == null || !JeiRuntimeHolder.ready()) {
            return; // 未装 JEI / runtime 未就绪：什么都不用做
        }
        postInit(screen);
        refresh(screen);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null) {
            // 再延后一 tick：首次 displayGuiScreen 时 currentScreen 可能尚未切换
            minecraft.addScheduledTask(() -> {
                if (minecraft.currentScreen != screen) {
                    return;
                }
                postInit(screen);
                refresh(screen);
            });
        }
    }

    private static void postInit(GuiScreen screen) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Post(screen, buttons(screen)));
    }

    /** 1.12.2 的 {@code GuiScreen#buttonList} 是 protected，事件却要一份列表。 */
    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttons(GuiScreen screen) {
        try {
            Field field = GuiScreen.class.getDeclaredField("buttonList");
            field.setAccessible(true);
            Object value = field.get(screen);
            if (value instanceof List) {
                return new ArrayList<>((List<GuiButton>) value);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 拿不到就发空列表：JEI 只是刷新缓存，空列表不会崩
        }
        return new ArrayList<>();
    }

    /**
     * JEI 4.x 的覆盖层里<b>没有</b> 7.x 的 {@code getScreenPropertiesUpdater()}，
     * 属性缓存完全由 {@code GuiScreenEvent.InitGuiEvent.Post} 驱动 —— 也就是上面的
     * {@link #postInit}。这里保留空实现，避免每个界面刷一条 NoSuchMethodException 警告。
     */
    private static void refresh(GuiScreen screen) {
        // 空实现：见方法注释
    }
}
