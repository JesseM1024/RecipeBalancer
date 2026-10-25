package com.jessetech.recipebalancer.core.io;

import com.jessetech.recipebalancer.core.model.Scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 场景文件仓库（纯 Java，目录由调用方注入）。
 *
 * <p>MC 层传入 {@code config/recipebalancer/scenarios}；core 测试用临时目录。
 * 文件名做白名单清洗（去掉路径分隔符 / 控制字符 / Windows 保留名），避免 {@code ../} 逃逸。
 */
public final class ScenarioStore {

    private static final Pattern WINDOWS_RESERVED =
            Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$");

    private static final int MAX_NAME_LENGTH = 48;

    private final Path directory;

    public ScenarioStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    public Path pathFor(String rawName) {
        return directory.resolve(sanitize(rawName) + ".json");
    }

    public List<String> names() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("列举场景失败: " + directory, e);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public boolean exists(String rawName) {
        return Files.isRegularFile(pathFor(rawName));
    }

    public Path save(String rawName, Scenario scenario) {
        Path path = pathFor(rawName);
        try {
            Files.createDirectories(directory);
            Files.writeString(path, ScenarioJson.dump(scenario), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("保存场景失败: " + path, e);
        }
        return path;
    }

    public Scenario load(String rawName) {
        Path path = pathFor(rawName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("场景不存在: " + sanitize(rawName));
        }
        return ScenarioJson.load(path);
    }

    /** 宽松载入草稿：允许目标/配方为空（自动保存用），其余字段仍校验。 */
    public Scenario loadDraft(String rawName) {
        Path path = pathFor(rawName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("场景不存在: " + sanitize(rawName));
        }
        return ScenarioJson.loadDraft(path);
    }

    public boolean delete(String rawName) {
        try {
            return Files.deleteIfExists(pathFor(rawName));
        } catch (IOException e) {
            throw new UncheckedIOException("删除场景失败: " + pathFor(rawName), e);
        }
    }

    /**
     * 文件名白名单：保留字母/数字/CJK/下划线/连字符/点/空格，去掉路径分隔符与控制字符；
     * 去掉首尾的点与空格（避免隐藏文件、结尾点）；空结果回退 {@code default}。
     */
    public static String sanitize(String rawName) {
        String raw = rawName == null ? "" : rawName.trim();
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && builder.length() < MAX_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ' ') {
                builder.append(c);
            }
        }
        String name = builder.toString().replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (name.isEmpty()) {
            return "default";
        }
        if (WINDOWS_RESERVED.matcher(name).matches()) {
            return "_" + name;
        }
        return name;
    }
}
