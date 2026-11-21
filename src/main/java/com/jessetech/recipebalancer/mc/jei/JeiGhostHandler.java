package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.IGhostIngredientHandler;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * JEI ghost 拖拽：物品拖到编码格/设备/目标格（1.12.2 / JEI 4.8.4+）。
 *
 * <p>4.x 的签名是 {@code getTargets(T, 裸配料, doStart)}，没有 {@code ITypedIngredient}；
 * {@code Target#getArea()} 返回 {@link Rectangle}。
 *
 * <p>高亮由本界面自己画（{@link JeiGhostHighlights}）：JEI 会经多条绘制路径各画一遍。
 * 这里 {@code shouldHighlightTargets()} 返回 {@code false}，JEI 只保留「拖拽判定」职责。
 */
public final class JeiGhostHandler implements IGhostIngredientHandler<BalancerScreen> {

    @Override
    public <I> List<Target<I>> getTargets(BalancerScreen screen, I ingredient, boolean doStart) {
        JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
        if (mapped == null) {
            return new ArrayList<>();
        }
        List<Target<I>> targets = new ArrayList<>();
        List<Rectangle> areas = new ArrayList<>();
        for (BalancerScreen.GhostSlot slot : screen.ghostSlots()) {
            Rectangle area = new Rectangle(slot.x(), slot.y(), 18, 18);
            areas.add(area);
            targets.add(new Target<I>() {
                @Override
                public Rectangle getArea() {
                    return area;
                }

                @Override
                public void accept(I value) {
                    screen.applyGhost(slot.kind(), slot.index(), mapped.key(), mapped.amount());
                }
            });
        }
        if (doStart) {
            JeiGhostHighlights.begin(areas);
            if (!areas.isEmpty()) {
                int last = areas.size() - 1;
                Rectangle first = areas.get(0);
                Rectangle mid = areas.get(areas.size() / 2);
                Rectangle tail = areas.get(last);
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] JEI ghost 开始: 共 {} 个, 首=({},{} {}x{}), 中=({},{}),"
                                + " 末=({},{}), GUI 原点=({},{}), 屏幕={}x{}",
                        areas.size(), first.x, first.y, first.width, first.height,
                        mid.x, mid.y, tail.x, tail.y,
                        screen.getGuiLeft(), screen.getGuiTop(), screen.width, screen.height);
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
        JeiGhostHighlights.end();
    }

    /** 高亮自己说了算，避免 JEI 在不同版本把它们画到偏移的位置或画两层。 */
    @Override
    public boolean shouldHighlightTargets() {
        return false;
    }
}
