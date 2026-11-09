package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.screen.KeySetScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import net.minecraft.client.renderer.Rectangle2d;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI ghost 拖拽：把物品拖进「无限外部供应物质 / 循环亏损补充物质」这类清单编辑屏
 * （1.16.5 / JEI 7.x）。
 *
 * <p>这些屏维护的是「种类集合」（{@link KeySetScreen}），没有数量概念，
 * 因此整块物料网格是一个落点，拖进来的配料直接追加到清单末尾；
 * 重复项与数量上限由 {@link KeySetScreen#addGhostKey(String)} 统一提示。
 *
 * <p>高亮与主界面一样由界面自绘：JEI 对普通 {@code Screen} 会经 {@code drawTooltips} 与
 * {@code drawOnForeground} 两条路径各画一遍，实机上就是「同一块网格画了两次」。
 */
public final class JeiKeySetGhostHandler implements IGhostIngredientHandler<KeySetScreen> {

    @Override
    public <I> List<Target<I>> getTargets(KeySetScreen screen, I ingredient, boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
        if (mapped == null) {
            return new ArrayList<>();
        }
        int[] grid = screen.ghostGrid();
        if (grid == null || grid.length < 4) {
            return new ArrayList<>();
        }
        Rectangle2d area = new Rectangle2d(grid[0], grid[1], grid[2], grid[3]);
        if (doStart) {
            List<Rectangle2d> areas = new ArrayList<>(1);
            areas.add(area);
            JeiGhostHighlights.begin(areas);
        }
        List<Target<I>> targets = new ArrayList<>(1);
        targets.add(new Target<I>() {
            @Override
            public Rectangle2d getArea() {
                return area;
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
