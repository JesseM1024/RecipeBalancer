package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 把 JEI 的任意配料（物品 / 流体 / 化学品…）转成可持久化的 key + 数量。
 *
 * <p>与 1.20.1 分支（JEI 15.x）的差异：
 * <ul>
 *   <li>9.x 的 {@code ITypedIngredient} 没有 {@code getItemStack()}，要用
 *       {@code getIngredient(VanillaTypes.ITEM)}；</li>
 *   <li>{@code IIngredientType} 只有 {@code getIngredientClass()}，没有 uid，
 *       所以类型标识用「配料类的全名」，读取时按同样规则反查已注册类型；</li>
 *   <li>helper 没有 {@code getAmount}，数量只能反射读配料自带的 {@code getAmount()}。</li>
 * </ul>
 */
public final class JeiIngredientMapper {

    public record Mapped(String key, double amount, String displayName) {
    }

    private JeiIngredientMapper() {
    }

    /** @return 解析不了返回 null */
    public static Mapped of(ITypedIngredient<?> typed) {
        if (typed == null) {
            return null;
        }
        ItemStack stack = typed.getIngredient(VanillaTypes.ITEM).orElse(ItemStack.EMPTY);
        Mapped item = ofTypedItem(stack);
        if (item != null) {
            return item;
        }
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Object ingredient = typed.getIngredient();
            IIngredientHelper<Object> helper = helperOf(runtime.getIngredientManager(), typed);
            if (helper == null) {
                return null;
            }
            String uniqueId = helper.getUniqueId(ingredient, UidContext.Ingredient);
            String name = helper.getDisplayName(ingredient);
            long rawAmount = reflectiveAmount(ingredient);
            // 非物品默认是 mB 计量；500 mB -> 0.5 B
            double amount = rawAmount > 0 ? rawAmount / 1000.0 : 1.0;
            String key = IngredientKey.encode(typeId(typed.getType()), uniqueId, name);
            return new Mapped(key, amount, name);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 物品直接按 registry id + 堆叠数换算；空栈返回 null。 */
    static Mapped ofTypedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return new Mapped(ItemKeyMapper.of(stack),
                Math.max(1.0, stack.getCount()),
                stack.getHoverName().getString());
    }

    /**
     * 按当前游戏语言重新取显示名。
     * 配方/场景里 key 存的是编码当时的名称，切到别的语言后必须重新问 JEI。
     *
     * @return 解析不出来时返回 null
     */
    static String displayNameOf(ITypedIngredient<?> typed) {
        if (typed == null) {
            return null;
        }
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Object ingredient = typed.getIngredient();
            IIngredientHelper<Object> helper = helperOf(runtime.getIngredientManager(), typed);
            if (helper == null) {
                return null;
            }
            String name = helper.getDisplayName(ingredient);
            return name == null || J8.isBlank(name) ? null : name;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 9.x 没有 {@code IIngredientType#getUid()}：用配料类的全名当类型标识。 */
    static String typeId(IIngredientType<?> type) {
        Class<?> ingredientClass = type == null ? null : type.getIngredientClass();
        return ingredientClass == null ? "" : ingredientClass.getName();
    }

    /** 按类型标识找回已注册的 {@code IIngredientType}。 */
    static Optional<IIngredientType<?>> findType(IIngredientManager manager, String typeId) {
        if (manager == null || typeId == null || J8.isBlank(typeId)) {
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
    static IIngredientHelper<Object> helperOf(IIngredientManager manager,
                                              ITypedIngredient<?> typed) {
        IIngredientHelper<?> helper = manager.getIngredientHelper(typed.getType());
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
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 物品之外的配料不一定有数量概念，按 1 处理
        }
        return -1;
    }

    static IJeiRuntime runtime() {
        Object runtime = JeiRuntimeHolder.get();
        return runtime instanceof IJeiRuntime jei ? jei : null;
    }
}
