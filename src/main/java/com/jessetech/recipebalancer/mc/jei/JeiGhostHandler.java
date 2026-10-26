package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/** JEI ghost 拖拽：物品拖到编码格/设备/目标格。 */
public final class JeiGhostHandler implements IGhostIngredientHandler<BalancerScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(BalancerScreen screen,
                                               ITypedIngredient<I> ingredient,
                                               boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
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
}
