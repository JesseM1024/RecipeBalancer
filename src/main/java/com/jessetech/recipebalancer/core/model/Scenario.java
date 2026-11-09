package com.jessetech.recipebalancer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 场景 = Python 场景 JSON 的内存形态（{@code io.py} schema 同构）。
 *
 * <p>{@code targetProducts} 保持 JSON 顺序；数值查找不依赖顺序。
 */
public final class Scenario {

    private final List<Recipe> recipes;
    private final Map<String, Double> targetProducts;
    private final SolveOptions options;

    public Scenario(List<Recipe> recipes,
                    Map<String, Double> targetProducts,
                    SolveOptions options) {
        this.recipes = Collections.unmodifiableList(new ArrayList<>(recipes));
        this.targetProducts = Collections.unmodifiableMap(new LinkedHashMap<>(targetProducts));
        this.options = options == null ? SolveOptions.defaults() : options;
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    public Map<String, Double> targetProducts() {
        return targetProducts;
    }

    public SolveOptions options() {
        return options;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Scenario)) {
            return false;
        }
        Scenario other = (Scenario) o;
        return recipes.equals(other.recipes)
                && targetProducts.equals(other.targetProducts)
                && options.equals(other.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipes, targetProducts, options);
    }

    @Override
    public String toString() {
        return "Scenario[recipes=" + recipes + ", targetProducts=" + targetProducts
                + ", options=" + options + "]";
    }
}
