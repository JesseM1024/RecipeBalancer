package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 与 Python {@code recipe_balancer.models} 中 recipe dict 逐字段对应；time 单位 = 秒。
 *
 * <p>物品身份是**不透明字符串**（架构契约 C2）：core 层不解析命名空间，
 * 由 MC 层负责 {@code ItemStack -> "item|<registry_id>"} 映射。
 *
 * <p>两张表都用 {@link LinkedHashMap} 拷贝以保留 JSON 中的迭代顺序——
 * Python 侧同样依赖 dict 插入序（矩阵装配、报告输出）。
 *
 * <p>1.16.5 线是 Java 8，没有 record：这里是等价的手写值类，
 * 访问器名字与原来一致（{@code sourceList()} 等），调用方无需改动。
 */
public final class Recipe {

    private final Map<String, Double> sourceList;
    private final Map<String, Double> productList;
    private final double time;
    private final String equType;

    public Recipe(Map<String, Double> sourceList,
                  Map<String, Double> productList,
                  double time,
                  String equType) {
        this.sourceList = copy(sourceList);
        this.productList = copy(productList);
        this.time = time;
        this.equType = equType;
    }

    private static Map<String, Double> copy(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public Map<String, Double> sourceList() {
        return sourceList;
    }

    public Map<String, Double> productList() {
        return productList;
    }

    public double time() {
        return time;
    }

    public String equType() {
        return equType;
    }

    public boolean consumes(String item) {
        return sourceList.containsKey(item);
    }

    public boolean produces(String item) {
        return productList.containsKey(item);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Recipe)) {
            return false;
        }
        Recipe other = (Recipe) o;
        return Double.compare(time, other.time) == 0
                && sourceList.equals(other.sourceList)
                && productList.equals(other.productList)
                && Objects.equals(equType, other.equType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceList, productList, time, equType);
    }

    @Override
    public String toString() {
        return "Recipe[sourceList=" + sourceList + ", productList=" + productList
                + ", time=" + time + ", equType=" + equType + "]";
    }
}
