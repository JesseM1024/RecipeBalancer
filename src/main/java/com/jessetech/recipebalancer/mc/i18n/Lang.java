package com.jessetech.recipebalancer.mc.i18n;

import net.minecraft.util.text.StringTextComponent;
import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.ui.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;

import java.util.Locale;

/**
 * 轻量 i18n 入口。
 *
 * <p>源文本使用中文作为 key；{@code en_us.json} 提供英文映射。
 * 中文环境下直接使用源文本，避免 zh_cn 文件缺项时回退成英文。
 *
 * <p>1.16.5 线：{@code Component/MutableComponent} 换成 {@code ITextComponent}
 * （{@link Chat#literal} 返回 {@code StringTextComponent}），{@code I18n} 换到
 * {@code net.minecraft.client.resources} 包。
 */
public final class Lang {

    private Lang() {
    }

    /** 返回可继续链式设置样式的组件（1.16.5 的样式方法是 {@code mergeStyle}）。 */
    public static net.minecraft.util.text.IFormattableTextComponent c(String key, Object... args) {
        return Chat.literal(t(key, args));
    }

    public static String t(String key, Object... args) {
        if (isChinese()) {
            return format(key, args);
        }
        if (I18n.hasKey(key)) {
            return I18n.format(key, args);
        }
        return format(key, args);
    }

    /** 文本报告的静态段落/字段翻译；物品名保持原样。 */
    public static String report(String text) {
        if (isChinese()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder(text.length() + 128);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(translateLine(lines[i]));
        }
        return builder.toString();
    }

    /**
     * 逐行翻译报告：保留行首缩进（设备统计段落是缩进的），
     * 整行命中语言文件时直接替换，否则把 "标签: 值" 的标签部分替换掉。
     */
    private static String translateLine(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        String body = line.substring(indent);
        if (body.isEmpty()) {
            return line;
        }
        String translated = translateBody(body);
        return indent == 0 ? translated : J8.repeat(" ", indent) + translated;
    }

    private static String translateBody(String body) {
        if (I18n.hasKey(body)) {
            return I18n.format(body);
        }
        int colon = body.indexOf(':');
        if (colon > 0) {
            String label = body.substring(0, colon);
            if (I18n.hasKey(label)) {
                return I18n.format(label) + body.substring(colon);
            }
        }
        return body;
    }

    /** 当前游戏语言代码（如 {@code en_us}）；语言切换时可用来判断缓存是否过期。 */
    public static String languageCode() {
        Minecraft minecraft = Minecraft.getInstance();
        String code = minecraft == null || minecraft.gameSettings == null
                ? "en_us" : minecraft.gameSettings.language;
        return code == null ? "en_us" : code;
    }

    private static boolean isChinese() {
        return languageCode().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String format(String key, Object... args) {
        return args.length == 0 ? key : String.format(Locale.ROOT, key, args);
    }
}
