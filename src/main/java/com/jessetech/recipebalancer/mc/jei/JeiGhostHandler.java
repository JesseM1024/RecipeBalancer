package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
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
            // 高亮由本界面自己画（JEI 不同版本的前景/后景通道坐标不一致，10.81 实测会偏移），
            // JEI 只保留「拖拽判定」职责；矩形仍是绝对屏幕坐标，投放命中不受影响。
            JeiGhostHighlights.begin(areas);
            if (!areas.isEmpty()) {
                int last = areas.size() - 1;
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] JEI ghost 开始: 共 {} 个, 首={}, 中={}, 末={},"
                                + " GUI 原点=({},{}), 屏幕={}x{}, JEI={}",
                        areas.size(), text(areas.get(0)), text(areas.get(areas.size() / 2)),
                        text(areas.get(last)), screen.guiOriginX(), screen.guiOriginY(),
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
