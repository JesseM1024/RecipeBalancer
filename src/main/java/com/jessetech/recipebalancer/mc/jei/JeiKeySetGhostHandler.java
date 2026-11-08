package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/**
 * 物料清单编辑屏的 JEI 拖拽：拖进网格即追加到清单末尾。
 *
 * <p>9.x 的 {@code getTargets} 传的是裸配料，映射统一走 {@link JeiGhostHandler#map(Object)}。
 */
public final class JeiKeySetGhostHandler implements IGhostIngredientHandler<KeySetScreen> {

    @Override
    public <I> List<Target<I>> getTargets(KeySetScreen screen, I ingredient, boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiGhostHandler.map(ingredient);
        if (mapped == null) {
            return List.of();
        }
        int[] grid = screen.ghostGrid();
        if (grid == null || grid.length < 4) {
            return List.of();
        }
        List<Target<I>> targets = new ArrayList<>();
        targets.add(new Target<I>() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(grid[0], grid[1], grid[2], grid[3]);
            }

            @Override
            public void accept(I value) {
                screen.addGhostKey(mapped.key());
            }
        });
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
