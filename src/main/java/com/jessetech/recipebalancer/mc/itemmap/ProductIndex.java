package com.jessetech.recipebalancer.mc.itemmap;

import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.mc.io.WorkspaceSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方库产物索引：物质 key → 最小配方序号。
 *
 * <p>两处用到：
 * <ul>
 *   <li>JEI 配方页在「编码此配方」按钮悬停时，给配方库能自产的物质标蓝（AE 风格）；</li>
 *   <li>导入 JEI 配方时，若某个原料有多种变体，优先写入配方库能自产的那个
 *       （序号最小者），而不是 JEI 当前轮播到的那一种。</li>
 * </ul>
 */
public final class ProductIndex {

    private static volatile ProductIndex cached;

    private final Map<String, Integer> byProduct = new HashMap<>();

    public ProductIndex(List<Recipe> recipes) {
        for (int i = 0; i < recipes.size(); i++) {
            for (String product : recipes.get(i).productList().keySet()) {
                byProduct.putIfAbsent(normalize(product), i);
            }
        }
    }

    /** @return 最小配方序号；配方库不产出该物质时返回 -1。 */
    public int indexOf(String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        return byProduct.getOrDefault(normalize(key), -1);
    }

    public boolean produces(String key) {
        return indexOf(key) >= 0;
    }

    /** 当前工作区（autosave）的产物索引，给 JEI 侧用；界面里配方库变动后调 {@link #invalidate()}。 */
    public static ProductIndex current() {
        ProductIndex snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = load();
        cached = snapshot;
        return snapshot;
    }

    public static void invalidate() {
        cached = null;
    }

    private static ProductIndex load() {
        try {
            return new ProductIndex(WorkspaceSession.store().loadDraft("autosave").recipes());
        } catch (RuntimeException e) {
            return new ProductIndex(List.of());
        }
    }

    private static String normalize(String key) {
        return ItemKeyMapper.normalize(key);
    }
}
