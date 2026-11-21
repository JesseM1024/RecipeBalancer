package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取 JEI 右侧书签里的全部 ingredient（1.12.2 / JEI 4.x）。
 *
 * <p>三级降级（逐 jar 核实过的版本边界）：
 * <ol>
 *   <li>4.15+：公开 API {@code IJeiRuntime#getBookmarkOverlay()} → 反射
 *       {@code BookmarkOverlay#bookmarkList}（按字段类型找） → {@code BookmarkList#getIngredientList()}；</li>
 *   <li>4.13 / 4.14：{@code getBookmarkOverlay()} 可能不可用 → 在 runtime 对象里按类型找
 *       {@code BookmarkOverlay} 字段，再走同上流程；</li>
 *   <li>更老：返回空表并只告警一次。</li>
 * </ol>
 *
 * <p>结构不对（JEI 改了内部实现）时返回空表，<b>绝不影响</b>界面与求解。
 * 本类不 import 任何 {@code mezz.jei.*} 类型，全部走反射。
 */
public final class JeiBookmarkReader {

    private static final String OVERLAY_TYPE = "mezz.jei.gui.overlay.bookmarks.BookmarkOverlay";
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
            Object overlay = overlay(runtime);
            if (overlay == null) {
                return keys;
            }
            Object list = JeiReflect.fieldOfType(overlay, LIST_TYPE);
            if (list == null) {
                // 字段类型可能被子类化，退一步按类名后缀扫描
                list = fieldOfTypeName(overlay, "BookmarkList");
            }
            if (list == null) {
                return keys;
            }
            Object elements = ingredientList(list);
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

    /** 4.15+ 走公开方法；更老的版本在 runtime 里按类型找字段。 */
    private static Object overlay(Object runtime) {
        Object overlay = JeiReflect.call(runtime, "getBookmarkOverlay");
        if (overlay != null) {
            return overlay;
        }
        Object byType = JeiReflect.fieldOfType(runtime, OVERLAY_TYPE);
        if (byType != null) {
            return byType;
        }
        return fieldOfTypeName(runtime, "BookmarkOverlay");
    }

    /** {@code getIngredientList()} 在 4.16+ 无参；更老的版本可能带一个 String 参数。 */
    private static Object ingredientList(Object list) {
        Object elements = JeiReflect.call(list, "getIngredientList");
        if (elements != null) {
            return elements;
        }
        try {
            java.lang.reflect.Method method = list.getClass().getMethod("getIngredientList", String.class);
            method.setAccessible(true);
            return method.invoke(list, "");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按「类名以给定后缀结尾」找字段（字段被声明成接口/父类时的兜底）。 */
    private static Object fieldOfTypeName(Object owner, String simpleNameSuffix) {
        Class<?> type = owner.getClass();
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType().getSimpleName().endsWith(simpleNameSuffix)) {
                    try {
                        field.setAccessible(true);
                        return field.get(owner);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
