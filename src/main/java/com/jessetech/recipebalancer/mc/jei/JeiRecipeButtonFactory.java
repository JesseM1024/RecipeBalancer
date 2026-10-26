package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 给 JEI 配方页追加一个“编码此配方”按钮。
 * 图标为自绘的“四角 + 十字”图案（比默认浅色加号更醒目）。
 */
public final class JeiRecipeButtonFactory implements IRecipeButtonControllerFactory {

    private final IDrawable icon = new PatternIcon();

    @Override
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        return new Controller<>(layout, icon);
    }

    /** 16×16 的深色底 + 白色四角/十字，对应参考图标的简化版。 */
    private static final class PatternIcon implements IDrawable {
        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.fill(x, y, x + 16, y + 16, 0xFF101010);
            graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF8B8B8B);
            int white = 0xFFFFFFFF;
            // 中央十字
            graphics.fill(x + 7, y + 2, x + 9, y + 14, white);
            graphics.fill(x + 2, y + 7, x + 14, y + 9, white);
            // 四个 L 角
            graphics.fill(x + 3, y + 3, x + 6, y + 5, white);
            graphics.fill(x + 3, y + 3, x + 5, y + 6, white);
            graphics.fill(x + 10, y + 3, x + 13, y + 5, white);
            graphics.fill(x + 11, y + 3, x + 13, y + 6, white);
            graphics.fill(x + 3, y + 11, x + 6, y + 13, white);
            graphics.fill(x + 3, y + 10, x + 5, y + 13, white);
            graphics.fill(x + 10, y + 11, x + 13, y + 13, white);
            graphics.fill(x + 11, y + 10, x + 13, y + 13, white);
        }
    }

    private static final class Controller<T> implements IIconButtonController {
        private final IRecipeLayoutDrawable<T> layout;
        private final IDrawable icon;

        private Controller(IRecipeLayoutDrawable<T> layout, IDrawable icon) {
            this.layout = layout;
            this.icon = icon;
        }

        private boolean hasOutput() {
            return layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT)
                    .stream().anyMatch(slot -> !slot.isEmpty());
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            state.setVisible(hasOutput());
        }

        @Override
        public void updateState(IButtonState state) {
            boolean visible = hasOutput();
            state.setVisible(visible);
            state.setActive(visible);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (input.isSimulate() || !hasOutput()) {
                return false;
            }
            InputConstants.Key key = input.getKey();
            if (key.getType() != InputConstants.Type.MOUSE || key.getValue() != 0) {
                return false;
            }
            RecipePattern pattern = new RecipePattern();
            int inputIndex = 0;
            for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews(RecipeIngredientRole.INPUT)) {
                Optional<ItemStack> stack = firstItem(slot);
                if (stack.isPresent() && inputIndex < RecipePattern.MAX_INPUTS) {
                    pattern.putInput(inputIndex++, ItemKeyMapper.of(stack.get()), 1.0);
                }
            }
            int outputIndex = 0;
            for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews(RecipeIngredientRole.OUTPUT)) {
                Optional<ItemStack> stack = firstItem(slot);
                if (stack.isPresent() && outputIndex < RecipePattern.MAX_OUTPUTS) {
                    pattern.putOutput(outputIndex++, ItemKeyMapper.of(stack.get()), 1.0);
                }
            }
            for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews(RecipeIngredientRole.CATALYST)) {
                Optional<ItemStack> stack = firstItem(slot);
                if (stack.isPresent()) {
                    pattern.setEquipmentKey(ItemKeyMapper.of(stack.get()));
                    break;
                }
            }
            Minecraft.getInstance().setScreen(new BalancerScreen(pattern));
            return true;
        }

        private static Optional<ItemStack> firstItem(IRecipeSlotView slot) {
            Optional<ItemStack> displayed = slot.getDisplayedItemStack();
            if (displayed.isPresent()) {
                return displayed;
            }
            return slot.getItemStacks().findFirst();
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.literal("编码此配方到 RecipeBalancer"));
        }
    }
}
