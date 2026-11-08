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
 *       {@code mezz.jei.gui.ingredients.RecipeSlot} 上），合成配方的九宫格形状只能靠它；</li>
 *   <li>10.x 的矩形在 {@code IRecipeSlotDrawable#getRect()}，同样不在公共父接口上。</li>
 * </ul>
 * 这里统一用反射取矩形（两个版本的实现类都有同签名的 public 方法），取不到就退化为
 * 「按顺序紧排」，不影响能编码出正确内容。
 */
public final class JeiSlots {

    /** 一个槽位：屏幕坐标（取不到矩形时 x/y 为 -1）、角色、全部候选配料。 */
    public record Slot(int x, int y, RecipeIngredientRole role,
                       List<ITypedIngredient<?>> ingredients) {

        boolean hasRect() {
            return x >= 0 && y >= 0;
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
                        rect == null ? -1 : rect.getY(), role, ingredients));
            }
        } catch (RuntimeException | LinkageError e) {
            // 槽位视图结构异常时返回已解析的部分
        }
        return slots;
    }

    /**
     * 反射取槽位矩形：9.x 的 {@code RecipeSlot} 与 10.x 的 {@code IRecipeSlotDrawable}
     * 都有 {@code public Rect2i getRect()}，但都不在公共父接口上。
     */
    private static Rect2i rectOf(Object slot) {
        try {
            Method method = slot.getClass().getMethod("getRect");
            method.setAccessible(true);
            Object value = method.invoke(slot);
            return value instanceof Rect2i rect ? rect : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
