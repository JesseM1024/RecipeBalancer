package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;

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
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 给 JEI 配方页追加一个“编码此配方”按钮。
 * 图标为自绘的“四角 + 十字”图案（比默认浅色加号更醒目）。
 */
public final class JeiRecipeButtonFactory implements IRecipeButtonControllerFactory {

    @Override
    public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
        // 每个按钮独立图标实例，记录自己的绘制位置用于 hover 外框
        return new Controller<>(layout, new PatternIcon());
    }

    /** 从参考图逐像素采样出的 13×13 图案（每格 1px 完全一致复制）。 */
    private static final class PatternIcon implements IDrawable {
        private static final int[][] PIXELS = {
                {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC3C3C3, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFFC2C2C2, 0xFF929292, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFF929292, 0xFF979797, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF979797, 0xFF909090, 0xFF8E8E8E, 0xFF797979, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF8D8D8D, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9B9B9B, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF919191, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFFFFFFFF, 0xFF909090, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF979797, 0xFFFFFFFF, 0xFF8E8E8E, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9B9B9B, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF9C9C9C, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFC2C2C2, 0xFF929292, 0xFFFFFFFF, 0xFF979797, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF929292, 0xFFFFFFFF, 0xFF979797, 0xFF7C7C7C, 0xFF000000},
                {0xFF000000, 0xFFBFBFBF, 0xFF949494, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFF9C9C9C, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF949494, 0xFF7E7E7E, 0xFF000000},
                {0xFF000000, 0xFFBFBFBF, 0xFF949494, 0xFF979797, 0xFF949494, 0xFF9B9B9B, 0xFFFFFFFF, 0xFF9C9C9C, 0xFF949494, 0xFF979797, 0xFF949494, 0xFF7D7D7D, 0xFF000000},
                {0xFF000000, 0xFF909090, 0xFF7B7B7B, 0xFF797979, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7C7C7C, 0xFF7E7E7E, 0xFF7B7B7B, 0xFF797979, 0xFF000000},
                {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000}
        };

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
            for (int row = 0; row < PIXELS.length; row++) {
                for (int col = 0; col < PIXELS[row].length; col++) {
                    graphics.fill(x + col, y + row, x + col + 1, y + row + 1, PIXELS[row][col]);
                }
            }
        }

        /** 用 JEI 传入的按钮矩形定位 hover 白框，避免使用图标绘制坐标导致偏移。 */
        void drawHoverBorder(GuiGraphics graphics, Rect2i rect) {
            int x = rect.getX();
            int y = rect.getY();
            int w = rect.getWidth();
            int h = rect.getHeight();
            int white = 0xFFFFFFFF;
            graphics.fill(x, y, x + w, y + 1, white);
            graphics.fill(x, y + h - 1, x + w, y + h, white);
            graphics.fill(x, y, x + 1, y + h, white);
            graphics.fill(x + w - 1, y, x + w, y + h, white);
        }
    }

    private static final class Controller<T> implements IIconButtonController {
        private final IRecipeLayoutDrawable<T> layout;
        private final PatternIcon icon;

        private Controller(IRecipeLayoutDrawable<T> layout, PatternIcon icon) {
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
        public void drawExtras(GuiGraphics graphics, Rect2i rect, int mouseX, int mouseY,
                               float partialTick) {
            if (rect.contains(mouseX, mouseY)) {
                icon.drawHoverBorder(graphics, rect);
            }
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
                JeiIngredientMapper.Mapped mapped = firstIngredient(slot);
                if (mapped != null && inputIndex < RecipePattern.MAX_INPUTS) {
                    pattern.putInput(inputIndex++, mapped.key(), mapped.amount());
                }
            }
            int outputIndex = 0;
            for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews(RecipeIngredientRole.OUTPUT)) {
                JeiIngredientMapper.Mapped mapped = firstIngredient(slot);
                if (mapped != null && outputIndex < RecipePattern.MAX_OUTPUTS) {
                    pattern.putOutput(outputIndex++, mapped.key(), mapped.amount());
                }
            }
            // JEI 左侧“可用设备”列：取该配方类型的第一个催化剂自动填设备
            boolean equipmentSet = false;
            Object runtimeObject = JeiRuntimeHolder.get();
            if (runtimeObject instanceof IJeiRuntime runtime) {
                try {
                    Optional<ItemStack> catalyst = runtime.getRecipeManager()
                            .createRecipeCatalystLookup(layout.getRecipeCategory().getRecipeType())
                            .getItemStack().findFirst();
                    if (catalyst.isPresent()) {
                        pattern.setEquipmentKey(ItemKeyMapper.of(catalyst.get()));
                        equipmentSet = true;
                    }
                } catch (RuntimeException ignored) {
                    // 某些自定义类别没有 catalyst lookup，退回 layout CATALYST
                }
            }
            if (!equipmentSet) {
                for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                        .getSlotViews(RecipeIngredientRole.CATALYST)) {
                    JeiIngredientMapper.Mapped mapped = firstIngredient(slot);
                    if (mapped != null) {
                        pattern.setEquipmentKey(mapped.key());
                        break;
                    }
                }
            }
            Minecraft.getInstance().setScreen(new BalancerScreen(pattern));
            return true;
        }

        private static JeiIngredientMapper.Mapped firstIngredient(IRecipeSlotView slot) {
            Optional<ITypedIngredient<?>> displayed = slot.getDisplayedIngredient();
            if (displayed.isPresent()) {
                return JeiIngredientMapper.of(displayed.get());
            }
            return slot.getAllIngredientsList().stream().findFirst()
                    .map(JeiIngredientMapper::of).orElse(null);
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Lang.c("编码此配方到 RecipeBalancer"));
        }
    }
}
