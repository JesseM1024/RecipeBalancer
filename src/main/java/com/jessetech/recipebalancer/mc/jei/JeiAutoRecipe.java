package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * 编码区右键的「自动撰写」实现：
 * 查该物质在 JEI 里有多少配方产出——只有 1 个就直接导入并写入配方集，
 * 多于 1 个（例如红石粉有合成 + 各种机器的处理）只打开 JEI 配方页让玩家自己挑。
 */
public final class JeiAutoRecipe implements AutoRecipeHooks.Hook {

    @Override
    public int producerCount(String key) {
        Lookup lookup = lookup(key);
        return lookup == null ? -1 : lookup.count();
    }

    @Override
    public void showRecipes(String key) {
        Lookup lookup = lookup(key);
        if (lookup != null) {
            lookup.runtime().getRecipesGui().show(List.of(lookup.focus()));
        }
    }

    @Override
    public RecipePattern singlePattern(String key) {
        Lookup lookup = lookup(key);
        if (lookup == null || lookup.count() != 1
                || lookup.category() == null || lookup.recipe() == null) {
            return null;
        }
        return build(lookup);
    }

    /** 该物质作为产物在 JEI 里的配方数量，以及唯一配方（若有）。 */
    private static Lookup lookup(String key) {
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return null;
        }
        ITypedIngredient<?> typed = typedFor(runtime, key);
        if (typed == null) {
            return null;
        }
        IFocus<?> focus = runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, typed);
        List<IFocus<?>> focuses = List.of(focus);
        int count = 0;
        IRecipeCategory<?> category = null;
        Object recipe = null;
        try {
            for (IRecipeCategory<?> candidate : runtime.getRecipeManager()
                    .createRecipeCategoryLookup().limitFocus(focuses).get().toList()) {
                List<?> recipes = recipesOf(runtime, candidate, focuses);
                count += recipes.size();
                if (recipes.size() == 1 && recipe == null) {
                    recipe = recipes.get(0);
                    category = candidate;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return new Lookup(runtime, focus, count, category, recipe);
    }

    private static ITypedIngredient<?> typedFor(IJeiRuntime runtime, String key) {
        try {
            ItemStack stack = ItemKeyMapper.stack(key);
            if (!stack.isEmpty()) {
                // 1 参重载已标记待删除，等价于 normalize=false
                return runtime.getIngredientManager()
                        .createTypedIngredient(stack).orElse(null);
            }
            return JeiIngredientRenderer.resolveTyped(key).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<?> recipesOf(IJeiRuntime runtime, IRecipeCategory<?> category,
                                     List<IFocus<?>> focuses) {
        return runtime.getRecipeManager()
                .createRecipeLookup((RecipeType) category.getRecipeType())
                .limitFocus(focuses)
                .get()
                .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RecipePattern build(Lookup lookup) {
        IFocusGroup group = lookup.runtime().getJeiHelpers().getFocusFactory()
                .getEmptyFocusGroup();
        Optional<IRecipeLayoutDrawable<?>> layout = (Optional) lookup.runtime().getRecipeManager()
                .createRecipeLayoutDrawable((IRecipeCategory) lookup.category(),
                        lookup.recipe(), group);
        return layout.map(JeiPatternImporter::build).orElse(null);
    }

    private record Lookup(IJeiRuntime runtime, IFocus<?> focus, int count,
                          IRecipeCategory<?> category, Object recipe) {
    }
}
