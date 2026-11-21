package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.ui.Draw;

import java.awt.Rectangle;
import java.util.List;

/**
 * JEI 拖拽到本界面时的高亮区域（纯数据，<b>不引用任何 {@code mezz.jei.*} 类型</b>）。
 *
 * <p>为什么不交给 JEI 画：JEI 对同一块目标区域会经多条绘制路径各画一遍，
 * 实机上就是「同一块区域画了两次」；容器界面的绘制通道与坐标空间又随版本变化。
 * 这里让 JEI 只负责「拖拽判定」（{@code Target#getArea()} 仍返回绝对屏幕坐标，
 * 鼠标命中与投放都正确），高亮改由本界面自己在 {@code drawScreen} 里按同一份矩形绘制。
 *
 * <p>1.12.2 的矩形类型是 {@link Rectangle}（{@code java.awt.Rectangle}）。
 * {@link JeiGhostHandler} 在拖拽开始/结束时写入与清空；界面只读。
 */
public final class JeiGhostHighlights {

    /** 普通目标色（JEI 默认值）。 */
    private static final int TARGET_COLOR = 0x4013C90A;
    /** 鼠标所在目标的高亮色（JEI 默认值）。 */
    private static final int HOVER_COLOR = 0x804CC959;

    /** 目标区域（绝对屏幕坐标，18×18 一格）。 */
    private static volatile List<Rectangle> areas = J8.list();

    private JeiGhostHighlights() {
    }

    /** 拖拽开始：记下本次可投放的所有目标区域。 */
    public static void begin(List<Rectangle> rects) {
        areas = rects == null || rects.isEmpty() ? J8.list() : J8.listCopy(rects);
    }

    /** 拖拽结束（投放/取消/关闭界面）。 */
    public static void end() {
        areas = J8.list();
    }

    public static boolean active() {
        return !areas.isEmpty();
    }

    public static List<Rectangle> areas() {
        return areas;
    }

    /**
     * 把当前高亮画一遍（绝对屏幕坐标）。
     *
     * <p>调用方必须在界面渲染的最后一层画（控件与内容之后、tooltip 之前），
     * 并且<b>每帧只调一次</b>，否则会重新变成「画两层」。
     */
    public static void draw(Draw graphics, int mouseX, int mouseY) {
        for (Rectangle area : areas) {
            boolean hovered = mouseX >= area.x && mouseX < area.x + area.width
                    && mouseY >= area.y && mouseY < area.y + area.height;
            graphics.fill(area.x, area.y, area.x + area.width, area.y + area.height,
                    hovered ? HOVER_COLOR : TARGET_COLOR);
        }
    }
}
