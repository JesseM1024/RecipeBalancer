package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * 把 JEI 的任意 ingredient 转成可持久化的 key + 数量。
 * 物品用 count；流体/化学品等非物品统一把 mB / 1000 转成 B。
 */
public final class JeiIngredientMapper {

    public record Mapped(String key, double amount, String displayName) {
    }

    private JeiIngredientMapper() {
    }

    public static Mapped of(ITypedIngredient<?> typed) {
        if (typed == null) {
            return null;
        }
        ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
        if (!stack.isEmpty()) {
            return new Mapped(ItemKeyMapper.of(stack),
                    Math.max(1.0, stack.getCount()),
                    stack.getHoverName().getString());
        }
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Object ingredient = typed.getIngredient();
            @SuppressWarnings("unchecked")
            IIngredientHelper<Object> helper = (IIngredientHelper<Object>)
                    (IIngredientHelper<?>) runtime.getIngredientManager()
                            .getIngredientHelper(typed.getType());
            String uid = helper.getUniqueId(ingredient, UidContext.Ingredient);
            String name = helper.getDisplayName(ingredient);
            long rawAmount = helper.getAmount(ingredient);
            if (rawAmount <= 0) {
                // JEI 默认 getAmount() 返回 -1；Mekanism ChemicalStack 等有自己的 getAmount()
                rawAmount = reflectiveAmount(ingredient);
            }
            // 非物品默认是 mB 计量；500 mB -> 0.5 B
            double amount = rawAmount > 0 ? rawAmount / 1000.0 : 1.0;
            String key = IngredientKey.encode(typed.getType().getUid(), uid, name);
            return new Mapped(key, amount, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long reflectiveAmount(Object ingredient) {
        try {
            Method method = ingredient.getClass().getMethod("getAmount");
            method.setAccessible(true);
            Object value = method.invoke(ingredient);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return -1;
    }

    static IJeiRuntime runtime() {
        Object runtime = JeiRuntimeHolder.get();
        return runtime instanceof IJeiRuntime jei ? jei : null;
    }
}
