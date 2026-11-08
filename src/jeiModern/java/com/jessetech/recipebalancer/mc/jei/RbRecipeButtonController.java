package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;

/**
 * 「编码此配方」按钮：左键把当前 JEI 配方转成编码样板，带草稿打开编码终端。
 *
 * <p>只有 10.x 有这套按钮 API；9.x 上本类不会被加载（见 {@code JeiCapabilities}）。
 */
public final class RbRecipeButtonController implements IIconButtonController {

    /** 13×13 的按钮图案：与 9.x 叠加层、1.20.1 共用同一份像素表（{@link JeiButtonSkin}）。 */
    private static final IDrawable ICON = new IDrawable() {
        @Override
        public int getWidth() {
            return JeiButtonSkin.SIZE;
        }

        @Override
        public int getHeight() {
            return JeiButtonSkin.SIZE;
        }

        @Override
        public void draw(PoseStack pose, int x, int y) {
            JeiButtonSkin.drawIcon(pose, x, y);
        }
    };

    /** 高亮结果缓存（500ms），避免每帧重算。 */
    private static final long HIGHLIGHT_REFRESH_MS = 500L;

    private final IRecipeLayoutDrawable layout;
    private List<JeiSlots.Slot> highlighted = List.of();
    private long highlightAt;

    RbRecipeButtonController(IRecipeLayoutDrawable layout) {
        this.layout = layout;
    }

    /**
     * 悬停按钮时，把配方库里已有生产配方的槽位标蓝（与 1.20.1 同一套配色与坐标换算）。
     *
     * <p>坐标必须用 {@code layout.getRect()} 做基准：JEI 给的槽位矩形是<b>配方局部坐标</b>。
     * {@code drawExtras} 在槽位物品之后调用，所以直接画就已经在物品上方。
     */
    @Override
    public void drawExtras(PoseStack pose, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (area == null || mouseX < area.getX() || mouseX >= area.getX() + area.getWidth()
                || mouseY < area.getY() || mouseY >= area.getY() + area.getHeight()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - highlightAt >= HIGHLIGHT_REFRESH_MS) {
                highlighted = JeiCraftableSlots.filter(
                        JeiSlots.fromView(layout.getRecipeSlotsView()), ProductIndex.current());
                highlightAt = now;
            }
            Rect2i layoutRect = layout.getRect();
            for (JeiSlots.Slot slot : highlighted) {
                // 槽位矩形在 10.x 可能是绝对坐标也可能是配方局部坐标，统一换算
                JeiHighlightOverlay.draw(pose, JeiSlots.absolute(slot, layoutRect));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 槽位视图结构异常 / 类加载问题时宁可不画高亮，也不影响配方页渲染
        }
    }

    /** 没有产物的配方（纯信息类）不显示按钮。 */
    static boolean hasOutput(IRecipeLayoutDrawable layout) {
        try {
            var slots = layout.getRecipeSlotsView();
            if (slots == null) {
                return false;
            }
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
                if (!slot.isEmpty()) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        return false;
    }

    @Override
    public void initState(IButtonState state) {
        state.setIcon(ICON);
        state.setActive(true);
        state.setVisible(true);
    }

    @Override
    public boolean onPress(IJeiUserInput input) {
        if (input.isSimulate()) {
            return false;
        }
        InputConstants.Key key = input.getKey();
        if (key.getType() != InputConstants.Type.MOUSE || key.getValue() != 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        RecipePattern pattern;
        try {
            // 10.x 直接读槽位视图（带矩形，能保留合成配方的九宫格形状）；
            // 槽位视图拿不到内容时再走旧的布局分组路径兜底。
            pattern = JeiPatternImporter.buildFromSlots(layout.getRecipeCategory(),
                    JeiSlots.fromView(layout.getRecipeSlotsView()));
            if (!pattern.hasContent()) {
                pattern = JeiPatternImporter.build(layout.getRecipeCategory(), layout);
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        // 正处在 JEI 的渲染/输入栈里，切屏排到主线程下一 tick，与 1.20.1 一致
        RecipePattern built = pattern;
        minecraft.execute(() -> minecraft.setScreen(BalancerScreen.withPattern(built)));
        return true;
    }
}
