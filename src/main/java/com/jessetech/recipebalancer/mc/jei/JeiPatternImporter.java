package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IGuiIngredient;
import mezz.jei.api.gui.ingredient.IGuiIngredientGroup;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * 把 JEI 的配方转成我们的编码样板（输入 / 输出 / 设备）。
 *
 * <p>9.x 没有 {@code IRecipeSlotsView} 的公开入口（10.x 才有），
 * 因此统一从 {@code IRecipeLayout} 的配料分组读：每个分组自带 input 标记与候选配料。
 */
public final class JeiPatternImporter {

    private JeiPatternImporter() {
    }

    /**
     * 入口：一份 JEI 配方布局 → 编码样板。
     *
     * <p>9.x 的 {@code IRecipeLayoutDrawable} 没有 {@code getRecipeCategory()}，
     * 所以类别由调用方一起传进来（催化剂查询要用）。
     */
    public static RecipePattern build(IRecipeCategory<?> category, IRecipeLayoutDrawable layout) {
        RecipePattern pattern = new RecipePattern();
        if (layout == null) {
            return pattern;
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return pattern;
        }
        int inputIndex = 0;
        int outputIndex = 0;
        try {
            for (IIngredientType<?> type : runtime.getIngredientManager()
                    .getRegisteredIngredientTypes()) {
                IGuiIngredientGroup<?> group = layout.getIngredientsGroup(type);
                if (group == null) {
                    continue;
                }
                for (Map.Entry<Integer, ? extends IGuiIngredient<?>> entry
                        : group.getGuiIngredients().entrySet()) {
                    IGuiIngredient<?> slot = entry.getValue();
                    if (slot == null || slot.getAllIngredients().isEmpty()) {
                        continue;
                    }
                    JeiIngredientMapper.Mapped mapped = pick(slot);
                    if (mapped == null) {
                        continue;
                    }
                    if (slot.isInput()) {
                        if (inputIndex < RecipePattern.MAX_INPUTS) {
                            pattern.putInput(inputIndex++, mapped.key(), mapped.amount());
                        }
                    } else if (outputIndex < RecipePattern.MAX_OUTPUTS) {
                        pattern.putOutput(outputIndex++, mapped.key(), mapped.amount());
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 某些自定义类别的分组读不出来时保留已解析的部分
        }
        applyEquipment(category, pattern);
        return pattern;
    }

    /** 设备：取该配方类型的第一个催化剂。 */
    private static void applyEquipment(IRecipeCategory<?> category, RecipePattern pattern) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || category == null) {
            return;
        }
        try {
            for (Object catalyst : runtime.getRecipeManager().getRecipeCatalysts(category, true)) {
                if (catalyst instanceof ItemStack stack && !stack.isEmpty()) {
                    pattern.setEquipmentKey(ItemKeyMapper.of(stack));
                    return;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 没有催化剂的类别直接跳过
        }
    }

    /**
     * 该槽位有多候选（如不同木板）时，优先选配方库能自产的那种，且取配方序号最小的；
     * 没有能自产的，退回 JEI 当前展示的那个。
     */
    public static JeiIngredientMapper.Mapped pick(IGuiIngredient<?> slot) {
        ProductIndex index = ProductIndex.current();
        JeiIngredientMapper.Mapped best = null;
        int bestRecipe = Integer.MAX_VALUE;
        for (Object candidate : slot.getAllIngredients()) {
            JeiIngredientMapper.Mapped mapped = map(candidate, slot);
            if (mapped == null) {
                continue;
            }
            int at = index.indexOf(mapped.key());
            if (at >= 0 && at < bestRecipe) {
                bestRecipe = at;
                best = mapped;
            }
        }
        return best != null ? best : firstIngredient(slot);
    }

    public static JeiIngredientMapper.Mapped firstIngredient(IGuiIngredient<?> slot) {
        Object displayed = slot.getDisplayedIngredient();
        if (displayed != null) {
            JeiIngredientMapper.Mapped mapped = map(displayed, slot);
            if (mapped != null) {
                return mapped;
            }
        }
        for (Object candidate : slot.getAllIngredients()) {
            JeiIngredientMapper.Mapped mapped = map(candidate, slot);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /** 9.x 的分组给的是裸配料，这里按槽位类型重新包成 {@link ITypedIngredient} 再解析。 */
    private static JeiIngredientMapper.Mapped map(Object candidate, IGuiIngredient<?> slot) {
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof ItemStack stack) {
            return JeiIngredientMapper.ofTypedItem(stack);
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Optional<? extends ITypedIngredient<?>> typed = wrap(runtime, slot, candidate);
            return typed.map(JeiIngredientMapper::of).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<ITypedIngredient<?>> wrap(IJeiRuntime runtime,
                                                     IGuiIngredient<?> slot,
                                                     Object candidate) {
        IIngredientType rawType = slot.getIngredientType();
        if (rawType == null) {
            return Optional.empty();
        }
        ITypedIngredient<?> typed = runtime.createTypedIngredient(rawType, candidate);
        return Optional.ofNullable(typed);
    }
}
