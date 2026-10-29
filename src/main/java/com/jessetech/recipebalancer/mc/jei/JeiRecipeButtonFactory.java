package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.mc.i18n.Lang;

import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

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

        /** 深蓝、低不透明度的“已有配方”高亮。 */
        private static final int HIGHLIGHT_FILL = 0x40004688;
        private static final int HIGHLIGHT_BORDER = 0x9F004E96;
        /** 我们编码区的原料列数，决定 JEI 槽位行列如何映射。 */
        private static final int INPUT_COLS = 3;

        private final IRecipeLayoutDrawable<T> layout;
        private final PatternIcon icon;
        private long highlightAt;
        private List<IRecipeSlotView> highlighted = List.of();

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
            if (!rect.contains(mouseX, mouseY)) {
                return;
            }
            icon.drawHoverBorder(graphics, rect);
            // AE 风格：悬停时把配方库里能自产的槽位标蓝
            for (IRecipeSlotView slot : craftableSlots()) {
                drawSlotHighlight(graphics, slot);
            }
        }

        /**
         * 自己画高亮：JEI 的 {@code drawHighlight} 用的是配方局部坐标，直接调用会画到左上角，
         * 这里用布局原点把槽位 rect 换算成屏幕坐标，并做一次范围校验避免画飞。
         */
        // getRect() 虽被标记待删除（官方建议改用 isMouseOver），但这里需要的是槽位几何位置，
        // 没有等价的替代（getAreaIncludingBackground 拿不到每格独立坐标），故保留并压掉告警。
        @SuppressWarnings("removal")
        private void drawSlotHighlight(GuiGraphics graphics, IRecipeSlotView slot) {
            if (!(slot instanceof IRecipeSlotDrawable drawable)) {
                return;
            }
            Rect2i layoutRect = layout.getRect();
            Rect2i slotRect = drawable.getRect();
            int x = slotRect.getX();
            int y = slotRect.getY();
            if (!layoutRect.contains(x + 1, y + 1)) {
                // 槽位 rect 是配方局部坐标，加上布局原点才是屏幕坐标
                x += layoutRect.getX();
                y += layoutRect.getY();
            }
            if (!layoutRect.contains(x + 1, y + 1)) {
                return;
            }
            int x2 = x + slotRect.getWidth();
            int y2 = y + slotRect.getHeight();
            // guiOverlay：不吃深度，保证盖在物品图标之上（和 JEI 自带高亮一致）
            overlayFill(graphics, x, y, x2, y2, HIGHLIGHT_FILL);
            overlayFill(graphics, x, y, x2, y + 1, HIGHLIGHT_BORDER);
            overlayFill(graphics, x, y2 - 1, x2, y2, HIGHLIGHT_BORDER);
            overlayFill(graphics, x, y, x + 1, y2, HIGHLIGHT_BORDER);
            overlayFill(graphics, x2 - 1, y, x2, y2, HIGHLIGHT_BORDER);
        }

        private static void overlayFill(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                        int color) {
            graphics.fillGradient(RenderType.guiOverlay(), x1, y1, x2, y2, color, color, 0);
        }

        /** 该槽位是否至少有一种候选物质能由配方库自产（结果缓存 500ms，避免每帧重算）。 */
        private List<IRecipeSlotView> craftableSlots() {
            long now = System.currentTimeMillis();
            if (now - highlightAt < 500) {
                return highlighted;
            }
            highlightAt = now;
            ProductIndex index = ProductIndex.current();
            List<IRecipeSlotView> result = new ArrayList<>();
            for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
                if (slot.isEmpty()) {
                    continue;
                }
                RecipeIngredientRole role = slot.getRole();
                if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                    continue;
                }
                for (ITypedIngredient<?> ingredient : slot.getAllIngredientsList()) {
                    JeiIngredientMapper.Mapped mapped = JeiIngredientMapper.of(ingredient);
                    if (mapped != null && index.produces(mapped.key())) {
                        result.add(slot);
                        break;
                    }
                }
            }
            highlighted = result;
            return result;
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
            RecipePattern pattern = JeiPatternImporter.build(layout);
            Minecraft.getInstance().setScreen(new BalancerScreen(pattern));
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Lang.c("编码此配方到 RecipeBalancer"));
            tooltip.add(Lang.c("高亮的物品已有配方存在配方库中").withStyle(ChatFormatting.GRAY));
        }
    }
}
