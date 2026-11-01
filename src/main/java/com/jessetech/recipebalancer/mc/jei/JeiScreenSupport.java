package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.screens.Screen;

/**
 * 刷新 JEI 的 GUI 属性缓存（1.20.1 分支：空实现）。
 *
 * <p>停用 JEI 后没有任何覆盖层需要刷新，方法保留为空壳，
 * 让界面层的调用点无需分支判断。恢复 JEI 时从 main 分支取回完整实现。
 */
public final class JeiScreenSupport {

    private JeiScreenSupport() {
    }

    /** 界面 {@code init()} 后调用：JEI 停用时什么也不做。 */
    public static void notifyGuiInit(Screen screen) {
        // JEI 已停用：无覆盖层需要重新定位
    }
}
