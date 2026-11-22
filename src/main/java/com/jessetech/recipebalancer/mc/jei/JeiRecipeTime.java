package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.time.TimeInferenceRules;
import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import mezz.jei.api.recipe.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 JEI 的原始配方对象里推断「配方原始耗时」（tick），仅供编码区预填参考。
 *
 * <p>1.12.2 / JEI 4.13 要点：
 * <ul>
 *   <li>{@code IRecipeCategory#getUid()} 返回 {@code String}；</li>
 *   <li>{@code RecipeLayout} <b>没有 getRecipe()</b>，反射私有字段 {@code recipeWrapper}；</li>
 *   <li>传进来的是 {@code IRecipeWrapper}：熔炉要再反射一层内部 {@code recipe} 才能取到
 *       {@code getCookTime()}；酿造按类名 {@code Brewing} 取原版固定 400t；</li>
 *   <li>Mekanism / AE2 的 wrapper 不暴露时间，由 {@link JeiMachineTimes} 现场读机器。</li>
 * </ul>
 */
public final class JeiRecipeTime {

    /** 1.12.2 没有 PotionBrewing.BREWING_TIME_SECONDS；酿造台 brewTime 判断为 400t。 */
    private static final int BREW_TICKS = 400;

    /** 推断来源。 */
    public enum Origin {
        /** 原版烹饪（或 4.x 的 SmeltingRecipeWrapper）。 */
        VANILLA_COOKING,
        /** 已知模组 / 通用探测命中的公开方法。 */
        MOD_API,
        /** 通用探测命中的字段。 */
        MOD_FIELD,
        /** 配方对象上没有时间，运行时读机器实体的基础耗时。 */
        MACHINE_BASE
    }

    /** @param ticks 推断出的耗时（tick），保证 {@code 0 < ticks <= MAX_TICKS} */
    public static final class Result {
        private final int ticks;
        private final Origin origin;

        public Result(int ticks, Origin origin) {
            this.ticks = ticks;
            this.origin = origin;
        }

        public int ticks() {
            return ticks;
        }

        public Origin origin() {
            return origin;
        }
    }

    private static final class ResolveKey {
        private final Class<?> type;
        private final String name;

        private ResolveKey(Class<?> type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ResolveKey)) {
                return false;
            }
            ResolveKey key = (ResolveKey) other;
            return type == key.type && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + name.hashCode();
        }
    }

    private static final Map<ResolveKey, Optional<Method>> METHOD_CACHE =
            new ConcurrentHashMap<ResolveKey, Optional<Method>>();
    private static final Map<ResolveKey, Optional<Field>> FIELD_CACHE =
            new ConcurrentHashMap<ResolveKey, Optional<Field>>();

    private JeiRecipeTime() {
    }

    /**
     * 推断耗时。永远不会抛异常，读不到返回 {@link Optional#empty()}。
     *
     * @param recipe   JEI 当前展示的配方包装对象（4.x 的 {@code IRecipeWrapper}），可为 null
     * @param category 配方类别，可为 null；用于取 uid 与查询催化剂机器
     */
    public static Optional<Result> infer(@Nullable Object recipe, @Nullable IRecipeCategory<?> category) {
        if (recipe == null || !GraphLayoutConfig.prefillRecipeTime()) {
            return Optional.empty();
        }
        try {
            // JEI 4.x 传的是自己的包装类（MachineRecipeWrapper 等），
            // 真实配方在内部 recipe 字段里；先解包再探测
            Object target = unwrap(recipe);
            int vanilla = vanillaTicks(target);
            if (vanilla > 0) {
                return Optional.of(new Result(vanilla, Origin.VANILLA_COOKING));
            }
            int api = modApiTicks(target);
            if (api > 0) {
                return Optional.of(new Result(api, Origin.MOD_API));
            }
            if (GraphLayoutConfig.prefillRecipeTimeReflection()) {
                int generic = genericTicks(target);
                if (generic > 0) {
                    return Optional.of(new Result(generic, Origin.MOD_API));
                }
                int field = fieldTicks(target);
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
        } catch (RuntimeException e) {
            RecipeBalancerMod.LOGGER.debug("[RecipeBalancer] 耗时推断失败：{}",
                    recipe.getClass().getName(), e);
            return Optional.empty();
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    /** 配方类型 uid；JEI 4.x 的 {@code getUid()} 直接返回 {@code String}。 */
    public static String categoryUid(@Nullable IRecipeCategory<?> category) {
        try {
            Object uid = category == null ? null : category.getUid();
            return uid == null ? "" : uid.toString();
        } catch (RuntimeException e) {
            return "";
        } catch (LinkageError e) {
            return "";
        }
    }

    /**
     * 从 JEI 的布局对象上取原始配方。
     *
     * <p>4.x 的 {@code RecipeLayout} 只有私有字段 {@code recipeWrapper}，没有 getRecipe()；
     * 取不到时再试 {@code getRecipe()} 方法（兼容少数分支）。
     */
    @Nullable
    public static Object recipeOf(@Nullable Object layout) {
        if (layout == null) {
            return null;
        }
        Object wrapper = readField(layout, "recipeWrapper");
        if (wrapper != null) {
            return wrapper;
        }
        Object recipe = invokeZeroArg(layout, "getRecipe");
        if (recipe != null) {
            return recipe;
        }
        return null;
    }

    @Nullable
    private static Object invokeZeroArg(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findAnyMethod(target.getClass(), name);
            return method == null ? null : method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /**
     * JEI 的包装类内部通常还有一层真实配方（字段名 {@code recipe}）。
     *
     * <p>1.12.2 的 Mekanism PRC 走 {@code DoubleMachineRecipeWrapper}，
     * 配方时间在内部 {@code PressurizedRecipe.ticks} 字段上，
     * 不解包的话字段探测永远命中不了。
     */
    @Nullable
    private static Object unwrap(@Nullable Object recipe) {
        if (recipe == null) {
            return null;
        }
        String name = recipe.getClass().getName();
        // 只解 JEI 自己的包装类，避免误读模组配方里同名的 recipe 字段
        if (name.contains("RecipeWrapper") || name.startsWith("mezz.jei.")) {
            Object inner = readField(recipe, "recipe");
            if (inner != null) {
                return inner;
            }
        }
        return recipe;
    }

    private static Object readField(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (IllegalAccessException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            } catch (LinkageError e) {
                return null;
            }
        }
        return null;
    }

    private static int vanillaTicks(Object recipe) {
        String name = recipe.getClass().getName();
        // 4.x 的熔炉包装类没有时间方法，内部还包着一层 MC 的 IRecipe
        if (name.contains("Smelting") || name.contains("Furnace")) {
            Object mcRecipe = readField(recipe, "recipe");
            if (mcRecipe != null) {
                int ticks = invoke(mcRecipe, "getCookTime");
                if (ticks > 0) {
                    return ticks;
                }
                // 1.16.5 之后才叫 getCookingTime；一并尝试，兼容自定义包装
                ticks = invoke(mcRecipe, "getCookingTime");
                if (ticks > 0) {
                    return ticks;
                }
            }
        }
        // 原版酿造：wrapper 类名含 Brewing，1.12.2 同样没有 BREWING_TIME_SECONDS
        if (name.contains("Brewing")) {
            return BREW_TICKS;
        }
        // 直接是 MC 配方对象（有些分支会直接传 IRecipe）
        int direct = invoke(recipe, "getCookTime");
        if (direct > 0) {
            return direct;
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
                    key -> findField(key.type, key.name));
            if (!cached.isPresent()) {
                continue;
            }
            try {
                Object value = cached.get().get(recipe);
                if (value instanceof Number) {
                    int ticks = TimeInferenceRules.sanitizeTicks(((Number) value).longValue());
                    if (ticks > 0) {
                        return ticks;
                    }
                }
            } catch (IllegalAccessException e) {
                return -1;
            } catch (RuntimeException e) {
                return -1;
            } catch (LinkageError e) {
                return -1;
            }
        }
        return -1;
    }

    private static int invoke(Object target, String methodName) {
        Optional<Method> cached = METHOD_CACHE.computeIfAbsent(
                new ResolveKey(target.getClass(), methodName),
                key -> findMethod(key.type, key.name));
        if (!cached.isPresent()) {
            return -1;
        }
        try {
            Object value = cached.get().invoke(target);
            return value instanceof Number
                    ? TimeInferenceRules.sanitizeTicks(((Number) value).longValue()) : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        } catch (LinkageError e) {
            return -1;
        }
    }

    /** 任意返回类型的方法（取配方对象用），不参与耗时数值判断。 */
    @Nullable
    private static Method findAnyMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        // 用 Class#getMethod：只找 public（含继承）方法，无需 setAccessible。
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())
                    || !isNumeric(method.getReturnType())) {
                return Optional.empty();
            }
            return Optional.of(method);
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || !isNumeric(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (RuntimeException e) {
                return Optional.empty();
            } catch (LinkageError e) {
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
