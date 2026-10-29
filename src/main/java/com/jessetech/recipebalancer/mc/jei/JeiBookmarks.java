package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient。
 *
 * <p>JEI 没有公开「列出全部书签」的 API（{@code IBookmarkOverlay} 只给了鼠标下的那一个），
 * 这里反射内部 {@code BookmarkOverlay.bookmarkList#getElements()}；版本差异导致读不到时返回空表。
 */
public final class JeiBookmarks {

    private JeiBookmarks() {
    }

    /** @return 去重后的 ingredient key（item 为注册名，非物品为 ingredient key）。 */
    public static Set<String> readKeys() {
        Set<String> keys = new LinkedHashSet<>();
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return keys;
        }
        try {
            Object overlay = runtime.getBookmarkOverlay();
            if (overlay == null) {
                return keys;
            }
            Field field = findField(overlay.getClass(), "bookmarkList");
            if (field == null) {
                return keys;
            }
            field.setAccessible(true);
            Object list = field.get(overlay);
            if (list == null) {
                return keys;
            }
            Method getElements = list.getClass().getMethod("getElements");
            if (!(getElements.invoke(list) instanceof List<?> elements)) {
                return keys;
            }
            Method getTyped = null;
            for (Object element : elements) {
                if (element == null) {
                    continue;
                }
                if (getTyped == null) {
                    getTyped = element.getClass().getMethod("getTypedIngredient");
                    getTyped.setAccessible(true);
                }
                if (!(getTyped.invoke(element) instanceof ITypedIngredient<?> typed)) {
                    continue;
                }
                JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(typed);
                if (mapped != null && !mapped.key().isBlank()) {
                    keys.add(mapped.key());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JEI 内部结构变了就当没有书签
        }
        return keys;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            }
        }
        return null;
    }
}
