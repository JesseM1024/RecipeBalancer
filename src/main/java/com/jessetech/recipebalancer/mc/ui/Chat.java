package com.jessetech.recipebalancer.mc.ui;

import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * 聊天组件的静态工厂。
 *
 * <p>1.16.5 的文本组件只有 {@link StringTextComponent} 与 {@link TranslationTextComponent}，
 * 样式方法是 {@code mergeStyle(...)}（高版本叫 {@code withStyle}）。返回类型用
 * {@link IFormattableTextComponent}，这样链式加样式不必到处强转。
 */
public final class Chat {

    private Chat() {
    }

    public static IFormattableTextComponent literal(String text) {
        return new StringTextComponent(text);
    }

    public static IFormattableTextComponent empty() {
        return new StringTextComponent("");
    }

    public static IFormattableTextComponent translatable(String key, Object... args) {
        return new TranslationTextComponent(key, args);
    }

    /** 原样返回：便于写 {@code Chat.of(component)} 这类兼容代码。 */
    public static ITextComponent of(ITextComponent component) {
        return component;
    }

    /**
     * 用 0xAARRGGBB 直接指定文字颜色。
     *
     * <p>1.16.5 的 {@code Style} 没有 {@code withColor(int)}，颜色要包成
     * {@link net.minecraft.util.text.Color}；把它收敛在这里，界面代码仍写
     * {@code mergeStyle(Chat.styleRgb(0xFF90FF90))}。
     */
    public static net.minecraft.util.text.Style styleRgb(int argb) {
        return net.minecraft.util.text.Style.EMPTY.setColor(
                net.minecraft.util.text.Color.fromInt(argb));
    }
}
