package com.jessetech.recipebalancer.mc.ui.graph;

import com.jessetech.recipebalancer.core.graph.DirectedGraph;
import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5 求解流程图：以配方为节点、物品物流为边。
 * 节点按配方间依赖的 SCC/拓扑分代分层；画布支持滚动。
 */
public final class SolveGraphView {

    private static final int NODE_W = 150;
    private static final int NODE_H = 56;
    private static final int GAP_X = 54;
    private static final int GAP_Y = 12;
    private static final int PAD = 14;
    private static final int EDGE = 0xFF4A90D9;
    private static final int EDGE_BACK = 0xFFC08040;

    private final SolveResult result;
    private final List<Recipe> recipes;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private int contentWidth = 1;
    private int contentHeight = 1;

    private static final class Node {
        final int recipeId;
        final double rate;
        final int machines;
        final int layer;
        int x;
        int y;

        Node(int recipeId, double rate, int machines, int layer) {
            this.recipeId = recipeId;
            this.rate = rate;
            this.machines = machines;
            this.layer = layer;
        }
    }

    private static final class Edge {
        final int from;
        final int to;
        final Map<String, Double> items;

        Edge(int from, int to, Map<String, Double> items) {
            this.from = from;
            this.to = to;
            this.items = items;
        }
    }

    private record Pair(int from, int to) {
    }

    public SolveGraphView(SolveResult result, List<Recipe> recipes) {
        this.result = result;
        this.recipes = recipes;
        build();
    }

    public int contentWidth() {
        return contentWidth;
    }

    public int contentHeight() {
        return contentHeight;
    }

    private void build() {
        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < result.recipeRates().size(); i++) {
            if (result.recipeRates().get(i) >= Constants.EPS_RATE) {
                active.add(i);
            }
        }
        if (active.isEmpty()) {
            return;
        }

        Map<String, List<Integer>> producers = new LinkedHashMap<>();
        for (int index : active) {
            Recipe recipe = recipes.get(index);
            for (String product : recipe.productList().keySet()) {
                producers.computeIfAbsent(product, ignored -> new ArrayList<>()).add(index);
            }
        }

        DirectedGraph<Integer> graph = new DirectedGraph<>();
        for (int index : active) {
            graph.addNode(index);
        }
        Map<Pair, Map<String, Double>> edgeItems = new LinkedHashMap<>();
        for (int consumer : active) {
            Recipe recipe = recipes.get(consumer);
            for (Map.Entry<String, Double> input : recipe.sourceList().entrySet()) {
                for (int producer : producers.getOrDefault(input.getKey(), List.of())) {
                    if (producer == consumer) {
                        continue;
                    }
                    graph.addEdge(producer, consumer);
                    Pair pair = new Pair(producer, consumer);
                    edgeItems.computeIfAbsent(pair, ignored -> new LinkedHashMap<>())
                            .merge(input.getKey(),
                                    input.getValue() * result.recipeRates().get(consumer),
                                    Double::sum);
                }
            }
        }

        Map<Integer, Integer> layers = layerByScc(graph, active);
        for (int index : active) {
            double rate = result.recipeRates().get(index);
            Recipe recipe = recipes.get(index);
            int machines = (int) Math.ceil(rate * recipe.time());
            nodes.add(new Node(index, rate, machines, layers.getOrDefault(index, 0)));
        }

        Map<Integer, List<Node>> byLayer = new LinkedHashMap<>();
        for (Node node : nodes) {
            byLayer.computeIfAbsent(node.layer, ignored -> new ArrayList<>()).add(node);
        }
        int maxRows = 1;
        for (List<Node> layer : byLayer.values()) {
            layer.sort((a, b) -> Integer.compare(a.recipeId, b.recipeId));
            for (int row = 0; row < layer.size(); row++) {
                Node node = layer.get(row);
                node.x = PAD + node.layer * (NODE_W + GAP_X);
                node.y = PAD + row * (NODE_H + GAP_Y);
            }
            maxRows = Math.max(maxRows, layer.size());
        }
        for (Map.Entry<Pair, Map<String, Double>> entry : edgeItems.entrySet()) {
            edges.add(new Edge(entry.getKey().from(), entry.getKey().to(), entry.getValue()));
        }
        int layersCount = byLayer.keySet().stream().mapToInt(i -> i + 1).max().orElse(1);
        contentWidth = PAD * 2 + layersCount * NODE_W + Math.max(0, layersCount - 1) * GAP_X;
        contentHeight = PAD * 2 + maxRows * NODE_H + Math.max(0, maxRows - 1) * GAP_Y;
    }

    private static Map<Integer, Integer> layerByScc(DirectedGraph<Integer> graph,
                                                   List<Integer> active) {
        Map<Integer, Integer> layer = new LinkedHashMap<>();
        List<Set<Integer>> components = graph.stronglyConnectedComponents();
        Map<Integer, Integer> componentOf = new LinkedHashMap<>();
        for (int c = 0; c < components.size(); c++) {
            for (int node : components.get(c)) {
                componentOf.put(node, c);
            }
        }
        DirectedGraph<Integer> condensed = new DirectedGraph<>();
        for (int c = 0; c < components.size(); c++) {
            condensed.addNode(c);
        }
        for (int from : active) {
            for (int to : graph.neighbors(from)) {
                int a = componentOf.get(from);
                int b = componentOf.get(to);
                if (a != b) {
                    condensed.addEdge(a, b);
                }
            }
        }
        List<List<Integer>> generations;
        try {
            generations = condensed.topologicalGenerations();
        } catch (IllegalStateException e) {
            generations = List.of(new ArrayList<>(componentOf.values()));
        }
        for (int generation = 0; generation < generations.size(); generation++) {
            for (int component : generations.get(generation)) {
                for (Map.Entry<Integer, Integer> entry : componentOf.entrySet()) {
                    if (entry.getValue() == component) {
                        layer.put(entry.getKey(), generation);
                    }
                }
            }
        }
        for (int index : active) {
            layer.putIfAbsent(index, 0);
        }
        return layer;
    }

    public void render(GuiGraphics graphics, Font font, int areaX, int areaY,
                       int areaW, int areaH, int scrollX, int scrollY) {
        graphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x10FFFFFF);
        graphics.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);
        if (nodes.isEmpty()) {
            graphics.drawString(font, "没有有效配方可绘制", areaX + 8, areaY + 8,
                    AeStyle.MUTED, false);
            graphics.disableScissor();
            return;
        }

        for (Edge edge : edges) {
            Node from = node(edge.from);
            Node to = node(edge.to);
            if (from == null || to == null) {
                continue;
            }
            drawEdge(graphics, font, edge, from, to, areaX, areaY, scrollX, scrollY);
        }
        for (Node node : nodes) {
            drawNode(graphics, font, node, areaX, areaY, scrollX, scrollY);
        }
        graphics.disableScissor();
    }

    private Node node(int recipeId) {
        for (Node node : nodes) {
            if (node.recipeId == recipeId) {
                return node;
            }
        }
        return null;
    }

    private void drawEdge(GuiGraphics graphics, Font font, Edge edge, Node from, Node to,
                          int areaX, int areaY, int scrollX, int scrollY) {
        int fx = areaX + from.x - scrollX;
        int fy = areaY + from.y - scrollY;
        int tx = areaX + to.x - scrollX;
        int ty = areaY + to.y - scrollY;
        int color = to.x <= from.x ? EDGE_BACK : EDGE;
        int sx = fx + NODE_W;
        int sy = fy + NODE_H / 2;
        int ex = tx;
        int ey = ty + NODE_H / 2;
        if (ex > sx + 10) {
            int mid = (sx + ex) / 2;
            graphics.fill(sx, sy, mid, sy + 1, color);
            graphics.fill(mid, Math.min(sy, ey), mid + 1, Math.max(sy, ey) + 1, color);
            graphics.fill(mid, ey, ex - 3, ey + 1, color);
            graphics.fill(ex - 4, ey - 2, ex, ey + 3, color);
        } else {
            // 同层/回流：走下绕线
            int by = Math.max(fy, ty) + NODE_H + 8;
            graphics.fill(fx + NODE_W / 2, fy + NODE_H, fx + NODE_W / 2 + 1, by, color);
            graphics.fill(Math.min(fx + NODE_W / 2, tx + NODE_W / 2), by,
                    Math.max(fx + NODE_W / 2, tx + NODE_W / 2) + 1, by + 1, color);
            graphics.fill(tx + NODE_W / 2, by, tx + NODE_W / 2 + 1, ty + NODE_H, color);
            graphics.fill(tx + NODE_W / 2 - 2, ty + NODE_H - 4,
                    tx + NODE_W / 2 + 3, ty + NODE_H, color);
        }
        String label = edgeLabel(edge);
        if (!label.isEmpty()) {
            int lx = ex > sx + 10 ? (sx + ex) / 2 + 2 : tx + NODE_W / 2 + 3;
            int ly = ey > sy ? ey - 10 : ey + 3;
            graphics.drawString(font, font.plainSubstrByWidth(label, 78), lx, ly,
                    AeStyle.MUTED, false);
        }
    }

    private static String edgeLabel(Edge edge) {
        if (edge.items.isEmpty()) {
            return "";
        }
        Map.Entry<String, Double> first = edge.items.entrySet().iterator().next();
        String label = ItemKeyMapper.displayName(first.getKey());
        if (edge.items.size() > 1) {
            label += " +" + (edge.items.size() - 1);
        }
        return label;
    }

    private void drawNode(GuiGraphics graphics, Font font, Node node,
                          int areaX, int areaY, int scrollX, int scrollY) {
        int x = areaX + node.x - scrollX;
        int y = areaY + node.y - scrollY;
        graphics.fill(x - 1, y - 1, x + NODE_W + 1, y + NODE_H + 1, AeStyle.BORDER_DARK);
        graphics.fill(x, y, x + NODE_W, y + NODE_H, 0xFFD8D8D8);
        graphics.fill(x, y, x + NODE_W, y + 14, 0xFFB8B8B8);
        graphics.fill(x, y + 14, x + NODE_W, y + 15, AeStyle.BORDER_DARK);
        graphics.drawString(font, "R" + node.recipeId, x + 4, y + 4, AeStyle.TEXT, false);
        String machines = node.machines + " 台";
        graphics.drawString(font, machines, x + NODE_W - 4 - font.width(machines),
                y + 4, AeStyle.TEXT, false);

        Recipe recipe = node.recipeId < recipes.size() ? recipes.get(node.recipeId) : null;
        if (recipe == null) {
            return;
        }
        AeStyle.slot(graphics, x + 4, y + 17);
        if (!recipe.equType().isBlank()) {
            ItemKeyMapper.renderIcon(graphics, font, recipe.equType(), x + 4, y + 17, null);
        }
        String product = recipe.productList().keySet().stream().findFirst().orElse("");
        AeStyle.slot(graphics, x + 25, y + 17);
        if (!product.isBlank()) {
            ItemKeyMapper.renderIcon(graphics, font, product, x + 25, y + 17, null);
        }
        String rate = "x" + com.jessetech.recipebalancer.core.time.TimeExpression
                .formatNumber(node.rate) + "/s";
        graphics.drawString(font, rate, x + 47, y + 18, AeStyle.TEXT, false);
        String equip = recipe.equType().isBlank() ? "-" : ItemKeyMapper.displayName(recipe.equType());
        graphics.drawString(font, font.plainSubstrByWidth(equip, NODE_W - 52),
                x + 47, y + 30, AeStyle.MUTED, false);
        String prod = product.isBlank() ? "-" : ItemKeyMapper.displayName(product);
        graphics.drawString(font, font.plainSubstrByWidth(prod, NODE_W - 10),
                x + 4, y + 42, AeStyle.MUTED, false);
    }
}
