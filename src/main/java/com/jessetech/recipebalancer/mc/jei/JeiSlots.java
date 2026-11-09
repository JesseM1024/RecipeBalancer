package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;
import mezz.jei.api.gui.ingredient.IGuiIngredient;
import mezz.jei.api.gui.ingredient.IGuiIngredientGroup;
import mezz.jei.gui.recipes.RecipeLayout;
import net.minecraft.client.renderer.Rectangle2d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 JEI 的配方布局统一成「位置 + 角色 + 候选配料」，供导入与高亮共用（1.16.5 / JEI 7.x）。
 *
 * <p>7.x 的槽位信息在 {@code RecipeLayout#getItemStacks()/getFluidStacks()} 返回的分组里：
 * 每个 {@code IGuiIngredient} 的实现类 {@code GuiIngredient} 才带矩形（{@code getRect()}）。
 * 这里按接口 + 实现类的组合取，取不到矩形就退化为「按顺序紧排」，不影响编码出正确内容。
 */
public final class JeiSlots {

    /** 一个槽位：屏幕坐标矩形（取不到时宽高为 0）与全部候选配料。 */
    public static final class Slot {

        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final boolean input;
        private final List<Object> ingredients;

        public Slot(int x, int y, int width, int height, boolean input,
                    List<Object> ingredients) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.input = input;
            this.ingredients = ingredients == null ? J8.list() : J8.listCopy(ingredients);
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public boolean input() {
            return input;
        }

        public List<Object> ingredients() {
            return ingredients;
        }

        public boolean hasRect() {
            return width > 0 && height > 0;
        }
    }

    private JeiSlots() {
    }

    /** 从一个 7.x 的 {@code RecipeLayout} 收集全部槽位（物品 + 流体）。 */
    public static List<Slot> fromLayout(Object layout) {
        List<Slot> slots = new ArrayList<>();
        if (!(layout instanceof RecipeLayout)) {
            return slots;
        }
        try {
            RecipeLayout<?> typed = (RecipeLayout<?>) layout;
            addGroup(slots, typed.getItemStacks(), true);
            addGroup(slots, typed.getFluidStacks(), false);
        } catch (RuntimeException | LinkageError e) {
            // 布局结构异常时返回已解析的部分
        }
        return slots;
    }

    private static <T> void addGroup(List<Slot> slots, IGuiIngredientGroup<T> group,
                                     boolean input) {
        if (group == null) {
            return;
        }
        Map<Integer, ? extends IGuiIngredient<T>> ingredients = group.getGuiIngredients();
        if (ingredients == null) {
            return;
        }
        for (IGuiIngredient<T> ingredient : ingredients.values()) {
            if (ingredient == null) {
                continue;
            }
            List<T> all = ingredient.getAllIngredients();
            if (all == null || all.isEmpty()) {
                continue;
            }
            Rectangle2d rect = rectOf(ingredient);
            slots.add(new Slot(
                    rect == null ? -1 : rect.getX(),
                    rect == null ? -1 : rect.getY(),
                    rect == null ? 0 : rect.getWidth(),
                    rect == null ? 0 : rect.getHeight(),
                    input,
                    new ArrayList<Object>(all)));
        }
    }

    /**
     * 7.x 的矩形只在实现类 {@code mezz.jei.gui.ingredients.GuiIngredient} 上
     * （公共接口 {@code IGuiIngredient} 没有），用反射取，取不到返回 null。
     */
    private static Rectangle2d rectOf(Object ingredient) {
        try {
            java.lang.reflect.Method method = ingredient.getClass().getMethod("getRect");
            method.setAccessible(true);
            Object value = method.invoke(ingredient);
            return value instanceof Rectangle2d ? (Rectangle2d) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 槽位矩形是否已经是屏幕绝对坐标（直接可用）。 */
    public static Rectangle2d absolute(Slot slot) {
        return new Rectangle2d(slot.x(), slot.y(), slot.width(), slot.height());
    }
}
