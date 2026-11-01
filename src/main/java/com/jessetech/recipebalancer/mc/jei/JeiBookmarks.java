package com.jessetech.recipebalancer.mc.jei;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取 JEI 书签（1.20.1 分支：恒为空表）。
 *
 * <p>界面「从书签导入」按钮在空表时会提示书签为空；JEI 停用的具体原因由
 * {@link JeiRuntimeHolder#installed()} 判断后另行提示。恢复 JEI 时从 main 分支取回完整实现。
 */
public final class JeiBookmarks {

    private JeiBookmarks() {
    }

    /** @return 去重后的 ingredient key；JEI 停用时为空表。 */
    public static Set<String> readKeys() {
        return new LinkedHashSet<>();
    }
}
