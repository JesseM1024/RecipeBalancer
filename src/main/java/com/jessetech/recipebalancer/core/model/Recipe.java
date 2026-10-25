package com.jessetech.recipebalancer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与 Python {@code recipe_balancer.models} 中 recipe dict 逐字段对应；time 单位 = 秒。
 *
 * <p>物品身份是**不透明字符串**（架构契约 C2）：core 层不解析命名空间，
 * 由 MC 层负责 {@code ItemStack -> "item|<registry_id>"} 映射。
 *
 * <p>两张表都用 {@link LinkedHashMap} 拷贝以保留 JSON 中的迭代顺序——
 * Python 侧同样依赖 dict 插入序（矩阵装配、报告输出）。
 */
public record Recipe(Map<String, Double> sourceList,
                     Map<String, Double> productList,
                     double time,
                     String equType) {

    public Recipe {
        sourceList = copy(sourceList);
        productList = copy(productList);
    }

    private static Map<String, Double> copy(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public boolean consumes(String item) {
        return sourceList.containsKey(item);
    }

    public boolean produces(String item) {
        return productList.containsKey(item);
    }
}
