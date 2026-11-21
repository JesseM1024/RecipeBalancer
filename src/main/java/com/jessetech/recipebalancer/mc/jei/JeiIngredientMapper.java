package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * 把 JEI 的任意配料（物品 / 流体 / 化学品…）转成可持久化的 key + 数量。
 *
 * <p>1.12.2 的 JEI 4.x 没有 {@code ITypedIngredient}：配料就是<b>裸对象</b>。
 * 类型标识用配料类的全名（{@code IIngredientType} 直到 4.12 才出现，本模组统一走
 * {@code Class} 系 API，一份代码覆盖 4.8.5 ~ 4.24）。
 *
 * <p>数量：物品取堆叠数；其它配料反射读 {@code getAmount()}（流体/化学品有，物品没有）。
 */
public final class JeiIngredientMapper {

    /** 已打过数量诊断的配料名（避免刷屏）。 */
    private static final java.util.Set<String> LOGGED =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * key → 原始配料对象的会话缓存（有界 LRU，最多 4096 条）。
     *
     * <p>为什么需要它：反查靠 {@code IIngredientHelper#getUniqueId}，但部分配料的 uniqueId
     * 不含完整 NBT，或同一配方对象在布局阶段被复制、uniqueId 与写入时有细微差别，
     * 导致「编码后变成自定义物质、R/U 失效」。只要这一局里是我们自己编码进去的 key，
     * 就直接记住对象本身，不依赖反查。
     */
    private static final Map<String, Object> KEY_CACHE =
            Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Object>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, Object> eldest) {
                            return size() > 4096;
                        }
                    });

    private JeiIngredientMapper() {
    }

    /** 记住 key 与它的原始配料，供渲染/悬停直接复用。 */
    static void remember(String key, Object ingredient) {
        if (key != null && ingredient != null) {
            KEY_CACHE.put(key, ingredient);
        }
    }

    /** 会话缓存查找（未命中返回 null，调用方再走 uniqueId 反查）。 */
    static Object recall(String key) {
        return key == null ? null : KEY_CACHE.get(key);
    }

    /** JEI runtime 重建时清空（对象来自旧 runtime，不能再持有）。 */
    public static void clearCache() {
        KEY_CACHE.clear();
        LOGGED.clear();
    }

    /** 一个可持久化的配料：key + 数量 + 显示名（Java 8，不能写 record）。 */
    public static final class Mapped {

        private final String key;
        private final double amount;
        private final String displayName;

        public Mapped(String key, double amount, String displayName) {
            this.key = key;
            this.amount = amount;
            this.displayName = displayName;
        }

        public String key() {
            return key;
        }

        public double amount() {
            return amount;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** @return 解析不了返回 null */
    public static Mapped of(Object ingredient) {
        if (ingredient == null) {
            return null;
        }
        if (ingredient instanceof ItemStack) {
            Mapped mapped = ofItem((ItemStack) ingredient);
            if (mapped != null) {
                remember(mapped.key(), ingredient);
            }
            return mapped;
        }
        IIngredientRegistry registry = registry();
        if (registry == null) {
            return null;
        }
        try {
            IIngredientHelper<Object> helper = helperOf(registry, ingredient);
            if (helper == null) {
                return null;
            }
            String uniqueId = helper.getUniqueId(ingredient);
            String name = helper.getDisplayName(ingredient);
            long rawAmount = reflectiveAmount(ingredient);
            // 非物品默认是 mB 计量；500 mB -> 0.5 B
            double amount = rawAmount > 0 ? rawAmount / 1000.0 : 1.0;
            String key = IngredientKey.encode(ingredient.getClass().getName(), uniqueId, name);
            // 诊断（每种配料只打一次）：确认 JEI 给的原始数量与换算是否正确
            if (LOGGED.add(name)) {
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] 配料数量: {} class={} 原始={} -> {} B,"
                                + " uniqueId={}, 实例={}",
                        name, ingredient.getClass().getSimpleName(), rawAmount, amount,
                        uniqueId, ingredient);
            }
            remember(key, ingredient);
            return new Mapped(key, amount, name);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 物品直接按 registry id + 堆叠数换算；空栈返回 null。 */
    static Mapped ofItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return new Mapped(ItemKeyMapper.of(stack),
                Math.max(1.0, stack.getCount()),
                stack.getDisplayName());
    }

    /**
     * 按当前游戏语言重新取显示名。
     * 配方/场景里 key 存的是编码当时的名称，切到别的语言后必须重新问 JEI。
     *
     * @return 解析不出来时返回 null
     */
    static String displayNameOf(Object ingredient) {
        if (ingredient == null) {
            return null;
        }
        IIngredientRegistry registry = registry();
        if (registry == null) {
            return null;
        }
        try {
            IIngredientHelper<Object> helper = helperOf(registry, ingredient);
            if (helper == null) {
                return null;
            }
            String name = helper.getDisplayName(ingredient);
            return name == null || J8.isBlank(name) ? null : name;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 按类型标识（配料类全名）找回注册过的配料 Class。 */
    static Class<?> findType(IIngredientRegistry registry, String typeId) {
        if (registry == null || J8.isBlank(typeId)) {
            return null;
        }
        try {
            for (Class<?> candidate : registeredClasses(registry)) {
                if (typeId.equals(candidate.getName())) {
                    return candidate;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 自定义配料类型缺失时当作解析失败
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Collection<Class<?>> registeredClasses(IIngredientRegistry registry) {
        return (Collection<Class<?>>) (Collection<?>) registry.getRegisteredIngredientClasses();
    }

    @SuppressWarnings("unchecked")
    static IIngredientHelper<Object> helperOf(IIngredientRegistry registry, Object ingredient) {
        if (registry == null || ingredient == null) {
            return null;
        }
        return (IIngredientHelper<Object>) registry.getIngredientHelper(ingredient);
    }

    @SuppressWarnings("unchecked")
    static <T> Collection<T> allIngredients(IIngredientRegistry registry, Class<T> type) {
        if (registry == null || type == null) {
            return Collections.emptyList();
        }
        return registry.getAllIngredients(type);
    }

    @SuppressWarnings("unchecked")
    static <T> IIngredientHelper<T> helperOfClass(IIngredientRegistry registry, Class<T> type) {
        if (registry == null || type == null) {
            return null;
        }
        return (IIngredientHelper<T>) registry.getIngredientHelper(type);
    }

    private static long reflectiveAmount(Object ingredient) {
        if (ingredient == null) {
            return -1;
        }
        // 1.12.2 的 FluidStack 只有 public int amount 字段（没有 getAmount()）：
        // 少了这条，流体量一律回落到默认 1 B —— 热力的 0.25 B、MEK 的 2 mB 都会变成 1 B。
        if (ingredient instanceof net.minecraftforge.fluids.FluidStack) {
            return ((net.minecraftforge.fluids.FluidStack) ingredient).amount;
        }
        try {
            Method method = ingredient.getClass().getMethod("getAmount");
            method.setAccessible(true);
            Object value = method.invoke(ingredient);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 换字段兜底
        }
        // 其它模组的「栈」类型（Mekanism 气体等）也常用 public int amount 字段
        try {
            java.lang.reflect.Field field = ingredient.getClass().getField("amount");
            Object value = field.get(ingredient);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 确实没有数量概念（例如某些自定义配料），按 1 处理
        }
        return -1;
    }

    /** 1.12.2 的配料注册表（在 {@code register(IModRegistry)} 阶段捕获）。 */
    static IIngredientRegistry registry() {
        Object registry = JeiRuntimeHolder.ingredientRegistry();
        return registry instanceof IIngredientRegistry ? (IIngredientRegistry) registry : null;
    }
}
