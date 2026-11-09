package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 书签读取的实际实现（含 {@code mezz.jei.*} 引用，只在 JEI 就绪后被加载）。
 *
 * <p>{@code IBookmarkOverlay} 只暴露「鼠标下那一个」，没有「列出全部」的公开 API，
 * 所以只能反射内部书签列表。各版本的入口一直在变，这里按「方法优先、字段兜底」逐级尝试：
 * <ul>
 *   <li>9.4~9.6：{@code BookmarkList#getIngredientList(String)}；</li>
 *   <li>9.7+：同名方法改成无参 {@code getIngredientList()}（<b>参数变了，不看版本号就会漏</b>）；</li>
 *   <li>10.6+：列表类搬到 {@code mezz.jei.gui.bookmarks}，条目改为 {@code getElements()}；</li>
 *   <li>兜底：直接读 {@code bookmarksList} 字段里的 {@code IBookmark#getElement()#getTypedIngredient()}。</li>
 * </ul>
 * 全部失败时返回空表，并打一条 warn（只打一次），便于实机排查。
 */
final class JeiBookmarkReader {

    /** 书签列表对象可能挂在覆盖层的这些字段上（名字跨版本变过）。 */
    private static final String[] KNOWN_LIST_FIELDS = {
            "bookmarkList", "bookmarkListWrapper", "bookmarkListSource"
    };

    private static boolean warned;

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
                warnOnce("runtime 没有书签覆盖层");
                return keys;
            }
            Object source = findBookmarkSource(overlay);
            if (source == null) {
                warnOnce("找不到书签列表（覆盖层类 " + overlay.getClass().getName() + "）");
                return keys;
            }
            for (Object typed : typedIngredients(source)) {
                if (typed instanceof ITypedIngredient<?> ingredient) {
                    JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                    if (mapped != null && !J8.isBlank(mapped.key())) {
                        keys.add(mapped.key());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("读取书签失败: " + e);
        }
        return keys;
    }

    /** 把书签列表对象里的条目统一成 {@link ITypedIngredient}。 */
    private static List<?> typedIngredients(Object source) throws ReflectiveOperationException {
        // 9.7 起 getIngredientList 没有参数；9.4~9.6 需要一个 String，两种都试
        List<?> legacy = callList(source, "getIngredientList");
        if (legacy == null) {
            legacy = callList(source, "getIngredientList", String.class);
        }
        if (legacy != null) {
            return legacy;
        }
        // 10.6+：getElements() → List<IElement>，元素上取 getTypedIngredient()
        List<?> elements = callList(source, "getElements");
        if (elements != null) {
            return elementsToTyped(elements);
        }
        // 兜底：直接读 bookmarksList 字段里的 IBookmark
        Object bookmarks = readFieldValue(findField(source.getClass(), "bookmarksList"), source);
        if (bookmarks instanceof List<?> list) {
            List<Object> typed = new ArrayList<>(list.size());
            for (Object bookmark : list) {
                Object element = invokeNoArg(bookmark, "getElement");
                Object ingredient = element == null ? null : invokeNoArg(element, "getTypedIngredient");
                if (ingredient != null) {
                    typed.add(ingredient);
                }
            }
            return typed;
        }
        throw new NoSuchMethodException(source.getClass().getName() + " 没有可用的书签枚举入口");
    }

    private static List<?> callList(Object source, String method, Class<?>... parameterTypes) {
        try {
            Method found = source.getClass().getMethod(method, parameterTypes);
            found.setAccessible(true);
            Object[] args = new Object[parameterTypes.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = "";
            }
            Object value = found.invoke(source, args);
            return value instanceof List<?> list ? list : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static List<?> elementsToTyped(List<?> elements) {
        List<Object> typed = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (element == null) {
                continue;
            }
            if (element instanceof ITypedIngredient<?>) {
                typed.add(element);
                continue;
            }
            Object ingredient = invokeNoArg(element, "getTypedIngredient");
            if (ingredient != null) {
                typed.add(ingredient);
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

    /** 字段值能提供书签列表（getIngredientList / getElements / bookmarksList）就采用。 */
    private static Object readListField(Field field, Object owner) throws IllegalAccessException {
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        Object value = field.get(owner);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> && "bookmarksList".equals(field.getName())) {
            return value;
        }
        if (!value.getClass().getName().contains("BookmarkList")) {
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

    private static Object readFieldValue(Field field, Object owner) {
        if (field == null || owner == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method found = target.getClass().getMethod(method);
            found.setAccessible(true);
            return found.invoke(target);
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

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 书签读取不可用：{}（仅提示一次）", message);
        }
    }
}
