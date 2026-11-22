package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.time.TimeInferenceRules;
import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 JEI 的原始配方对象里推断「配方原始耗时」（tick），仅供编码区预填参考。
 *
 * <p>1.18.2 版本要点：
 * <ul>
 *   <li>JEI 9.x 的 {@code IRecipeCategory} 没有 {@code getRecipeType()}，类别 uid 只能取 {@code getUid()}；</li>
 *   <li>没有 {@code RecipeHolder}，配方对象就是本体，不需要解包；</li>
 *   <li>最后一级的机器基础耗时由 {@link JeiMachineTimes} 现场读取，本类不持有任何类别数值表。</li>
 * </ul>
 *
 * <p>任何异常（含 {@link LinkageError}）都退化为「没有参考值」，由调用方保持默认耗时。
 */
public final class JeiRecipeTime {

    /** 推断来源，日志与界面据此说明这个数字从哪来。 */
    public enum Origin {
        /** 原版烹饪配方的 {@code getCookingTime()}。 */
        VANILLA_COOKING,
        /** 已知模组 / 通用探测命中的公开方法。 */
        MOD_API,
        /** 通用探测命中的字段。 */
        MOD_FIELD,
        /** 配方对象上没有时间，运行时读机器实体的基础耗时（Mekanism / AE2 这类）。 */
        MACHINE_BASE
    }

    /** @param ticks 推断出的耗时（tick），保证 {@code 0 < ticks <= MAX_TICKS} */
    public record Result(int ticks, Origin origin) {
    }

    private record ResolveKey(Class<?> type, String name) {
    }

    private static final Map<ResolveKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResolveKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private JeiRecipeTime() {
    }

    /**
     * 推断耗时。永远不会抛异常，读不到返回 {@link Optional#empty()}。
     *
     * @param recipe   JEI 当前展示的原始配方对象，可为 null
     * @param category 配方类别，可为 null；用于取 uid 与查询催化剂机器
     */
    public static Optional<Result> infer(@Nullable Object recipe, @Nullable IRecipeCategory<?> category) {
        if (recipe == null || !GraphLayoutConfig.prefillRecipeTime()) {
            return Optional.empty();
        }
        try {
            int vanilla = vanillaTicks(recipe);
            if (vanilla > 0) {
                return Optional.of(new Result(vanilla, Origin.VANILLA_COOKING));
            }
            int api = modApiTicks(recipe);
            if (api > 0) {
                return Optional.of(new Result(api, Origin.MOD_API));
            }
            if (GraphLayoutConfig.prefillRecipeTimeReflection()) {
                int generic = genericTicks(recipe);
                if (generic > 0) {
                    return Optional.of(new Result(generic, Origin.MOD_API));
                }
                int field = fieldTicks(recipe);
                if (field > 0) {
                    return Optional.of(new Result(field, Origin.MOD_FIELD));
                }
            }
            // 最后一步：配方上确实没有时间（Mekanism / AE2 这类），
            // 运行时找到该类别的机器实体并读它自己的基础耗时；仍然读不到就放弃
            if (GraphLayoutConfig.prefillMachineBaseTime()) {
                int machine = JeiMachineTimes.ticksFor(category, categoryUid(category));
                if (machine > 0) {
                    return Optional.of(new Result(machine, Origin.MACHINE_BASE));
                }
            }
            return Optional.empty();
        } catch (RuntimeException | LinkageError e) {
            RecipeBalancerMod.LOGGER.debug("[RecipeBalancer] 耗时推断失败：{}",
                    recipe.getClass().getName(), e);
            return Optional.empty();
        }
    }

    /** 配方类型 uid；9.x 只有 {@code getUid()}，拿不到时返回空串。 */
    public static String categoryUid(@Nullable IRecipeCategory<?> category) {
        try {
            Object uid = category == null ? null : category.getUid();
            return uid == null ? "" : uid.toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * 从 JEI 的布局对象上取原始配方。
     *
     * <p>9.x / 10.x 的 {@code IRecipeLayoutDrawable} 接口没有 {@code getRecipe()}，
     * 但内部实现 {@code mezz.jei.gui.recipes.RecipeLayout} 一直有；按钮与叠加层只拿得到
     * 布局对象，所以统一在这里反射取一次。取不到时再读一层包装字段（旧版适配器）。
     */
    @Nullable
    public static Object recipeOf(@Nullable Object layout) {
        if (layout == null) {
            return null;
        }
        Object recipe = invokeZeroArg(layout, "getRecipe");
        if (recipe != null) {
            return recipe;
        }
        for (String fieldName : List.of("recipeLayout", "delegate", "layout")) {
            Object inner = readField(layout, fieldName);
            if (inner != null && inner != layout) {
                Object innerRecipe = invokeZeroArg(inner, "getRecipe");
                if (innerRecipe != null) {
                    return innerRecipe;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Object invokeZeroArg(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            // 注意：不能复用 findMethod——那是判耗时用的，只接受数值返回；
            // getRecipe() 返回泛型对象，会被数值过滤直接跳过（1.18.2 全部 1t 的根因）
            Optional<Method> method = findAnyMethod(target.getClass(), name);
            return method.isPresent() ? method.get().invoke(target) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Nullable
    private static Object readField(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (!field.trySetAccessible()) {
                    return null;
                }
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (IllegalAccessException | RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    private static int vanillaTicks(Object recipe) {
        if (recipe instanceof AbstractCookingRecipe cooking) {
            try {
                return TimeInferenceRules.sanitizeTicks(cooking.getCookingTime());
            } catch (RuntimeException | LinkageError e) {
                return -1;
            }
        }
        return -1;
    }

    private static int modApiTicks(Object recipe) {
        String className = recipe.getClass().getName();
        for (String methodName : TimeInferenceRules.methodsFor(className)) {
            int ticks = invoke(recipe, methodName);
            if (ticks > 0) {
                return ticks;
            }
        }
        return -1;
    }

    private static int genericTicks(Object recipe) {
        String className = recipe.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("net.minecraft.")) {
            return -1;
        }
        for (String methodName : TimeInferenceRules.genericMethods()) {
            int ticks = invoke(recipe, methodName);
            if (ticks > 0) {
                return ticks;
            }
        }
        return -1;
    }

    private static int fieldTicks(Object recipe) {
        for (String fieldName : TimeInferenceRules.fieldNames()) {
            Optional<Field> cached = FIELD_CACHE.computeIfAbsent(
                    new ResolveKey(recipe.getClass(), fieldName),
                    key -> findField(key.type(), key.name()));
            if (cached.isEmpty()) {
                continue;
            }
            try {
                Object value = cached.get().get(recipe);
                if (value instanceof Number number) {
                    int ticks = TimeInferenceRules.sanitizeTicks(number.longValue());
                    if (ticks > 0) {
                        return ticks;
                    }
                }
            } catch (IllegalAccessException | RuntimeException | LinkageError e) {
                return -1;
            }
        }
        return -1;
    }

    private static int invoke(Object target, String methodName) {
        Optional<Method> cached = METHOD_CACHE.computeIfAbsent(
                new ResolveKey(target.getClass(), methodName),
                key -> findMethod(key.type(), key.name()));
        if (cached.isEmpty()) {
            return -1;
        }
        try {
            Object value = cached.get().invoke(target);
            return value instanceof Number number
                    ? TimeInferenceRules.sanitizeTicks(number.longValue()) : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** 任意返回类型的方法（取配方对象用），不参与耗时数值判断。 */
    private static Optional<Method> findAnyMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!method.trySetAccessible()) {
                    return Optional.empty();
                }
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) || !isNumeric(method.getReturnType())) {
                    continue;
                }
                if (!method.trySetAccessible()) {
                    return Optional.empty();
                }
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || !isNumeric(field.getType())) {
                    continue;
                }
                if (!field.trySetAccessible()) {
                    return Optional.empty();
                }
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || Number.class.isAssignableFrom(type);
    }
}
