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
            String line = lines[i];
            String translated = switch (line) {
                case "配方运行方案（流量单位 /t）" -> "Recipe Plan (flow unit /t)";
                case "配方运行方案" -> "Recipe Plan";
                case "汇总数据" -> "Summary";
                case "无" -> "None";
                case ">>自动检测外部原料" -> ">>Auto-detected external inputs";
                case ">>自增殖配方启动库存" -> ">>Self-propagating recipe bootstrap";
                case ">>完美循环启动投料（最小集合）" -> ">>Perfect-cycle bootstrap (minimal set)";
                case ">>原料输入（/t）" -> ">>External input (/t)";
                case ">>原料输入" -> ">>External input";
                case ">>副产物产出（/t）" -> ">>Byproducts (/t)";
                case ">>副产物产出" -> ">>Byproducts";
                case ">>设备统计" -> ">>Equipment";
                default -> null;
            };
            if (translated == null) {
                translated = translatePrefix(line);
            }
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(translated);
        }
        return builder.toString();
    }

    private static String translatePrefix(String line) {
        String[][] prefixes = {
                {"设备类型: ", "Equipment: "},
                {"设备并行数: ", "Parallel: "},
                {"需要设备数: ", "Machines: "},
                {"实际并行数: ", "Parallel: "},
                {"实际并行数和: ", "Parallel sum: "},
                {"最小设备数和: ", "Minimum machines: "},
                {"单配方设备数和: ", "Per-recipe machines: "},
                {"最小总设备数: ", "Minimum total machines: "},
                {"最小单配方总设备数: ", "Minimum per-recipe total: "},
                {">>最小总设备数: ", ">>Minimum total machines: "},
                {">>最小单配方总设备数: ", ">>Minimum per-recipe total: "},
                {"求解失败:", "Solve failed:"}
        };
        for (String[] prefix : prefixes) {
            if (line.startsWith(prefix[0])) {
                return prefix[1] + line.substring(prefix[0].length());
            }
        }
        return line;
    }

    private static boolean isChinese() {
        Minecraft minecraft = Minecraft.getInstance();
        String code = minecraft == null || minecraft.options == null
                ? "en_us" : minecraft.options.languageCode;
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String format(String key, Object... args) {
        return args.length == 0 ? key : String.format(Locale.ROOT, key, args);
    }
}
