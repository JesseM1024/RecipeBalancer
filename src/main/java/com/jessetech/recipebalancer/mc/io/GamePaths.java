package com.jessetech.recipebalancer.mc.io;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.FMLInjectionData;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 游戏目录 / 配置目录（1.12.2 没有 {@code FMLPaths}）。
 *
 * <p>来源优先级：
 * <ol>
 *   <li>preInit 时由 {@code FMLPreInitializationEvent#getModConfigurationDirectory()} 注入；</li>
 *   <li>{@code FMLInjectionData.data()[6]}（FML 启动时算出的游戏根目录），
 *       供极早期（配置之前）调用；</li>
 *   <li>{@code Minecraft#gameDir}，运行期兜底。</li>
 * </ol>
 */
public final class GamePaths {

    private static Path configDir;
    private static Path gameDir;

    private GamePaths() {
    }

    /** 由 {@code RecipeBalancerMod#preInit} 调用。 */
    public static synchronized void init(File modConfigurationDirectory) {
        if (modConfigurationDirectory == null) {
            return;
        }
        configDir = modConfigurationDirectory.toPath();
        File parent = modConfigurationDirectory.getParentFile();
        if (parent != null) {
            gameDir = parent.toPath();
        }
    }

    public static synchronized Path configDir() {
        if (configDir == null) {
            try {
                Object[] data = FMLInjectionData.data();
                if (data != null && data.length > 6 && data[6] instanceof String) {
                    gameDir = Paths.get((String) data[6]);
                    configDir = gameDir.resolve("config");
                }
            } catch (Throwable ignored) {
                // 落到下面的兜底
            }
        }
        if (configDir == null) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null && minecraft.mcDataDir != null) {
                gameDir = minecraft.mcDataDir.toPath();
                configDir = gameDir.resolve("config");
            } else {
                configDir = Paths.get("config");
            }
        }
        return configDir;
    }

    public static synchronized Path gameDir() {
        if (gameDir == null) {
            configDir();
        }
        if (gameDir == null) {
            Minecraft minecraft = Minecraft.getMinecraft();
            gameDir = minecraft != null && minecraft.mcDataDir != null
                    ? minecraft.mcDataDir.toPath() : Paths.get(".");
        }
        return gameDir;
    }
}
