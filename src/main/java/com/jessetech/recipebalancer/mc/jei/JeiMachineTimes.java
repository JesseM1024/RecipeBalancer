package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时构建「类别 uid → 机器基础耗时」，不维护任何静态数值表。
 *
 * <p>数据来源：该 JEI 类别的催化剂机器物品 → 方块 → 方块实体类型 → 实例化机器实体 →
 * 调用它自己的 {@code getTicksRequired()} / {@code getMaxProcessingTime()} 等方法。
 * 只在正常推断失败时调用，结果（含失败）按 uid 缓存。
 *
 * <p>为什么可行：Mekanism 的机器时间定义在机器实体上（1.20.1 富集室构造里 {@code sipush 200}），
 * AE2 压印机的 {@code getMaxProcessingTime()} 同样返回 200；这些都没有类级数据接口，
 * 只能拿到实体实例再读。
 *
 * <p>所有步骤都吞异常：构造机器实体可能触及能力/世界逻辑，失败就当作"读不到"。
 */
public final class JeiMachineTimes {

    private static final int MAX_TICKS = 72_000;

    private static final List<String> TIME_METHODS = List.of(
            "getTicksRequired", "getMaxProcessingTime", "getProcessingDuration",
            "getProcessingTime", "getTotalProcessTime", "getDuration", "getTime");

    private static final List<String> TIME_FIELDS = List.of(
            "BASE_TICKS_REQUIRED", "TICKS_REQUIRED", "BASE_TICKS");

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
    private static final Map<Key, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private record Key(Class<?> type, String name) {
    }

    private JeiMachineTimes() {
    }

    /** 读不到返回 -1。失败结果同样缓存，避免每次翻页都重试。 */
    public static int ticksFor(@Nullable IRecipeCategory<?> category, String uid) {
        if (category == null || uid == null || uid.isEmpty()) {
            return -1;
        }
        Integer cached = CACHE.get(uid);
        if (cached != null) {
            return cached;
        }
        int ticks = resolve(category, uid);
        CACHE.put(uid, ticks);
        return ticks;
    }

    private static int resolve(IRecipeCategory<?> category, String uid) {
        Object runtime = JeiRuntimeHolder.get();
        if (runtime == null) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，JEI runtime 未就绪", uid);
            return -1;
        }
        if (!(runtime instanceof IJeiRuntime jeiRuntime)) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，runtime 类型异常 {}",
                    uid, runtime.getClass().getName());
            return -1;
        }
        try {
            List<ItemStack> catalysts = jeiRuntime.getRecipeManager()
                    .createRecipeCatalystLookup(category.getRecipeType())
                    .getItemStack()
                    .toList();
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，催化剂 {} 个",
                    uid, catalysts.size());
            if (catalysts.isEmpty()) {
                return -1;
            }
            for (ItemStack stack : catalysts) {
                if (!(stack.getItem() instanceof BlockItem blockItem)) {
                    continue;
                }
                Block block = blockItem.getBlock();
                int ticks = ticksOf(block, uid);
                if (ticks > 0) {
                    RecipeBalancerMod.LOGGER.info(
                            "[RecipeBalancer] 机器基准耗时：{} -> {}t（来自 {}）",
                            uid, ticks, block.getName().getString());
                    return ticks;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 机器基准耗时读取失败：{}", uid, e);
        }
        return -1;
    }

    private static int ticksOf(Block block, String uid) {
        int matched = 0;
        BlockState state = block.defaultBlockState();
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            // 公开 API：该方块状态是否属于这个方块实体类型（比反射 validBlocks 可靠）
            if (!type.isValid(state)) {
                continue;
            }
            matched++;
            BlockEntity entity = instantiate(type, state);
            if (entity == null) {
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器实例化失败：{}（{}）",
                        type, block.getName().getString());
                continue;
            }
            if (!uidMatches(entity, uid)) {
                Object typeUid = call(call(entity, "getRecipeType"), "getRecipeType");
                Object uidObject = call(typeUid, "getUid");
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器 uid 不匹配：{}（目标 {}，实际 {}）",
                        type, uid, uidObject);
                continue;
            }
            int ticks = readTicks(entity);
            if (ticks > 0) {
                return ticks;
            }
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器没有耗时方法：{}（{}）",
                    type, block.getName().getString());
        }
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 方块 {} 命中 {} 个方块实体类型",
                block.getName().getString(), matched);
        return -1;
    }

    @Nullable
    private static BlockEntity instantiate(BlockEntityType<?> type, BlockState state) {
        try {
            return type.create(BlockPos.ZERO, state);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 机器实体若暴露 {@code getRecipeType()}，必须与目标 uid 一致，避免同族机器拿错
     * （例如 chemical_infusing 会命中多种 Chemical 机器）；没有该方法时以催化剂对应关系为准。
     */
    private static boolean uidMatches(BlockEntity entity, String uid) {
        Object type = call(entity, "getRecipeType");
        if (type == null) {
            return true;
        }
        Object recipeType = call(type, "getRecipeType");
        Object uidObject = call(recipeType == null ? type : recipeType, "getUid");
        return uidObject == null || uid.equals(uidObject.toString());
    }

    private static int readTicks(BlockEntity entity) {
        for (String name : TIME_METHODS) {
            int ticks = invoke(entity, name);
            if (ticks > 0) {
                return ticks;
            }
        }
        for (String name : TIME_FIELDS) {
            int ticks = readStaticInt(entity.getClass(), name);
            if (ticks > 0) {
                return ticks;
            }
        }
        return -1;
    }

    private static int invoke(Object target, String name) {
        Optional<Method> method = METHODS.computeIfAbsent(new Key(target.getClass(), name),
                key -> findMethod(key.type(), key.name()));
        if (method.isEmpty()) {
            return -1;
        }
        try {
            Object value = method.get().invoke(target);
            return value instanceof Number number ? sanitize(number.longValue()) : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }

    @Nullable
    private static Object call(Object target, String name) {
        Optional<Method> method = METHODS.computeIfAbsent(new Key(target.getClass(), name),
                key -> findMethod(key.type(), key.name()));
        if (method.isEmpty()) {
            return null;
        }
        try {
            return method.get().invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        // 同 JeiRecipeTime：public 继承方法用 getMethod 取，绕开 setAccessible 的坑
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                return Optional.empty();
            }
            return Optional.of(method);
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static int readStaticInt(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers()) || !isNumeric(field.getType())) {
                    continue;
                }
                if (!field.trySetAccessible()) {
                    return -1;
                }
                return sanitize(field.getLong(null));
            } catch (NoSuchFieldException ignored) {
                continue;
            } catch (IllegalAccessException | RuntimeException | LinkageError e) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || Number.class.isAssignableFrom(type);
    }

    private static int sanitize(long ticks) {
        return ticks > 0 && ticks <= MAX_TICKS ? (int) ticks : -1;
    }
}
