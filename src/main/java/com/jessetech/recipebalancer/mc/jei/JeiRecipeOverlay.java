package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.ProductIndex;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.jessetech.recipebalancer.mc.ui.Draw;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * JEI 配方页的「编码此配方」按钮（1.12.2 专有实现）。
 *
 * <p>为什么只能自绘：1.12.2 的 JEI 4.x 没有配方页按钮 API
 * （{@code IIconButtonController} 全版本都不存在），唯一的办法是反射
 * {@code RecipesGui} 的私有字段 {@code recipeLayouts}，在
 * {@code GuiScreenEvent.DrawScreenEvent.Post} 里自己画按钮、悬停高亮与提示，
 * 并在 {@code MouseInputEvent.Post} 里处理导入。
 *
 * <p>摆位原则（与其它版本线一致）：优先贴在配方矩形右上角内侧；若压住槽位就依次尝试
 * 右侧外侧、左侧外侧、右下角，实在放不下就跳过这一份配方 —— 按钮绝不能压住槽位，
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
    private static GuiScreen cachedScreen;
    private static long cachedAt;
    private static List<Hit> hits = new ArrayList<>();

    private JeiRecipeOverlay() {
    }

    /** 注册到 Forge 游戏总线（插件在 onRuntimeAvailable 里调用）。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // LOWEST：让本监听器在 JEI 画完物品面板之后再执行，否则按钮与提示会被
        // JEI 的物品列表盖住。
        MinecraftForge.EVENT_BUS.register(new JeiRecipeOverlay.Listener());
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 配方页叠加层：已注册（1.12.2 / 4.x）");
    }

    /** 事件订阅体（1.12.2 是实例订阅 + @SubscribeEvent）。 */
    public static final class Listener {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
            JeiRecipeOverlay.draw(event.getGui(), event.getMouseX(), event.getMouseY());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onMouseInput(GuiScreenEvent.MouseInputEvent.Post event) {
            JeiRecipeOverlay.mouseInput(event.getGui());
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private static void draw(GuiScreen screen, int mouseX, int mouseY) {
        if (!(screen instanceof RecipesGui)) {
            cache(screen, new ArrayList<Hit>());
            return;
        }
        try {
            List<Hit> current = hitsFor(screen);
            Draw graphics = new Draw();
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            for (Hit hit : current) {
                boolean hovered = inside(mouseX, mouseY, hit.x, hit.y);
                if (hovered) {
                    drawCraftableHighlights(graphics, hit);
                }
                JeiButtonSkin.draw(graphics, hit.x, hit.y, hovered);
                if (hovered) {
                    drawTooltip(font, Lang.t("编码此配方到 RecipeBalancer"), mouseX, mouseY);
                }
            }
        } catch (RuntimeException | LinkageError t) {
            warn(t);
        }
    }

    /** 悬停时把「配方库能自产」的槽位标蓝（配色与其它版本线严格一致）。 */
    private static void drawCraftableHighlights(Draw graphics, Hit hit) {
        ProductIndex index = ProductIndex.current();
        List<JeiSlots.Slot> craftable = JeiCraftableSlots.filter(hit.slots, index);
        // 诊断：只在「悬停目标变化」时打一行，避免刷屏
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
            JeiHighlightOverlay.draw(graphics, JeiSlots.absolute(slot));
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
     * 提示用游戏本体的 tooltip 渲染（Forge {@code GuiUtils.drawHoveringText}）：
     * 自带原版背景/紫色边框、屏幕边缘回退与按宽度自动换行。
     */
    private static void drawTooltip(FontRenderer font, String text, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getMinecraft();
        List<String> lines = new ArrayList<>(1);
        lines.add(text);
        GuiUtils.drawHoveringText(lines, mouseX, mouseY, minecraft.currentScreen.width,
                minecraft.currentScreen.height, 180, font);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    private static void mouseInput(GuiScreen screen) {
        if (!(screen instanceof RecipesGui)) {
            return;
        }
        if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        int mouseX = Mouse.getEventX() * screen.width / minecraft.displayWidth;
        int mouseY = screen.height - Mouse.getEventY() * screen.height / minecraft.displayHeight - 1;
        try {
            for (Hit hit : hitsFor(screen)) {
                if (!inside(mouseX, mouseY, hit.x, hit.y)) {
                    continue;
                }
                RecipePattern pattern = JeiPatternImporter.build(hit.category, hit.layout);
                minecraft.displayGuiScreen(BalancerScreen.withPattern(pattern));
                return;
            }
        } catch (RuntimeException | LinkageError t) {
            warn(t);
        }
    }

    // ------------------------------------------------------------------
    // 布局计算（按屏幕缓存 500ms）
    // ------------------------------------------------------------------

    private static List<Hit> hitsFor(GuiScreen screen) {
        long now = System.currentTimeMillis();
        if (screen == cachedScreen && now - cachedAt < REFRESH_MS) {
            return hits;
        }
        List<Hit> computed = compute(screen);
        cache(screen, computed);
        return computed;
    }

    private static void cache(GuiScreen screen, List<Hit> value) {
        cachedScreen = screen;
        cachedAt = System.currentTimeMillis();
        hits = value;
    }

    private static List<Hit> compute(GuiScreen screen) {
        List<Hit> result = new ArrayList<>();
        Object layouts = JeiReflect.field(screen, "recipeLayouts");
        if (!(layouts instanceof List)) {
            return result;
        }
        for (Object value : (List<?>) layouts) {
            if (!(value instanceof RecipeLayout)) {
                continue;
            }
            RecipeLayout layout = (RecipeLayout) value;
            Rectangle rect = layoutRect(layout);
            if (rect == null) {
                continue;
            }
            List<JeiSlots.Slot> slots = JeiSlots.fromLayout(layout);
            List<Rectangle> blocked = new ArrayList<>();
            for (JeiSlots.Slot slot : slots) {
                if (slot.hasRect()) {
                    blocked.add(new Rectangle(slot.x(), slot.y(), slot.width(), slot.height()));
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
    private static Rectangle layoutRect(RecipeLayout layout) {
        try {
            int width = 120;
            int height = 60;
            IRecipeCategory<?> category = layout.getRecipeCategory();
            IDrawable background = category == null ? null : category.getBackground();
            if (background != null) {
                width = background.getWidth();
                height = background.getHeight();
            }
            return new Rectangle(layout.getPosX(), layout.getPosY(), width, height);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 把 JEI 自己的「配方转移」按钮记为禁区。
     *
     * <p>AE / RS 编码终端这类容器屏上，JEI 会在配方右下角画转移按钮；
     * 我们的按钮优先位置正好落在附近，不避让就会叠在一起。
     */
    private static boolean addTransferButton(List<Rectangle> blocked, RecipeLayout layout) {
        Object button = JeiReflect.call(layout, "getRecipeTransferButton");
        if (button == null) {
            return false;
        }
        Object visible = JeiReflect.field(button, "visible");
        if (visible instanceof Boolean && !((Boolean) visible)) {
            return false;
        }
        int x = intField(button, "x");
        int y = intField(button, "y");
        int width = intField(button, "width");
        int height = intField(button, "height");
        if (width > 0 && height > 0) {
            blocked.add(new Rectangle(x, y, width, height));
            return true;
        }
        return false;
    }

    private static int intField(Object owner, String name) {
        Object value = JeiReflect.field(owner, name);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /**
     * 按钮摆位：**一律**贴配方右侧上方。
     *
     * <p>右下角是各家 JEI 插件的「转移 / 编码 / 书签」按钮聚集区
     * （JEI 自己的转移按钮、AE2 / RS 的编码按钮都在那一带），
     * 而这些都是第三方控件、没有可枚举的 API，无法做精确避让。
     * 现在统一放右上，再依次向右中、内部右上、右下回退。
     *
     * @return {x, y}；放不下返回 null
     */
    private static int[] place(Rectangle rect, List<Rectangle> blocked, GuiScreen screen) {
        int size = JeiButtonSkin.SIZE;
        int right = rect.x + rect.width + 2;
        int bottom = rect.y + rect.height - size;
        int middle = rect.y + (rect.height - size) / 2;
        int top = rect.y + 2;
        int[][] candidates = {
                {right, top},
                {right, middle},
                {rect.x + rect.width - size - 1, rect.y + 1},
                {right, bottom},
                {rect.x + rect.width - size - 1, bottom - 1},
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
    private static void logPlacement(Rectangle rect, int blocked, int[] at) {
        String key = rect.x + "," + rect.y + "," + rect.width + ","
                + rect.height + "->" + at[0] + "," + at[1];
        if (!PLACED.add(key)) {
            return;
        }
        RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] JEI 按钮位置: 配方[{},{} {}x{}] 禁区={} -> [{},{}]",
                rect.x, rect.y, rect.width, rect.height, blocked, at[0], at[1]);
    }

    private static boolean overlapsAny(int x, int y, int size, List<Rectangle> blocked) {
        for (Rectangle area : blocked) {
            if (x < area.x + area.width && area.x < x + size
                    && y < area.y + area.height && area.y < y + size) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + JeiButtonSkin.SIZE
                && mouseY >= y && mouseY < y + JeiButtonSkin.SIZE;
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
        private final RecipeLayout layout;
        private final IRecipeCategory<?> category;
        private final List<JeiSlots.Slot> slots;

        Hit(int x, int y, RecipeLayout layout, IRecipeCategory<?> category,
            List<JeiSlots.Slot> slots) {
            this.x = x;
            this.y = y;
            this.layout = layout;
            this.category = category;
            this.slots = slots;
        }
    }
}
