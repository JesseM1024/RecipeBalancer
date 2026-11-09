package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/**
 * 物料清单编辑屏的 JEI 拖拽：拖进网格即追加到清单末尾。
 *
 * <p>9.x 的 {@code getTargets} 传的是裸配料，映射统一走 {@link JeiGhostHandler#map(Object)}。
 *
 * <p>高亮与主界面一样由界面自绘：JEI 对普通 {@code Screen} 会经 {@code drawTooltips} 与
 * {@code drawOnForeground} 两条路径各画一遍，实机上就是「同一块区域画了两次」，
 * 因此关掉 JEI 的高亮、自己画一次。
 */
public final class JeiKeySetGhostHandler implements IGhostIngredientHandler<KeySetScreen> {

    @Override
    public <I> List<Target<I>> getTargets(KeySetScreen screen, I ingredient, boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiGhostHandler.map(ingredient);
        if (mapped == null) {
            return J8.list();
        }
        int[] grid = screen.ghostGrid();
        if (grid == null || grid.length < 4) {
            return J8.list();
        }
        if (doStart) {
            JeiGhostHighlights.begin(J8.list(new Rect2i(grid[0], grid[1], grid[2], grid[3])));
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
        JeiGhostHighlights.end();
    }

    /** 高亮由界面自绘（JEI 会画两遍，见类注释）。 */
    @Override
    public boolean shouldHighlightTargets() {
        return false;
    }
}
