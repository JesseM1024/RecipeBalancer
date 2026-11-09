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
            int originX = typed.getPosX();
            int originY = typed.getPosY();
            addGroup(slots, typed.getItemStacks(), originX, originY);
            addGroup(slots, typed.getFluidStacks(), originX, originY);
            addOtherTypes(slots, typed, originX, originY);
        } catch (RuntimeException | LinkageError e) {
            // 布局结构异常时返回已解析的部分
        }
        return slots;
    }

    /**
     * 收集物品/流体之外的配料类型（Mekanism 的 gas、AE2 的 gas 等）。
     *
     * <p>JEI 的槽位是按「配料类型」分组存放的：只读 {@code getItemStacks()/getFluidStacks()}
     * 会漏掉整条配方里的化学品，于是导入后「产物为空」→ 写入时报「至少标记 1 个产物」。
     * 7.x 提供了 {@code getRegisteredIngredientTypes()}，逐个类型取分组即可。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addOtherTypes(List<Slot> slots, RecipeLayout<?> layout,
                                      int originX, int originY) {
        mezz.jei.api.runtime.IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null) {
            return;
        }
        for (mezz.jei.api.ingredients.IIngredientType<?> type
                : runtime.getIngredientManager().getRegisteredIngredientTypes()) {
            if (type == mezz.jei.api.constants.VanillaTypes.ITEM
                    || type == mezz.jei.api.constants.VanillaTypes.FLUID) {
                continue;
            }
            try {
                IGuiIngredientGroup group = layout.getIngredientsGroup((mezz.jei.api.ingredients.IIngredientType) type);
                addGroup(slots, group, originX, originY);
            } catch (RuntimeException | LinkageError ignored) {
                // 该类别没有定义这种配料类型，跳过
            }
        }
    }

    /**
     * 收集一个分组里的槽位。
     *
     * <p>输入/输出<b>必须逐槽位问 {@code GuiIngredient#isInput()}</b>：
     * 物品组里既有原料也有产物（合成配方的产物就在同一组），
     * 一律当输入会导致「产物落到原料区、输出为空」。
     */
    private static <T> void addGroup(List<Slot> slots, IGuiIngredientGroup<T> group,
                                     int originX, int originY) {
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
            // 候选顺序影响导入时取到的数量：JEI 槽位可能同时给出多个候选
            // （同一流体的不同 amount / 不同 NBT），把「当前显示的那个」排到最前，
            // 这样编码出来的数量就是玩家在配方页看到的数字。
            List<Object> ordered = new ArrayList<>(all.size() + 1);
            T displayed = ingredient.getDisplayedIngredient();
            if (displayed != null) {
                ordered.add(displayed);
            }
            for (T candidate : all) {
                if (candidate != displayed) {
                    ordered.add(candidate);
                }
            }
            Rectangle2d rect = rectOf(ingredient);
            // 7.x 的槽位矩形是**配方局部坐标**（绘制时靠 MatrixStack 平移），
            // 必须加上 RecipeLayout 的原点才是屏幕绝对坐标，否则高亮会画到左上角。
            slots.add(new Slot(
                    rect == null ? -1 : rect.getX() + originX,
                    rect == null ? -1 : rect.getY() + originY,
                    rect == null ? 0 : rect.getWidth(),
                    rect == null ? 0 : rect.getHeight(),
                    isInput(ingredient),
                    ordered));
        }
    }

    /** 7.x 的输入/输出在实现类上是 {@code isInput()}（公共接口没有）。 */
    private static boolean isInput(Object ingredient) {
        try {
            java.lang.reflect.Method method = ingredient.getClass().getMethod("isInput");
            method.setAccessible(true);
            Object value = method.invoke(ingredient);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 取不到就按输入处理：宁可多一个原料，也不要丢产物
            return true;
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
