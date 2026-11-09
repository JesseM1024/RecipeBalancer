package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.jessetech.recipebalancer.mc.ui.Chat;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.mojang.blaze3d.matrix.MatrixStack;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.renderer.Rectangle2d;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * JEI 配方页的「编码此配方」按钮（1.16.5 专有实现）。
 *
 * <p>为什么只能自绘：JEI 7.6 ~ 7.8 既没有 {@code IRecipeCategoryDecorator}，也没有按钮 API
 * （{@code IAdvancedRegistration} 只有 {@code addRecipeManagerPlugin}），
 * 唯一的办法是反射 {@code RecipesGui} 的私有字段 {@code recipeLayouts}，
 * 在 {@code GuiScreenEvent.DrawScreenEvent.Post} 里自己画按钮、悬停高亮与提示，
 * 并在鼠标点击事件里处理导入。
 *
 * <p>摆位原则（与 1.18.2 一致）：优先贴在配方矩形右上角内侧；若压住槽位就依次尝试
 * 右侧外侧、左侧外侧、左上角，实在放不下就跳过这一份配方 —— 按钮绝不能压住槽位，
 * 否则悬停时 JEI 会叠两层物品提示。
 *
 * <p>所有反射都是「取到就用、取不到只告警一次」，任何异常都不能影响主界面与求解。
 */
public final class JeiRecipeOverlay {

    /** 蓝色高亮结果缓存时长（避免每帧对整份配方做候选配料判定）。 */
    private static final long REFRESH_MS = 500L;

    private static boolean installed;
    private static boolean warned;

    /** 当前屏幕的按钮位置与配方（每帧最多重算一次，屏幕/配方变了才刷新）。 */
    private static Screen cachedScreen;
    private static long cachedAt;
    private static List<Hit> hits = new ArrayList<>();

    private JeiRecipeOverlay() {
    }

    /** 注册到 Forge 游戏总线（插件在 registerGuiHandlers 之后调用）。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        MinecraftForge.EVENT_BUS.addListener(JeiRecipeOverlay::onDraw);
        MinecraftForge.EVENT_BUS.addListener(JeiRecipeOverlay::onMouseClicked);
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页叠加层：已注册（1.16.5 / 7.x）");
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private static void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        Screen screen = event.getGui();
        if (!(screen instanceof RecipesGui)) {
            cache(screen, new ArrayList<Hit>());
            return;
        }
        try {
            List<Hit> current = hitsFor(screen);
            MatrixStack pose = event.getMatrixStack();
            FontRenderer font = Minecraft.getInstance().fontRenderer;
            int mouseX = event.getMouseX();
            int mouseY = event.getMouseY();
            for (Hit hit : current) {
                boolean hovered = inside(mouseX, mouseY, hit.x, hit.y);
                if (hovered) {
                    drawCraftableHighlights(pose, hit);
                }
                JeiButtonSkin.draw(pose, hit.x, hit.y, hovered);
                if (hovered) {
                    drawTooltip(pose, font, Lang.t("编码此配方到 RecipeBalancer"), mouseX, mouseY);
                }
            }
        } catch (RuntimeException | LinkageError t) {
            warn(t);
        }
    }

    /** 悬停时把「配方库能自产」的槽位标蓝（配色与 1.20.1 / 1.18.2 严格一致）。 */
    private static void drawCraftableHighlights(MatrixStack pose, Hit hit) {
        ProductIndex index = ProductIndex.current();
        for (JeiSlots.Slot slot : JeiCraftableSlots.filter(hit.slots, index)) {
            JeiHighlightOverlay.draw(pose, JeiSlots.absolute(slot));
        }
    }

    /** 自绘一行提示（Screen 的 tooltip 方法是 protected，这里按原版样式手画）。 */
    private static void drawTooltip(MatrixStack pose, FontRenderer font, String text,
                                    int mouseX, int mouseY) {
        int width = font.getStringWidth(text);
        int x = mouseX + 12;
        int y = mouseY - 12;
        AbstractGui.fill(pose, x - 3, y - 3, x + width + 3, y + 11, 0xF0100010);
        font.drawStringWithShadow(pose, text, x, y, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    private static void onMouseClicked(GuiScreenEvent.MouseClickedEvent.Post event) {
        if (event.getButton() != 0) {
            return;
        }
        Screen screen = event.getGui();
        if (!(screen instanceof RecipesGui)) {
            return;
        }
        try {
            double mouseX = event.getMouseX();
            double mouseY = event.getMouseY();
            for (Hit hit : hitsFor(screen)) {
                if (!inside(mouseX, mouseY, hit.x, hit.y)) {
                    continue;
                }
                RecipePattern pattern = JeiPatternImporter.build(hit.category, hit.layout);
                Minecraft.getInstance().displayGuiScreen(BalancerScreen.withPattern(pattern));
                return;
            }
        } catch (RuntimeException | LinkageError t) {
            warn(t);
        }
    }

    // ------------------------------------------------------------------
    // 布局计算（按屏幕缓存 500ms）
    // ------------------------------------------------------------------

    private static List<Hit> hitsFor(Screen screen) {
        long now = System.currentTimeMillis();
        if (screen == cachedScreen && now - cachedAt < REFRESH_MS) {
            return hits;
        }
        List<Hit> computed = compute(screen);
        cache(screen, computed);
        return computed;
    }

    private static void cache(Screen screen, List<Hit> value) {
        cachedScreen = screen;
        cachedAt = System.currentTimeMillis();
        hits = value;
    }

    private static List<Hit> compute(Screen screen) {
        List<Hit> result = new ArrayList<>();
        Object layouts = field(screen, "recipeLayouts");
        if (!(layouts instanceof List)) {
            return result;
        }
        for (Object value : (List<?>) layouts) {
            if (!(value instanceof RecipeLayout)) {
                continue;
            }
            RecipeLayout<?> layout = (RecipeLayout<?>) value;
            Rectangle2d rect = layoutRect(layout);
            if (rect == null) {
                continue;
            }
            List<JeiSlots.Slot> slots = JeiSlots.fromLayout(layout);
            int[] at = place(rect, slots, screen);
            if (at == null) {
                continue;
            }
            result.add(new Hit(at[0], at[1], layout, layout.getRecipeCategory(), slots));
        }
        return result;
    }

    /** 配方矩形：位置取 {@code getPosX/getPosY}，尺寸取类别背景图（取不到按 120×60 估）。 */
    private static Rectangle2d layoutRect(RecipeLayout<?> layout) {
        try {
            int width = 120;
            int height = 60;
            IDrawable background = layout.getRecipeCategory() == null ? null
                    : layout.getRecipeCategory().getBackground();
            if (background != null) {
                width = background.getWidth();
                height = background.getHeight();
            }
            return new Rectangle2d(layout.getPosX(), layout.getPosY(), width, height);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 按钮摆位：先试右上角内侧，再试右侧外侧、左侧外侧、左上角。
     *
     * @return {x, y}；放不下返回 null
     */
    private static int[] place(Rectangle2d rect, List<JeiSlots.Slot> slots, Screen screen) {
        int size = JeiButtonSkin.SIZE;
        int[][] candidates = {
                {rect.getX() + rect.getWidth() - size - 1, rect.getY() + 1},
                {rect.getX() + rect.getWidth() + 2, rect.getY()},
                {rect.getX() - size - 2, rect.getY()},
                {rect.getX() + 1, rect.getY() + 1},
        };
        for (int[] at : candidates) {
            if (at[0] < 0 || at[1] < 0 || at[0] + size > screen.width
                    || at[1] + size > screen.height) {
                continue;
            }
            if (!overlapsAny(at[0], at[1], size, slots)) {
                return at;
            }
        }
        return null;
    }

    private static boolean overlapsAny(int x, int y, int size, List<JeiSlots.Slot> slots) {
        for (JeiSlots.Slot slot : slots) {
            if (!slot.hasRect()) {
                continue;
            }
            if (x < slot.x() + slot.width() && slot.x() < x + size
                    && y < slot.y() + slot.height() && slot.y() < y + size) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + JeiButtonSkin.SIZE
                && mouseY >= y && mouseY < y + JeiButtonSkin.SIZE;
    }

    private static Object field(Object owner, String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static void warn(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        RecipeBalancerMod.LOGGER.warn(
                "[RecipeBalancer] JEI 配方页叠加层不可用（仅提示一次），本功能降级: {}",
                t.toString());
    }

    /** 一条命中记录：按钮位置 + 配方布局 + 类别 + 槽位快照。 */
    private static final class Hit {

        private final int x;
        private final int y;
        private final RecipeLayout<?> layout;
        private final IRecipeCategory<?> category;
        private final List<JeiSlots.Slot> slots;

        Hit(int x, int y, RecipeLayout<?> layout, IRecipeCategory<?> category,
            List<JeiSlots.Slot> slots) {
            this.x = x;
            this.y = y;
            this.layout = layout;
            this.category = category;
            this.slots = slots;
        }
    }
}
