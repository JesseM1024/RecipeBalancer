package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient（仅 JEI 4.15+）。
 *
 * <p>只保留一条路径：{@code IJeiRuntime#getBookmarkOverlay()}（4.15.0.276 起的公开方法）
 * → 反射 {@code BookmarkOverlay#bookmarkList} → {@code BookmarkList#getIngredientList()}。
 *
 * <p><b>不再做对象图搜索/按类型广搜</b>：4.13/4.14 把书签面板藏成 {@code ILeftAreaContent}
 * 接口，实测不可靠，索性按版本判能力——低于 4.15.0.276 时界面直接提示需要升级，
 * 按钮文字用金橙色标出，不浪费一次失败的反射。
 *
 * <p>本类不 import 任何 {@code mezz.jei.*} 类型；调用方（{@link JeiBookmarks}）
 * 已先做能力判断，因此进到这里时版本一定够。
 */
public final class JeiBookmarkReader {

    private static final String LIST_TYPE = "mezz.jei.bookmarks.BookmarkList";

    /** 反射失败只告警一次，避免每次打开物料清单都刷屏。 */
    private static boolean warned;

    private JeiBookmarkReader() {
    }

    /** @return 去重后的 ingredient key（物品为注册名，非物品为 ingredient key）。 */
    public static Set<String> read() {
        Set<String> keys = new LinkedHashSet<>();
        Object runtime = JeiRuntimeHolder.get();
        if (runtime == null) {
            return keys;
        }
        try {
            Object overlay = JeiReflect.call(runtime, "getBookmarkOverlay");
            if (overlay == null) {
                return keys;
            }
            Object list = JeiReflect.fieldOfType(overlay, LIST_TYPE);
            if (list == null) {
                return keys;
            }
            Object elements = JeiReflect.call(list, "getIngredientList");
            if (!(elements instanceof Collection)) {
                return keys;
            }
            for (Object element : (Collection<?>) elements) {
                if (element == null) {
                    continue;
                }
                Object ingredient = JeiReflect.call(element, "getIngredient");
                if (ingredient == null) {
                    continue;
                }
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                if (mapped != null) {
                    keys.add(mapped.key());
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                RecipeBalancerMod.LOGGER.warn(
                        "[RecipeBalancer] JEI 书签读取失败（仅提示一次），本功能降级: {}",
                        t.toString());
            }
        }
        return keys;
    }
}
