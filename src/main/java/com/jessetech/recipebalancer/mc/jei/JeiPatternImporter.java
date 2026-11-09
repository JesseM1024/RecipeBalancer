package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.recipes.RecipeLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 JEI 的配方转成我们的编码样板（输入 / 输出 / 设备），1.16.5 / JEI 7.x 版。
 *
 * <p>7.x 的槽位来源是 {@code RecipeLayout} 的分组（{@link JeiSlots#fromLayout}）：
 * 合成配方带矩形时按九宫格还原形状，机器配方或取不到矩形时按顺序紧排。
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
        RecipePattern pattern = new RecipePattern();
        if (slots == null || slots.isEmpty()) {
            applyEquipment(category, pattern);
            return pattern;
        }
        // Create 的 JEI 类别把「排水」的流体槽用 init(0, true, ...) 声明成输入
        // （ItemDrainCategory 的既有写法），视觉上它其实是输出；
        // 按类别 uid 修正，否则气/液产物会全部落到原料区、产物为空。
        boolean draining = isCreateDraining(category);
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
                        restoreAmount(category, slot, mapped.amount(), fluidOriginals));
            }
        }
        int outputIndex = 0;
        for (JeiSlots.Slot slot : outputs) {
            JeiIngredientMapper.Mapped mapped = pick(slot.ingredients());
            if (mapped == null || outputIndex >= RecipePattern.MAX_OUTPUTS) {
                continue;
            }
            pattern.putOutput(outputIndex++, mapped.key(),
                    restoreAmount(category, slot, mapped.amount(), fluidOriginals));
        }
        applyEquipment(category, pattern);
        return pattern;
    }

    /** 一份 JEI 配方布局 → 编码样板（7.x 的布局对象是 {@code RecipeLayout}）。 */
    public static RecipePattern build(IRecipeCategory<?> category, Object layout) {
        List<JeiSlots.Slot> slots = JeiSlots.fromLayout(layout);
        return buildFromSlots(category, slots, fluidOriginals(layout));
    }

    /**
     * 从配方对象读出流体的<b>真实</b>用量（桶）。
     *
     * <p>比按显示量做逆运算可靠：Create 的部分类别会把槽位里的 FluidStack 数量放大
     * （{@code withImprovedVisibility}），但配方对象本身（{@code FillingRecipe#getRequiredFluid}、
     * {@code EmptyingRecipe#getResultingFluid} 等）始终是原始值。
     * 这里反射取这两类 getter，顺带支持 Create 的 {@code FluidIngredient}（取其匹配流体列表）。
     *
     * @return 流体注册名 → 桶数；取不到返回空表（调用方再退回逆函数兜底）
     */
    private static Map<String, Double> fluidOriginals(Object layout) {
        Map<String, Double> out = new HashMap<>();
        Object recipe;
        try {
            recipe = ((RecipeLayout<?>) layout).getRecipe();
        } catch (RuntimeException | LinkageError e) {
            return out;
        }
        if (recipe == null) {
            return out;
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
                || stack.getFluid().getRegistryName() == null) {
            return;
        }
        out.put(stack.getFluid().getRegistryName().toString(), stack.getAmount() / 1000.0);
    }

    /** 槽位里第一个流体配料的注册名，用于匹配配方原始量。 */
    private static String fluidNameOf(JeiSlots.Slot slot) {
        for (Object candidate : slot.ingredients()) {
            if (candidate instanceof FluidStack) {
                FluidStack stack = (FluidStack) candidate;
                if (stack.getFluid() != null && stack.getFluid().getRegistryName() != null) {
                    return stack.getFluid().getRegistryName().toString();
                }
            }
        }
        return null;
    }

    /**
     * Create 的「排水 / 倒空」（Item Draining）类别把流体槽声明成了输入
     * （{@code ItemDrainCategory} 里是 {@code fluidStacks.init(0, true, ...)}），
     * 但它在配方页画在右侧、语义上是产物。这里按类别 uid 修正。
     */
    private static boolean isCreateDraining(IRecipeCategory<?> category) {
        try {
            net.minecraft.util.ResourceLocation uid = category.getUid();
            return uid != null && "create".equals(uid.getNamespace())
                    && uid.getPath().contains("drain");
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 已提示过的（类别 + 显示量），避免每次导入都刷屏。 */
    private static final java.util.Set<String> AMOUNT_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 还原 Create 的「流体可见性放大」。
     *
     * <p>Create 的 {@code CreateRecipeCategory#withImprovedVisibility(FluidStack)} 是：
     * {@code amount = (int)(原量 * 0.75) + 250}（1000 正好是不动点）。
     * 于是在 JEI 里看到的 tooltip 是原量（250 mB），槽位里存的 FluidStack 却是 437 mB。
     * 按逆函数 {@code ceil((shown - 250) / 0.75)} 还原：437→250、625→500、1000→1000。
     *
     * <p>只对 {@code create} 命名空间的类别生效，且只作用于流体槽。
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
        net.minecraft.util.ResourceLocation uid;
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
    private static boolean isCraftingRecipe(IRecipeCategory<?> category) {
        try {
            net.minecraft.util.ResourceLocation uid = category.getUid();
            return uid != null && uid.getPath().contains("crafting");
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
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
    private static void applyEquipment(IRecipeCategory<?> category, RecipePattern pattern) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || category == null) {
            return;
        }
        try {
            for (Object catalyst : runtime.getRecipeManager().getRecipeCatalysts(category)) {
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
