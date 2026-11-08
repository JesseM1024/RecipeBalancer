package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/** JEI ghost 拖拽：物品拖到编码格 / 设备 / 目标格。 */
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
        List<Rect2i> areas = new ArrayList<>();
        for (BalancerScreen.GhostSlot slot : screen.ghostSlots()) {
            Rect2i area = new Rect2i(slot.x(), slot.y(), 18, 18);
            areas.add(area);
            targets.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I value) {
                    screen.applyGhost(slot.kind(), slot.index(), mapped.key(), mapped.amount());
                }
            });
        }
        if (doStart) {
            // 高亮由本界面自己画（不同 JEI 版本把幽灵高亮画在不同坐标空间，位置会偏），
            // JEI 只保留「拖拽判定」职责；矩形仍是绝对屏幕坐标，投放命中不受影响。
            JeiGhostHighlights.begin(areas);
            if (!areas.isEmpty()) {
                int last = areas.size() - 1;
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] JEI ghost 开始: 共 {} 个, 首={}, 中={}, 末={},"
                                + " GUI 原点=({},{}), 屏幕={}x{}, JEI={}",
                        areas.size(), text(areas.get(0)), text(areas.get(areas.size() / 2)),
                        text(areas.get(last)), screen.getGuiLeft(), screen.getGuiTop(),
                        screen.width, screen.height, JeiCapabilities.version());
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
        JeiGhostHighlights.end();
    }

    /** 高亮自己说了算，避免 JEI 在不同版本把它们画到偏移的位置。 */
    @Override
    public boolean shouldHighlightTargets() {
        return false;
    }

    private static String text(Rect2i area) {
        return "(" + area.getX() + "," + area.getY() + " " + area.getWidth() + "x"
                + area.getHeight() + ")";
    }
}
