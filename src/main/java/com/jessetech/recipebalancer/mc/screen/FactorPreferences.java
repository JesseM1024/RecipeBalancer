package com.jessetech.recipebalancer.mc.screen;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/**
 * 快捷系数槽位：整个游戏实例共用（写 config，跨存档/世界生效）。
 *
 * <p>存的是用户敲进去的原始表达式（如 {@code 1/9/10}），点应用时才解析，
 * 这样下次打开界面看到的仍是可编辑的表达式，而不是被量化后的十进制数。
 */
final class FactorPreferences {

    /** 两列两行，共四个槽位。 */
    static final int COUNT = 4;

    private static final Path PATH = FMLPaths.CONFIGDIR.get()
            .resolve("recipebalancer").resolve("ui_factors.properties");
    private static final Properties PROPERTIES = new Properties();
    private static final String[] VALUES = new String[COUNT];

    static {
        Arrays.fill(VALUES, "1");
        load();
    }

    private FactorPreferences() {
    }

    static String get(int index) {
        return index >= 0 && index < COUNT ? VALUES[index] : "1";
    }

    /** 只改内存；批量修改后自行调 {@link #save()}。 */
    static void put(int index, String value) {
        if (index < 0 || index >= COUNT) {
            return;
        }
        String text = value == null ? "" : value.trim();
        VALUES[index] = text.isEmpty() ? "1" : text;
    }

    static void save() {
        for (int i = 0; i < COUNT; i++) {
            PROPERTIES.setProperty("factor_" + (i + 1), VALUES[i]);
        }
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                PROPERTIES.store(writer, "RecipeBalancer quick factors (shared by all worlds)");
            }
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        if (!Files.isRegularFile(PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            PROPERTIES.load(reader);
            for (int i = 0; i < COUNT; i++) {
                String value = PROPERTIES.getProperty("factor_" + (i + 1));
                if (value != null && !value.trim().isEmpty()) {
                    VALUES[i] = value.trim();
                }
            }
        } catch (IOException ignored) {
        }
    }
}
