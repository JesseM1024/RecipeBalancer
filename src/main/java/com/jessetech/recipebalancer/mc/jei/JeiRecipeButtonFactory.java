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

    /** 13×13 的深色底 + 白色四角/十字，对应参考图标。 */
    private static final class PatternIcon implements IDrawable {
        @Override
        public int getWidth() {
            return 13;
        }

        @Override
        public int getHeight() {
            return 13;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.fill(x, y, x + 13, y + 13, 0xFF101010);
            graphics.fill(x + 1, y + 1, x + 12, y + 12, 0xFF8B8B8B);
            int white = 0xFFFFFFFF;
            // 中央十字
            graphics.fill(x + 6, y + 1, x + 8, y + 12, white);
            graphics.fill(x + 1, y + 6, x + 12, y + 8, white);
            // 四个 L 角
            graphics.fill(x + 2, y + 2, x + 5, y + 4, white);
            graphics.fill(x + 2, y + 2, x + 4, y + 5, white);
            graphics.fill(x + 8, y + 2, x + 11, y + 4, white);
            graphics.fill(x + 9, y + 2, x + 11, y + 5, white);
            graphics.fill(x + 2, y + 9, x + 5, y + 11, white);
            graphics.fill(x + 2, y + 8, x + 4, y + 11, white);
            graphics.fill(x + 8, y + 9, x + 11, y + 11, white);
            graphics.fill(x + 9, y + 8, x + 11, y + 11, white);
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
                    pattern.putInput(inputIndex++, ItemKeyMapper.of(stack.get()),
                            Math.max(1.0, stack.get().getCount()));
                }
            }
            int outputIndex = 0;
            for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews(RecipeIngredientRole.OUTPUT)) {
                Optional<ItemStack> stack = firstItem(slot);
                if (stack.isPresent() && outputIndex < RecipePattern.MAX_OUTPUTS) {
                    // 合成输出可能是 3 个门等，保留 JEI 槽位的数量
                    pattern.putOutput(outputIndex++, ItemKeyMapper.of(stack.get()),
                            Math.max(1.0, stack.get().getCount()));
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
