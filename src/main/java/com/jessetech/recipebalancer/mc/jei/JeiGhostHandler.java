package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** JEI ghost 拖拽：物品 / 流体 / 化学品拖到编码格 / 设备 / 目标格。 */
public final class JeiGhostHandler implements IGhostIngredientHandler<BalancerScreen> {

    @Override
    public <I> List<Target<I>> getTargets(BalancerScreen screen, I ingredient, boolean doStart) {
        JeiIngredientMapper.Mapped mapped = map(ingredient);
        if (mapped == null) {
            return List.of();
        }
        List<Target<I>> targets = new ArrayList<>();
        for (BalancerScreen.GhostSlot slot : screen.ghostSlots()) {
            targets.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(slot.x(), slot.y(), 18, 18);
                }

                @Override
                public void accept(I value) {
                    screen.applyGhost(slot.kind(), slot.index(), mapped.key(), mapped.amount());
                }
            });
        }
        return targets;
    }

    @Override
    public void onComplete() {
    }

    @Override
    public boolean shouldHighlightTargets() {
        return true;
    }

    /**
     * 9.x 传进来的是<b>裸配料</b>（ItemStack / FluidStack / 化学品对象），
     * 这里先按物品直读，其余交给 JEI 的类型表包装成 {@link ITypedIngredient}。
     */
    static JeiIngredientMapper.Mapped map(Object ingredient) {
        if (ingredient instanceof ItemStack stack) {
            if (stack.isEmpty()) {
                return null;
            }
            return JeiIngredientMapper.ofTypedItem(stack);
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        if (runtime == null || ingredient == null) {
            return null;
        }
        try {
            IIngredientType<Object> type = runtime.getIngredientManager()
                    .getIngredientType(ingredient);
            if (type == null) {
                return null;
            }
            ITypedIngredient<Object> typed = runtime.createTypedIngredient(type, ingredient);
            return JeiIngredientMapper.of(typed);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
