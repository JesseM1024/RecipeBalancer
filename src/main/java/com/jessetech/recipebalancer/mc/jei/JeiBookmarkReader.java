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
 * <p>{@code IBookmarkOverlay} 只暴露「鼠标下那一个」，没有「列出全部」的公开 API，
 * 所以只能反射内部书签列表：
 * <ul>
 *   <li>9.x：覆盖层持有 {@code mezz.jei.bookmarks.BookmarkList}，
 *       用 {@code getIngredientList(String)} 拿 {@code List<ITypedIngredient<?>>}；</li>
 *   <li>10.x：列表类改名 {@code mezz.jei.gui.bookmarks.BookmarkList}，
 *       用 {@code getElements()} 拿 {@code List<IElement<?>>}，元素上再取 {@code getTypedIngredient()}。</li>
 * </ul>
 * 字段名跨版本会变，这里按结构探测；都失败就返回空表（界面提示「JEI 书签为空」）。
 */
final class JeiBookmarkReader {

    private static final String[] KNOWN_LIST_FIELDS = {
            "bookmarkList", "bookmarkListWrapper", "bookmarkListSource"
    };

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
            Object source = findBookmarkSource(overlay);
            if (source == null) {
                return keys;
            }
            for (Object typed : typedIngredients(source)) {
                if (typed instanceof ITypedIngredient<?> ingredient) {
                    JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                    if (mapped != null && !mapped.key().isBlank()) {
                        keys.add(mapped.key());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // JEI 内部结构变了就当没有书签
        }
        return keys;
    }

    /** 把书签列表对象里的条目统一成 {@link ITypedIngredient}。 */
    private static List<?> typedIngredients(Object source) throws ReflectiveOperationException {
        try {
            Method getIngredientList = source.getClass().getMethod("getIngredientList", String.class);
            getIngredientList.setAccessible(true);
            Object value = getIngredientList.invoke(source, "");
            if (value instanceof List<?> list) {
                return list;
            }
        } catch (NoSuchMethodException ignored) {
            // 10.x 没有这个方法，往下走 getElements
        }
        Method getElements = source.getClass().getMethod("getElements");
        getElements.setAccessible(true);
        Object elements = getElements.invoke(source);
        if (!(elements instanceof List<?> list)) {
            return List.of();
        }
        // 10.x 的条目是 IElement：取 getTypedIngredient()；9.x 已经是 ITypedIngredient
        java.util.List<Object> typed = new java.util.ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof ITypedIngredient<?>) {
                typed.add(element);
                continue;
            }
            if (element == null) {
                continue;
            }
            try {
                Method getTyped = element.getClass().getMethod("getTypedIngredient");
                getTyped.setAccessible(true);
                typed.add(getTyped.invoke(element));
            } catch (NoSuchMethodException ignored) {
                // 认不出来的条目直接跳过
            }
        }
        return typed;
    }

    /** 按「已知字段名 → 结构探测」两级策略找到书签列表对象。 */
    private static Object findBookmarkSource(Object overlay) throws IllegalAccessException {
        for (String name : KNOWN_LIST_FIELDS) {
            Object value = readListField(findField(overlay.getClass(), name), overlay);
            if (value != null) {
                return value;
            }
        }
        for (Class<?> current = overlay.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Object value = readListField(field, overlay);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 字段值能提供书签列表（{@code getIngredientList} 或 {@code getElements}）就采用。 */
    private static Object readListField(Field field, Object owner) throws IllegalAccessException {
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        Object value = field.get(owner);
        if (value == null) {
            return null;
        }
        String name = value.getClass().getName();
        if (!name.contains("BookmarkList")) {
            return null;
        }
        for (Method method : value.getClass().getMethods()) {
            String methodName = method.getName();
            if ("getIngredientList".equals(methodName) || "getElements".equals(methodName)) {
                return value;
            }
        }
        return null;
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
