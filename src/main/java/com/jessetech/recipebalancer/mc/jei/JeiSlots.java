package com.jessetech.recipebalancer.mc.jei;

import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.renderer.Rect2i;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 JEI 的槽位视图统一成「位置 + 角色 + 候选配料」，供两代 JEI 共用同一套导入逻辑。
 *
 * <p>为什么需要中间层：
 * <ul>
 *   <li>9.x 的 {@code IRecipeSlotView} 没有矩形（{@code getRect} 只在内部实现类
 *       {@code mezz.jei.gui.ingredients.RecipeSlot} 上，返回的是
 *       {@code mezz.jei.common.util.ImmutableRect2i}），合成配方的九宫格形状只能靠它；</li>
 *   <li>10.x 的矩形在 {@code IRecipeSlotDrawable#getRect()}，返回 MC 的 {@code Rect2i}，
 *       同样不在公共父接口上。</li>
 * </ul>
 * 这里统一用反射取矩形（两个版本的实现类都有同签名的 public 方法），取不到就退化为
 * 「按顺序紧排」，不影响能编码出正确内容。坐标是<b>配方局部坐标</b>（相对配方背景左上角）。
 */
public final class JeiSlots {

    /** 一个槽位：配方局部坐标（取不到矩形时 x/y 为 -1）、矩形尺寸、角色、全部候选配料。 */
    public record Slot(int x, int y, int width, int height, RecipeIngredientRole role,
                       List<ITypedIngredient<?>> ingredients) {

        public boolean hasRect() {
            return x >= 0 && y >= 0 && width > 0 && height > 0;
        }
    }

    private JeiSlots() {
    }

    /** 从公共槽位视图收集；9.x / 10.x 通用。 */
    public static List<Slot> fromView(IRecipeSlotsView view) {
        List<Slot> slots = new ArrayList<>();
        if (view == null) {
            return slots;
        }
        try {
            for (IRecipeSlotView slot : view.getSlotViews()) {
                if (slot == null || slot.isEmpty()) {
                    continue;
                }
                List<ITypedIngredient<?>> ingredients = slot.getAllIngredients().toList();
                if (ingredients.isEmpty()) {
                    continue;
                }
                Rect2i rect = rectOf(slot);
                RecipeIngredientRole role = slot.getRole();
                slots.add(new Slot(rect == null ? -1 : rect.getX(),
                        rect == null ? -1 : rect.getY(),
                        rect == null ? 0 : rect.getWidth(),
                        rect == null ? 0 : rect.getHeight(),
                        role, ingredients));
            }
        } catch (RuntimeException | LinkageError e) {
            // 槽位视图结构异常时返回已解析的部分
        }
        return slots;
    }

    /**
     * 反射取槽位矩形。
     *
     * <p>9.x 的 {@code RecipeSlot#getRect()} 返回 {@code mezz.jei.common.util.ImmutableRect2i}，
     * 与 MC 的 {@code Rect2i} 没有继承关系，只能按 getter 拼一个；
     * 10.x 的 {@code IRecipeSlotDrawable#getRect()} 直接返回 {@code Rect2i}。
     */
    private static Rect2i rectOf(Object slot) {
        try {
            Method method = slot.getClass().getMethod("getRect");
            method.setAccessible(true);
            Object value = method.invoke(slot);
            if (value instanceof Rect2i rect) {
                return rect;
            }
            if (value == null) {
                return null;
            }
            return new Rect2i(number(value, "getX"), number(value, "getY"),
                    number(value, "getWidth"), number(value, "getHeight"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 把槽位矩形统一成<b>屏幕绝对坐标</b>。
     *
     * <p>JEI 各版本给的坐标空间不一致：9.x 与 10.81 在布局定位时会把槽位矩形直接设成绝对坐标，
     * 10.6 / 15.x 则是配方局部坐标（绘制时靠位姿平移）。这里用「是否与配方矩形相交」判断：
     * 相交说明已经是绝对坐标，否则按局部坐标加上配方原点。
     */
    public static Rect2i absolute(Slot slot, Rect2i layoutRect) {
        Rect2i raw = new Rect2i(slot.x(), slot.y(), slot.width(), slot.height());
        if (layoutRect == null || overlaps(raw, layoutRect)) {
            return raw;
        }
        return new Rect2i(raw.getX() + layoutRect.getX(), raw.getY() + layoutRect.getY(),
                raw.getWidth(), raw.getHeight());
    }

    /** 配方局部坐标（按钮摆位的重叠检测用）。 */
    public static Rect2i local(Slot slot, Rect2i layoutRect) {
        Rect2i absolute = absolute(slot, layoutRect);
        int originX = layoutRect == null ? 0 : layoutRect.getX();
        int originY = layoutRect == null ? 0 : layoutRect.getY();
        return new Rect2i(absolute.getX() - originX, absolute.getY() - originY,
                absolute.getWidth(), absolute.getHeight());
    }

    private static boolean overlaps(Rect2i a, Rect2i b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }

    private static int number(Object owner, String getter) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(getter);
        method.setAccessible(true);
        Object value = method.invoke(owner);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
