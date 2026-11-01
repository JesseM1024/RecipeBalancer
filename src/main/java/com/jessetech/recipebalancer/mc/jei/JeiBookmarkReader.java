package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 书签读取的实际实现（含 {@code mezz.jei.*} 引用，只在 JEI 就绪后被加载）。
 *
 * <p>15.x 的 {@code IBookmarkOverlay} 只暴露「鼠标下那一个」，没有「列出全部」的 API
 * （较新版本加的 {@code IBookmarkManager} 也只有 contains/add/remove），所以只能反射内部
 * 覆盖层的字段列表。字段名跨版本会变，这里按结构探测：
 * 先试已知名称，再退化为「找一个能调 {@code getElements()} 的字段」；
 * 都失败就返回空表（界面显示「JEI 书签为空」），不影响其它功能。
 */
final class JeiBookmarkReader {

    private static final String[] KNOWN_LIST_FIELDS = {"bookmarkList", "bookmarkListWrapper"};

    private JeiBookmarkReader() {
    }

    static Set<String> read() {
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
            Object elements = findElements(overlay);
            if (!(elements instanceof List<?> list)) {
                return keys;
            }
            Method getTyped = null;
            for (Object element : list) {
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

    /** 按「已知字段名 → 结构探测」两级策略找到书签列表对象。 */
    private static Object findElements(Object overlay) throws IllegalAccessException {
        for (String name : KNOWN_LIST_FIELDS) {
            Object value = readList(findField(overlay.getClass(), name), overlay);
            if (value != null) {
                return value;
            }
        }
        for (Class<?> current = overlay.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Object value = readList(field, overlay);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 字段值若带无参 {@code getElements()} 且返回 List，则视为书签列表。 */
    private static Object readList(Field field, Object owner) throws IllegalAccessException {
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        Object value = field.get(owner);
        if (value == null) {
            return null;
        }
        try {
            Method getElements = value.getClass().getMethod("getElements");
            getElements.setAccessible(true);
            Object elements = getElements.invoke(value);
            return elements instanceof List<?> ? elements : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
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
