package com.jessetech.recipebalancer.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 8 兼容的不可变集合工厂。
 *
 * <p>1.16.5 线只能跑在 Java 8 上，{@code List.of} / {@code Set.of} / {@code Map.of}
 * （Java 9+）在编译期就不存在。这里提供同样调用形状的替代：
 * <ul>
 *   <li>{@link #list(Object...)} ← {@code List.of(...)}</li>
 *   <li>{@link #set(Object...)} ← {@code Set.of(...)}</li>
 *   <li>{@link #map(Object...)} ← {@code Map.of(k1, v1, k2, v2, ...)}</li>
 *   <li>{@link #listCopy(Collection)} / {@link #setCopy(Collection)} / {@link #mapCopy(Map)}
 *       ← {@code List.copyOf} / {@code Set.copyOf} / {@code Map.copyOf}</li>
 * </ul>
 *
 * <p>与 JDK 版本的关键差异（有意为之，避免逐处手工改写破坏语义）：
 * <ul>
 *   <li>允许 {@code null} 元素（JDK 的 of/copyOf 会 NPE）；本项目不依赖该 NPE；</li>
 *   <li>{@link #map(Object...)} 保持**插入顺序**（{@code LinkedHashMap}），
 *       而 {@code Map.of} 的顺序是随机的——对本项目的报告输出反而更稳。</li>
 * </ul>
 */
public final class J8 {

    private J8() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        return Collections.unmodifiableList(Arrays.asList(items.clone()));
    }

    public static <T> List<T> listCopy(Collection<? extends T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> emptyList() {
        return (List<T>) Collections.emptyList();
    }

    @SafeVarargs
    public static <T> Set<T> set(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(items.clone())));
    }

    public static <T> Set<T> setCopy(Collection<? extends T> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    @SuppressWarnings("unchecked")
    public static <T> Set<T> emptySet() {
        return (Set<T>) Collections.emptySet();
    }

    /**
     * 形如 {@code map("a", 1.0, "b", 2.0)} 的键值对构造，保持插入顺序。
     *
     * @throws IllegalArgumentException 参数个数为奇数
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map 需要成对的键值，实际参数个数: " + keyValues.length);
        }
        Map<K, V> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((K) keyValues[i], (V) keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    public static <K, V> Map<K, V> mapCopy(Map<? extends K, ? extends V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> emptyMap() {
        return (Map<K, V>) Collections.emptyMap();
    }

    /**
     * {@code String#isBlank()}（Java 11）的 Java 8 等价实现。
     *
     * <p>不能用 {@code trim().isEmpty()} 顶替：{@code trim()} 只去 {@code <= U+0020}，
     * 而 {@code isBlank()} 用的是 {@link Character#isWhitespace(char)}——中文输入法常插入的
     * 全角空格（U+3000）、行分隔符（U+2028）等都会被 {@code trim()} 漏掉，
     * 那样「全是空白的输入」会被当成有效内容，直接把脏数据带进求解。
     *
     * <p>{@code null} 视为空白（调用方一般已判空，这里只是防御）。
     */
    /** {@code String#repeat(int)}（Java 11）的 Java 8 等价实现。 */
    public static String repeat(String text, int times) {
        if (times <= 0 || text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    public static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
