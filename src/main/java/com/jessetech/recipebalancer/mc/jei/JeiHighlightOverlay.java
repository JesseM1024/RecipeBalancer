package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 「配方库已有」高亮的绘制与延后提交。
 *
 * <p>JEI 15.x 的 {@code IRecipeCategoryDecorator#draw} 在**槽位物品之前**调用，
 * 在那里直接画高亮会被物品图标压住（看起来「图标盖在高亮上」）。所以装饰器路径只负责
 * 把「这一帧要高亮的屏幕矩形」排进队列，等 {@code ScreenEvent.Render.Post}
 * （此时屏幕已渲染并 flush 完）再统一画，保证高亮在最上层。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>，未装 JEI 时也能安全注册事件。
 */
public final class JeiHighlightOverlay {

    /** 深蓝、低不透明度的「已有配方」高亮（与 main 分支同一组配色）。 */
    public static final int HIGHLIGHT_FILL = 0x40004688;
    public static final int HIGHLIGHT_BORDER = 0x9F004E96;

    private static final List<Rect2i> QUEUED = new ArrayList<>();

    private JeiHighlightOverlay() {
    }

    /** 排入本帧要画的矩形（屏幕坐标）。 */
    public static void queue(Rect2i screenRect) {
        if (screenRect != null && screenRect.getWidth() > 0 && screenRect.getHeight() > 0) {
            QUEUED.add(screenRect);
        }
    }

    /** 在槽位矩形上叠一层蓝色高亮；rect 必须与当前绘制坐标系同一空间。 */
    public static void drawHighlight(GuiGraphics graphics, Rect2i rect) {
        int x = rect.getX();
        int y = rect.getY();
        int x2 = x + rect.getWidth();
        int y2 = y + rect.getHeight();
        overlayFill(graphics, x, y, x2, y2, HIGHLIGHT_FILL);
        overlayFill(graphics, x, y, x2, y + 1, HIGHLIGHT_BORDER);
        overlayFill(graphics, x, y2 - 1, x2, y2, HIGHLIGHT_BORDER);
        overlayFill(graphics, x, y + 0, x + 1, y2, HIGHLIGHT_BORDER);
        overlayFill(graphics, x2 - 1, y, x2, y2, HIGHLIGHT_BORDER);
    }

    /** 屏幕渲染完成后统一画队列里的高亮（画完立即 flush，确保盖住物品）。 */
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (QUEUED.isEmpty()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        // 只认 JEI 的配方页；其它屏幕（例如玩家中途关屏）直接丢弃这一帧的队列
        String screenName = event.getScreen() == null ? ""
                : event.getScreen().getClass().getName();
        if (screenName.startsWith("mezz.jei")) {
            for (Rect2i rect : QUEUED) {
                drawHighlight(graphics, rect);
            }
            graphics.flush();
        }
        QUEUED.clear();
    }

    /** guiOverlay：不吃深度，保证盖在物品图标之上（和 JEI 自带高亮一致）。 */
    private static void overlayFill(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fillGradient(RenderType.guiOverlay(), x1, y1, x2, y2, color, color, 0);
    }
}
