package com.jessetech.recipebalancer.mc.itemmap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * 非物品 JEI ingredient（流体/化学品等）的身份 key。
 * 格式：ingredient|base64(typeUid)|base64(uniqueId)|base64(displayName)
 * 数量统一用 B（500 mB = 0.5）。
 */
public final class IngredientKey {

    public static final String PREFIX = "ingredient|";

    private IngredientKey() {
    }

    public static String encode(String typeUid, String uniqueId, String displayName) {
        return PREFIX + b64(typeUid) + "|" + b64(uniqueId) + "|" + b64(displayName);
    }

    public static boolean isIngredientKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    public static String typeUid(String key) {
        return decodePart(key, 1);
    }

    public static String uniqueId(String key) {
        return decodePart(key, 2);
    }

    public static String displayName(String key) {
        String name = decodePart(key, 3);
        return name.isBlank() ? uniqueId(key) : name;
    }

    public static String describe(String key) {
        String type = typeUid(key);
        String uid = uniqueId(key);
        return type.isBlank() ? uid : type + ":" + uid;
    }

    public static String shortLabel(String key) {
        String name = displayName(key);
        if (name.isBlank()) {
            return "?";
        }
        int first = name.codePointAt(0);
        if (first < 128 && Character.isLetterOrDigit(first)) {
            String ascii = name.length() >= 3 ? name.substring(0, 3) : name;
            return ascii.toUpperCase(Locale.ROOT);
        }
        return new String(Character.toChars(first));
    }

    private static String decodePart(String key, int index) {
        if (!isIngredientKey(key)) {
            return "";
        }
        String[] parts = key.split("\\|", -1);
        if (parts.length <= index) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(parts[index]),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
}
