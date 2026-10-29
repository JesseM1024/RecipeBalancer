package com.jessetech.recipebalancer.mc.config;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 流程图布局的热读取配置（分层布局的间距）。
 *
 * <p>文件：{@code config/recipebalancer/graph-layout.properties}
 * 每 {@link #CHECK_INTERVAL_MS} 毫秒检查一次修改时间，无需重启游戏。
 */
public final class GraphLayoutConfig {

    private static final long CHECK_INTERVAL_MS = 1_000L;
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("recipebalancer").resolve("graph-layout.properties");
    private static final Settings DEFAULTS = new Settings(32.0, 128.0);

    private static Settings cached = DEFAULTS;
    private static long lastCheckMillis;
    private static long lastModified = Long.MIN_VALUE;

    private GraphLayoutConfig() {
    }

    /**
     * @param nodeGap    同列节点间距附加量（像素）
     * @param edgeLength 列间距基准（像素）
     */
    public record Settings(double nodeGap, double edgeLength) {
    }

    public static Settings get() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMillis < CHECK_INTERVAL_MS) {
            return cached;
        }
        lastCheckMillis = now;
        try {
            if (!Files.exists(FILE)) {
                writeDefaults();
            }
            long modified = Files.getLastModifiedTime(FILE).toMillis();
            if (modified != lastModified) {
                lastModified = modified;
                cached = load();
            }
        } catch (IOException | RuntimeException e) {
            RecipeBalancerMod.LOGGER.warn(
                    "[RecipeBalancer] 读取流程图布局配置失败，使用默认值: {}", e.toString());
            cached = DEFAULTS;
        }
        return cached;
    }

    private static Settings load() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        double nodeGap = parse(properties, "nodeGap", DEFAULTS.nodeGap(), 0.0, 200.0);
        double edgeLength = parse(properties, "edgeLength", DEFAULTS.edgeLength(), 20.0, 400.0);
        return new Settings(nodeGap, edgeLength);
    }

    private static double parse(Properties properties, String key,
                                double fallback, double min, double max) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void writeDefaults() throws IOException {
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, """
                # RecipeBalancer 流程图布局配置 / graph layout
                # 修改后约 1 秒内热读取，无需重启游戏。
                # nodeGap: 同列节点间距附加量（像素）
                # edgeLength: 列间距基准（像素）
                nodeGap=32
                edgeLength=128
                """, StandardCharsets.UTF_8);
    }
}
