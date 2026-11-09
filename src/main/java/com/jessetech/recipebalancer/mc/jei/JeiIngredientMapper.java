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

    /**
     * key → 原始 {@link ITypedIngredient} 的会话缓存（有界 LRU，4096 条）。
     *
     * <p>Create 的药水流体等配料 uniqueId 不含完整 NBT，按 uniqueId 反查会失败
     * （表现为编码成自定义物质、R/U 失效）；编码时顺手记住对象本身即可绕开反查。
     */
    private static final java.util.Map<String, ITypedIngredient<?>> KEY_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, ITypedIngredient<?>>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, ITypedIngredient<?>> eldest) {
                            return size() > 4096;
                        }
                    });

    static void remember(String key, ITypedIngredient<?> typed) {
        if (key != null && typed != null) {
            KEY_CACHE.put(key, typed);
        }
    }

    static ITypedIngredient<?> recall(String key) {
        return key == null ? null : KEY_CACHE.get(key);
    }

    /** JEI runtime 重建时清空（旧对象不能再持有）。 */
    public static void clearCache() {
        KEY_CACHE.clear();
    }

    private JeiIngredientMapper() {
    }

    // getUniqueId 虽被标记待删除，但它产出的 uid 就是存档里 ingredient key 的部分；
    // 换成 getUid()/codec 会改变 key 编码，需要数据迁移，这里先保持行为并压掉告警。
    @SuppressWarnings("removal")
    public static Mapped of(ITypedIngredient<?> typed) {
        if (typed == null) {
            return null;
        }
        ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
        if (!stack.isEmpty()) {
            Mapped mapped = new Mapped(ItemKeyMapper.of(stack),
                    Math.max(1.0, stack.getCount()),
                    stack.getHoverName().getString());
            remember(mapped.key(), typed);
            return mapped;
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
            remember(key, typed);
            return new Mapped(key, amount, name);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 按当前游戏语言重新取显示名。
     * 配方/场景里 key 存的是编码当时的名称，切到别的语言后必须重新问 JEI。
     *
     * @return 解析不出来时返回 null
     */
    @SuppressWarnings("unchecked")
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
            IIngredientHelper<Object> helper = (IIngredientHelper<Object>)
                    (IIngredientHelper<?>) runtime.getIngredientManager()
                            .getIngredientHelper(typed.getType());
            String name = helper.getDisplayName(ingredient);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException | LinkageError e) {
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
