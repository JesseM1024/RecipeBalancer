package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.model.RecipePattern;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import com.mojang.blaze3d.vertex.PoseStack;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 配方页「编码此配方」按钮的 9.x 实现（客户端叠加层）。
 *
 * <p><b>为什么需要叠加层</b>：JEI 9.x 既没有 {@code IRecipeButtonControllerFactory}
 * 也没有 {@code IRecipeCategoryDecorator}；官方唯一的「配方页按钮」是配方转移按钮，
 * 而转移处理器由<b>服务端</b>执行 —— 纯客户端模组（服务端可能没装）用不了。
 * 所以这里直接在 JEI 配方页上自绘按钮：
 * <ul>
 *   <li>绘制：{@code ScreenEvent.DrawScreenEvent.Post}，只对 JEI 的配方界面生效；</li>
 *   <li>点击：{@code ScreenEvent.MouseClickedEvent.Pre}（可取消），不会漏给 JEI 的槽位；</li>
 *   <li>配方来源：反射读 {@code RecipesGui} 的 {@code recipeLayouts} 字段，
 *       每份布局取类别、位置与槽位视图（槽位矩形靠 {@link JeiSlots} 统一）。</li>
 * </ul>
 *
 * <p>图案与摆位严格对齐 1.20.1 的 {@code JeiLegacyRecipeButton}：
 * 图案用 {@link JeiButtonSkin}（13×13 像素表 + 悬停白框）；
 * 优先放在配方<b>右侧的按钮列</b>（JEI 自带按钮上方一格），那一列不属于任何槽位，
 * 因此鼠标悬停时 JEI 不会再叠一层物品提示。
 */
public final class JeiRecipeOverlay {

    private static final int BUTTON = JeiButtonSkin.SIZE;
    /** JEI 配方边框内边距。 */
    private static final int BORDER_PADDING = 4;
    /** JEI 自带配方按钮的间距。 */
    private static final int BUTTON_SPACING = 2;
    /** JEI 槽位边长（16 物品格 + 1px 边框，取 18 做保守的重叠判断）。 */
    private static final int SLOT = 18;

    private static boolean installed;
    /** 反射失败只提示一次，避免每帧刷日志。 */
    private static boolean warned;

    private JeiRecipeOverlay() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // 用带 Class 参数的重载：方法引用推断不出泛型事件类型，用 Consumer 重载会在运行期抛
        // “Could not resolve type”。这里显式指定事件类，最稳。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                ScreenEvent.DrawScreenEvent.Post.class, JeiRecipeOverlay::onDraw);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                ScreenEvent.MouseClickedEvent.Pre.class, JeiRecipeOverlay::onClick);
        RecipeBalancerMod.LOGGER.info(
                "[RecipeBalancer] JEI 配方页按钮：客户端叠加层已安装（{}；官方按钮注册成功时自动让位）",
                JeiCapabilities.version());
    }

    private static void onDraw(ScreenEvent.DrawScreenEvent.Post event) {
        Screen screen = event.getScreen();
        if (!active(screen)) {
            return;
        }
        List<Hit> hits = collect(screen);
        if (hits.isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();
        for (Hit hit : hits) {
            boolean hovered = inside(mouseX, mouseY, hit.x(), hit.y());
            JeiButtonSkin.draw(pose, hit.x(), hit.y(), hovered);
            if (hovered) {
                screen.renderTooltip(pose, Lang.c("编码此配方到 RecipeBalancer"),
                        mouseX, mouseY);
            }
        }
    }

    private static void onClick(ScreenEvent.MouseClickedEvent.Pre event) {
        Screen screen = event.getScreen();
        if (event.getButton() != 0 || !active(screen)) {
            return;
        }
        for (Hit hit : collect(screen)) {
            if (!inside(event.getMouseX(), event.getMouseY(), hit.x(), hit.y())) {
                continue;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            RecipePattern pattern = JeiPatternImporter.buildFromSlots(hit.category(), hit.slots());
            // 正处在 JEI 的输入/渲染调用栈里，切屏排到主线程下一 tick，避免渲染栈错位
            minecraft.execute(() -> minecraft.setScreen(BalancerScreen.withPattern(pattern)));
            event.setCanceled(true);
            return;
        }
    }

    /** 只对 JEI 配方界面生效；官方按钮注册成功时让位（避免重复按钮）。 */
    private static boolean active(Screen screen) {
        if (screen == null || RecipeBalancerJeiPlugin.officialRecipeButtons()) {
            return false;
        }
        IJeiRuntime runtime = JeiIngredientMapper.runtime();
        return runtime != null && runtime.getRecipesGui() == screen;
    }

    /** 一个可点击的按钮位置 + 该配方的编码数据。 */
    private record Hit(int x, int y, IRecipeCategory<?> category, List<JeiSlots.Slot> slots) {
    }

    private static List<Hit> collect(Screen screen) {
        List<Hit> hits = new ArrayList<>();
        if (screen == null) {
            return hits;
        }
        List<?> layouts = recipeLayouts(screen);
        if (layouts == null) {
            // 9.x 用 recipeLayouts；10.x 换成了 RecipeGuiLayouts，字段名不同，这里走 10.x 路径
            return collectModern(screen);
        }
        for (Object layout : layouts) {
            if (layout == null) {
                continue;
            }
            IRecipeCategory<?> category = asCategory(invoke(layout, "getRecipeCategory"));
            Integer posX = asInt(invoke(layout, "getPosX"));
            Integer posY = asInt(invoke(layout, "getPosY"));
            if (category == null || posX == null || posY == null) {
                continue;
            }
            int width;
            int height;
            try {
                width = Math.max(category.getBackground().getWidth(), BUTTON);
                height = Math.max(category.getBackground().getHeight(), BUTTON);
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            Object slotsHolder = invoke(layout, "getRecipeSlots");
            Object view = slotsHolder == null ? null : invoke(slotsHolder, "getView");
            List<JeiSlots.Slot> slots = view instanceof IRecipeSlotsView slotsView
                    ? JeiSlots.fromView(slotsView) : List.of();
            if (slots.isEmpty()) {
                // 取不到槽位就不画按钮，避免点出一个空样板
                continue;
            }
            int[] local = place(width, height, slotRects(slots));
            int x = clamp(posX + local[0], screen.width);
            int y = clamp(posY + local[1], screen.height);
            boolean duplicated = false;
            for (Hit existing : hits) {
                if (existing.x() == x && existing.y() == y) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                hits.add(new Hit(x, y, category, slots));
            }
        }
        return hits;
    }

    /**
     * 10.x（含没有官方按钮 API 的 10.6）的布局枚举：
     * {@code RecipesGui.layouts} → {@code RecipeGuiLayouts.recipeLayoutsWithButtons}
     * → 每项取 {@code IRecipeLayoutDrawable}（{@code recipeLayout()/getRecipeLayout()}）。
     *
     * <p>10.x 的布局矩形是<b>屏幕绝对坐标</b>，所以按钮直接贴在配方右侧的按钮列，
     * 不需要再做局部坐标换算。
     */
    private static List<Hit> collectModern(Screen screen) {
        List<Hit> hits = new ArrayList<>();
        Object guiLayouts = readFieldValue(findField(screen.getClass(), "layouts"), screen);
        if (guiLayouts == null) {
            warnOnce("找不到 RecipesGui.layouts 字段（10.x）");
            return hits;
        }
        Object raw = readFieldValue(findListField(guiLayouts.getClass()), guiLayouts);
        if (!(raw instanceof List<?> layouts)) {
            warnOnce("找不到 RecipeGuiLayouts 的布局列表字段");
            return hits;
        }
        for (Object item : layouts) {
            Object layout = invokeAny(item, "recipeLayout", "getRecipeLayout");
            if (layout == null) {
                continue;
            }
            Object rectValue = invoke(layout, "getRect");
            IRecipeCategory<?> category = asCategory(invoke(layout, "getRecipeCategory"));
            Object view = invoke(layout, "getRecipeSlotsView");
            if (!(rectValue instanceof Rect2i rect) || category == null
                    || !(view instanceof IRecipeSlotsView slotsView)) {
                continue;
            }
            List<JeiSlots.Slot> slots = JeiSlots.fromView(slotsView);
            if (slots.isEmpty()) {
                continue;
            }
            // 配方右侧的按钮列（JEI 自带按钮上方一格），与 1.20.1 的位置一致
            int x = clamp(rect.getX() + rect.getWidth() + BORDER_PADDING + 2, screen.width);
            int y = clamp(rect.getY() + rect.getHeight() - BUTTON - (BUTTON + BUTTON_SPACING),
                    screen.height);
            if (duplicated(hits, x, y)) {
                continue;
            }
            hits.add(new Hit(x, y, category, slots));
        }
        return hits;
    }

    private static boolean duplicated(List<Hit> hits, int x, int y) {
        for (Hit existing : hits) {
            if (existing.x() == x && existing.y() == y) {
                return true;
            }
        }
        return false;
    }

    private static Object readFieldValue(Field field, Object owner) {
        if (field == null || owner == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 在类层级里找第一个 {@code List} 类型的字段（10.x 的布局列表）。 */
    private static Field findListField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Object invokeAny(Object target, String... methods) {
        for (String method : methods) {
            Object value = invoke(target, method);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 与 1.20.1 完全一致的摆位：优先叠在 JEI 自带按钮列的上方一格，
     * 放不下时退到配方外上方，最后才是配方内部的两个角落。
     */
    private static int[] place(int width, int height, List<Rect2i> used) {
        int columnX = width + BORDER_PADDING + 2;
        int stackY = height - BUTTON - (BUTTON + BUTTON_SPACING);
        int[][] candidates = {
                {columnX, stackY},
                {columnX, -BUTTON - BUTTON_SPACING},
                {width - BUTTON, 0},
                {width - BUTTON, height - BUTTON},
        };
        for (int[] at : candidates) {
            if (!overlapsAny(new Rect2i(at[0], at[1], BUTTON, BUTTON), used)) {
                return at;
            }
        }
        return candidates[0];
    }

    private static List<Rect2i> slotRects(List<JeiSlots.Slot> slots) {
        List<Rect2i> rects = new ArrayList<>(slots.size());
        for (JeiSlots.Slot slot : slots) {
            if (slot.hasRect()) {
                rects.add(new Rect2i(slot.x(), slot.y(), SLOT, SLOT));
            }
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

    /** 反射读 {@code RecipesGui.recipeLayouts}（9.x 的当前页布局列表）。 */
    private static List<?> recipeLayouts(Screen screen) {
        Field field = findField(screen.getClass(), "recipeLayouts");
        if (field == null) {
            warnOnce("找不到 RecipesGui.recipeLayouts 字段");
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(screen);
            return value instanceof List<?> list ? list : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("读取 RecipesGui.recipeLayouts 失败: " + e);
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            }
        }
        return null;
    }

    private static Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method found = target.getClass().getMethod(method);
            found.setAccessible(true);
            return found.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("调用 " + target.getClass().getSimpleName() + "." + method + " 失败: " + e);
            return null;
        }
    }

    private static IRecipeCategory<?> asCategory(Object value) {
        return value instanceof IRecipeCategory<?> category ? category : null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int clamp(int value, int limit) {
        return Math.max(0, Math.min(value, limit - BUTTON - 1));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + BUTTON && mouseY >= y && mouseY < y + BUTTON;
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] JEI 配方页按钮（叠加层）不可用：{}（仅提示一次）", message);
        }
    }
}
