package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.AutoRecipeHooks;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 「自动撰写」：右键编码区里的物质时，查 JEI 里生产它的配方（1.12.2 / JEI 4.x）。
 *
 * <p>只有一个配方就把它转成编码样板并直接写入配方集；有多个则只打开 JEI 配方页，
 * 由玩家自己挑。查询全部走 {@code IRecipeRegistry} 的公开 API。
 *
 * <p>4.x 的差异：配方是 {@code IRecipeWrapper}，类别 uid 是 {@code String}，
 * 焦点没有公开工厂（4.16 才有 {@code IFocusFactory}），统一反射
 * {@code mezz.jei.gui.Focus(IFocus.Mode, V)} —— 该构造器 4.7.9 ~ 4.24 都在。
 */
public final class JeiAutoRecipe implements AutoRecipeHooks.Hook {

    /** 配方与它所属类别，用于「唯一配方」时创建布局。 */
    private static final class Found {

        /** 4.x 的 {@code IRecipeCategory<T extends IRecipeWrapper>} 有上界，这里用裸类型更省事。 */
        @SuppressWarnings("rawtypes")
        private final IRecipeCategory category;
        private final Object wrapper;

        @SuppressWarnings("rawtypes")
        Found(IRecipeCategory category, Object wrapper) {
            this.category = category;
            this.wrapper = wrapper;
        }
    }

    @Override
    public int producerCount(String key) {
        return find(key).size();
    }

    @Override
    public void showRecipes(String key) {
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return;
        }
        try {
            IFocus<ItemStack> focus = focus(key);
            if (focus != null) {
                runtime.getRecipesGui().show(focus);
            }
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
        IJeiRuntime runtime = runtime();
        if (runtime == null) {
            return null;
        }
        try {
            Found only = found.get(0);
            IRecipeLayoutDrawable layout = runtime.getRecipeRegistry()
                    .createRecipeLayoutDrawable(only.category,
                            (mezz.jei.api.recipe.IRecipeWrapper) only.wrapper, focus(key));
            return layout == null ? null : JeiPatternImporter.build(only.category, layout);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 查询生产该物质的所有配方（按类别分组后摊平）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Found> find(String key) {
        List<Found> result = new ArrayList<>();
        IJeiRuntime runtime = runtime();
        if (runtime == null || key == null || J8.isBlank(key)) {
            return result;
        }
        IFocus<ItemStack> focus = focus(key);
        if (focus == null) {
            return result;
        }
        try {
            IRecipeRegistry registry = runtime.getRecipeRegistry();
            List categories = registry.getRecipeCategories(focus);
            if (categories == null) {
                return result;
            }
            for (Object category : categories) {
                if (!(category instanceof IRecipeCategory)) {
                    continue;
                }
                List wrappers = registry.getRecipeWrappers((IRecipeCategory) category, focus);
                if (wrappers == null) {
                    continue;
                }
                for (Object wrapper : wrappers) {
                    result.add(new Found((IRecipeCategory) category, wrapper));
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 查询失败按「查不到」处理
        }
        return result;
    }

    /** 4.x 没有公开的 focus 工厂：反射内部实现类 {@code mezz.jei.gui.Focus}。 */
    @SuppressWarnings("unchecked")
    private static IFocus<ItemStack> focus(String key) {
        ItemStack stack = ItemKeyMapper.stack(key);
        if (stack.isEmpty()) {
            return null;
        }
        try {
            Class<?> focusClass = JeiReflect.type("mezz.jei.gui.Focus");
            if (focusClass == null) {
                JeiReflect.warnOnce("focus-class",
                        "[RecipeBalancer] JEI 焦点类不存在，R/U/A 跳转降级");
                return null;
            }
            Constructor<?> constructor =
                    focusClass.getConstructor(IFocus.Mode.class, Object.class);
            constructor.setAccessible(true);
            return (IFocus<ItemStack>) constructor.newInstance(IFocus.Mode.OUTPUT, stack);
        } catch (Throwable t) {
            JeiReflect.warnOnce("focus-new",
                    "[RecipeBalancer] JEI 焦点构造失败，R/U/A 跳转降级: " + t);
            return null;
        }
    }

    static IJeiRuntime runtime() {
        Object runtime = JeiRuntimeHolder.get();
        return runtime instanceof IJeiRuntime ? (IJeiRuntime) runtime : null;
    }

    /** runtime 重建时记一条日志，确认 hook 已挂上。 */
    static void installed() {
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 自动撰写已挂载: {}", JeiCapabilities.describe());
    }
}
