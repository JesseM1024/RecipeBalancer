package com.jessetech.recipebalancer.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有向图：节点与邻接点都保持**插入顺序**，用于复刻 networkx 的确定性行为。
 *
 * <p>只实现本项目需要的部分：SCC（Tarjan）、拓扑分代（与 networkx 3.4
 * {@code topological_generations} 同序）、子图边枚举。
 */
public final class DirectedGraph<N> {

    private final Map<N, LinkedHashSet<N>> adjacency = new LinkedHashMap<>();

    public void addNode(N node) {
        adjacency.computeIfAbsent(node, ignored -> new LinkedHashSet<>());
    }

    public void addEdge(N from, N to) {
        addNode(from);
        addNode(to);
        adjacency.get(from).add(to);
    }

    public boolean contains(N node) {
        return adjacency.containsKey(node);
    }

    public boolean hasEdge(N from, N to) {
        Set<N> out = adjacency.get(from);
        return out != null && out.contains(to);
    }

    public Set<N> nodes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    public Set<N> neighbors(N node) {
        Set<N> out = adjacency.get(node);
        return out == null ? Set.of() : Collections.unmodifiableSet(out);
    }

    public int nodeCount() {
        return adjacency.size();
    }

    /** 删除节点及其所有入边（networkx {@code remove_nodes_from} 语义）。 */
    public void removeNodes(Collection<N> toRemove) {
        for (N node : toRemove) {
            adjacency.remove(node);
        }
        for (Set<N> out : adjacency.values()) {
            out.removeAll(toRemove);
        }
    }

    /** 子图中两端都在 {@code subset} 内的边（按插入顺序）。 */
    public List<Edge<N>> edgesWithin(Collection<N> subset) {
        Set<N> keep = new LinkedHashSet<>(subset);
        List<Edge<N>> edges = new ArrayList<>();
        for (Map.Entry<N, LinkedHashSet<N>> entry : adjacency.entrySet()) {
            if (!keep.contains(entry.getKey())) {
                continue;
            }
            for (N to : entry.getValue()) {
                if (keep.contains(to)) {
                    edges.add(new Edge<>(entry.getKey(), to));
                }
            }
        }
        return edges;
    }

    /**
     * 强连通分量（Tarjan）。顺序为 networkx 的逆拓扑序近似，但本项目所有调用点
     * 都只把结果当**集合集合**使用，不依赖分量顺序。
     */
    public List<Set<N>> stronglyConnectedComponents() {
        Tarjan<N> tarjan = new Tarjan<>(adjacency);
        return tarjan.run();
    }

    /**
     * 拓扑分代，逐行复刻 networkx 3.4.2 {@code topological_generations}：
     * 每一代按零入度节点的插入顺序产出，儿子按邻接顺序加入下一代。
     *
     * @throws IllegalStateException 图含环（对应 networkx {@code NetworkXUnfeasible}）
     */
    public List<List<N>> topologicalGenerations() {
        Map<N, Integer> indegree = new HashMap<>();
        List<N> zeroIndegree = new ArrayList<>();
        for (Map.Entry<N, LinkedHashSet<N>> entry : adjacency.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            for (N child : entry.getValue()) {
                indegree.merge(child, 1, Integer::sum);
            }
        }
        for (N node : adjacency.keySet()) {
            if (!indegree.containsKey(node)) {
                zeroIndegree.add(node);
            }
        }

        List<List<N>> generations = new ArrayList<>();
        while (!zeroIndegree.isEmpty()) {
            List<N> thisGeneration = zeroIndegree;
            zeroIndegree = new ArrayList<>();
            for (N node : thisGeneration) {
                for (N child : adjacency.get(node)) {
                    Integer remaining = indegree.get(child);
                    if (remaining == null) {
                        continue; // 与 networkx 相同：已被上一代消耗
                    }
                    if (remaining == 1) {
                        indegree.remove(child);
                        zeroIndegree.add(child);
                    } else {
                        indegree.put(child, remaining - 1);
                    }
                }
            }
            generations.add(List.copyOf(thisGeneration));
        }
        if (!indegree.isEmpty()) {
            throw new IllegalStateException("Graph contains a cycle or graph changed during iteration");
        }
        return generations;
    }

    public boolean isAcyclic() {
        try {
            topologicalGenerations();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public record Edge<N>(N from, N to) {
    }

    private static final class Tarjan<N> {

        private final Map<N, ? extends Set<N>> adjacency;
        private final Map<N, Integer> index = new HashMap<>();
        private final Map<N, Integer> lowLink = new HashMap<>();
        private final Deque<N> stack = new ArrayDeque<>();
        private final Set<N> onStack = new LinkedHashSet<>();
        private final List<Set<N>> components = new ArrayList<>();
        private int nextIndex;

        Tarjan(Map<N, ? extends Set<N>> adjacency) {
            this.adjacency = adjacency;
        }

        List<Set<N>> run() {
            for (N node : adjacency.keySet()) {
                if (!index.containsKey(node)) {
                    connect(node);
                }
            }
            return components;
        }

        private void connect(N node) {
            index.put(node, nextIndex);
            lowLink.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);

            for (N next : adjacency.get(node)) {
                if (!index.containsKey(next)) {
                    connect(next);
                    lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(next)));
                } else if (onStack.contains(next)) {
                    lowLink.put(node, Math.min(lowLink.get(node), index.get(next)));
                }
            }

            if (lowLink.get(node).equals(index.get(node))) {
                LinkedHashSet<N> component = new LinkedHashSet<>();
                N current;
                do {
                    current = stack.pop();
                    onStack.remove(current);
                    component.add(current);
                } while (!current.equals(node));
                components.add(Collections.unmodifiableSet(component));
            }
        }
    }
}
