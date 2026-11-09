package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 把 JEI 的任意配料（物品 / 流体 / 化学品…）转成可持久化的 key + 数量。
 *
 * <p>1.16.5 的 JEI 7.x 与我熟悉的 9.x/15.x 差别很大：
 * <ul>
 *   <li>没有 {@code ITypedIngredient}，配料就是<b>裸对象</b>（{@code ItemStack} / {@code FluidStack} / …）；</li>
 *   <li>类型标识用 {@code IIngredientType#getIngredientClass().getName()}
 *       （7.x 没有 uid 概念）；</li>
 *   <li>helper 没有 {@code getAmount}，数量只能反射读配料自带的 {@code getAmount()}。</li>
 * </ul>
 */
public final class JeiIngredientMapper {

    /** 已打过数量诊断的配料名（避免刷屏）。 */
    private static final java.util.Set<String> LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 一个可持久化的配料：key + 数量 + 显示名（record 在 Java 8 上不可用）。 */
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

    private JeiIngredientMapper() {
    }

    /** @return 解析不了返回 null */
    public static Mapped of(Object ingredient) {
        if (ingredient == null) {
            return null;
        }
        if (ingredient instanceof ItemStack) {
            return ofItem((ItemStack) ingredient);
        }
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            IIngredientManager manager = runtime.getIngredientManager();
            IIngredientHelper<Object> helper = helperOf(manager, ingredient);
            if (helper == null) {
                return null;
            }
            String uniqueId = helper.getUniqueId(ingredient);
            String name = helper.getDisplayName(ingredient);
            long rawAmount = reflectiveAmount(ingredient);
            // 非物品默认是 mB 计量；500 mB -> 0.5 B
            double amount = rawAmount > 0 ? rawAmount / 1000.0 : 1.0;
            String key = IngredientKey.encode(typeId(manager.getIngredientType(ingredient)),
                    uniqueId, name);
            // 诊断（每种配料只打一次）：确认 JEI 给的原始数量与换算是否正确
            if (LOGGED.add(name)) {
                // toString 里对流体会带 amount（FluidStack{...Amount=250}），
                // 用于核对「JEI 显示的数量」与「我们读到的数量」是否一致
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] 配料数量: {} class={} 原始={} -> {} B,"
                                + " uniqueId={}, 实例={}",
                        name, ingredient.getClass().getSimpleName(), rawAmount, amount,
                        uniqueId, ingredient);
            }
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
                stack.getDisplayName().getString());
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
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            IIngredientHelper<Object> helper =
                    helperOf(runtime.getIngredientManager(), ingredient);
            if (helper == null) {
                return null;
            }
            String name = helper.getDisplayName(ingredient);
            return name == null || J8.isBlank(name) ? null : name;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 7.x 没有 uid：用配料类的全名当类型标识。 */
    static String typeId(IIngredientType<?> type) {
        Class<?> ingredientClass = type == null ? null : type.getIngredientClass();
        return ingredientClass == null ? "" : ingredientClass.getName();
    }

    /** 按类型标识找回已注册的 {@code IIngredientType}。 */
    static Optional<IIngredientType<?>> findType(IIngredientManager manager, String typeId) {
        if (manager == null || J8.isBlank(typeId)) {
            return Optional.empty();
        }
        for (IIngredientType<?> candidate : manager.getRegisteredIngredientTypes()) {
            if (typeId.equals(typeId(candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    static IIngredientHelper<Object> helperOf(IIngredientManager manager, Object ingredient) {
        if (manager == null || ingredient == null) {
            return null;
        }
        IIngredientHelper<?> helper = manager.getIngredientHelper(ingredient);
        return (IIngredientHelper<Object>) helper;
    }

    private static long reflectiveAmount(Object ingredient) {
        if (ingredient == null) {
            return -1;
        }
        try {
            Method method = ingredient.getClass().getMethod("getAmount");
            method.setAccessible(true);
            Object value = method.invoke(ingredient);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 物品之外的配料不一定有数量概念，按 1 处理
        }
        return -1;
    }

    static IJeiRuntime runtime() {
        Object runtime = JeiRuntimeHolder.get();
        return runtime instanceof IJeiRuntime ? (IJeiRuntime) runtime : null;
    }
}
