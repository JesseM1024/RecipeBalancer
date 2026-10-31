package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 19.39 之前的「编码此配方」按钮。
 *
 * <p>19.39 才引入 {@code IRecipeButtonControllerFactory}；更低版本里唯一能画进配方页的钩子是
 * {@link IRecipeCategoryDecorator}。因此这里自己画按钮并自己判定点击：
 * <ul>
 *   <li>图案用 {@link JeiButtonSkin}（与高版本按钮同一份逐像素表，13×13，与 JEI 侧边按钮同尺寸）；</li>
 *   <li>注册时按 {@code IJeiHelpers.getAllRecipeTypes()} 遍历<b>所有</b>已注册配方类型，
 *       所以模组配方同样有按钮，不局限于原版类别；</li>
 *   <li>装饰器拿不到输入事件，用左键「按下→松开」的边沿判定（与 JEI 自己的按钮一样在松开时触发）。</li>
 * </ul>
 *
 * <p>为什么不像 AE2-JEI-Integration 那样直接注册 {@code IRecipeTransferHandler}、让 JEI 自己画按钮？
 * 那条路要求当前界面是 {@code AbstractContainerScreen} 且 {@code getMenu()} 类型匹配，
 * 前端容器为空时 JEI 直接把按钮隐藏（{@code RecipeTransferButton.update}）；
 * 本模组的编码终端是纯客户端 Screen，没有 Menu，所以用不了。
 *
 * <p>只在老版本 JEI 上注册；19.39+ 走 {@code JeiRecipeButtonFactory}，不会重复出现两个按钮。
 */
public final class JeiLegacyRecipeButton implements IRecipeCategoryDecorator<Object> {

    /** 全局唯一实例：注册时挂到每一个配方类型上。 */
    public static final JeiLegacyRecipeButton INSTANCE = new JeiLegacyRecipeButton();

    /** 按钮边长，与 JEI 配方页侧边按钮一致。 */
    private static final int SIZE = JeiButtonSkin.SIZE;
    /** JEI 配方边框内边距（{@code RecipeLayout} 里的 DEFAULT_RECIPE_BORDER_PADDING）。 */
    private static final int BORDER_PADDING = 4;

    /**
     * 当前被悬停的配方。
     *
     * <p>一页可能同时绘制多个 recipe layout，{@link #draw} 每帧会被逐个调用；
     * 若把「上一帧是否按下」存成全局字段，最先绘制的那一个会在松开帧把状态清掉，
     * 真正被悬停的那个永远等不到边沿 —— 所以状态必须跟随被悬停的那个配方。
     */
    private Object hoveredRecipe;
    /** 悬停期间是否出现过按下（防止「按住拖入再松开」误触发）。 */
    private boolean hoveredArmed;
    /** 针对被悬停配方，上一帧左键是否按下。 */
    private boolean hoveredWasDown;

    private JeiLegacyRecipeButton() {
    }

    @Override
    public void draw(Object recipe, IRecipeCategory<Object> category, IRecipeSlotsView slots,
                     GuiGraphics graphics, double mouseX, double mouseY) {
        try {
            if (!hasOutput(slots)) {
                clearHover(recipe);
                return;
            }
            Rect2i rect = place(category, slots);
            boolean hover = contains(rect, mouseX, mouseY);
            JeiButtonSkin.draw(graphics, rect.getX(), rect.getY(), hover);
            if (!hover) {
                clearHover(recipe);
                return;
            }
            boolean down = isLeftDown();
            if (hoveredRecipe != recipe) {
                hoveredRecipe = recipe;
                hoveredArmed = false;
                hoveredWasDown = down;
            }
            if (down) {
                hoveredArmed = true;
            } else if (hoveredArmed && hoveredWasDown) {
                // 松开时才触发，和 JEI 原生按钮（EXECUTE 阶段）一致，也避免拖拽误触
                hoveredArmed = false;
                openTerminal(category, slots);
            }
            hoveredWasDown = down;
        } catch (RuntimeException e) {
            // 单帧绘制异常不能把 JEI 的配方页带崩
        }
    }

    /** 鼠标离开该配方（或该配方不再有产物）时，释放属于它的悬停状态。 */
    private void clearHover(Object recipe) {
        if (hoveredRecipe == recipe) {
            hoveredRecipe = null;
            hoveredArmed = false;
            hoveredWasDown = false;
        }
    }

    @Override
    public void decorateTooltips(ITooltipBuilder tooltip, Object recipe,
                                 IRecipeCategory<Object> category, IRecipeSlotsView slots,
                                 double mouseX, double mouseY) {
        try {
            if (!hasOutput(slots) || !contains(place(category, slots), mouseX, mouseY)) {
                return;
            }
            tooltip.add(Lang.c("编码此配方到 RecipeBalancer"));
            tooltip.add(Lang.c("点击后打开配方编码终端").withStyle(ChatFormatting.GRAY));
        } catch (RuntimeException ignored) {
            // 拿不到槽位几何时宁可不显示提示，也不影响配方页
        }
    }

    /**
     * 直接问 GLFW 要左键状态。
     *
     * <p>两个坑都踩过：
     * <ul>
     *   <li>{@code Minecraft.mouseHandler.isLeftPressed()}：界面打开时按键由 Screen 接收，
     *       MouseHandler 的 activeButton 不会停留在按下态；</li>
     *   <li>{@code InputConstants.isKeyDown(...)}：内部是 {@code glfwGetKey}，只能查<b>键盘</b>，
     *       传鼠标键码永远返回 false。查鼠标必须用 {@code glfwGetMouseButton}。</li>
     * </ul>
     */
    private static boolean isLeftDown() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** 只有存在非空产物时才提供按钮（与高版本一致）。 */
    private static boolean hasOutput(IRecipeSlotsView slots) {
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            if (!slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选一个不压住槽位的落点。
     *
     * <p>坐标是配方局部坐标（JEI 调装饰器前已把 pose 平移到配方原点）。
     * 优先放进 JEI 侧边按钮那一列（{@code width + 边框内边距 + 2}），与 JEI 自带的
     * 转移 / 书签按钮同一 x，纵向对齐；那一列被占时再退回配方内部的角落。
     */
    private static Rect2i place(IRecipeCategory<?> category, IRecipeSlotsView slots) {
        int width = Math.max(category.getWidth(), SIZE);
        int height = Math.max(category.getHeight(), SIZE);
        int pad = category.needsRecipeBorder() ? BORDER_PADDING : 0;
        int columnX = width + pad + 2;
        List<Rect2i> used = slotRects(slots);
        // 玩家正开着容器界面时，JEI 可能自己在列底画转移按钮，我们就让到列顶
        int[][] candidates = {
                {columnX, hasOtherContainerOpen() ? -pad : height + pad - SIZE},
                {columnX, -pad},
                {width - SIZE, height - SIZE},
                {width - SIZE, 0}
        };
        for (int[] at : candidates) {
            Rect2i rect = new Rect2i(at[0], at[1], SIZE, SIZE);
            if (!overlapsAny(rect, used)) {
                return rect;
            }
        }
        return new Rect2i(candidates[0][0], candidates[0][1], SIZE, SIZE);
    }

    /** 除玩家背包外还开着容器界面（此时 JEI 会在侧边列底画转移按钮）。 */
    private static boolean hasOtherContainerOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        return minecraft.player.containerMenu != minecraft.player.inventoryMenu;
    }

    // getRect() 被标记待删除，但装饰器里没有别的办法拿到每格几何位置（isMouseOver 只有布尔值），
    // 这里只为避开槽位，保留并压掉告警（与 JeiPatternImporter 同样处理）。
    @SuppressWarnings("removal")
    private static List<Rect2i> slotRects(IRecipeSlotsView slots) {
        List<Rect2i> rects = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.isEmpty() || !(slot instanceof IRecipeSlotDrawable drawable)) {
                continue;
            }
            rects.add(drawable.getRect());
        }
        return rects;
    }

    private static boolean overlapsAny(Rect2i rect, List<Rect2i> used) {
        int x1 = rect.getX();
        int y1 = rect.getY();
        int x2 = x1 + rect.getWidth();
        int y2 = y1 + rect.getHeight();
        for (Rect2i other : used) {
            int ox2 = other.getX() + other.getWidth();
            int oy2 = other.getY() + other.getHeight();
            if (x1 < ox2 && other.getX() < x2 && y1 < oy2 && other.getY() < y2) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Rect2i rect, double mouseX, double mouseY) {
        return mouseX >= rect.getX() && mouseX < rect.getX() + rect.getWidth()
                && mouseY >= rect.getY() && mouseY < rect.getY() + rect.getHeight();
    }

    /** 转成编码样板并打开配方编码终端（与高版本按钮同一路径）。 */
    private static void openTerminal(IRecipeCategory<?> category, IRecipeSlotsView slots) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        RecipePattern pattern = JeiPatternImporter.build(category, slots);
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：{} → 打开配方编码终端",
                category.getRecipeType().getUid());
        // 正在渲染 JEI 配方页时直接切屏会让渲染栈错位，排到主线程下一 tick 再切
        minecraft.execute(() -> minecraft.setScreen(new BalancerScreen(pattern)));
    }
}
