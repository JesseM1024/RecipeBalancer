package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;

/**
 * JEI ghost 拖拽：把物品拖进「无限外部供应物质 / 循环亏损补充物质」这类清单编辑屏。
 *
 * <p>这些屏维护的是「种类集合」（{@link KeySetScreen}），没有数量概念，
 * 因此整块物料网格是一个落点，拖进来的配料直接追加到清单末尾；
 * 重复项与数量上限由 {@link KeySetScreen#dropIngredient(String)} 统一提示。
 */
public final class JeiKeySetGhostHandler implements IGhostIngredientHandler<KeySetScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(KeySetScreen screen,
                                               ITypedIngredient<I> ingredient,
                                               boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
        if (mapped == null) {
            return List.of();
        }
        return List.of(new Target<I>() {
            @Override
            public Rect2i getArea() {
                return screen.jeiGridArea();
            }

            @Override
            public void accept(I value) {
                screen.dropIngredient(mapped.key());
            }
        });
    }

    @Override
    public void onComplete() {
    }

    @Override
    public boolean shouldHighlightTargets() {
        return true;
    }
}
