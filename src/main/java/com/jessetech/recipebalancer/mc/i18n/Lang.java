package com.jessetech.recipebalancer.mc.i18n;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * 轻量 i18n 入口。
 *
 * <p>源文本使用中文作为 key；{@code en_us.json} 提供英文映射。
 * 中文环境下直接使用源文本，避免 zh_cn 文件缺项时回退成英文。
 */
public final class Lang {

    private Lang() {
    }

    public static MutableComponent c(String key, Object... args) {
        return Component.literal(t(key, args));
    }

    public static String t(String key, Object... args) {
        if (isChinese()) {
            return format(key, args);
        }
        if (I18n.exists(key)) {
            return I18n.get(key, args);
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
        return indent == 0 ? translated : " ".repeat(indent) + translated;
    }

    private static String translateBody(String body) {
        if (I18n.exists(body)) {
            return I18n.get(body);
        }
        int colon = body.indexOf(':');
        if (colon > 0) {
            String label = body.substring(0, colon);
            if (I18n.exists(label)) {
                return I18n.get(label) + body.substring(colon);
            }
        }
        return body;
    }

    /** 当前游戏语言代码（如 {@code en_us}）；语言切换时可用来判断缓存是否过期。 */
    public static String languageCode() {
        Minecraft minecraft = Minecraft.getInstance();
        String code = minecraft == null || minecraft.options == null
                ? "en_us" : minecraft.options.languageCode;
        return code == null ? "en_us" : code;
    }

    private static boolean isChinese() {
        return languageCode().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String format(String key, Object... args) {
        return args.length == 0 ? key : String.format(Locale.ROOT, key, args);
    }
}
