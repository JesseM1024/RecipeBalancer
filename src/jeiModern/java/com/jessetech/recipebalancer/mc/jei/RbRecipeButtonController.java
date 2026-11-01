package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 「编码此配方」按钮的行为：图标来自 {@link JeiButtonSkin}，按下后把配方转成编码样板并打开终端。
 *
 * <p>只在 JEI 提供 {@code IIconButtonController} 时加载（本地实测 15.48.0.179 起有）。
 */
final class RbRecipeButtonController implements IIconButtonController {

    private final IRecipeLayoutDrawable<?> layout;

    RbRecipeButtonController(IRecipeLayoutDrawable<?> layout) {
        this.layout = layout;
    }

    @Override
    public void initState(IButtonState state) {
        state.setIcon(Icon.INSTANCE);
        state.setActive(true);
    }

    @Override
    public boolean onPress(IJeiUserInput input) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        RecipePattern pattern;
        try {
            pattern = JeiPatternImporter.build(layout);
        } catch (Throwable t) {
            // 类加载失败（LinkageError）也要收口，否则会顺着 JEI 的输入处理冒到主循环
            RecipeBalancerMod.LOGGER.error(
                    "[RecipeBalancer] 配方页按钮：解析配方失败（配方页保持原样）", t);
            return false;
        }
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：{} → 打开配方编码终端",
                layout.getRecipeCategory().getRecipeType().getUid());
        // 正在渲染 JEI 配方页时直接切屏会让渲染栈错位，排到主线程下一 tick 再切
        minecraft.execute(() -> minecraft.setScreen(new BalancerScreen(pattern)));
        return true;
    }

    @Override
    public void getTooltips(ITooltipBuilder tooltip) {
        tooltip.add(Lang.c("编码此配方到 RecipeBalancer"));
        tooltip.add(Lang.c("点击后打开配方编码终端").withStyle(ChatFormatting.GRAY));
    }

    /** 13×13 的图案（与老版本自绘按钮共用同一份像素表）。 */
    private static final class Icon implements IDrawable {

        private static final Icon INSTANCE = new Icon();

        @Override
        public int getWidth() {
            return JeiButtonSkin.SIZE;
        }

        @Override
        public int getHeight() {
            return JeiButtonSkin.SIZE;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            JeiButtonSkin.drawIcon(graphics, x, y);
        }
    }
}
