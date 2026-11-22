package com.jessetech.recipebalancer.mc.i18n;

import com.jessetech.recipebalancer.core.util.J8;
import com.jessetech.recipebalancer.mc.ui.Chat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;

import java.util.Locale;

/**
 * 轻量 i18n 入口。
 *
 * <p>源文本使用中文作为 key；{@code en_us.lang} 提供英文映射。
 * 中文环境下直接使用源文本，避免 zh_cn 文件缺项时回退成英文。
 *
 * <p>1.12.2 线：文本组件只有 {@link ITextComponent}（没有
 * {@code ITextComponent}），{@code I18n} 仍在
 * {@code net.minecraft.client.resources} 包，语言文件是 {@code .lang}。
 */
public final class Lang {

    private Lang() {
    }

    /** 返回文本组件（1.12.2 的样式方法直接作用在组件上）。 */
    public static ITextComponent c(String key, Object... args) {
        return Chat.literal(t(key, args));
    }

    public static String t(String key, Object... args) {
        if (isChinese()) {
            return format(key, args);
        }
        String text = I18n.hasKey(key) ? I18n.format(key, args) : format(key, args);
        return localizeUnits(text);
    }

    /**
     * 翻译「由 core 抛出的消息」。
     *
     * <p>core 是零 MC 依赖的纯 Java 层，消息里带动态片段（例如 {@code 配方 [0] 缺少键: time}），
     * 没法整串命中语言文件。这里按两层处理：整串命中就整串翻；否则只翻冒号前的标签，
     * 冒号后的动态内容原样保留。
     */
    public static String err(String message) {
        if (message == null || isChinese()) {
            return message;
        }
        if (I18n.hasKey(message)) {
            return I18n.format(message);
        }
        int colon = message.indexOf(':');
        if (colon > 0) {
            String label = message.substring(0, colon);
            if (I18n.hasKey(label)) {
                return I18n.format(label) + message.substring(colon);
            }
        }
        // 形如 "options.foo 必须为数组"：只翻最后一个空格后的固定短语
        int space = message.lastIndexOf(' ');
        if (space > 0 && space < message.length() - 1) {
            String tail = message.substring(space + 1);
            if (I18n.hasKey(tail)) {
                return message.substring(0, space + 1) + I18n.format(tail);
            }
        }
        return localizeUnits(message);
    }

    /**
     * 时间单位后缀本地化：core 的 {@code TimeUnit} 只懂「秒/分/时」，
     * 英文界面下换成 s / min / h（只匹配「数字 + 空格 + 单位」，不碰正文里的其它中文）。
     */
    public static String localizeUnits(String text) {
        if (text == null || isChinese()) {
            return text;
        }
        return text.replaceAll("([0-9]) ?秒", "$1 s")
                .replaceAll("([0-9]) ?分", "$1 min")
                .replaceAll("([0-9]) ?时", "$1 h");
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
        String translated = localizeUnits(translateBody(body));
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
        Minecraft minecraft = Minecraft.getMinecraft();
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
