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
 * 流程图启动动画的热读取配置。
 *
 * <p>文件：{@code config/recipebalancer/graph-animation.properties}
 * 每 {@link #CHECK_INTERVAL_MS} 毫秒检查一次修改时间，无需重启游戏。
 */
public final class GraphAnimationConfig {

    private static final long CHECK_INTERVAL_MS = 1_000L;
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("recipebalancer").resolve("graph-animation.properties");
    private static final Settings DEFAULTS = new Settings(100.0, 2.0, 6.0);

    private static Settings cached = DEFAULTS;
    private static long lastCheckMillis;
    private static long lastModified = Long.MIN_VALUE;

    private GraphAnimationConfig() {
    }

    public record Settings(double stepsPerSecond, double durationSeconds,
                           double stepDistance) {
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
                    "[RecipeBalancer] 读取流程图动画配置失败，使用默认值: {}", e.toString());
            cached = DEFAULTS;
        }
        return cached;
    }

    private static Settings load() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        double steps = parse(properties, "stepsPerSecond",
                DEFAULTS.stepsPerSecond(), 1.0, 1_000.0);
        double duration = parse(properties, "durationSeconds",
                DEFAULTS.durationSeconds(), 0.1, 10.0);
        double distance = parse(properties, "stepDistance",
                DEFAULTS.stepDistance(), 0.1, 20.0);
        return new Settings(steps, duration, distance);
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
                # RecipeBalancer 启动动画配置 / graph startup animation
                # 修改后约 1 秒内热读取，无需重启游戏。
                # stepsPerSecond: 每秒求解步数
                # durationSeconds: 总求解时长（秒）
                # stepDistance: 单步最大位移（像素）
                stepsPerSecond=100
                durationSeconds=2.0
                stepDistance=6.0
                """, StandardCharsets.UTF_8);
    }
}
