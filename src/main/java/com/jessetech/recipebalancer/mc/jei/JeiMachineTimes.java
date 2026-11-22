package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时构建「类别 uid → 机器基础耗时」，不维护任何静态数值表。
 *
 * <p>1.12.2 没有 {@code TileEntityType}，整条链路与 1.16.5 不同：
 * <ul>
 *   <li>催化剂：{@code IRecipeRegistry#getRecipeCatalysts(category)}（元素 {@link ItemStack}）；</li>
 *   <li>方块：{@code ItemBlock#getBlock()}；</li>
 *   <li>判断/实例化：{@code Block#hasTileEntity(state)} + {@code Block#createTileEntity(null, state)}；</li>
 *   <li>时间：先反射方法（AE2 的 {@code getMaxProcessingTime}），再反射<b>实例字段</b>
 *       （Mekanism 9.8 的 {@code BASE_TICKS_REQUIRED} 是 public 实例字段，不是静态也不是方法）。</li>
 * </ul>
 *
 * <p>已知限制：TE 5.5 的时间在 package-private 的 {@code processMax}，未启动时为 0，
 * 读不到基础耗时，保持 1t。
 */
public final class JeiMachineTimes {

    private static final int MAX_TICKS = 72_000;

    private static final List<String> TIME_METHODS = Arrays.asList(
            "getMaxProcessingTime", "getTicksRequired", "getProcessingDuration",
            "getProcessingTime", "getTotalProcessTime", "getDuration", "getTime");

    private static final List<String> TIME_FIELDS = Arrays.asList(
            "BASE_TICKS_REQUIRED", "TICKS_REQUIRED", "ticksRequired", "processMax");

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<String, Integer>();
    private static final Map<Key, Optional<Method>> METHODS = new ConcurrentHashMap<Key, Optional<Method>>();

    private static final class Key {
        private final Class<?> type;
        private final String name;

        private Key(Class<?> type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return type == key.type && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + name.hashCode();
        }
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
            return cached.intValue();
        }
        int ticks = resolve(category, uid);
        CACHE.put(uid, Integer.valueOf(ticks));
        return ticks;
    }

    private static int resolve(IRecipeCategory<?> category, String uid) {
        Object runtime = JeiRuntimeHolder.get();
        if (!(runtime instanceof IJeiRuntime)) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，JEI runtime 未就绪", uid);
            return -1;
        }
        IJeiRuntime jeiRuntime = (IJeiRuntime) runtime;
        try {
            IRecipeRegistry registry = jeiRuntime.getRecipeRegistry();
            List<Object> catalysts = registry.getRecipeCatalysts(category);
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，催化剂 {} 个",
                    uid, Integer.valueOf(catalysts.size()));
            for (Object catalyst : catalysts) {
                if (!(catalyst instanceof ItemStack)) {
                    continue;
                }
                ItemStack stack = (ItemStack) catalyst;
                if (stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) {
                    continue;
                }
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                int ticks = ticksOf(block, uid);
                if (ticks > 0) {
                    RecipeBalancerMod.LOGGER.info(
                            "[RecipeBalancer] 机器基准耗时：{} -> {}t（来自 {}）",
                            uid, Integer.valueOf(ticks), block.getLocalizedName());
                    return ticks;
                }
            }
        } catch (RuntimeException e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 机器基准耗时读取失败：{}", uid, e);
        } catch (LinkageError e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 机器基准耗时读取失败：{}", uid, e);
        }
        return -1;
    }

    private static int ticksOf(Block block, String uid) {
        int matched = 0;
        try {
            IBlockState state = block.getDefaultState();
            // 1.12.2 没有 TileEntityType，只能让方块自己创建方块实体
            if (!block.hasTileEntity(state)) {
                return -1;
            }
            matched++;
            TileEntity entity = instantiate(block, state);
            if (entity == null) {
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器实例化失败：{}",
                        block.getLocalizedName());
                return -1;
            }
            if (!uidMatches(entity, uid)) {
                Object type = call(entity, "getRecipeType");
                Object recipeType = call(type, "getRecipeType");
                Object uidObject = call(recipeType == null ? type : recipeType, "getUid");
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器 uid 不匹配：{}（目标 {}，实际 {}）",
                        block.getLocalizedName(), uid, uidObject);
                return -1;
            }
            int ticks = readTicks(entity);
            if (ticks > 0) {
                return ticks;
            }
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器没有耗时方法：{}",
                    block.getLocalizedName());
        } catch (RuntimeException e) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 方块 {} 实例化异常：{}",
                    block.getLocalizedName(), e.toString());
        } catch (LinkageError e) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 方块 {} 实例化异常：{}",
                    block.getLocalizedName(), e.toString());
        }
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 方块 {} 命中 {} 个候选",
                block.getLocalizedName(), Integer.valueOf(matched));
        return -1;
    }

    @Nullable
    private static TileEntity instantiate(Block block, IBlockState state) {
        try {
            // 1.12.2 没有 TileEntityType；方块自己创建实体。多数 mod 的 createTileEntity 忽略 world，
            // 传 null 安全；依赖 world 的实现会在 catch 里被跳过
            return block.createTileEntity(null, state);
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /**
     * 机器实体若暴露 {@code getRecipeType()}，必须与目标 uid 一致，避免同族机器拿错；
     * 没有该方法时（AE2 压印机 / 1.12.2 的 Mekanism）以催化剂对应关系为准。
     */
    private static boolean uidMatches(TileEntity entity, String uid) {
        Object type = call(entity, "getRecipeType");
        if (type == null) {
            return true;
        }
        Object recipeType = call(type, "getRecipeType");
        Object uidObject = call(recipeType == null ? type : recipeType, "getUid");
        return uidObject == null || uid.equals(uidObject.toString());
    }

    private static int readTicks(TileEntity entity) {
        for (String name : TIME_METHODS) {
            int ticks = invoke(entity, name);
            if (ticks > 0) {
                return ticks;
            }
        }
        // Mekanism 9.8：BASE_TICKS_REQUIRED / ticksRequired 是 public 实例字段
        for (String name : TIME_FIELDS) {
            int ticks = readInstanceInt(entity, name);
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
                key -> findMethod(key.type, key.name));
        if (!method.isPresent()) {
            return -1;
        }
        try {
            Object value = method.get().invoke(target);
            return value instanceof Number
                    ? sanitize(((Number) value).longValue()) : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        } catch (LinkageError e) {
            return -1;
        }
    }

    @Nullable
    private static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        Optional<Method> method = METHODS.computeIfAbsent(new Key(target.getClass(), name),
                key -> findMethod(key.type, key.name));
        if (!method.isPresent()) {
            return null;
        }
        try {
            return method.get().invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        // public 继承方法用 getMethod 取；返回类型不做限制（call 还要拿 getRecipeType 这类对象）
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
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

    private static int readInstanceInt(TileEntity entity, String name) {
        for (Class<?> current = entity.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || !isNumeric(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return sanitize(field.getLong(entity));
            } catch (NoSuchFieldException ignored) {
                continue;
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

    private static int readStaticInt(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers()) || !isNumeric(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return sanitize(field.getLong(null));
            } catch (NoSuchFieldException ignored) {
                continue;
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

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || Number.class.isAssignableFrom(type);
    }

    private static int sanitize(long ticks) {
        return ticks > 0 && ticks <= MAX_TICKS ? (int) ticks : -1;
    }
}
