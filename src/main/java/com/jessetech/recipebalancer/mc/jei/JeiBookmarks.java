package com.jessetech.recipebalancer.mc.jei;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient（界面层入口）。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>：真正的反射实现放在
 * {@link JeiBookmarkReader}，只有 JEI 就绪时才会被加载。这样未装 JEI 的实例
 * 调用本书签入口也不会触发 {@code NoClassDefFoundError}。
 */
public final class JeiBookmarks {

    private JeiBookmarks() {
    }

    /** @return 去重后的 ingredient key（item 为注册名，非物品为 ingredient key）。 */
    public static Set<String> readKeys() {
        if (!JeiRuntimeHolder.ready()) {
            return new LinkedHashSet<>();
        }
        return JeiBookmarkReader.read();
    }
}
