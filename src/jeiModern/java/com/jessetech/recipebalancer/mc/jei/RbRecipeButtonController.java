package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
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

    private final IRecipeLayoutDrawable layout;

    RbRecipeButtonController(IRecipeLayoutDrawable layout) {
        this.layout = layout;
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
