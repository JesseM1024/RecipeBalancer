package com.jessetech.recipebalancer.mc.jei;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import java.util.List;

/**
 * JEI 拖拽到本界面时的高亮区域（纯数据，<b>不引用任何 {@code mezz.jei.*} 类型</b>）。
 *
 * <p>为什么不交给 JEI 画：不同 JEI 版本把幽灵高亮画在不同的绘制通道里，
 * 坐标空间的补偿各不相同（1.18.2 分支上 10.81 实测会整体偏移）。这里让 JEI 只负责
 * 「拖拽判定」——{@code Target#getArea()} 仍返回绝对屏幕坐标，鼠标命中与投放都正确，
 * 高亮改由本界面自己在 {@code render} 里按同一份矩形绘制，位置与投放区永远一致。
 *
 * <p>{@link JeiGhostHandler} 在拖拽开始/结束时写入与清空；界面只读。
 */
public final class JeiGhostHighlights {

    /** 普通目标色（JEI 默认值）。 */
    private static final int TARGET_COLOR = 0x4013C90A;
    /** 鼠标所在目标的高亮色（JEI 默认值）。 */
    private static final int HOVER_COLOR = 0x804CC959;

    /** 目标区域（绝对屏幕坐标，18×18 一格）。 */
    private static volatile List<Rect2i> areas = List.of();

    private JeiGhostHighlights() {
    }

    /** 拖拽开始：记下本次可投放的所有目标区域。 */
    public static void begin(List<Rect2i> rects) {
        areas = rects == null || rects.isEmpty() ? List.of() : List.copyOf(rects);
    }

    /** 拖拽结束（投放/取消/关闭界面）。 */
    public static void end() {
        areas = List.of();
    }

    public static boolean active() {
        return !areas.isEmpty();
    }

    public static List<Rect2i> areas() {
        return areas;
    }

    /**
     * 把当前高亮画一遍（绝对屏幕坐标）。
     *
     * <p>调用方必须在界面渲染的最后一层画（控件与内容之后、tooltip 之前），并且<b>每帧只调一次</b>：
     * JEI 自己对普通 {@code Screen} 有两条绘制路径（{@code drawTooltips} 与
     * {@code drawOnForeground}），两边都会画一遍，视觉上就是「同一块区域画了两次」。
     * 本模组把 {@code shouldHighlightTargets()} 关掉后由界面自绘，正好只画一次。
     */
    public static void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Rect2i area : areas) {
            boolean hovered = mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                    && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
            graphics.fill(area.getX(), area.getY(),
                    area.getX() + area.getWidth(), area.getY() + area.getHeight(),
                    hovered ? HOVER_COLOR : TARGET_COLOR);
        }
    }
}
