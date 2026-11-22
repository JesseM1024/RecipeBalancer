package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.time.TimeInferenceRules;
import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
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
 * <p>四级策略，任一命中即返回：
 * <ol>
 *   <li><b>原版烹饪</b>：{@link AbstractCookingRecipe#getCookingTime()}（熔炉 200 / 高炉 100 / 烟熏 100）；</li>
 *   <li><b>已知模组公开方法</b>：按「包名前缀 → 方法名」表反射调用，不链接源模组类，
 *       因此不需要编译期依赖，模组缺失时也不会走到这里；</li>
 *   <li><b>通用探测</b>（需要配置开启）：先按语义明确的方法名，再按字段名反射读取；</li>
 *   <li><b>机器基础耗时</b>（需要配置开启）：{@link JeiMachineTimes} 现场找该类别的催化剂机器，
 *       实例化后读它自己的耗时方法——不维护任何静态类别数值表。</li>
 * </ol>
 *
 * <p>本类只读：不修改配方、不缓存配方实例，只缓存「类 → 方法/字段」的解析结果。
 * 任何异常（含兼容模组版本错配的 {@link LinkageError}）都退化为「没有参考值」，
 * 由调用方保持默认耗时，不允许影响配方导入。
 *
 * <p>规则表本身在 {@link TimeInferenceRules}，与反射代码分开维护。
 */
public final class JeiRecipeTime {

    /** 推断来源，界面据此说明这个数字从哪来。 */
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

    /** 类解析结果缓存：同一条配方类只查一次反射，之后直接复用。 */
    private static final Map<ResolveKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResolveKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private JeiRecipeTime() {
    }

    /**
     * 推断耗时。永远不会抛异常，读不到返回 {@link Optional#empty()}。
     *
     * @param recipe   JEI 当前展示的原始配方对象，可为 null（旧调用路径没有 recipe）
     * @param category 配方类别，可为 null；保留用于将来按类别收窄规则
     */
    public static Optional<Result> infer(@Nullable Object recipe, @Nullable IRecipeCategory<?> category) {
        if (recipe == null || !GraphLayoutConfig.prefillRecipeTime()) {
            return Optional.empty();
        }
        // JEI 19 的原版 / Create / Mekanism 类别泛型都是 IRecipeCategory<RecipeHolder<T>>，
        // 所以 layout.getRecipe() 拿到的是包装对象；不解包就永远认不出配方本体（表现为全部 1t）。
        Object subject = unwrap(recipe);
        if (subject == null) {
            return Optional.empty();
        }
        try {
            int vanilla = vanillaTicks(subject);
            if (vanilla > 0) {
                return Optional.of(new Result(vanilla, Origin.VANILLA_COOKING));
            }
            int api = modApiTicks(subject);
            if (api > 0) {
                return Optional.of(new Result(api, Origin.MOD_API));
            }
            // 反射探测可以关；类别基础耗时是静态表，不受该开关影响
            if (GraphLayoutConfig.prefillRecipeTimeReflection()) {
                int generic = genericTicks(subject);
                if (generic > 0) {
                    return Optional.of(new Result(generic, Origin.MOD_API));
                }
                int field = fieldTicks(subject);
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
                    subject.getClass().getName(), e);
            return Optional.empty();
        }
    }

    /**
     * 剥掉 {@link RecipeHolder} 包装。
     *
     * <p>JEI 的配方类型以 holder 为泛型（需要 recipe id 做书签/转移），所以
     * {@code IRecipeLayoutDrawable#getRecipe()} 与装饰器回调拿到的通常都是 holder。
     * 最多剥三层，避免异常结构导致死循环。
     */
    /** 配方类型 uid；拿不到时返回空串。 */
    public static String categoryUid(@Nullable IRecipeCategory<?> category) {
        try {
            var uid = category == null ? null : category.getRecipeType().getUid();
            return uid == null ? "" : uid.toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    @Nullable
    private static Object unwrap(@Nullable Object recipe) {
        Object current = recipe;
        for (int depth = 0; depth < 3 && current instanceof RecipeHolder<?> holder; depth++) {
            current = holder.value();
        }
        return current;
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
        // 原版配方的时间只有烹饪一类，已由 vanillaTicks 覆盖；这里跳过整个 net.minecraft，
        // 既不浪费反射，也避免把原版其他 getter 误当成耗时。
        if (className.startsWith("java.") || className.startsWith("net.minecraft.")) {
            return -1;
        }
        List<String> methods = TimeInferenceRules.genericMethods();
        for (String methodName : methods) {
            int ticks = invoke(recipe, methodName);
            if (ticks > 0) {
                return ticks;
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
            // 单条配方读失败只丢这一条；方法缓存保留，避免同族配方反复踩同一个坑
            return -1;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method method;
            try {
                method = current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                continue;
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || method.getParameterCount() != 0) {
                continue;
            }
            if (!TimeInferenceRules.isNumeric(method.getReturnType())) {
                continue;
            }
            if (!method.trySetAccessible()) {
                return Optional.empty();
            }
            return Optional.of(method);
        }
        return Optional.empty();
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

    private static Optional<Field> findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field field;
            try {
                field = current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
            if (Modifier.isStatic(field.getModifiers()) || !TimeInferenceRules.isNumeric(field.getType())) {
                continue;
            }
            if (!field.trySetAccessible()) {
                return Optional.empty();
            }
            return Optional.of(field);
        }
        return Optional.empty();
    }
}
