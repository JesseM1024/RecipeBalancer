package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.gui.recipes.RecipeLayout;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 把 JEI 的配方布局统一成「位置 + 角色 + 候选配料」，供导入与高亮共用（1.12.2 / JEI 4.x）。
 *
 * <p>4.x 的槽位信息在 {@code RecipeLayout#getItemStacks()/getFluidStacks()} 返回的分组里：
 * 每个 {@code IGuiIngredient} 的实现类 {@code GuiIngredient} 才带矩形。
 * 矩形按<b>私有字段 {@code rect}</b> 反射取（4.8.5 之前的 {@code getRect()} 不存在，
 * 而字段从 4.7.9 到 4.24 一直都在），取不到就退化为「无矩形」，不影响编码出正确内容。
 *
 * <p>配料类型统一按 {@code Class} 遍历（{@code getRegisteredIngredientClasses()}），
 * 不用 4.12 才出现的 {@code IIngredientType}。
 */
public final class JeiSlots {

    /** 一个槽位：屏幕坐标矩形（取不到时宽高为 0）与全部候选配料。 */
    public static final class Slot {

        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final boolean input;
        private final boolean fluid;
        private final List<Object> ingredients;

        public Slot(int x, int y, int width, int height, boolean input, boolean fluid,
                    List<Object> ingredients) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.input = input;
            this.fluid = fluid;
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

        /** 是否为流体槽（Create 的 JEI 类别会把显示量放大，需要按类型还原）。 */
        public boolean fluid() {
            return fluid;
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

    /** 从一个 4.x 的 {@code RecipeLayout} 收集全部槽位（物品 + 流体 + 其它类型）。 */
    public static List<Slot> fromLayout(Object layout) {
        List<Slot> slots = new ArrayList<>();
        if (!(layout instanceof RecipeLayout)) {
            return slots;
        }
        try {
            RecipeLayout typed = (RecipeLayout) layout;
            int originX = typed.getPosX();
            int originY = typed.getPosY();
            addGroup(slots, typed.getItemStacks(), originX, originY, false);
            addGroup(slots, typed.getFluidStacks(), originX, originY, true);
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
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addOtherTypes(List<Slot> slots, RecipeLayout layout,
                                      int originX, int originY) {
        IIngredientRegistry registry = JeiIngredientMapper.registry();
        if (registry == null) {
            return;
        }
        Collection<Class<?>> types = JeiIngredientMapper.registeredClasses(registry);
        for (Class<?> type : types) {
            if (type == ItemStack.class || type == FluidStack.class) {
                continue;
            }
            try {
                IGuiIngredientGroup group = layout.getIngredientsGroup((Class) type);
                addGroup(slots, group, originX, originY, false);
            } catch (RuntimeException | LinkageError ignored) {
                // 该类别没有定义这种配料类型，跳过
            }
        }
    }

    /**
     * 收集一个分组里的槽位。
     *
     * <p>输入/输出<b>必须逐槽位问 {@code IGuiIngredient#isInput()}</b>：
     * 物品组里既有原料也有产物（合成配方的产物就在同一组），
     * 一律当输入会导致「产物落到原料区、输出为空」。
     */
    private static <T> void addGroup(List<Slot> slots, IGuiIngredientGroup<T> group,
                                     int originX, int originY, boolean fluid) {
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
            Rectangle rect = rectOf(ingredient);
            // 4.x 的槽位矩形是**配方局部坐标**（绘制时靠 xOffset/yOffset 平移），
            // 必须加上 RecipeLayout 的原点才是屏幕绝对坐标，否则高亮会画到左上角。
            slots.add(new Slot(
                    rect == null ? -1 : rect.x + originX,
                    rect == null ? -1 : rect.y + originY,
                    rect == null ? 0 : rect.width,
                    rect == null ? 0 : rect.height,
                    ingredient.isInput(),
                    fluid,
                    ordered));
        }
    }

    /** 4.x 的矩形只在实现类 {@code mezz.jei.gui.ingredients.GuiIngredient} 的私有字段上。 */
    private static Rectangle rectOf(Object ingredient) {
        Object value = JeiReflect.field(ingredient, "rect");
        if (value instanceof Rectangle) {
            return (Rectangle) value;
        }
        Object called = JeiReflect.call(ingredient, "getRect");
        return called instanceof Rectangle ? (Rectangle) called : null;
    }

    /** 槽位矩形是否已经是屏幕绝对坐标（直接可用）。 */
    public static Rectangle absolute(Slot slot) {
        return new Rectangle(slot.x(), slot.y(), slot.width(), slot.height());
    }
}
