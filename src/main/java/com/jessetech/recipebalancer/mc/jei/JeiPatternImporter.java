package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.gui.recipes.RecipeLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 JEI 的配方转成我们的编码样板（输入 / 输出 / 设备），1.12.2 / JEI 4.x 版。
 *
 * <p>槽位来源是 {@code RecipeLayout} 的分组（{@link JeiSlots#fromLayout}）：
 * 合成配方带矩形时按九宫格还原形状，机器配方或取不到矩形时按顺序紧排。
 *
 * <p>4.x 的类别 uid 是 {@code String}（{@code "create:item_draining"} 这种），
 * 因此命名空间判断从 {@code ResourceLocation} 改成字符串前缀。
 */
public final class JeiPatternImporter {

    /** 我们编码区的原料列数，决定 JEI 槽位行列如何映射。 */
    private static final int INPUT_COLS = 3;

    private JeiPatternImporter() {
    }

    /** 统一入口：槽位视图 → 编码样板（不带配方原始量，数量按槽位显示值）。 */
    public static RecipePattern buildFromSlots(IRecipeCategory<?> category,
                                               List<JeiSlots.Slot> slots) {
        return buildFromSlots(category, slots, Collections.<String, Double>emptyMap());
    }

    /**
     * 带「配方原始流体量」的入口。
     *
     * @param fluidOriginals 流体注册名 → 配方里声明的真实桶数（由 {@link #fluidOriginals} 提取）
     */
    public static RecipePattern buildFromSlots(IRecipeCategory<?> category,
                                               List<JeiSlots.Slot> slots,
                                               Map<String, Double> fluidOriginals) {
        return buildFromSlots(category, slots, fluidOriginals,
                Collections.<String>emptySet());
    }

    /**
     * 完整入口。
     *
     * @param realOutputs 配方对象里声明的**真实产物** key（可为空）；用于剔除被 JEI 标记为
     *                    “输出”的辅助显示槽 —— 例如 Mekanism 的高级机器类别会把「能提供所需
     *                    气体的燃料物品列表」也注册成输出槽，直接导入会把圆石之类写进产物区。
     */
    public static RecipePattern buildFromSlots(IRecipeCategory<?> category,
                                               List<JeiSlots.Slot> slots,
                                               Map<String, Double> fluidOriginals,
                                               Set<String> realOutputs) {
        return buildFromSlots(category, slots, fluidOriginals, realOutputs, null);
    }

    /** 带原始配方的版本：在原有输入/输出/设备之外，多一步耗时预填。 */
    public static RecipePattern buildFromSlots(IRecipeCategory<?> category,
                                               List<JeiSlots.Slot> slots,
                                               Map<String, Double> fluidOriginals,
                                               Set<String> realOutputs,
                                               Object recipe) {
        RecipePattern pattern = buildFromSlotsInternal(category, slots, fluidOriginals, realOutputs);
        applyTime(category, recipe, pattern);
        return pattern;
    }

    private static RecipePattern buildFromSlotsInternal(IRecipeCategory<?> category,
                                                        List<JeiSlots.Slot> slots,
                                                        Map<String, Double> fluidOriginals,
                                                        Set<String> realOutputs) {
        RecipePattern pattern = new RecipePattern();
        if (slots == null || slots.isEmpty()) {
            applyEquipment(category, pattern);
            return pattern;
        }
        // 部分类别的「排水」流体槽在 JEI 里声明成输入，视觉上其实是输出；
        // 按类别 uid 修正，否则气/液产物会全部落到原料区、产物为空。
        boolean draining = isDrainingCategory(category);
        List<JeiSlots.Slot> inputs = new ArrayList<>();
        List<JeiSlots.Slot> outputs = new ArrayList<>();
        for (JeiSlots.Slot slot : slots) {
            boolean asOutput = !slot.input() || (draining && slot.fluid());
            if (asOutput) {
                outputs.add(slot);
            } else {
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
        boolean grid = allHaveRect && isCraftingCategory(category)
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
                double amount = restoreAmount(category, slot, mapped.amount(), fluidOriginals);
                pattern.putInput(index, mapped.key(), amount);
                logImported("in", slot, mapped, amount);
            }
        }
        int itemOutputSlots = 0;
        for (JeiSlots.Slot slot : outputs) {
            if (isItemSlot(slot)) {
                itemOutputSlots++;
            }
        }
        // 只有当“物品输出槽数量 > 配方真实产物数量”时才启用剔除，避免把真正的多产物配方砍掉
        boolean filterAuxiliary = !realOutputs.isEmpty()
                && itemOutputSlots > realOutputs.size();
        int outputIndex = 0;
        for (JeiSlots.Slot slot : outputs) {
            JeiIngredientMapper.Mapped mapped = pick(slot.ingredients());
            if (mapped == null || outputIndex >= RecipePattern.MAX_OUTPUTS) {
                continue;
            }
            if (filterAuxiliary && isItemSlot(slot) && !realOutputs.contains(mapped.key())) {
                // 辅助显示槽（MEK 的“能提供所需气体的燃料物品”）：不是产物，但确实是玩家要投入的
                // 物料，按输入处理，不能直接丢掉。
                int target = pattern.firstEmptyInput();
                if (target >= 0 && target < RecipePattern.MAX_INPUTS) {
                    pattern.putInput(target, mapped.key(), mapped.amount());
                    logImported("in-aux", slot, mapped, mapped.amount());
                }
                continue;
            }
            double amount = restoreAmount(category, slot, mapped.amount(), fluidOriginals);
            pattern.putOutput(outputIndex++, mapped.key(), amount);
            logImported("out", slot, mapped, amount);
        }
        applyEquipment(category, pattern);
        return pattern;
    }

    /** 已记录过的导入槽位（key + 数量 + 角色），避免每次导入都刷屏。 */
    private static final java.util.Set<String> IMPORT_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 每个（角色 + key + 数量）只记一次。
     *
     * <p>用于排查"某个槽位的数量读成了默认 1 B"这类问题：日志里能直接看到
     * 槽位是输入/输出、是不是流体、拿到的数量是多少。
     */
    private static void logImported(String role, JeiSlots.Slot slot,
                                    JeiIngredientMapper.Mapped mapped, double amount) {
        String key = role + '|' + mapped.key() + '|' + amount;
        if (!IMPORT_LOGGED.add(key)) {
            return;
        }
        com.jessetech.recipebalancer.RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] 导入槽位[{}]: key={} fluid={} 显示名={} 数量={}",
                role, mapped.key(), slot.fluid(), mapped.displayName(), amount);
    }

    /** 一份 JEI 配方布局 → 编码样板（4.x 的布局对象是 {@code RecipeLayout}）。 */
    public static RecipePattern build(IRecipeCategory<?> category, Object layout) {
        return build(category, layout, null);
    }

    /** 带原始配方的版本：多一步耗时预填。 */
    public static RecipePattern build(IRecipeCategory<?> category, Object layout, Object recipe) {
        List<JeiSlots.Slot> slots = JeiSlots.fromLayout(layout);
        return buildFromSlots(category, slots, fluidOriginals(layout), realOutputKeys(layout), recipe);
    }

    /** 槽位是不是物品槽（候选配料是 {@code ItemStack}）。 */
    private static boolean isItemSlot(JeiSlots.Slot slot) {
        List<Object> ingredients = slot.ingredients();
        return !ingredients.isEmpty() && ingredients.get(0) instanceof ItemStack;
    }

    /**
     * 从配方对象里读「真实产物」。
     *
     * <p>为什么需要：Mekanism 的 {@code AdvancedMachineRecipeCategory} 会把
     * 「能提供所需气体的燃料物品列表」注册成 {@code init(slot, false, ...)}（输出槽），
     * 于是融合机这类配方编码后会把圆石一类的燃料物品写进产物区。用配方里声明的
     * {@code recipeOutput.output} 做交叉验证，就能把这类辅助槽剔掉。
     *
     * <p>4.x 的 {@code RecipeLayout} 没有公开的 {@code getRecipe()}，只能反射
     * {@code recipeWrapper}，再走 wrapper 的 {@code getRecipe()} / {@code recipe} 字段。
     *
     * @return 提取不到时返回空集合（调用方据此关闭剔除逻辑）
     */
    private static Set<String> realOutputKeys(Object layout) {
        Set<String> keys = new HashSet<>();
        if (!(layout instanceof RecipeLayout)) {
            return keys;
        }
        try {
            Object wrapper = JeiReflect.field(layout, "recipeWrapper");
            if (wrapper == null) {
                return keys;
            }
            Object recipe = JeiReflect.call(wrapper, "getRecipe");
            if (recipe == null) {
                recipe = JeiReflect.field(wrapper, "recipe");
            }
            if (recipe == null) {
                return keys;
            }
            Object output = JeiReflect.field(recipe, "recipeOutput");
            if (output == null) {
                return keys;
            }
            Object stack = JeiReflect.field(output, "output");
            if (stack instanceof ItemStack && !((ItemStack) stack).isEmpty()) {
                keys.add(ItemKeyMapper.of((ItemStack) stack));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 提取失败＝关闭剔除逻辑，保持原行为
        }
        return keys;
    }

    /**
     * 从配方对象读出流体的<b>真实</b>用量（桶）。
     *
     * <p>4.x 的 {@code RecipeLayout} 没有公开的 {@code getRecipe()}，只能反射私有字段
     * {@code recipeWrapper}；拿到 wrapper 后再试 {@code getRequiredFluid/getResultingFluid}
     * 以及 wrapper 内部的 {@code recipe} 字段。取不到返回空表（调用方退回逆函数兜底）。
     */
    private static Map<String, Double> fluidOriginals(Object layout) {
        Map<String, Double> out = new HashMap<>();
        if (!(layout instanceof RecipeLayout)) {
            return out;
        }
        Object recipe = JeiReflect.field(layout, "recipeWrapper");
        if (recipe == null) {
            return out;
        }
        Object inner = JeiReflect.field(recipe, "recipe");
        if (inner != null) {
            recipe = inner;
        }
        for (String getter : new String[]{"getRequiredFluid", "getResultingFluid"}) {
            try {
                collectFluid(out, recipe.getClass().getMethod(getter).invoke(recipe));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 该配方类没有这个方法：换下一个
            }
        }
        return out;
    }

    private static void collectFluid(Map<String, Double> out, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof FluidStack) {
            putFluid(out, (FluidStack) value);
            return;
        }
        // Create 的 FluidIngredient：getMatchingFluidStacks() 里的 FluidStack 是原始量
        try {
            Object stacks = value.getClass().getMethod("getMatchingFluidStacks").invoke(value);
            if (stacks instanceof Iterable) {
                for (Object stack : (Iterable<?>) stacks) {
                    if (stack instanceof FluidStack) {
                        putFluid(out, (FluidStack) stack);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 不是流体描述对象，忽略
        }
    }

    private static void putFluid(Map<String, Double> out, FluidStack stack) {
        if (stack == null || stack.getFluid() == null
                || net.minecraftforge.fluids.FluidRegistry.getFluidName(stack.getFluid()) == null) {
            return;
        }
        out.put(net.minecraftforge.fluids.FluidRegistry.getFluidName(stack.getFluid()).toString(), stack.amount / 1000.0);
    }

    /** 槽位里第一个流体配料的注册名，用于匹配配方原始量。 */
    private static String fluidNameOf(JeiSlots.Slot slot) {
        for (Object candidate : slot.ingredients()) {
            if (candidate instanceof FluidStack) {
                FluidStack stack = (FluidStack) candidate;
                if (stack.getFluid() != null && net.minecraftforge.fluids.FluidRegistry.getFluidName(stack.getFluid()) != null) {
                    return net.minecraftforge.fluids.FluidRegistry.getFluidName(stack.getFluid()).toString();
                }
            }
        }
        return null;
    }

    /** 4.x 的类别 uid 是 {@code String}（如 {@code create:item_draining}）。 */
    private static String uidOf(IRecipeCategory<?> category) {
        try {
            return category == null ? null : category.getUid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 「排水 / 倒空」类别把流体槽声明成了输入，但它在配方页画在右侧、语义上是产物。
     * 按类别 uid 修正。
     */
    private static boolean isDrainingCategory(IRecipeCategory<?> category) {
        String uid = uidOf(category);
        return uid != null && uid.startsWith("create:") && uid.contains("drain");
    }

    /** 已提示过的（类别 + 显示量），避免每次导入都刷屏。 */
    private static final java.util.Set<String> AMOUNT_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 还原「流体可见性放大」。
     *
     * <p>Create 的 {@code CreateRecipeCategory#withImprovedVisibility(FluidStack)} 是：
     * {@code amount = (int)(原量 * 0.75) + 250}（1000 正好是不动点）。
     * 按逆函数 {@code ceil((shown - 250) / 0.75)} 还原：437→250、625→500、1000→1000。
     * 只对 {@code create:} 类别生效，且只作用于流体槽。
     */
    private static double restoreAmount(IRecipeCategory<?> category, JeiSlots.Slot slot,
                                        double amount, Map<String, Double> originals) {
        if (!slot.fluid() || amount <= 0) {
            return amount;
        }
        // 首选：配方对象里声明的真实用量（与模组无关，只要配方类暴露了 getter）
        String fluid = fluidNameOf(slot);
        if (fluid != null && originals != null) {
            Double original = originals.get(fluid);
            if (original != null && original > 0) {
                if (Math.abs(original - amount) > 1e-6) {
                    logRestore(fluid, amount, original, "配方原始量");
                }
                return original;
            }
        }
        // 兜底：Create 的显示量放大（仅 create 命名空间）
        return createInverse(category, amount);
    }

    private static double createInverse(IRecipeCategory<?> category, double amount) {
        String uid = uidOf(category);
        if (uid == null || !uid.startsWith("create:")) {
            return amount;
        }
        double shownMb = amount * 1000.0;
        double restoredMb = Math.ceil((shownMb - 250.0) / 0.75);
        if (restoredMb <= 0) {
            return amount;
        }
        double restored = restoredMb / 1000.0;
        if (Math.abs(restored - amount) > 1e-6) {
            logRestore(uid + "/" + shownMb, amount, restored, "显示量逆运算");
        }
        return restored;
    }

    private static void logRestore(String what, double shown, double restored, String source) {
        if (!AMOUNT_LOGGED.add(what + "@" + shown)) {
            return;
        }
        com.jessetech.recipebalancer.RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] 流体量还原（{}）: {} mB -> {} mB",
                source, shown * 1000.0, restored * 1000.0);
    }

    /** 只有合成配方保留空格（纯视觉），机器配方等一律按顺序紧排。 */
    private static boolean isCraftingCategory(IRecipeCategory<?> category) {
        String uid = uidOf(category);
        return uid != null && uid.contains("crafting");
    }

    /**
     * 该槽位有多候选（如不同木板）时，优先选配方库能自产的那种，且取配方序号最小的；
     * 没有能自产的，退回 JEI 当前展示的那个（取第一个候选）。
     */
    public static JeiIngredientMapper.Mapped pick(List<Object> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ProductIndex index = ProductIndex.current();
        JeiIngredientMapper.Mapped best = null;
        int bestRecipe = Integer.MAX_VALUE;
        for (Object candidate : candidates) {
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
        for (Object candidate : candidates) {
            JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(candidate);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /** 设备：取该配方类型的第一个催化剂。 */
    /**
     * 预填源配方的耗时。
     *
     * <p>只作为参考值：写入后仍是普通耗时，用户打开耗时编辑屏即可改写；
     * 推断失败时什么都不做，保持 1t。
     */
    private static void applyTime(IRecipeCategory<?> category, Object recipe, RecipePattern pattern) {
        try {
            // 类别 uid 只在这里取一次，用于结果标注与日志
            String categoryUid = JeiRecipeTime.categoryUid(category);
            Optional<JeiRecipeTime.Result> inferred = JeiRecipeTime.infer(recipe, category);
            if (!inferred.isPresent()) {
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 耗时未推断：recipe={}，类别={}",
                        recipe == null ? "null" : recipe.getClass().getName(), categoryUid);
                return;
            }
            int ticks = inferred.get().ticks();
            pattern.setTimeTicks(ticks);
            pattern.setTimeHint(ticks, categoryUid);
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 耗时预填：{} -> {}t（{}，类别 {}）",
                    recipe == null ? "null" : recipe.getClass().getSimpleName(),
                    Integer.valueOf(ticks), inferred.get().origin(), categoryUid);
        } catch (RuntimeException e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 耗时预填异常：recipe={}",
                    recipe == null ? "null" : recipe.getClass().getName(), e);
        } catch (LinkageError e) {
            // 兼容模组缺失/版本错配：静默保持 1t
        }
    }

    private static void applyEquipment(IRecipeCategory<?> category, RecipePattern pattern) {
        IJeiRuntime runtime = JeiAutoRecipe.runtime();
        if (runtime == null || category == null) {
            return;
        }
        try {
            for (Object catalyst : runtime.getRecipeRegistry().getRecipeCatalysts(category)) {
                if (catalyst instanceof ItemStack) {
                    ItemStack stack = (ItemStack) catalyst;
                    if (stack.isEmpty()) {
                        continue;
                    }
                    pattern.setEquipmentKey(ItemKeyMapper.of(stack));
                    return;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 催化剂查不到就不填设备，编码内容本身仍然正确
        }
    }
}
