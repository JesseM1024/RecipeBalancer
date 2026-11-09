package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import mezz.jei.bookmarks.BookmarkList;
import mezz.jei.gui.ingredients.IIngredientListElement;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient（1.16.5 / JEI 7.x）。
 *
 * <p>7.x 的公开 API 只有 {@code IBookmarkOverlay#getIngredientUnderMouse()}，
 * 拿不到「全部书签」，只能反射内部字段：
 * {@code BookmarkOverlay#bookmarkList} → {@code BookmarkList#getIngredientList(String)}
 * → 每个 {@code IIngredientListElement#getIngredient()}。
 *
 * <p>结构不对（JEI 改了内部实现）时返回空表并只告警一次，<b>绝不影响</b>界面与求解。
 */
public final class JeiBookmarkReader {

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
            Object overlay = runtime.getClass().getMethod("getBookmarkOverlay").invoke(runtime);
            if (!(overlay instanceof BookmarkOverlay)) {
                return keys;
            }
            BookmarkList list = bookmarkList((BookmarkOverlay) overlay);
            if (list == null) {
                return keys;
            }
            List<IIngredientListElement<?>> elements = list.getIngredientList("");
            if (elements == null) {
                return keys;
            }
            for (IIngredientListElement<?> element : elements) {
                if (element == null) {
                    continue;
                }
                JeiIngredientMapper.Mapped mapped =
                        JeiIngredientMapper.of(element.getIngredient());
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

    private static BookmarkList bookmarkList(BookmarkOverlay overlay) {
        Class<?> type = overlay.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (BookmarkList.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (BookmarkList) field.get(overlay);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return null;
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
