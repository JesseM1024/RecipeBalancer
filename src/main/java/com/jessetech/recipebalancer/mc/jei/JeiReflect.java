package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JEI 内部结构的反射锚点（1.12.2 / JEI 4.x）。
 *
 * <p>1.12.2 的 JEI 没有任何"配方页扩展点"的公开 API（{@code IIconButtonController}
 * 全版本都不存在），所以配方页按钮、书签列表只能反射内部字段。这些字段在
 * 4.7.9 ~ 4.24 之间是稳定的（已逐 jar {@code javap -p} 核实）：
 * <ul>
 *   <li>{@code mezz.jei.gui.recipes.RecipesGui#recipeLayouts}（private final List）；</li>
 *   <li>{@code mezz.jei.gui.ingredients.GuiIngredient#rect}（private final java.awt.Rectangle，
 *       比 4.8.5 才有的 {@code getRect()} 更稳）；</li>
 *   <li>{@code mezz.jei.gui.recipes.RecipeLayout#recipeWrapper}（没有公开的 getRecipe）。</li>
 * </ul>
 *
 * <p>所有结果都缓存；取不到时只告警一次并返回 null，<b>绝不抛出</b>。
 * 1.12.2 的配方页每帧重绘，逐帧做 {@code getDeclaredField} 会直接吃掉帧率。
 */
public final class JeiReflect {

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<String>());

    private JeiReflect() {
    }

    /** 按名字取类；不存在返回 null（不告警：能力探测会用到）。 */
    public static Class<?> type(String className) {
        Class<?> cached = CLASSES.get(className);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> type = Class.forName(className, false, JeiReflect.class.getClassLoader());
            CLASSES.put(className, type);
            return type;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读字段（沿父类查找，自动 setAccessible）。取不到返回 null 并只告警一次。 */
    public static Object field(Object owner, String name) {
        if (owner == null || name == null) {
            return null;
        }
        String cacheKey = owner.getClass().getName() + '#' + name;
        Field field = FIELDS.get(cacheKey);
        if (field == null) {
            if (MISSING.contains(cacheKey)) {
                return null;
            }
            field = findField(owner.getClass(), name);
            if (field == null) {
                MISSING.add(cacheKey);
                warnOnce(cacheKey, "[RecipeBalancer] JEI 内部字段不存在: " + cacheKey);
                return null;
            }
            FIELDS.put(cacheKey, field);
        }
        try {
            return field.get(owner);
        } catch (Throwable t) {
            warnOnce(cacheKey, "[RecipeBalancer] JEI 内部字段读取失败: " + cacheKey + " " + t);
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 按类型找字段（书签列表这种"字段名不稳定、类型稳定"的场景）。 */
    public static Object fieldOfType(Object owner, String typeName) {
        if (owner == null) {
            return null;
        }
        String cacheKey = owner.getClass().getName() + "|<" + typeName + ">";
        Field field = FIELDS.get(cacheKey);
        if (field == null) {
            Class<?> current = owner.getClass();
            while (current != null && field == null) {
                for (Field candidate : current.getDeclaredFields()) {
                    if (typeName.equals(candidate.getType().getName())) {
                        candidate.setAccessible(true);
                        field = candidate;
                        break;
                    }
                }
                current = current.getSuperclass();
            }
            if (field == null) {
                return null;
            }
            FIELDS.put(cacheKey, field);
        }
        try {
            return field.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调无参方法（自动 setAccessible）。取不到返回 null 并只告警一次。 */
    public static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        String cacheKey = target.getClass().getName() + "#" + method + "()";
        Method reflected = METHODS.get(cacheKey);
        if (reflected == null) {
            if (MISSING.contains(cacheKey)) {
                return null;
            }
            try {
                reflected = target.getClass().getMethod(method);
                reflected.setAccessible(true);
                METHODS.put(cacheKey, reflected);
            } catch (Throwable t) {
                MISSING.add(cacheKey);
                warnOnce(cacheKey, "[RecipeBalancer] JEI 方法不存在: " + cacheKey);
                return null;
            }
        }
        try {
            return reflected.invoke(target);
        } catch (Throwable t) {
            warnOnce(cacheKey, "[RecipeBalancer] JEI 方法调用失败: " + cacheKey + " " + t);
            return null;
        }
    }

    /** 同一 key 只告警一次（避免每帧刷屏）。 */
    public static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            RecipeBalancerMod.LOGGER.warn(message);
        }
    }

    /** JEI runtime 重建时清空方法/字段缓存（对象换了，但结构不变，字段可保留）。 */
    public static void clear() {
        MISSING.clear();
        WARNED.clear();
    }
}
