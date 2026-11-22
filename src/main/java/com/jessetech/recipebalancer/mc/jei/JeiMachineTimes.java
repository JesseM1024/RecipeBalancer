package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraftforge.registries.ForgeRegistries;
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
 * <p>1.16.5 / JEI 7.x 的链路：
 * <ul>
 *   <li>催化剂用 {@code IRecipeManager#getRecipeCatalysts(category, false)}（返回 {@code List<Object>}，
 *       元素是 {@link ItemStack}）；7.x 没有 {@code createRecipeCatalystLookup}；</li>
 *   <li>方块实体注册表是 {@link ForgeRegistries#TILE_ENTITIES}；</li>
 *   <li>{@code TileEntityType#isValid(Block)} 接收 {@link Block}（不是 BlockState）；</li>
 *   <li>{@code TileEntityType#create()} 无参（1.16.5 的 TileEntity 构造只收 type）。</li>
 * </ul>
 *
 * <p>数据只来自机器实体自身的方法/常量（Mekanism 的 {@code getTicksRequired()}、
 * AE2 的 {@code getMaxProcessingTime()}），所有异常都吞掉，失败缓存 -1。
 */
public final class JeiMachineTimes {

    private static final int MAX_TICKS = 72_000;

    private static final List<String> TIME_METHODS = Arrays.asList(
            "getTicksRequired", "getMaxProcessingTime", "getProcessingDuration",
            "getProcessingTime", "getTotalProcessTime", "getDuration", "getTime");

    private static final List<String> TIME_FIELDS = Arrays.asList(
            "BASE_TICKS_REQUIRED", "TICKS_REQUIRED", "BASE_TICKS");

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
            // 7.6.1 的编译基线只有单参版本（7.8 才加 boolean）
            List<Object> catalysts = jeiRuntime.getRecipeManager()
                    .getRecipeCatalysts(category);
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 机器基准查询：{}，催化剂 {} 个",
                    uid, Integer.valueOf(catalysts.size()));
            for (Object catalyst : catalysts) {
                if (!(catalyst instanceof ItemStack)) {
                    continue;
                }
                ItemStack stack = (ItemStack) catalyst;
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                    continue;
                }
                Block block = ((BlockItem) stack.getItem()).getBlock();
                int ticks = ticksOf(block, uid);
                if (ticks > 0) {
                    RecipeBalancerMod.LOGGER.info(
                            "[RecipeBalancer] 机器基准耗时：{} -> {}t（来自 {}）",
                            uid, Integer.valueOf(ticks), block.getTranslatedName().getString());
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
        for (TileEntityType<?> type : ForgeRegistries.TILE_ENTITIES.getValues()) {
            // 1.16.5 的 MCP 名是 isValidBlock(Block)（不是 isValid(BlockState)）
            if (!type.isValidBlock(block)) {
                continue;
            }
            matched++;
            TileEntity entity = instantiate(type);
            if (entity == null) {
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 候选机器实例化失败：{}（{}）",
                        type, block.getTranslatedName().getString());
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
                    type, block.getTranslatedName().getString());
        }
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 方块 {} 命中 {} 个方块实体类型",
                block.getTranslatedName().getString(), Integer.valueOf(matched));
        return -1;
    }

    @Nullable
    private static TileEntity instantiate(TileEntityType<?> type) {
        try {
            // 1.16.5 只有无参 create()
            return type.create();
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /**
     * 机器实体若暴露 {@code getRecipeType()}，必须与目标 uid 一致，避免同族机器拿错；
     * 没有该方法时（AE2 压印机 / 1.16.5 的 Mekanism）以催化剂对应关系为准。
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
        // public 继承方法用 getMethod 取，绕开 setAccessible 的坑；
        // 返回类型不做限制（call 还要拿 getRecipeType 这类对象）
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
