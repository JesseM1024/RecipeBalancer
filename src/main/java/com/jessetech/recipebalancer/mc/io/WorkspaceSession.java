package com.jessetech.recipebalancer.mc.io;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.core.io.ScenarioStore;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作区文件的位置与「当前工作区名」。
 *
 * <p>统一放在游戏实例根目录的 {@code RecipeBalancer/}：一个工作区 = 一个 json；
 * 当前工作区名单独存 {@code current-workspace.txt}，不混进 json 列表。
 * 旧版本把工作区存放在 {@code config/recipebalancer/scenarios}，首次启动时复制过来（不删旧文件）。
 */
public final class WorkspaceSession {

    /** 新建工作区 / 没有任何历史名称时使用的默认名。 */
    public static final String DEFAULT_NAME = "proc line default";

    private static final String DIRECTORY_NAME = "RecipeBalancer";
    private static final String NAME_FILE = "current-workspace.txt";
    private static final String MIGRATION_MARKER = ".migrated-from-config";
    private static final String LEGACY_DIRECTORY = "recipebalancer/scenarios";

    private static final Path DIRECTORY = FMLPaths.GAMEDIR.get().resolve(DIRECTORY_NAME);

    private static ScenarioStore store;

    private WorkspaceSession() {
    }

    /** 工作区仓库（懒建，顺便完成一次旧数据迁移）。 */
    public static synchronized ScenarioStore store() {
        if (store == null) {
            migrateLegacy();
            store = new ScenarioStore(DIRECTORY);
        }
        return store;
    }

    /** 文件名清洗；空名字回退到默认工作区名。 */
    public static String nameOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_NAME;
        }
        return ScenarioStore.sanitize(raw);
    }

    /** 上次使用的工作区名（关闭界面/重启后仍保留）。 */
    public static synchronized String currentName() {
        Path path = DIRECTORY.resolve(NAME_FILE);
        try {
            if (Files.isRegularFile(path)) {
                String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
                return raw.isEmpty() ? DEFAULT_NAME : ScenarioStore.sanitize(raw);
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不到就用默认名，不影响进入界面
        }
        return DEFAULT_NAME;
    }

    public static synchronized void saveName(String name) {
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(DIRECTORY.resolve(NAME_FILE), nameOf(name),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 记录当前工作区名失败", e);
        }
    }

    private static void migrateLegacy() {
        Path legacy = FMLPaths.CONFIGDIR.get().resolve(LEGACY_DIRECTORY);
        Path marker = DIRECTORY.resolve(MIGRATION_MARKER);
        if (Files.exists(marker) || !Files.isDirectory(legacy)) {
            return;
        }
        try {
            Files.createDirectories(DIRECTORY);
            List<Path> files;
            try (Stream<Path> stream = Files.list(legacy)) {
                files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .toList();
            }
            int copied = 0;
            for (Path source : files) {
                Path target = DIRECTORY.resolve(source.getFileName().toString());
                if (Files.exists(target)) {
                    continue;
                }
                Files.copy(source, target);
                copied++;
            }
            // 标记已迁移：之后在新目录里删掉的工作区不会被再次复制回来
            Files.writeString(marker, "");
            if (copied > 0) {
                RecipeBalancerMod.LOGGER.info("[RecipeBalancer] 已迁移 {} 个工作区到 {}",
                        copied, DIRECTORY);
            }
        } catch (IOException | RuntimeException e) {
            RecipeBalancerMod.LOGGER.warn("[RecipeBalancer] 迁移旧工作区失败", e);
        }
    }
}
