package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * 聊天组件的静态工厂（1.12.2 版）。
 *
 * <p>1.12.2 的文本组件是 {@link TextComponentString} / {@link TextComponentTranslation}，
 * 没有 {@code ITextComponent}；链式样式方法是 {@code setStyle(...)}
 * （1.16.5 是 {@code mergeStyle}），返回 {@link ITextComponent}。
 *
 * <p>颜色只有 16 个 {@link TextFormatting} 常量，{@link #styleRgb(int)} 按 RGB 距离取最近的一个。
 */
public final class Chat {

    private Chat() {
    }

    public static ITextComponent literal(String text) {
        return new TextComponentString(text);
    }

    public static ITextComponent empty() {
        return new TextComponentString("");
    }

    public static ITextComponent translatable(String key, Object... args) {
        return new TextComponentTranslation(key, args);
    }

    /** 原样返回：便于写 {@code Chat.of(component)} 这类兼容代码。 */
    public static ITextComponent of(ITextComponent component) {
        return component;
    }

    /** 生成带样式的组件（1.12.2 没有 {@code mergeStyle}，等价写法是 {@code setStyle}）。 */
    public static ITextComponent styled(String text, Style style) {
        TextComponentString component = new TextComponentString(text);
        component.setStyle(style);
        return component;
    }

    /**
     * 用 0xAARRGGBB 指定文字颜色：1.12.2 只支持 16 色，取 RGB 距离最近的 {@link TextFormatting}。
     *
     * <p>界面里大量使用 {@code Chat.styleRgb(0xFF90FF90)} 这类写法；1.12.2 上会退化到
     * 最接近的原版颜色，语义（正常/警告/强调）保持不变。
     */
    public static Style styleRgb(int argb) {
        return new Style().setColor(nearest(argb));
    }

    private static TextFormatting nearest(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        TextFormatting best = TextFormatting.WHITE;
        int bestDistance = Integer.MAX_VALUE;
        for (TextFormatting formatting : TextFormatting.values()) {
            if (!formatting.isColor()) {
                continue;
            }
            Integer rgb = COLOR_RGB[formatting.ordinal()];
            if (rgb == null) {
                continue;
            }
            int dr = r - ((rgb >> 16) & 0xFF);
            int dg = g - ((rgb >> 8) & 0xFF);
            int db = b - (rgb & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = formatting;
            }
        }
        return best;
    }

    /** 原版 16 色在 1.12.2 的 RGB（{@code TextFormatting#getColorIndex} 对应的实际颜色）。 */
    private static final Integer[] COLOR_RGB = new Integer[TextFormatting.values().length];

    static {
        COLOR_RGB[TextFormatting.BLACK.ordinal()] = 0x000000;
        COLOR_RGB[TextFormatting.DARK_BLUE.ordinal()] = 0x0000AA;
        COLOR_RGB[TextFormatting.DARK_GREEN.ordinal()] = 0x00AA00;
        COLOR_RGB[TextFormatting.DARK_AQUA.ordinal()] = 0x00AAAA;
        COLOR_RGB[TextFormatting.DARK_RED.ordinal()] = 0xAA0000;
        COLOR_RGB[TextFormatting.DARK_PURPLE.ordinal()] = 0xAA00AA;
        COLOR_RGB[TextFormatting.GOLD.ordinal()] = 0xFFAA00;
        COLOR_RGB[TextFormatting.GRAY.ordinal()] = 0xAAAAAA;
        COLOR_RGB[TextFormatting.DARK_GRAY.ordinal()] = 0x555555;
        COLOR_RGB[TextFormatting.BLUE.ordinal()] = 0x5555FF;
        COLOR_RGB[TextFormatting.GREEN.ordinal()] = 0x55FF55;
        COLOR_RGB[TextFormatting.AQUA.ordinal()] = 0x55FFFF;
        COLOR_RGB[TextFormatting.RED.ordinal()] = 0xFF5555;
        COLOR_RGB[TextFormatting.LIGHT_PURPLE.ordinal()] = 0xFF55FF;
        COLOR_RGB[TextFormatting.YELLOW.ordinal()] = 0xFFFF55;
        COLOR_RGB[TextFormatting.WHITE.ordinal()] = 0xFFFFFF;
    }
}
