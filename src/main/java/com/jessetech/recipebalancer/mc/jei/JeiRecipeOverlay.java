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

    /** 已记录过摆位日志的（配方矩形 + 落点），避免每帧刷屏。 */
    private static final java.util.Set<String> PLACED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 上一次打过高亮诊断的命中项（避免刷屏）。 */
    private static Hit lastLoggedHit;

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
        // LOWEST：让本监听器在 JEI 画完物品面板之后再执行，否则按钮与提示会被
        // JEI 的物品列表盖住（1.16.5 的 Screen 没有「延迟 tooltip」队列可用）。
        MinecraftForge.EVENT_BUS.addListener(
                net.minecraftforge.eventbus.api.EventPriority.LOWEST, false,
                JeiRecipeOverlay::onDraw);
        MinecraftForge.EVENT_BUS.addListener(
                net.minecraftforge.eventbus.api.EventPriority.LOWEST, false,
                JeiRecipeOverlay::onMouseClicked);
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
        List<JeiSlots.Slot> craftable = JeiCraftableSlots.filter(hit.slots, index);
        // 诊断：只在「悬停目标变化」时打一行，避免刷屏；用来判断是高亮判定为空还是坐标不对
        if (hit != lastLoggedHit) {
            lastLoggedHit = hit;
            RecipeBalancerMod.LOGGER.info(
                    "[RecipeBalancer] 配方页高亮诊断: 槽位 {} 个, 有矩形 {} 个, 可自产 {} 个,"
                            + " 配方库可用={}",
                    hit.slots.size(), countWithRect(hit.slots), craftable.size(),
                    index != null);
            if (!craftable.isEmpty()) {
                JeiSlots.Slot first = craftable.get(0);
                RecipeBalancerMod.LOGGER.info(
                        "[RecipeBalancer] 首个高亮矩形: ({},{} {}x{})",
                        first.x(), first.y(), first.width(), first.height());
            }
        }
        for (JeiSlots.Slot slot : craftable) {
            JeiHighlightOverlay.draw(pose, JeiSlots.absolute(slot));
        }
    }

    private static int countWithRect(List<JeiSlots.Slot> slots) {
        int count = 0;
        for (JeiSlots.Slot slot : slots) {
            if (slot.hasRect()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 提示直接用**游戏本体**的 tooltip 渲染（Forge {@code GuiUtils.drawHoveringText}）。
     *
     * <p>它内部会 {@code disableDepthTest()} 再画，因此不会被 JEI 的槽位物品（渲染在
     * z=100 的 3D 物品）挡住；同时自带原版背景/紫色边框、屏幕边缘回退与按宽度自动换行，
     * 不需要任何「往左偏一点」的布局规避。
     */
    private static void drawTooltip(MatrixStack pose, FontRenderer font, String text,
                                    int mouseX, int mouseY) {
        net.minecraft.client.MainWindow window = Minecraft.getInstance().getMainWindow();
        java.util.List<net.minecraft.util.text.ITextComponent> lines = new java.util.ArrayList<>(1);
        lines.add(Chat.literal(text));
        net.minecraftforge.fml.client.gui.GuiUtils.drawHoveringText(
                pose, lines, mouseX, mouseY, window.getScaledWidth(), window.getScaledHeight(),
                180, font);
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
            List<Rectangle2d> blocked = new ArrayList<>();
            for (JeiSlots.Slot slot : slots) {
                if (slot.hasRect()) {
                    blocked.add(new Rectangle2d(slot.x(), slot.y(),
                            slot.width(), slot.height()));
                }
            }
            addTransferButton(blocked, layout);
            int[] at = place(rect, blocked, screen);
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
     * 把 JEI 自己的「配方转移」按钮记为禁区。
     *
     * <p>AE / RS 编码终端这类容器屏上，JEI 会在配方右下角画转移按钮；
     * 我们的按钮优先位置正好落在附近，不避让就会叠在一起。
     * 7.x 的 {@code RecipeTransferButton} 是原版 {@code Widget}，坐标在 protected 字段里，
     * 只能按字段名反射；拿不到就忽略（不影响其余避让）。
     *
     * @return 该布局是否带可见的转移按钮（用于判断「容器屏」，从而调整优先位置）
     */
    private static boolean addTransferButton(List<Rectangle2d> blocked, RecipeLayout<?> layout) {
        Object button = null;
        try {
            button = layout.getClass().getMethod("getRecipeTransferButton").invoke(layout);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 没有这个方法（或返回 null）：该版本不画转移按钮
        }
        if (button == null) {
            return false;
        }
        Object visible = field(button, "visible");
        if (visible instanceof Boolean && !((Boolean) visible)) {
            return false;
        }
        int x = intField(button, "x");
        int y = intField(button, "y");
        int width = intField(button, "width");
        int height = intField(button, "height");
        if (width > 0 && height > 0) {
            blocked.add(new Rectangle2d(x, y, width, height));
            return true;
        }
        return false;
    }

    private static int intField(Object owner, String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 按钮摆位：**一律**贴配方右侧上方。
     *
     * <p>右下角是各家 JEI 插件的「转移 / 编码 / 书签」按钮聚集区
     * （JEI 自己的转移按钮、AE2 / RS 的编码按钮都在那一带），
     * 而这些都是第三方控件、没有可枚举的 API，无法做精确避让 ——
     * 上一版尝试只在容器屏把按钮上移，但 AE/RS 终端并没有注册 JEI 转移按钮，
     * 检测不到「容器屏」，于是仍落在右下角重叠。
     * 现在统一放右上（实测各终端该处为空白），再依次向右中、内部右上、右下回退。
     *
     * @return {x, y}；放不下返回 null
     */
    private static int[] place(Rectangle2d rect, List<Rectangle2d> blocked, Screen screen) {
        int size = JeiButtonSkin.SIZE;
        int right = rect.getX() + rect.getWidth() + 2;
        int bottom = rect.getY() + rect.getHeight() - size;
        int middle = rect.getY() + (rect.getHeight() - size) / 2;
        int top = rect.getY() + 2;
        int[][] candidates = {
                {right, top},
                {right, middle},
                {rect.getX() + rect.getWidth() - size - 1, rect.getY() + 1},
                {right, bottom},
                {rect.getX() + rect.getWidth() - size - 1, bottom - 1},
        };
        for (int[] at : candidates) {
            if (at[0] < 0 || at[1] < 0 || at[0] + size > screen.width
                    || at[1] + size > screen.height) {
                continue;
            }
            if (!overlapsAny(at[0], at[1], size, blocked)) {
                logPlacement(rect, blocked.size(), at);
                return at;
            }
        }
        return null;
    }

    /** 每个布局只打一次，便于排查「按钮与第三方控件重叠」这类没有 API 可查的情况。 */
    private static void logPlacement(Rectangle2d rect, int blocked, int[] at) {
        String key = rect.getX() + "," + rect.getY() + "," + rect.getWidth() + ","
                + rect.getHeight() + "->" + at[0] + "," + at[1];
        if (!PLACED.add(key)) {
            return;
        }
        com.jessetech.recipebalancer.RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] JEI 按钮位置: 配方[{},{} {}x{}] 禁区={} -> [{},{}]",
                rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), blocked, at[0], at[1]);
    }

    private static boolean overlapsAny(int x, int y, int size, List<Rectangle2d> blocked) {
        for (Rectangle2d area : blocked) {
            if (x < area.getX() + area.getWidth() && area.getX() < x + size
                    && y < area.getY() + area.getHeight() && area.getY() < y + size) {
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
