package com.jessetech.recipebalancer.core.bootstrap;

import com.jessetech.recipebalancer.core.graph.DirectedGraph;
import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 完美循环启动投料（Python {@code bootstrap.detect_perfect_cycles}）。
 *
 * <p>只做结构判定，不解析物品名：活跃配方 → 移除外部输入 → SCC → 最小反馈顶点集
 * （|SCC| ≤ {@link Constants#MAX_EXACT_FVS} 精确枚举，否则贪心）→ 按稳态速率跑 1 秒
 * 统计缺口 → 向上取整到整批投料。
 *
 * <p>为与 Python 结果对齐，以下细节被刻意复刻：
 * <ul>
 *   <li>拓扑序使用 networkx 3.4 {@code topological_generations} 的"分代 + 插入序"语义；</li>
 *   <li>精确枚举按 {@code (节点数, round(总投料量, 9), 字典序)} 取优，round 为银行家舍入；</li>
 *   <li>缺口累加顺序 = 拓扑序，比较阈值固定 1e-9。</li>
 * </ul>
 */
public final class PerfectCycleBootstrapper {

    private static final double EPS = 1e-9;

    private PerfectCycleBootstrapper() {
    }

    public static Map<String, Double> detect(List<Recipe> recipes,
                                             List<Double> recipeRates,
                                             Set<String> externalInputs) {
        Set<String> external = externalInputs == null ? Set.of() : externalInputs;
        List<Integer> active = activeRecipes(recipes, recipeRates);
        if (active.isEmpty()) {
            return Map.of();
        }

        Set<String> seeds = new LinkedHashSet<>(selectSeedItems(recipes, active, external));
        if (seeds.isEmpty()) {
            return Map.of();
        }

        Requirement requirement = startupRequirements(recipes, active, recipeRates, seeds, external);
        Set<String> extra = requirement.extra();
        if (!extra.isEmpty()) {
            seeds.addAll(extra);
            requirement = startupRequirements(recipes, active, recipeRates, seeds, external);
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (String item : new TreeSet<>(seeds)) {
            double batch = oneBatchAmount(recipes, active, item);
            result.put(item, roundUpToBatch(requirement.required().getOrDefault(item, 0.0), batch));
        }
        return result;
    }

    private static List<Integer> activeRecipes(List<Recipe> recipes, List<Double> rates) {
        List<Integer> active = new ArrayList<>();
        for (int index = 0; index < recipes.size(); index++) {
            if (rates == null || rates.get(index) > Constants.EPS_RATE) {
                active.add(index);
            }
        }
        return active;
    }

    // ------------------------------------------------------------------
    // 1. 选最小启动集合
    // ------------------------------------------------------------------

    private static Set<String> selectSeedItems(List<Recipe> recipes,
                                               List<Integer> active,
                                               Set<String> external) {
        DirectedGraph<String> graph = new DirectedGraph<>();
        for (int index : active) {
            Recipe recipe = recipes.get(index);
            for (String source : recipe.sourceList().keySet()) {
                for (String product : recipe.productList().keySet()) {
                    graph.addEdge(source, product);
                }
            }
        }
        graph.removeNodes(external);

        Set<String> seeds = new LinkedHashSet<>();
        for (Set<String> scc : graph.stronglyConnectedComponents()) {
            if (scc.size() < 2 && !(scc.size() == 1 && graph.hasEdge(scc.iterator().next(), scc.iterator().next()))) {
                continue;
            }
            List<String> nodes = new ArrayList<>(new TreeSet<>(scc));
            List<DirectedGraph.Edge<String>> edges = graph.edgesWithin(scc);
            Map<String, Double> weights = new HashMap<>();
            for (String node : nodes) {
                weights.put(node, oneBatchAmount(recipes, active, node));
            }
            seeds.addAll(minFeedbackVertexSet(nodes, edges, weights));
        }
        return seeds;
    }

    private static List<String> minFeedbackVertexSet(List<String> nodes,
                                                     List<DirectedGraph.Edge<String>> edges,
                                                     Map<String, Double> weights) {
        int n = nodes.size();
        if (n <= Constants.MAX_EXACT_FVS) {
            for (int k = 1; k <= n; k++) {
                Best best = null;
                for (List<String> combination : combinations(nodes, k)) {
                    Set<String> remaining = new LinkedHashSet<>(nodes);
                    remaining.removeAll(combination);
                    if (!isAcyclic(remaining, edges)) {
                        continue;
                    }
                    double sum = 0.0;
                    for (String node : combination) {
                        sum += weights.getOrDefault(node, 1.0);
                    }
                    Best candidate = new Best(k, roundTo9(sum), combination);
                    if (best == null || candidate.compareTo(best) < 0) {
                        best = candidate;
                    }
                }
                if (best != null) {
                    return best.nodes();
                }
            }
        }

        // 贪心回退（超大 SCC）。Python 用 set 迭代序做 max 的首次胜出；
        // Java 侧按排序序保证确定性（>12 的 SCC 不在金标准覆盖范围内）。
        Set<String> remaining = new LinkedHashSet<>(nodes);
        List<String> removed = new ArrayList<>();
        while (!isAcyclic(remaining, edges)) {
            String pick = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (String candidate : new TreeSet<>(remaining)) {
                int indegree = 0;
                int outdegree = 0;
                for (DirectedGraph.Edge<String> edge : edges) {
                    if (edge.to().equals(candidate) && remaining.contains(edge.from())) {
                        indegree++;
                    }
                    if (edge.from().equals(candidate) && remaining.contains(edge.to())) {
                        outdegree++;
                    }
                }
                double score = (double) indegree * outdegree;
                if (score > bestScore) {
                    bestScore = score;
                    pick = candidate;
                }
            }
            remaining.remove(pick);
            removed.add(pick);
        }
        return removed;
    }

    private static boolean isAcyclic(Collection<String> nodes, List<DirectedGraph.Edge<String>> edges) {
        Set<String> remaining = new LinkedHashSet<>(nodes);
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : remaining) {
            indegree.put(node, 0);
        }
        for (DirectedGraph.Edge<String> edge : edges) {
            if (remaining.contains(edge.from()) && remaining.contains(edge.to())) {
                indegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        List<String> zero = new ArrayList<>();
        for (String node : remaining) {
            if (indegree.get(node) == 0) {
                zero.add(node);
            }
        }
        int seen = 0;
        while (!zero.isEmpty()) {
            String node = zero.remove(zero.size() - 1);
            seen++;
            for (DirectedGraph.Edge<String> edge : edges) {
                if (!edge.from().equals(node) || !remaining.contains(edge.to())) {
                    continue;
                }
                int value = indegree.merge(edge.to(), -1, Integer::sum);
                if (value == 0) {
                    zero.add(edge.to());
                }
            }
        }
        return seen == remaining.size();
    }

    private static List<List<String>> combinations(List<String> nodes, int k) {
        List<List<String>> result = new ArrayList<>();
        int[] indices = new int[k];
        for (int i = 0; i < k; i++) {
            indices[i] = i;
        }
        while (true) {
            List<String> combination = new ArrayList<>(k);
            for (int index : indices) {
                combination.add(nodes.get(index));
            }
            result.add(combination);

            int position = k - 1;
            while (position >= 0 && indices[position] == nodes.size() - k + position) {
                position--;
            }
            if (position < 0) {
                return result;
            }
            indices[position]++;
            for (int i = position + 1; i < k; i++) {
                indices[i] = indices[i - 1] + 1;
            }
        }
    }

    /** Python {@code round(x, 9)} 的银行家舍入语义（BigDecimal 保留精确二进制值）。 */
    private static double roundTo9(double value) {
        return new BigDecimal(value).setScale(9, RoundingMode.HALF_EVEN).doubleValue();
    }

    // ------------------------------------------------------------------
    // 2. 计算启动量
    // ------------------------------------------------------------------

    private static double oneBatchAmount(List<Recipe> recipes, List<Integer> active, String item) {
        double max = 0.0;
        boolean found = false;
        for (int index : active) {
            Double amount = recipes.get(index).sourceList().get(item);
            if (amount != null) {
                max = found ? Math.max(max, amount) : amount;
                found = true;
            }
        }
        return found ? max : 1.0;
    }

    private static Requirement startupRequirements(List<Recipe> recipes,
                                                  List<Integer> active,
                                                  List<Double> recipeRates,
                                                  Set<String> seeds,
                                                  Set<String> external) {
        DirectedGraph<Integer> dependency = new DirectedGraph<>();
        for (int index : active) {
            dependency.addNode(index);
        }

        Map<String, List<Integer>> producers = new LinkedHashMap<>();
        for (int index : active) {
            for (String item : recipes.get(index).productList().keySet()) {
                producers.computeIfAbsent(item, ignored -> new ArrayList<>()).add(index);
            }
        }
        for (int index : active) {
            for (String item : recipes.get(index).sourceList().keySet()) {
                if (seeds.contains(item) || external.contains(item)) {
                    continue;
                }
                for (int producer : producers.getOrDefault(item, List.of())) {
                    if (producer != index) {
                        dependency.addEdge(producer, index);
                    }
                }
            }
        }

        List<Integer> order;
        if (dependency.isAcyclic()) {
            order = new ArrayList<>();
            for (List<Integer> generation : dependency.topologicalGenerations()) {
                order.addAll(generation);
            }
        } else {
            // 理论上不会发生（种子集合已破环）
            order = new ArrayList<>(new TreeSet<>(active));
        }

        Map<String, Double> inventory = new HashMap<>();
        Map<String, Double> required = new HashMap<>();
        Set<String> extra = new LinkedHashSet<>();

        for (int index : order) {
            Recipe recipe = recipes.get(index);
            double rate = recipeRates == null ? 1.0 : recipeRates.get(index);
            if (rate <= Constants.EPS_RATE) {
                continue;
            }

            for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
                String item = entry.getKey();
                if (external.contains(item)) {
                    continue; // 外部输入视为无限，不计库存
                }
                double need = entry.getValue() * rate;
                if (inventory.getOrDefault(item, 0.0) + EPS < need) {
                    double shortfall = need - inventory.getOrDefault(item, 0.0);
                    required.merge(item, shortfall, Double::sum);
                    inventory.merge(item, shortfall, Double::sum);
                    if (!seeds.contains(item)) {
                        extra.add(item);
                    }
                }
            }

            for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
                if (external.contains(entry.getKey())) {
                    continue;
                }
                inventory.merge(entry.getKey(), -entry.getValue() * rate, Double::sum);
            }

            for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
                if (external.contains(entry.getKey())) {
                    continue;
                }
                inventory.merge(entry.getKey(), entry.getValue() * rate, Double::sum);
            }
        }
        return new Requirement(required, extra);
    }

    private static double roundUpToBatch(double required, double batch) {
        if (batch <= 0) {
            batch = 1.0;
        }
        if (required <= EPS) {
            return batch;
        }
        long batches = Math.max(1L, (long) Math.ceil(required / batch - EPS));
        return batches * batch;
    }

    private record Requirement(Map<String, Double> required, Set<String> extra) {
    }

    private record Best(int size, double total, List<String> nodes) implements Comparable<Best> {

        @Override
        public int compareTo(Best other) {
            if (size != other.size) {
                return Integer.compare(size, other.size);
            }
            int byTotal = Double.compare(total, other.total);
            if (byTotal != 0) {
                return byTotal;
            }
            for (int i = 0; i < Math.min(nodes.size(), other.nodes.size()); i++) {
                int byName = nodes.get(i).compareTo(other.nodes.get(i));
                if (byName != 0) {
                    return byName;
                }
            }
            return Integer.compare(nodes.size(), other.nodes.size());
        }
    }
}
