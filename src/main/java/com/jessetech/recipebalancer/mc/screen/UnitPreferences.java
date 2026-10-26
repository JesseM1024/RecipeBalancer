package com.jessetech.recipebalancer.mc.screen;

import com.jessetech.recipebalancer.core.time.RateUnit;
import com.jessetech.recipebalancer.core.time.TimeUnit;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 记住上次使用的耗时/速率单位（UI 偏好，不写进场景 schema）。 */
final class UnitPreferences {

    private static final Path PATH = FMLPaths.CONFIGDIR.get()
            .resolve("recipebalancer").resolve("ui_units.properties");
    private static final Properties PROPERTIES = new Properties();
    private static TimeUnit timeUnit;
    private static RateUnit rateUnit;

    static {
        load();
    }

    private UnitPreferences() {
    }

    static TimeUnit timeUnit(TimeUnit fallback) {
        return timeUnit == null ? fallback : timeUnit;
    }

    static RateUnit rateUnit(RateUnit fallback) {
        return rateUnit == null ? fallback : rateUnit;
    }

    static void setTimeUnit(TimeUnit unit) {
        if (unit != null) {
            timeUnit = unit;
            PROPERTIES.setProperty("time_unit", unit.name());
            save();
        }
    }

    static void setRateUnit(RateUnit unit) {
        if (unit != null) {
            rateUnit = unit;
            PROPERTIES.setProperty("rate_unit", unit.name());
            save();
        }
    }

    private static void load() {
        if (!Files.isRegularFile(PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            PROPERTIES.load(reader);
            String time = PROPERTIES.getProperty("time_unit");
            String rate = PROPERTIES.getProperty("rate_unit");
            if (time != null) {
                try {
                    timeUnit = TimeUnit.valueOf(time);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (rate != null) {
                try {
                    rateUnit = RateUnit.valueOf(rate);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                PROPERTIES.store(writer, "RecipeBalancer UI unit preferences");
            }
        } catch (IOException ignored) {
        }
    }
}
