package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「自动撰写」：右键编码区里的物质时，查 JEI 里生产它的配方。
 *
 * <p>只有一个配方就把它转成编码样板并直接写入配方集；有多个则只打开 JEI 配方页，
 * 由玩家自己挑。查询全部走 {@code IRecipeManager} 的公开 API（9.x / 10.x 通用）。
 */
public final class JeiAutoRecipe implements AutoRecipeHooks.Hook {

    /** 配方与它所属类别，用于「唯一配方」时创建布局。 */
    private record Found(IRecipeCategory<Object> category, Object recipe) {
    }

    @Override
    public int producerCount(String key) {
        return find(key).size();
    }

    @Override
    public void showRecipes(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return;
        }
        try {
            runtime.getRecipesGui().show(focus(runtime, key));
        } catch (RuntimeException | LinkageError ignored) {
            // 打不开配方页时什么都不做，界面层会提示「JEI 不可用」
        }
    }

    @Override
    public RecipePattern singlePattern(String key) {
        List<Found> found = find(key);
        if (found.size() != 1) {
            return null;
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Found only = found.get(0);
            IRecipeLayoutDrawable layout = runtime.getRecipeManager()
                    .createRecipeLayoutDrawable(only.category(), only.recipe(),
                            focus(runtime, key));
            return layout == null ? null : JeiPatternImporter.build(only.category(), layout);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 查询生产该物质的所有配方（按类别分组后摊平）。 */
    private static List<Found> find(String key) {
        List<Found> result = new ArrayList<>();
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || key == null || key.isBlank()) {
            return result;
        }
        IFocus<ItemStack> focus = focus(runtime, key);
        if (focus == null) {
            return result;
        }
        try {
            for (IRecipeCategory<?> category : runtime.getRecipeManager()
                    .getRecipeCategories(focus, true)) {
                result.addAll(recipesOf(category, focus));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 查询失败按「查不到」处理
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Found> recipesOf(IRecipeCategory<?> category, IFocus<ItemStack> focus) {
        List<Found> result = new ArrayList<>();
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || category == null) {
            return result;
        }
        List<Object> recipes = runtime.getRecipeManager()
                .getRecipes((IRecipeCategory<Object>) category, focus, true);
        for (Object recipe : recipes) {
            result.add(new Found((IRecipeCategory<Object>) category, recipe));
        }
        return result;
    }

    private static IFocus<ItemStack> focus(IJeiRuntime runtime, String key) {
        ItemStack stack = ItemKeyMapper.stack(key);
        if (stack.isEmpty()) {
            return null;
        }
        try {
            return runtime.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM, stack);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
