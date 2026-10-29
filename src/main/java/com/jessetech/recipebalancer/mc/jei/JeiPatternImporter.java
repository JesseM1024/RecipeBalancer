package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 把 JEI 的配方布局转成我们的编码样板（输入 / 输出 / 设备）。
 *
 * <p>JEI 加号按钮与编码区右键「自动撰写」共用这套逻辑，保证两边行为一致。
 */
public final class JeiPatternImporter {

    /** 我们编码区的原料列数，决定 JEI 槽位行列如何映射。 */
    private static final int INPUT_COLS = 3;

    private JeiPatternImporter() {
    }

    public static RecipePattern build(IRecipeLayoutDrawable<?> layout) {
        RecipePattern pattern = new RecipePattern();
        applyInputs(layout, pattern);
        int outputIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                .getSlotViews(RecipeIngredientRole.OUTPUT)) {
            JeiIngredientMapper.Mapped mapped = pickIngredient(slot);
            if (mapped != null && outputIndex < RecipePattern.MAX_OUTPUTS) {
                pattern.putOutput(outputIndex++, mapped.key(), mapped.amount());
            }
        }
        applyEquipment(layout, pattern);
        return pattern;
    }

    /** 只有合成配方保留空格（纯视觉），机器配方等一律按顺序紧排。 */
    private static boolean isCraftingRecipe(IRecipeLayoutDrawable<?> layout) {
        try {
            ResourceLocation uid = layout.getRecipeCategory().getRecipeType().getUid();
            return uid != null && uid.getPath().contains("crafting");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 按 JEI 里的相对行列把输入写进编码区；合成配方保留空格，让形状和 JEI 一致；
     * 其它配方（或行列推不出来时）按顺序紧排。
     */
    // getRect() 虽被标记待删除，但它是唯一能拿到「每格独立坐标」的接口
    // （isMouseOver 只能判断悬停、getAreaIncludingBackground 拿不到分格位置），保留并压掉告警。
    @SuppressWarnings("removal")
    private static void applyInputs(IRecipeLayoutDrawable<?> layout, RecipePattern pattern) {
        List<IRecipeSlotView> filled = new ArrayList<>();
        List<Rect2i> rects = new ArrayList<>();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                .getSlotViews(RecipeIngredientRole.INPUT)) {
            if (slot.isEmpty() || !(slot instanceof IRecipeSlotDrawable drawable)) {
                continue;
            }
            filled.add(slot);
            rects.add(drawable.getRect());
        }
        if (filled.isEmpty()) {
            return;
        }
        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (Rect2i rect : rects) {
            if (!xs.contains(rect.getX())) {
                xs.add(rect.getX());
            }
            if (!ys.contains(rect.getY())) {
                ys.add(rect.getY());
            }
        }
        Collections.sort(xs);
        Collections.sort(ys);
        boolean grid = isCraftingRecipe(layout) && xs.size() <= INPUT_COLS && ys.size() <= 9;
        for (int i = 0; i < filled.size(); i++) {
            JeiIngredientMapper.Mapped mapped = pickIngredient(filled.get(i));
            if (mapped == null) {
                continue;
            }
            int index = i;
            if (grid) {
                Rect2i rect = rects.get(i);
                index = ys.indexOf(rect.getY()) * INPUT_COLS + xs.indexOf(rect.getX());
            }
            if (index < RecipePattern.MAX_INPUTS) {
                pattern.putInput(index, mapped.key(), mapped.amount());
            }
        }
    }

    /** 设备：优先取该配方类型的第一个催化剂，其次布局里的 CATALYST 槽。 */
    private static void applyEquipment(IRecipeLayoutDrawable<?> layout, RecipePattern pattern) {
        Object runtimeObject = JeiRuntimeHolder.get();
        if (runtimeObject instanceof IJeiRuntime runtime) {
            try {
                Optional<ItemStack> catalyst = runtime.getRecipeManager()
                        .createRecipeCatalystLookup(layout.getRecipeCategory().getRecipeType())
                        .getItemStack().findFirst();
                if (catalyst.isPresent()) {
                    pattern.setEquipmentKey(ItemKeyMapper.of(catalyst.get()));
                    return;
                }
            } catch (RuntimeException ignored) {
                // 某些自定义类别没有 catalyst lookup，退回 layout CATALYST
            }
        }
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                .getSlotViews(RecipeIngredientRole.CATALYST)) {
            JeiIngredientMapper.Mapped mapped = firstIngredient(slot);
            if (mapped != null) {
                pattern.setEquipmentKey(mapped.key());
                return;
            }
        }
    }

    /**
     * 该槽位有多候选（如不同木板）时，优先选配方库能自产的那种，且取配方序号最小的；
     * 没有能自产的，退回 JEI 当前展示的那个。
     */
    public static JeiIngredientMapper.Mapped pickIngredient(IRecipeSlotView slot) {
        ProductIndex index = ProductIndex.current();
        JeiIngredientMapper.Mapped best = null;
        int bestRecipe = Integer.MAX_VALUE;
        for (ITypedIngredient<?> ingredient : slot.getAllIngredientsList()) {
            JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
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

    public static JeiIngredientMapper.Mapped firstIngredient(IRecipeSlotView slot) {
        Optional<ITypedIngredient<?>> displayed = slot.getDisplayedIngredient();
        if (displayed.isPresent()) {
            return JeiIngredientMapper.of(displayed.get());
        }
        return slot.getAllIngredientsList().stream().findFirst()
                .map(JeiIngredientMapper::of).orElse(null);
    }
}
