package com.jessetech.recipebalancer.core.matrix;

import com.jessetech.recipebalancer.core.model.Recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 物料矩阵 {@code A[i][j] = 产量_ij − 消耗量_ij}：每个配方对各物品的净产出。
 * 只描述物品数量关系，**与化学无关**（对应 Python {@code matrix.py}）。
 *
 * <p>物品顺序 = {@link TreeSet} 自然序，即 Python {@code sorted(all_items)}。
 * registry id / 物品名均为 BMP 字符时，{@code String.compareTo}（UTF-16 code unit）
 * 与 Python 的 code point 序一致；非 BMP 字符才会分叉（见 ARCHITECTURE §3.4）。
 *
 * <p>装配顺序刻意与 Python 对齐：**先加产物、再减原料**，保证浮点累加序一致。
 */
public final class MaterialMatrix {

    private final List<String> items;
    private final Map<String, Integer> itemIndex;
    private final double[][] a;
    private final int recipeCount;

    private MaterialMatrix(List<String> items, Map<String, Integer> itemIndex,
                           double[][] a, int recipeCount) {
        this.items = items;
        this.itemIndex = itemIndex;
        this.a = a;
        this.recipeCount = recipeCount;
    }

    /** 收集全部物品并排序（目标产物与库存中的物品也计入）。 */
    public static List<String> collectItems(List<Recipe> recipes,
                                            Map<String, Double> targetProducts,
                                            Map<String, Double> inventory) {
        TreeSet<String> all = new TreeSet<>();
        for (Recipe recipe : recipes) {
            all.addAll(recipe.sourceList().keySet());
            all.addAll(recipe.productList().keySet());
        }
        all.addAll(targetProducts.keySet());
        all.addAll(inventory.keySet());
        return List.copyOf(all);
    }

    public static MaterialMatrix build(List<Recipe> recipes, List<String> items) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            index.put(items.get(i), i);
        }
        double[][] a = new double[items.size()][recipes.size()];
        for (int j = 0; j < recipes.size(); j++) {
            Recipe recipe = recipes.get(j);
            for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
                a[row(index, entry.getKey())][j] += entry.getValue();
            }
            for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
                a[row(index, entry.getKey())][j] -= entry.getValue();
            }
        }
        return new MaterialMatrix(List.copyOf(items), Collections.unmodifiableMap(index),
                a, recipes.size());
    }

    private static int row(Map<String, Integer> index, String item) {
        Integer row = index.get(item);
        if (row == null) {
            throw new IllegalArgumentException("物品未在索引中: " + item);
        }
        return row;
    }

    /** 目标向量 b（仅写入目标产物；其余为 0）。 */
    public double[] targetVector(Map<String, Double> targetProducts) {
        double[] b = new double[items.size()];
        for (Map.Entry<String, Double> entry : targetProducts.entrySet()) {
            b[row(itemIndex, entry.getKey())] = entry.getValue();
        }
        return b;
    }

    /** 库存向量 s。 */
    public double[] inventoryVector(Map<String, Double> inventory) {
        double[] s = new double[items.size()];
        for (Map.Entry<String, Double> entry : inventory.entrySet()) {
            s[row(itemIndex, entry.getKey())] = entry.getValue();
        }
        return s;
    }

    public List<String> items() {
        return items;
    }

    public Map<String, Integer> itemIndex() {
        return itemIndex;
    }

    public double[][] matrix() {
        return a;
    }

    public int itemCount() {
        return items.size();
    }

    public int recipeCount() {
        return recipeCount;
    }
}
