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
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 给 JEI 配方页追加一个“编码此配方”加号按钮。
 * 点击后把当前 JEI 配方转换为 RecipePattern 并打开编码终端。
 */
public final class JeiRecipeButtonFactory implements IRecipeButtonControllerFactory {

    private final IDrawable icon;

    public JeiRecipeButtonFactory(IGuiHelper helper) {
        this.icon = helper.getRecipePlusSign();
    }

    @Override
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        return new Controller<>(layout, icon);
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
