package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
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
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.20.1（JEI 15.x）的「编码此配方」按钮。
 *
 * <p>15.x 没有配方页按钮 API（{@code IRecipeButtonControllerFactory} 是更后面的版本才有），
 * 唯一能画进配方页的公开钩子是 {@link IRecipeCategoryDecorator}。因此这里自己画按钮、
 * 自己判定点击：
 * <ul>
 *   <li>图案用 {@link JeiButtonSkin}（13×13，与 JEI 侧边按钮同尺寸）；</li>
 *   <li>注册时按 {@code IJeiHelpers.getAllRecipeTypes()} 遍历<b>所有</b>已注册配方类型，
 *       模组配方同样有按钮；</li>
 *   <li>装饰器拿不到输入事件，用左键「按下→松开」的边沿判定（与 JEI 自己的按钮一样在松开时触发）。</li>
 * </ul>
 */
public final class JeiLegacyRecipeButton implements IRecipeCategoryDecorator<Object> {

    /** 全局唯一实例：注册时挂到每一个配方类型上。 */
    public static final JeiLegacyRecipeButton INSTANCE = new JeiLegacyRecipeButton();

    private static final int SIZE = JeiButtonSkin.SIZE;
    /** JEI 配方边框内边距（15.x 的 RecipeLayout 固定按 4 计算）。 */
    private static final int BORDER_PADDING = 4;
    /** JEI 自带配方按钮的间距（实测 RecipeLayout.RECIPE_BUTTON_SPACING = 2）。 */
    private static final int BUTTON_SPACING = 2;
    /** 高亮结果缓存（500ms），避免每帧重算。 */
    private static final long HIGHLIGHT_REFRESH_MS = 500L;
    /** 按钮异常只记一次日志（绘制每帧都跑，不加这个会刷屏）。 */
    private static boolean LOGGED_FAILURE;

    /**
     * 当前被悬停的配方。
     *
     * <p>一页可能同时绘制多个 recipe layout，{@link #draw} 每帧会被逐个调用；
     * 若把「上一帧是否按下」存成全局字段，最先绘制的那一个会在松开帧把状态清掉，
     * 真正被悬停的那个永远等不到边沿——所以状态必须跟随被悬停的那个配方。
     */
    private Object hoveredRecipe;
    private boolean hoveredArmed;
    private boolean hoveredWasDown;

    private Object highlightRecipe;
    private long highlightAt;
    private List<IRecipeSlotView> highlighted = List.of();

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
            if (hover) {
                drawCraftableHighlights(graphics, recipe, slots, category);
            }
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
                // 松开时才触发，和 JEI 原生按钮一致，也避免拖拽误触
                hoveredArmed = false;
                openTerminal(category, slots);
            }
            hoveredWasDown = down;
        } catch (Throwable t) {
            // 单帧绘制异常（含 NoClassDefFoundError 这类 Error）不能把 JEI 的配方页带崩：
            // 这是渲染线程，任何逃出去的异常都会导致「Rendering screen」崩溃
            logFailureOnce(t);
        }
    }

    /**
     * 按钮相关异常只记一次，避免每帧刷屏；同时把「类加载失败」和普通绘制异常区分开，
     * 前者几乎只可能是 jar 被热替换/打包缺类，日志里要能看出来。
     */
    private static void logFailureOnce(Throwable t) {
        if (LOGGED_FAILURE) {
            return;
        }
        LOGGED_FAILURE = true;
        if (t instanceof LinkageError) {
            RecipeBalancerMod.LOGGER.error(
                    "[RecipeBalancer] JEI 配方页按钮被禁用：类加载失败（jar 是否在游戏运行中被替换过？）",
                    t);
        } else {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] JEI 配方页按钮绘制异常（已忽略）",
                    t);
        }
    }

    @Override
    public List<Component> decorateExistingTooltips(List<Component> tooltips, Object recipe,
                                                    IRecipeCategory<Object> category,
                                                    IRecipeSlotsView slots,
                                                    double mouseX, double mouseY) {
        try {
            if (!hasOutput(slots) || !contains(place(category, slots), mouseX, mouseY)) {
                return tooltips;
            }
            tooltips.add(Lang.c("编码此配方到 RecipeBalancer"));
            tooltips.add(Lang.c("点击后打开配方编码终端").withStyle(ChatFormatting.GRAY));
        } catch (Throwable t) {
            // 拿不到槽位几何 / 类加载失败时宁可不显示提示，也不影响配方页
            logFailureOnce(t);
        }
        return tooltips;
    }

    /** 悬停按钮时，把配方库能自产的物质标蓝。 */
    private void drawCraftableHighlights(GuiGraphics graphics, Object recipe, IRecipeSlotsView slots,
                                         IRecipeCategory<?> category) {
        long now = System.currentTimeMillis();
        if (highlightRecipe != recipe || now - highlightAt >= HIGHLIGHT_REFRESH_MS) {
            highlighted = JeiCraftableSlots.find(slots, ProductIndex.current());
            highlightRecipe = recipe;
            highlightAt = now;
        }
        int width = category.getWidth();
        int height = category.getHeight();
        for (IRecipeSlotView slot : highlighted) {
            Rect2i rect = slotRect(slot, width, height);
            if (rect != null) {
                JeiCraftableSlots.drawHighlight(graphics, rect);
            }
        }
    }

    /** 槽位矩形：完全落在配方外的跳过，避免高亮糊到配方区之外。 */
    private static Rect2i slotRect(IRecipeSlotView slot, int width, int height) {
        if (!(slot instanceof IRecipeSlotDrawable drawable)) {
            return null;
        }
        Rect2i rect = drawable.getRect();
        if (rect.getX() + rect.getWidth() <= 0 || rect.getY() + rect.getHeight() <= 0
                || rect.getX() >= width || rect.getY() >= height) {
            return null;
        }
        return rect;
    }

    /** 鼠标离开该配方（或该配方不再有产物）时，释放属于它的悬停状态。 */
    private void clearHover(Object recipe) {
        if (hoveredRecipe == recipe) {
            hoveredRecipe = null;
            hoveredArmed = false;
            hoveredWasDown = false;
        }
    }

    /**
     * 直接问 GLFW 要左键状态。
     *
     * <p>{@code MouseHandler.isLeftPressed()} 在界面打开时不可靠；
     * {@code InputConstants.isKeyDown} 只能查键盘。查鼠标必须用 {@code glfwGetMouseButton}。
     */
    private static boolean isLeftDown() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** 只有存在非空产物时才提供按钮。 */
    private static boolean hasOutput(IRecipeSlotsView slots) {
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            if (!slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选一个不压住槽位的落点（配方局部坐标）。
     *
     * <p>右边那一列是 JEI 放自带按钮的地方（实测 JEI 的 <code>RecipeLayout</code> 里
     * {@code RECIPE_BUTTON_SIZE=13}、{@code RECIPE_BUTTON_SPACING=2}，按钮从配方区域
     * 右下角往上叠）。所以我们的按钮也放进这一列，并叠在 JEI 那些按钮**上面**一格：
     * <ul>
     *   <li>有「收藏此配方」按钮（内部类探测）时，列里已有 2 格；</li>
     *   <li>没有时只有转移按钮 1 格（甚至 0 格）。</li>
     * </ul>
     * 若那一格被槽位占用，再退回配方外的上方、最后才是内部角落。
     */
    private static Rect2i place(IRecipeCategory<?> category, IRecipeSlotsView slots) {
        int width = Math.max(category.getWidth(), SIZE);
        int height = Math.max(category.getHeight(), SIZE);
        int columnX = width + BORDER_PADDING + 2;
        List<Rect2i> used = slotRects(slots);
        List<int[]> candidates = new ArrayList<>();
        // 叠在 JEI 自带按钮上方一格：从底部往上数 occupied 格
        int occupied = JeiCapabilities.hasJeiRecipeBookmarkButton() ? 2 : 1;
        int stackY = height - SIZE - occupied * (SIZE + BUTTON_SPACING);
        candidates.add(new int[]{columnX, stackY});
        // 上方一格放不下（压到槽位）时，退到配方区域上方同一列
        candidates.add(new int[]{columnX, -SIZE - BUTTON_SPACING});
        candidates.add(new int[]{width - SIZE, 0});
        candidates.add(new int[]{width - SIZE, height - SIZE});
        for (int[] at : candidates) {
            Rect2i rect = new Rect2i(at[0], at[1], SIZE, SIZE);
            if (!overlapsAny(rect, used)) {
                return rect;
            }
        }
        return new Rect2i(candidates.get(0)[0], candidates.get(0)[1], SIZE, SIZE);
    }

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

    /**
     * 转成编码样板并打开配方编码终端。
     *
     * <p>解析配方与切屏都放到主线程的下一 tick：这里正处在 JEI 的渲染调用栈里，
     * 直接做重活/切屏容易把渲染栈和 GUI 状态搅乱；同时任何解析异常（含类加载失败）
     * 都在 try/catch 里收口，不能让它冒到渲染线程。
     */
    private static void openTerminal(IRecipeCategory<?> category, IRecipeSlotsView slots) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            try {
                RecipePattern pattern = JeiPatternImporter.build(category, slots);
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页按钮：{} → 打开配方编码终端",
                        category.getRecipeType().getUid());
                minecraft.setScreen(new BalancerScreen(pattern));
            } catch (Throwable t) {
                RecipeBalancerMod.LOGGER.error(
                        "[RecipeBalancer] JEI 配方页按钮：解析配方失败（配方页保持原样）", t);
            }
        });
    }
}
