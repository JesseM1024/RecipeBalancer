package com.jessetech.recipebalancer.mc.jei;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient（界面层入口）。
 *
 * <p>1.12.2 的 JEI 只有 <b>4.15.0.276</b> 起才有公开的 {@code IBookmarkOverlay}
 * （以及 {@code IJeiRuntime#getBookmarkOverlay()}）。更老的版本内部结构把书签面板藏成
 * {@code ILeftAreaContent} 接口，实测反射/对象图搜索都不可靠，因此<b>不再做反射兜底</b>：
 * 版本不够时返回空表，界面按钮用金橙色提示"需要 JEI 4.15.0.276"。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>：真正的实现放在
 * {@link JeiBookmarkReader}，只有能力满足时才会被加载。
 */
public final class JeiBookmarks {

    private JeiBookmarks() {
    }

    /** 当前 JEI 是否支持公开的书签读取。 */
    public static boolean supported() {
        return JeiCapabilities.hasBookmarkApi();
    }

    /** @return 去重后的 ingredient key；版本不支持或未就绪时为空表。 */
    public static Set<String> readKeys() {
        if (!supported() || !JeiRuntimeHolder.ready()) {
            return new LinkedHashSet<>();
        }
        return JeiBookmarkReader.read();
    }
}
