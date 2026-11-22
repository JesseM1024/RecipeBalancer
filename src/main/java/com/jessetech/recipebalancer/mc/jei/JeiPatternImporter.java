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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 JEI 的配方转成我们的编码样板（输入 / 输出 / 设备）。
 *
 * <p>两种入口：
 * <ul>
 *   <li>{@link #buildFromSlots}：从统一的槽位视图导入（9.x 叠加按钮 / 10.x 官方按钮），
 *       带矩形时保留合成配方的九宫格形状；</li>
 *   <li>{@link #build}：9.x 的备用路径，按配料分组顺序导入（分组 API 在 9.x 可用，
 *       10.x 已被废弃/裁掉，只作为兜底）。</li>
 * </ul>
 */
public final class JeiPatternImporter {

    /** 我们编码区的原料列数，决定 JEI 槽位行列如何映射。 */
    private static final int INPUT_COLS = 3;

    private JeiPatternImporter() {
    }

    /**
     * 统一入口：槽位视图 → 编码样板。
     *
     * <p>合成配方按槽位矩形还原九宫格形状（与 1.20.1 行为一致）；机器配方或取不到矩形时
     * 按顺序紧排，保证内容不丢。
     */
    public static RecipePattern buildFromSlots(IRecipeCategory<?> category,
                                               List<JeiSlots.Slot> slots) {
        RecipePattern pattern = new RecipePattern();
        if (slots == null || slots.isEmpty()) {
            applyEquipment(category, pattern);
            return pattern;
        }
        List<JeiSlots.Slot> inputs = new ArrayList<>();
        List<JeiSlots.Slot> outputs = new ArrayList<>();
        for (JeiSlots.Slot slot : slots) {
            if (slot.role() == mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT) {
                outputs.add(slot);
            } else if (slot.role() == mezz.jei.api.recipe.RecipeIngredientRole.INPUT) {
                inputs.add(slot);
            }
        }

        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        boolean allHaveRect = true;
        for (JeiSlots.Slot slot : inputs) {
            if (!slot.hasRect()) {
                allHaveRect = false;
                continue;
            }
            if (!xs.contains(slot.x())) {
                xs.add(slot.x());
            }
            if (!ys.contains(slot.y())) {
                ys.add(slot.y());
            }
        }
        Collections.sort(xs);
        Collections.sort(ys);
        boolean grid = allHaveRect && isCraftingRecipe(category)
                && !xs.isEmpty() && xs.size() <= INPUT_COLS && ys.size() <= 9;

        int inputIndex = 0;
        for (JeiSlots.Slot slot : inputs) {
            JeiIngredientMapper.Mapped mapped = pick(slot.ingredients());
            if (mapped == null) {
                continue;
            }
            int index = inputIndex++;
            if (grid) {
                index = ys.indexOf(slot.y()) * INPUT_COLS + xs.indexOf(slot.x());
            }
            if (index >= 0 && index < RecipePattern.MAX_INPUTS) {
                pattern.putInput(index, mapped.key(),
                        restoreAmount(category, slot, mapped.amount()));
            }
        }
        int outputIndex = 0;
        for (JeiSlots.Slot slot : outputs) {
            JeiIngredientMapper.Mapped mapped = pick(slot.ingredients());
            if (mapped == null || outputIndex >= RecipePattern.MAX_OUTPUTS) {
                continue;
            }
            pattern.putOutput(outputIndex++, mapped.key(),
                    restoreAmount(category, slot, mapped.amount()));
        }
        applyEquipment(category, pattern);
        return pattern;
    }

    /** 只有合成配方保留空格（纯视觉），机器配方等一律按顺序紧排。 */
    /**
     * 还原 Create 的「流体可见性放大」。
     *
     * <p>Create 的 {@code CreateRecipeCategory#withImprovedVisibility(FluidStack)} 把槽位里的
     * 数量改成 {@code (int)(原量 * 0.75) + 250}（1000 是不动点），于是 JEI 槽位里的 250 mB
     * 会显示成 437 mB。按逆函数还原：437→250、625→500、1000→1000。
     *
     * <p>只作用于 {@code create} 命名空间的类别与流体槽；其它模组不受影响。
     * Create 6.0（1.20.1 / 1.21.1）可能调整过这套算法，若数值不符需按实际公式更新。
     */
    private static double restoreAmount(IRecipeCategory<?> category, JeiSlots.Slot slot,
                                        double amount) {
        if (amount <= 0 || !isFluidSlot(slot)) {
            return amount;
        }
        net.minecraft.resources.ResourceLocation uid;
        try {
            uid = category == null ? null : category.getUid();
        } catch (RuntimeException | LinkageError e) {
            return amount;
        }
        if (uid == null || !"create".equals(uid.getNamespace())) {
            return amount;
        }
        double shownMb = amount * 1000.0;
        double restoredMb = Math.ceil((shownMb - 250.0) / 0.75);
        return restoredMb > 0 ? restoredMb / 1000.0 : amount;
    }

    private static boolean isFluidSlot(JeiSlots.Slot slot) {
        for (ITypedIngredient<?> candidate : slot.ingredients()) {
            if (candidate != null
                    && candidate.getIngredient(mezz.jei.api.constants.VanillaTypes.FLUID).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCraftingRecipe(IRecipeCategory<?> category) {
        try {
            net.minecraft.resources.ResourceLocation uid = category.getUid();
            return uid != null && uid.getPath().contains("crafting");
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * 该槽位有多候选（如不同木板）时，优先选配方库能自产的那种，且取配方序号最小的；
     * 没有能自产的，退回 JEI 当前展示的那个（取第一个候选）。
     */
    public static JeiIngredientMapper.Mapped pick(List<ITypedIngredient<?>> candidates) {
        ProductIndex index = ProductIndex.current();
        JeiIngredientMapper.Mapped best = null;
        int bestRecipe = Integer.MAX_VALUE;
        for (ITypedIngredient<?> candidate : candidates) {
            JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(candidate);
            if (mapped == null) {
                continue;
            }
            int at = index.indexOf(mapped.key());
            if (at >= 0 && at < bestRecipe) {
                bestRecipe = at;
                best = mapped;
            }
        }
        if (best != null) {
            return best;
        }
        for (ITypedIngredient<?> candidate : candidates) {
            JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(candidate);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /**
     * 入口：一份 JEI 配方布局 → 编码样板。
     *
     * <p>先尝试 10.x 的 {@code getRecipeSlotsView()}（反射调用，9.x 没有这个方法），
     * 这样能保留九宫格形状；取不到再退回 9.x 的配料分组顺序导入。
     *
     * <p>9.x 的 {@code IRecipeLayoutDrawable} 没有 {@code getRecipeCategory()}，
     * 所以类别由调用方一起传进来（催化剂查询要用）。
     */
    public static RecipePattern build(IRecipeCategory<?> category, IRecipeLayoutDrawable layout) {
        RecipePattern pattern = new RecipePattern();
        if (layout == null) {
            return pattern;
        }
        RecipePattern viaSlots = buildViaSlotsView(category, layout);
        if (viaSlots != null) {
            return viaSlots;
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

    /** 10.x 才有 {@code getRecipeSlotsView()}：反射取槽位视图，取不到返回 null 走旧路径。 */
    private static RecipePattern buildViaSlotsView(IRecipeCategory<?> category, Object layout) {
        try {
            java.lang.reflect.Method method = layout.getClass().getMethod("getRecipeSlotsView");
            method.setAccessible(true);
            Object view = method.invoke(layout);
            if (view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotsView slotsView) {
                List<JeiSlots.Slot> slots = JeiSlots.fromView(slotsView);
                if (!slots.isEmpty()) {
                    return buildFromSlots(category, slots);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 9.x 没有这个方法，或实现类换了名字：交给下面的分组路径
        }
        return null;
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
