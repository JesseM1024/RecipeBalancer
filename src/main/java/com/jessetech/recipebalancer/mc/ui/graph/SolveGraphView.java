package com.jessetech.recipebalancer.mc.ui.graph;

import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * M5 求解流程图：
 * <ul>
 *   <li>橙色方形 = 配方（设备图标/首字母）；</li>
 *   <li>绿/蓝/红/黄/紫圆角矩形 = 外部输入/中间产物/启动库存/副产物/最终目标；</li>
 *   <li>箭头按每 tick 物流绘制，线宽随流量变化；</li>
 *   <li>力导向布局，支持拖动平移和滚轮缩放。</li>
 * </ul>
 */
public final class SolveGraphView {

    private static final int MATERIAL = 22;
    private static final int RECIPE = 40;
    private static final int INPUT = 0xFF5FD35F;
    private static final int INTERMEDIATE = 0xFF4F9BE8;
    private static final int STARTUP = 0xFFE06464;
    private static final int BYPRODUCT = 0xFFE0C63A;
    private static final int TARGET = 0xFFA96BE0;
    private static final int RECIPE_FILL = 0xFFE8A33D;
    private static final int BORDER = 0xFF4A4A4A;
    private static final int EDGE = 0xFF9A9A9A;
    private static final double NODE_GAP = 38.0;
    private static final double EDGE_REST = 84.0;
    private static final long AUTO_SETTLE_NANOS = 2_000_000_000L;
    private static final double AUTO_STEP_SECONDS = 1.0 / 100.0;
    private static final double AUTO_MAX_MOVE = 6.0;

    private enum Category {
        INPUT, INTERMEDIATE, STARTUP, BYPRODUCT, TARGET
    }

    private final SolveResult result;
    private final List<Recipe> recipes;
    private final Map<String, Double> targets;
    private final boolean inventoryEnabled;
    private final List<GNode> nodes = new ArrayList<>();
    private final List<GEdge> edges = new ArrayList<>();
    private final Map<String, GNode> materials = new LinkedHashMap<>();
    private final Map<Integer, GNode> recipeNodes = new LinkedHashMap<>();

    private double panX;
    private double panY;
    private double zoom = 1.0;
    private boolean cameraReady;
    private int cameraAreaW;
    private int cameraAreaH;
    private double minX;
    private double minY;
    private double contentW = 1;
    private double contentH = 1;
    private int lastAreaX;
    private int lastAreaY;
    private int lastAreaW;
    private int lastAreaH;

    private final Map<Long, List<GNode>> spatialGrid = new HashMap<>();
    private GNode draggingNode;
    private double dragOffsetX;
    private double dragOffsetY;
    private int settleSteps;

    private boolean autoSettlePending = true;
    private boolean autoSettling;
    private GNode autoAnchor;
    private long autoSettleEndNanos;
    private long autoSettleLastNanos;
    private double autoSettleAccumulator;
    private int autoSettleStepCounter;

    private static final class GNode {
        final boolean recipe;
        final String key;
        final int recipeId;
        final Category category;
        final int machines;
        int index;
        double x;
        double y;

        private GNode(boolean recipe, String key, int recipeId,
                      Category category, int machines) {
            this.recipe = recipe;
            this.key = key;
            this.recipeId = recipeId;
            this.category = category;
            this.machines = machines;
        }
    }

    private static final class GEdge {
        final GNode from;
        final GNode to;
        final double flow;
        final String material;

        private GEdge(GNode from, GNode to, double flow, String material) {
            this.from = from;
            this.to = to;
            this.flow = flow;
            this.material = material;
        }
    }

    public SolveGraphView(SolveResult result, List<Recipe> recipes) {
        this(result, recipes, Map.of(), false);
    }

    public SolveGraphView(SolveResult result, List<Recipe> recipes,
                          Map<String, Double> targets, boolean inventoryEnabled) {
        this.result = result;
        this.recipes = recipes;
        this.targets = targets == null ? Map.of() : new LinkedHashMap<>(targets);
        this.inventoryEnabled = inventoryEnabled;
        build();
        layout();
    }

    private void build() {
        Set<String> external = result.externalInputs().keySet();
        Set<String> byproducts = result.finalOutputs().keySet();
        Set<String> startup = inventoryEnabled
                ? result.bootstrapInventory().keySet() : Set.of();

        for (int index = 0; index < result.recipeRates().size(); index++) {
            double rate = result.recipeRates().get(index);
            if (rate < Constants.EPS_RATE) {
                continue;
            }
            Recipe recipe = recipes.get(index);
            GNode node = new GNode(true, recipe.equType(), index, null,
                    (int) Math.ceil(rate * recipe.time()));
            recipeNodes.put(index, node);
            nodes.add(node);
        }

        for (int index : recipeNodes.keySet()) {
            Recipe recipe = recipes.get(index);
            GNode recipeNode = recipeNodes.get(index);
            double ratePerTick = result.recipeRates().get(index) / 20.0;
            for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
                GNode material = material(entry.getKey(), external, byproducts, startup);
                edges.add(new GEdge(material, recipeNode,
                        entry.getValue() * ratePerTick, entry.getKey()));
            }
            for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
                GNode material = material(entry.getKey(), external, byproducts, startup);
                edges.add(new GEdge(recipeNode, material,
                        entry.getValue() * ratePerTick, entry.getKey()));
            }
        }
        // 目标若未出现在活跃配方里也画出来
        for (String target : targets.keySet()) {
            material(target, external, byproducts, startup);
        }
    }

    private GNode material(String key, Set<String> external, Set<String> byproducts,
                           Set<String> startup) {
        return materials.computeIfAbsent(key, ignored -> {
            Category category;
            if (targets.containsKey(key)) {
                category = Category.TARGET;
            } else if (startup.contains(key)) {
                category = Category.STARTUP;
            } else if (external.contains(key)) {
                category = Category.INPUT;
            } else if (byproducts.contains(key)) {
                category = Category.BYPRODUCT;
            } else {
                category = Category.INTERMEDIATE;
            }
            GNode node = new GNode(false, key, -1, category, 0);
            nodes.add(node);
            return node;
        });
    }

    private void layout() {
        if (nodes.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).index = i;
        }
        Random random = new Random(0x5EEDL);
        double targetRadius = Math.max(90, nodes.size() * 10.0);
        for (int i = 0; i < nodes.size(); i++) {
            double angle = 2 * Math.PI * i / nodes.size() + random.nextDouble() * 0.35;
            double r = targetRadius * (0.65 + random.nextDouble() * 0.35);
            nodes.get(i).x = Math.cos(angle) * r;
            nodes.get(i).y = Math.sin(angle) * r;
        }

        // 势能最小化：
        // 1) 节点斥力 + 最短距离约束；2) 边弹簧（固定自然长度）；
        // 3) 向心/圆环势能；4) 碰撞松弛；5) 交叉/穿节点的局部能量搜索。
        double k = 52.0;
        double restLength = EDGE_REST;
        int iterations = nodes.size() > 180 ? 140 : 260;
        for (int iteration = 0; iteration < iterations; iteration++) {
            double temperature = Math.max(1.0, k * (1.0 - iteration / (double) iterations));
            double[] dx = new double[nodes.size()];
            double[] dy = new double[nodes.size()];

            for (int i = 0; i < nodes.size(); i++) {
                GNode a = nodes.get(i);
                for (int j = i + 1; j < nodes.size(); j++) {
                    GNode b = nodes.get(j);
                    double vx = a.x - b.x;
                    double vy = a.y - b.y;
                    double dist = Math.max(1.0, Math.hypot(vx, vy));
                    double minDist = minDistance(a, b);
                    double force = k * k / dist;
                    if (dist < minDist) {
                        force += (minDist - dist) * 5.0;
                    }
                    double ux = vx / dist;
                    double uy = vy / dist;
                    dx[i] += ux * force;
                    dy[i] += uy * force;
                    dx[j] -= ux * force;
                    dy[j] -= uy * force;
                }
            }

            for (GEdge edge : edges) {
                double vx = edge.from.x - edge.to.x;
                double vy = edge.from.y - edge.to.y;
                double dist = Math.max(1.0, Math.hypot(vx, vy));
                double force = (dist - restLength) * 0.065;
                double ux = vx / dist;
                double uy = vy / dist;
                dx[edge.from.index] -= ux * force;
                dy[edge.from.index] -= uy * force;
                dx[edge.to.index] += ux * force;
                dy[edge.to.index] += uy * force;
            }

            for (int i = 0; i < nodes.size(); i++) {
                GNode node = nodes.get(i);
                double dist = Math.max(1.0, Math.hypot(node.x, node.y));
                // 向心重力 + 圆环径向势能，保持整体圆形且不散开
                dx[i] -= node.x / dist * dist * 0.055;
                dy[i] -= node.y / dist * dist * 0.055;
                double radial = (dist - targetRadius) * 0.09;
                dx[i] -= node.x / dist * radial;
                dy[i] -= node.y / dist * radial;
                double length = Math.max(1.0, Math.hypot(dx[i], dy[i]));
                double step = Math.min(length, temperature);
                node.x += dx[i] / length * step;
                node.y += dy[i] / length * step;
            }
        }

        collisionRelax();
        minimizeCrossings(targetRadius);
        collisionRelax();

        minX = Double.MAX_VALUE;
        minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (GNode node : nodes) {
            double half = (node.recipe ? RECIPE : MATERIAL) / 2.0 + 42;
            minX = Math.min(minX, node.x - half);
            minY = Math.min(minY, node.y - half);
            maxX = Math.max(maxX, node.x + half);
            maxY = Math.max(maxY, node.y + half);
        }
        contentW = Math.max(1.0, maxX - minX);
        contentH = Math.max(1.0, maxY - minY);
        cameraReady = false;
    }

    private static double minDistance(GNode a, GNode b) {
        return (a.recipe ? RECIPE : MATERIAL) / 2.0
                + (b.recipe ? RECIPE : MATERIAL) / 2.0 + NODE_GAP;
    }

    /** 碰撞松弛：保证节点/标签区域至少保有固定间距。 */
    private void collisionRelax() {
        for (int pass = 0; pass < 28; pass++) {
            for (int i = 0; i < nodes.size(); i++) {
                GNode a = nodes.get(i);
                for (int j = i + 1; j < nodes.size(); j++) {
                    GNode b = nodes.get(j);
                    double dx = a.x - b.x;
                    double dy = a.y - b.y;
                    double dist = Math.hypot(dx, dy);
                    double min = minDistance(a, b);
                    if (dist >= min || dist < 1e-6) {
                        continue;
                    }
                    double push = (min - dist) * 0.51;
                    double ux = dx / dist;
                    double uy = dy / dist;
                    a.x += ux * push;
                    a.y += uy * push;
                    b.x -= ux * push;
                    b.y -= uy * push;
                }
            }
        }
    }

    /**
     * 局部能量搜索：用较小的候选位移降低
     * 边交叉数、边穿节点数、边长和节点重叠；比全局模拟退火快得多。
     */
    private void minimizeCrossings(double targetRadius) {
        if (edges.size() < 2 || nodes.size() > 260) {
            return;
        }
        int passes = edges.size() <= 180 ? 4 : 2;
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (GNode node : nodes) {
                List<GEdge> incident = new ArrayList<>();
                for (GEdge edge : edges) {
                    if (edge.from == node || edge.to == node) {
                        incident.add(edge);
                    }
                }
                if (incident.isEmpty()) {
                    continue;
                }
                double bestX = node.x;
                double bestY = node.y;
                double bestCost = localCost(node, node.x, node.y, incident, targetRadius);
                for (int direction = 0; direction < 16; direction++) {
                    double angle = Math.PI * 2 * direction / 16.0;
                    for (double radius : new double[]{12.0, 24.0, 38.0}) {
                        double candidateX = node.x + Math.cos(angle) * radius;
                        double candidateY = node.y + Math.sin(angle) * radius;
                        double cost = localCost(node, candidateX, candidateY,
                                incident, targetRadius);
                        if (cost + 1e-6 < bestCost) {
                            bestCost = cost;
                            bestX = candidateX;
                            bestY = candidateY;
                        }
                    }
                }
                if (bestX != node.x || bestY != node.y) {
                    node.x = bestX;
                    node.y = bestY;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            collisionRelax();
        }
    }

    private double localCost(GNode node, double x, double y,
                             List<GEdge> incident, double targetRadius) {
        double cost = 0.0;
        double dist = Math.max(1.0, Math.hypot(x, y));
        cost += Math.pow(dist - targetRadius, 2) * 0.002;

        for (GEdge edge : incident) {
            GNode other = edge.from == node ? edge.to : edge.from;
            double dx = x - other.x;
            double dy = y - other.y;
            cost += (dx * dx + dy * dy) * 0.005;
        }

        for (GNode other : nodes) {
            if (other == node) {
                continue;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double d = Math.hypot(dx, dy);
            double min = minDistance(node, other);
            if (d < min) {
                cost += (min - d) * (min - d) * 8.0;
            }
        }

        for (GEdge edge : incident) {
            double ax = edge.from == node ? x : edge.from.x;
            double ay = edge.from == node ? y : edge.from.y;
            double bx = edge.to == node ? x : edge.to.x;
            double by = edge.to == node ? y : edge.to.y;

            for (GEdge other : edges) {
                if (other == edge || other.from == node || other.to == node) {
                    continue;
                }
                if (segmentsIntersect(ax, ay, bx, by,
                        other.from.x, other.from.y, other.to.x, other.to.y)) {
                    cost += 800.0;
                }
            }
            for (GNode check : nodes) {
                if (check == node || check == edge.from || check == edge.to) {
                    continue;
                }
                if (pointSegmentDistance(check.x, check.y, ax, ay, bx, by) < 14.0) {
                    cost += 350.0;
                }
            }
        }
        return cost;
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double o1 = cross(ax, ay, bx, by, cx, cy);
        double o2 = cross(ax, ay, bx, by, dx, dy);
        double o3 = cross(cx, cy, dx, dy, ax, ay);
        double o4 = cross(cx, cy, dx, dy, bx, by);
        return ((o1 > 1e-6 && o2 < -1e-6) || (o1 < -1e-6 && o2 > 1e-6))
                && ((o3 > 1e-6 && o4 < -1e-6) || (o3 < -1e-6 && o4 > 1e-6));
    }

    private static double cross(double ax, double ay, double bx, double by,
                                double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** 鼠标命中节点；没有则返回 null。 */
    public boolean beginNodeDrag(double screenX, double screenY) {
        if (lastAreaW <= 0) {
            return false;
        }
        double worldX = (screenX - lastAreaX - panX) / zoom;
        double worldY = (screenY - lastAreaY - panY) / zoom;
        GNode hit = null;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            GNode node = nodes.get(i);
            double half = (node.recipe ? RECIPE : MATERIAL) / 2.0 + 3;
            if (Math.abs(worldX - node.x) <= half && Math.abs(worldY - node.y) <= half) {
                hit = node;
                break;
            }
        }
        if (hit == null) {
            return false;
        }
        draggingNode = hit;
        settleSteps = 0;
        autoSettling = false;
        autoSettlePending = false;
        dragOffsetX = worldX - hit.x;
        dragOffsetY = worldY - hit.y;
        return true;
    }

    public boolean isNodeDragging() {
        return draggingNode != null;
    }

    public void dragNodeTo(double screenX, double screenY) {
        if (draggingNode == null) {
            return;
        }
        draggingNode.x = (screenX - lastAreaX - panX) / zoom - dragOffsetX;
        draggingNode.y = (screenY - lastAreaY - panY) / zoom - dragOffsetY;
    }

    public void endNodeDrag() {
        if (draggingNode != null) {
            draggingNode = null;
            settleSteps = 10;
        }
    }

    /**
     * 拖动时的局部弹性：
     * 仅查询鼠标节点周围网格内的邻居，施加斥力/相连边弹簧，
     * 不跑全图 O(n²)；每帧成本与邻居数相关。
     */
    private void elasticStep(GNode dragged) {
        rebuildGrid();
        double influence = 190.0;
        for (GNode other : nearby(dragged, influence)) {
            if (other == dragged) {
                continue;
            }
            double dx = other.x - dragged.x;
            double dy = other.y - dragged.y;
            double dist = Math.max(0.001, Math.hypot(dx, dy));
            double ux = dx / dist;
            double uy = dy / dist;
            double min = minDistance(dragged, other);
            double move = 0.0;
            if (dist < min * 1.3) {
                move += (min * 1.3 - dist) * 0.14;
            }
            if (connected(dragged, other)) {
                double rest = EDGE_REST;
                move += (rest - dist) * 0.075;
            }
            move = Math.max(-6.0, Math.min(6.0, move));
            other.x += ux * move;
            other.y += uy * move;
        }
        // 被拖节点不允许压到邻居
        for (GNode other : nearby(dragged, influence)) {
            if (other == dragged) {
                continue;
            }
            double dx = other.x - dragged.x;
            double dy = other.y - dragged.y;
            double dist = Math.hypot(dx, dy);
            double min = minDistance(dragged, other);
            if (dist < min && dist > 0.001) {
                double push = min - dist;
                other.x += dx / dist * push;
                other.y += dy / dist * push;
            }
        }
    }

    /** 松手后的局部回弹，几帧内收敛，不做全图重新布局。 */
    private void settleStep() {
        settleStep(true);
    }

    private void settleStep(boolean collide) {
        settleStep(collide, 2.0);
    }

    private void settleStep(boolean collide, double maxMove) {
        rebuildGrid();
        double influence = 170.0;
        for (GNode node : nodes) {
            double moveX = 0.0;
            double moveY = 0.0;
            for (GNode other : nearby(node, influence)) {
                if (other == node) {
                    continue;
                }
                double dx = node.x - other.x;
                double dy = node.y - other.y;
                double dist = Math.max(0.001, Math.hypot(dx, dy));
                double min = minDistance(node, other);
                if (dist < min * 1.2) {
                    double push = (min * 1.2 - dist) * 0.06;
                    moveX += dx / dist * push;
                    moveY += dy / dist * push;
                }
            }
            for (GEdge edge : edges) {
                if (edge.from != node && edge.to != node) {
                    continue;
                }
                GNode other = edge.from == node ? edge.to : edge.from;
                double dx = node.x - other.x;
                double dy = node.y - other.y;
                double dist = Math.max(0.001, Math.hypot(dx, dy));
                double spring = (dist - EDGE_REST) * 0.025;
                moveX -= dx / dist * spring;
                moveY -= dy / dist * spring;
            }
            node.x += Math.max(-maxMove, Math.min(maxMove, moveX));
            node.y += Math.max(-maxMove, Math.min(maxMove, moveY));
        }
        if (collide) {
            collisionRelax();
        }
    }

    /**
     * 启动动画：选中一个节点并“按住不动”，即它固定在原地，
     * 但它对邻居的斥力/相连弹簧持续求解，约 2 秒后松手。
     */
    private void startAutoSettleIfNeeded(long now) {
        if (!autoSettlePending) {
            return;
        }
        autoSettlePending = false;
        if (nodes.isEmpty()) {
            return;
        }
        autoAnchor = chooseAutoAnchor();
        autoSettling = true;
        autoSettleEndNanos = now + AUTO_SETTLE_NANOS;
        autoSettleLastNanos = now;
        autoSettleAccumulator = 0.0;
        autoSettleStepCounter = 0;
    }

    private void updateAutoSettle(long now) {
        if (!autoSettling) {
            return;
        }
        double dt = Math.max(0.0, Math.min(0.05,
                (now - autoSettleLastNanos) / 1_000_000_000.0));
        autoSettleLastNanos = now;
        autoSettleAccumulator += dt;
        int guard = 0;
        while (autoSettleAccumulator >= AUTO_STEP_SECONDS
                && now < autoSettleEndNanos && guard < 6) {
            autoSettleAccumulator -= AUTO_STEP_SECONDS;
            autoSettleStepCounter++;
            autoSettleStep();
            guard++;
        }
        if (now >= autoSettleEndNanos) {
            autoSettling = false;
            collisionRelax();
        }
    }

    private void autoSettleStep() {
        if (autoAnchor == null) {
            return;
        }
        double anchorX = autoAnchor.x;
        double anchorY = autoAnchor.y;
        // 模拟“按住该节点”：邻居受局部力，全局边弹簧继续收缩。
        elasticStep(autoAnchor);
        autoAnchor.x = anchorX;
        autoAnchor.y = anchorY;
        settleStep(false, AUTO_MAX_MOVE);
        autoAnchor.x = anchorX;
        autoAnchor.y = anchorY;
        if (autoSettleStepCounter % 8 == 0) {
            collisionRelax();
            autoAnchor.x = anchorX;
            autoAnchor.y = anchorY;
        }
    }

    private GNode chooseAutoAnchor() {
        GNode best = nodes.get(0);
        int bestDegree = -1;
        double bestDistance = Double.MAX_VALUE;
        double centerX = 0.0;
        double centerY = 0.0;
        for (GNode node : nodes) {
            centerX += node.x;
            centerY += node.y;
        }
        centerX /= nodes.size();
        centerY /= nodes.size();
        for (GNode node : nodes) {
            int degree = 0;
            for (GEdge edge : edges) {
                if (edge.from == node || edge.to == node) {
                    degree++;
                }
            }
            double distance = Math.hypot(node.x - centerX, node.y - centerY);
            if (degree > bestDegree
                    || (degree == bestDegree && distance < bestDistance)) {
                best = node;
                bestDegree = degree;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean connected(GNode a, GNode b) {
        for (GEdge edge : edges) {
            if ((edge.from == a && edge.to == b) || (edge.from == b && edge.to == a)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildGrid() {
        spatialGrid.clear();
        double cell = 96.0;
        for (GNode node : nodes) {
            long key = cellKey((int) Math.floor(node.x / cell),
                    (int) Math.floor(node.y / cell));
            spatialGrid.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
        }
    }

    private List<GNode> nearby(GNode node, double radius) {
        if (spatialGrid.isEmpty()) {
            return nodes;
        }
        double cell = 96.0;
        int range = (int) Math.ceil(radius / cell);
        int cx = (int) Math.floor(node.x / cell);
        int cy = (int) Math.floor(node.y / cell);
        Set<GNode> result = new HashSet<>();
        for (int x = cx - range; x <= cx + range; x++) {
            for (int y = cy - range; y <= cy + range; y++) {
                List<GNode> cellNodes = spatialGrid.get(cellKey(x, y));
                if (cellNodes != null) {
                    result.addAll(cellNodes);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static long cellKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    public void resetCamera(int areaW, int areaH) {
        if (nodes.isEmpty()) {
            return;
        }
        cameraAreaW = areaW;
        cameraAreaH = areaH;
        zoom = Math.max(0.35, Math.min(1.2,
                Math.min((areaW - 24) / contentW, (areaH - 24) / contentH)));
        panX = areaW / 2.0 - (minX + contentW / 2.0) * zoom;
        panY = areaH / 2.0 - (minY + contentH / 2.0) * zoom;
        cameraReady = true;
    }

    public void panBy(double dx, double dy) {
        panX += dx;
        panY += dy;
    }

    public void zoomAt(double mouseX, double mouseY, double factor) {
        double old = zoom;
        double next = Math.max(0.25, Math.min(3.0, zoom * factor));
        if (Math.abs(next - old) < 1e-6) {
            return;
        }
        double worldX = (mouseX - panX) / old;
        double worldY = (mouseY - panY) / old;
        zoom = next;
        panX = mouseX - worldX * zoom;
        panY = mouseY - worldY * zoom;
    }

    public void render(GuiGraphics graphics, Font font, int areaX, int areaY,
                       int areaW, int areaH) {
        lastAreaX = areaX;
        lastAreaY = areaY;
        lastAreaW = areaW;
        lastAreaH = areaH;
        if (!cameraReady || cameraAreaW != areaW || cameraAreaH != areaH) {
            resetCamera(areaW, areaH);
        }
        long now = System.nanoTime();
        startAutoSettleIfNeeded(now);
        updateAutoSettle(now);

        if (draggingNode != null) {
            elasticStep(draggingNode);
        } else if (settleSteps > 0) {
            settleStep();
            settleSteps--;
        }
        graphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x10FFFFFF);
        graphics.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);

        if (nodes.isEmpty()) {
            graphics.drawString(font, "没有有效配方可绘制", areaX + 8, areaY + 24,
                    AeStyle.MUTED, false);
            graphics.disableScissor();
            return;
        }

        double viewMinX = (-panX) / zoom - 120;
        double viewMaxX = (areaW - panX) / zoom + 120;
        double viewMinY = (-panY) / zoom - 120;
        double viewMaxY = (areaH - panY) / zoom + 120;
        // LOD 阈值放低：只有缩得非常小才省略元素。
        boolean showNodeLabels = zoom >= 0.26;
        boolean showEdgeLabels = zoom >= 0.33;
        boolean showIcons = zoom >= 0.22;

        graphics.pose().pushPose();
        graphics.pose().translate(areaX + panX, areaY + panY, 0);
        graphics.pose().scale((float) zoom, (float) zoom, 1.0f);
        drawEdges(graphics, font, viewMinX, viewMaxX, viewMinY, viewMaxY, showEdgeLabels);
        for (GNode node : nodes) {
            if (node.x < viewMinX || node.x > viewMaxX
                    || node.y < viewMinY || node.y > viewMaxY) {
                continue;
            }
            drawNode(graphics, font, node, showNodeLabels, showIcons);
        }
        graphics.pose().popPose();
        graphics.disableScissor();

        drawLegend(graphics, font, areaX, areaY);
        graphics.drawString(font, "拖动平移 · 滚轮缩放 · 流量单位 /t",
                areaX + 4, areaY + areaH - 12, AeStyle.MUTED, false);
    }

    /** 鼠标悬浮：配方/物质/物流详细 tooltip。 */
    public void renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (nodes.isEmpty() || lastAreaW <= 0
                || mouseX < lastAreaX || mouseX >= lastAreaX + lastAreaW
                || mouseY < lastAreaY || mouseY >= lastAreaY + lastAreaH) {
            return;
        }
        double wx = (mouseX - lastAreaX - panX) / zoom;
        double wy = (mouseY - lastAreaY - panY) / zoom;
        GNode hovered = null;
        for (GNode node : nodes) {
            double half = (node.recipe ? RECIPE : MATERIAL) / 2.0;
            if (Math.abs(wx - node.x) <= half && Math.abs(wy - node.y) <= half) {
                hovered = node;
                break;
            }
        }
        if (hovered != null) {
            graphics.renderComponentTooltip(font,
                    hovered.recipe ? recipeTooltip(hovered) : materialTooltip(hovered),
                    mouseX, mouseY);
            return;
        }
        GEdge edge = hoveredEdge(wx, wy);
        if (edge != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(ItemKeyMapper.displayName(edge.material))
                    .withStyle(ChatFormatting.BOLD));
            lines.add(Component.literal(nodeLabel(edge.from) + " → " + nodeLabel(edge.to))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("流量: "
                    + TimeExpression.formatEditableNumber(edge.flow) + " /t"));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private GEdge hoveredEdge(double wx, double wy) {
        GEdge best = null;
        double bestDistance = 7.0;
        for (GEdge edge : edges) {
            double distance = pointSegmentDistance(wx, wy,
                    edge.from.x, edge.from.y, edge.to.x, edge.to.y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = edge;
            }
        }
        return best;
    }

    private static double pointSegmentDistance(double px, double py,
                                               double x1, double y1,
                                               double x2, double y2) {
        double vx = x2 - x1;
        double vy = y2 - y1;
        double lengthSq = vx * vx + vy * vy;
        if (lengthSq <= 1e-9) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = ((px - x1) * vx + (py - y1) * vy) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + vx * t), py - (y1 + vy * t));
    }

    private List<Component> recipeTooltip(GNode node) {
        Recipe recipe = recipes.get(node.recipeId);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("R" + node.recipeId + " · "
                + ItemKeyMapper.displayName(recipe.equType())).withStyle(ChatFormatting.BOLD));
        double perSecond = result.recipeRates().get(node.recipeId);
        lines.add(Component.literal("设备并行: "
                + TimeExpression.formatEditableNumber(perSecond * recipe.time())
                + "  设备数: " + node.machines)
                .withStyle(ChatFormatting.GRAY));
        double ratePerTick = perSecond / 20.0;
        lines.add(Component.literal("运行速率: "
                + TimeExpression.formatEditableNumber(ratePerTick) + " /t"));
        lines.add(Component.literal("输入 (/t):").withStyle(ChatFormatting.GREEN));
        for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
            lines.add(Component.literal("  - " + ItemKeyMapper.displayName(entry.getKey())
                    + " × " + TimeExpression.formatEditableNumber(entry.getValue() * ratePerTick)));
        }
        lines.add(Component.literal("输出 (/t):").withStyle(ChatFormatting.GOLD));
        for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
            lines.add(Component.literal("  + " + ItemKeyMapper.displayName(entry.getKey())
                    + " × " + TimeExpression.formatEditableNumber(entry.getValue() * ratePerTick)));
        }
        return lines;
    }

    private List<Component> materialTooltip(GNode node) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(ItemKeyMapper.displayName(node.key))
                .withStyle(ChatFormatting.BOLD));
        lines.add(Component.literal("类别: " + categoryLabel(node.category))
                .withStyle(ChatFormatting.GRAY));
        double produced = 0.0;
        double consumed = 0.0;
        for (GEdge edge : edges) {
            if (edge.to == node) {
                produced += edge.flow;
            }
            if (edge.from == node) {
                consumed += edge.flow;
            }
        }
        lines.add(Component.literal("生成: " + TimeExpression.formatEditableNumber(produced)
                + " /t  消耗: " + TimeExpression.formatEditableNumber(consumed) + " /t"));
        if (node.category == Category.TARGET) {
            Double target = targets.get(node.key);
            if (target != null) {
                lines.add(Component.literal("目标: "
                        + TimeExpression.formatEditableNumber(target) + " /t")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
        if (node.category == Category.INPUT) {
            Double amount = result.externalInputs().get(node.key);
            if (amount != null) {
                lines.add(Component.literal("外部输入: "
                        + TimeExpression.formatEditableNumber(amount / 20.0) + " /t")
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        if (node.category == Category.STARTUP) {
            Double amount = result.bootstrapInventory().get(node.key);
            if (amount != null) {
                lines.add(Component.literal("启动库存: "
                        + TimeExpression.formatEditableNumber(amount))
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (node.category == Category.BYPRODUCT) {
            Double amount = result.finalOutputs().get(node.key);
            if (amount != null) {
                lines.add(Component.literal("副产物: "
                        + TimeExpression.formatEditableNumber(amount / 20.0) + " /t")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        return lines;
    }

    private static String nodeLabel(GNode node) {
        return node.recipe ? "R" + node.recipeId
                : ItemKeyMapper.displayName(node.key);
    }

    private static String categoryLabel(Category category) {
        return switch (category) {
            case INPUT -> "外部输入";
            case STARTUP -> "启动库存";
            case BYPRODUCT -> "副产物";
            case TARGET -> "最终目标";
            default -> "中间产物";
        };
    }

    private void drawLegend(GuiGraphics graphics, Font font, int areaX, int areaY) {
        int x = areaX + 4;
        int y = areaY + 4;
        String legend = "橙=配方  绿=输入  蓝=中间  红=启动  黄=副产  紫=目标";
        int width = font.width(legend) + 8;
        graphics.fill(x, y, x + width, y + 13, 0xB0C6C6C6);
        graphics.drawString(font, legend, x + 4, y + 3, AeStyle.TEXT, false);
    }

    /**
     * 所有边/箭头合批到一个 GUI VertexConsumer，避免每条边每像素一次 fill。
     * 边标签在合批刷新后单独绘制。
     */
    private void drawEdges(GuiGraphics graphics, Font font,
                           double minX, double maxX, double minY, double maxY,
                           boolean showLabels) {
        if (edges.isEmpty()) {
            return;
        }
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f matrix = graphics.pose().last().pose();
        for (GEdge edge : edges) {
            if (!edgeVisible(edge, minX, maxX, minY, maxY)) {
                continue;
            }
            drawEdgeGeometry(consumer, matrix, edge);
        }
        graphics.flush();

        if (!showLabels) {
            return;
        }
        for (GEdge edge : edges) {
            if (!edgeVisible(edge, minX, maxX, minY, maxY)) {
                continue;
            }
            drawEdgeLabel(graphics, font, edge);
        }
    }

    private static boolean edgeVisible(GEdge edge, double minX, double maxX,
                                       double minY, double maxY) {
        double left = Math.min(edge.from.x, edge.to.x);
        double right = Math.max(edge.from.x, edge.to.x);
        double top = Math.min(edge.from.y, edge.to.y);
        double bottom = Math.max(edge.from.y, edge.to.y);
        return right >= minX && left <= maxX && bottom >= minY && top <= maxY;
    }

    private void drawEdgeGeometry(VertexConsumer consumer, Matrix4f matrix, GEdge edge) {
        double sx = edge.from.x;
        double sy = edge.from.y;
        double ex = edge.to.x;
        double ey = edge.to.y;
        double dx = ex - sx;
        double dy = ey - sy;
        double dist = Math.max(1.0, Math.hypot(dx, dy));
        double ux = dx / dist;
        double uy = dy / dist;
        double fromRadius = (edge.from.recipe ? RECIPE : MATERIAL) * 0.55;
        double toRadius = (edge.to.recipe ? RECIPE : MATERIAL) * 0.55;
        double x1 = sx + ux * fromRadius;
        double y1 = sy + uy * fromRadius;
        double x2 = ex - ux * toRadius;
        double y2 = ey - uy * toRadius;

        int thickness = 1 + Math.min(4, (int) Math.sqrt(Math.max(0.0, edge.flow)) * 2);
        addLine(consumer, matrix, x1, y1, x2, y2, thickness, EDGE);
        double backX = x2 - ux * 7;
        double backY = y2 - uy * 7;
        double px = -uy;
        double py = ux;
        addLine(consumer, matrix, x2, y2, backX + px * 3.5, backY + py * 3.5,
                thickness + 1, EDGE);
        addLine(consumer, matrix, x2, y2, backX - px * 3.5, backY - py * 3.5,
                thickness + 1, EDGE);
    }

    private void drawEdgeLabel(GuiGraphics graphics, Font font, GEdge edge) {
        double sx = edge.from.x;
        double sy = edge.from.y;
        double ex = edge.to.x;
        double ey = edge.to.y;
        double dx = ex - sx;
        double dy = ey - sy;
        double dist = Math.max(1.0, Math.hypot(dx, dy));
        double ux = dx / dist;
        double uy = dy / dist;
        double fromRadius = (edge.from.recipe ? RECIPE : MATERIAL) * 0.55;
        double toRadius = (edge.to.recipe ? RECIPE : MATERIAL) * 0.55;
        double x1 = sx + ux * fromRadius;
        double y1 = sy + uy * fromRadius;
        double x2 = ex - ux * toRadius;
        double y2 = ey - uy * toRadius;

        String label = TimeExpression.formatCompact(edge.flow) + "/t";
        int width = font.width(label);
        float labelX = (float) ((x1 + x2) / 2 - width / 2.0 - uy * 4);
        float labelY = (float) ((y1 + y2) / 2 - 4 + ux * 4);
        graphics.drawString(font, label, (int) labelX, (int) labelY, 0xFF666666, false);
    }

    private static void addLine(VertexConsumer consumer, Matrix4f matrix,
                                double x1, double y1, double x2, double y2,
                                double thickness, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-6) {
            return;
        }
        double nx = -dy / dist * thickness * 0.5;
        double ny = dx / dist * thickness * 0.5;
        // 与 GuiGraphics.fill 相同的四边形绕序，避免被背面剔除。
        vertex(consumer, matrix, x1 - nx, y1 - ny, color);
        vertex(consumer, matrix, x1 + nx, y1 + ny, color);
        vertex(consumer, matrix, x2 + nx, y2 + ny, color);
        vertex(consumer, matrix, x2 - nx, y2 - ny, color);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix,
                               double x, double y, int color) {
        consumer.addVertex(matrix, (float) x, (float) y, 0.0f).setColor(color);
    }

    private void drawNode(GuiGraphics graphics, Font font, GNode node,
                          boolean showLabels, boolean showIcons) {
        if (node.recipe) {
            drawRecipeNode(graphics, font, node, showLabels, showIcons);
        } else {
            drawMaterialNode(graphics, font, node, showLabels, showIcons);
        }
        if (node == draggingNode) {
            int half = (node.recipe ? RECIPE : MATERIAL) / 2;
            int x = (int) Math.round(node.x) - half;
            int y = (int) Math.round(node.y) - half;
            int size = half * 2;
            graphics.fill(x - 2, y - 2, x + size + 2, y - 1, 0xFF4A90D9);
            graphics.fill(x - 2, y + size + 1, x + size + 2, y + size + 2, 0xFF4A90D9);
            graphics.fill(x - 2, y - 2, x - 1, y + size + 2, 0xFF4A90D9);
            graphics.fill(x + size + 1, y - 2, x + size + 2, y + size + 2, 0xFF4A90D9);
        }
    }

    private void drawRecipeNode(GuiGraphics graphics, Font font, GNode node,
                                boolean showLabels, boolean showIcons) {
        int x = (int) Math.round(node.x) - RECIPE / 2;
        int y = (int) Math.round(node.y) - RECIPE / 2;
        graphics.fill(x - 1, y - 1, x + RECIPE + 1, y + RECIPE + 1, BORDER);
        graphics.fill(x, y, x + RECIPE, y + RECIPE, RECIPE_FILL);
        graphics.fill(x, y, x + RECIPE, y + 1, 0x40FFFFFF);
        String id = "R" + node.recipeId;
        graphics.drawString(font, id, x + 3, y + 3, 0xFF3A2A10, false);
        String count = "x" + node.machines;
        graphics.drawString(font, count, x + RECIPE - 3 - font.width(count),
                y + 3, 0xFF3A2A10, false);

        if (showIcons && !node.key.isBlank() && !ItemKeyMapper.stack(node.key).isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 11, y + 15, null);
        } else {
            String initial = node.key.isBlank() ? "?" : shortInitial(
                    ItemKeyMapper.displayName(node.key));
            graphics.drawString(font, initial, x + RECIPE / 2 - font.width(initial) / 2,
                    y + 19, 0xFF3A2A10, false);
        }
        if (showLabels) {
            String name = node.key.isBlank() ? "配方" : ItemKeyMapper.displayName(node.key);
            drawCenteredLabel(graphics, font, name, node.x, node.y + RECIPE / 2.0 + 3);
        }
    }

    private void drawMaterialNode(GuiGraphics graphics, Font font, GNode node,
                                  boolean showLabels, boolean showIcons) {
        int color = switch (node.category) {
            case INPUT -> INPUT;
            case STARTUP -> STARTUP;
            case BYPRODUCT -> BYPRODUCT;
            case TARGET -> TARGET;
            default -> INTERMEDIATE;
        };
        int x = (int) Math.round(node.x) - MATERIAL / 2;
        int y = (int) Math.round(node.y) - MATERIAL / 2;
        roundedFill(graphics, x - 1, y - 1, MATERIAL + 2, MATERIAL + 2, 5, BORDER);
        roundedFill(graphics, x, y, MATERIAL, MATERIAL, 4, color);

        boolean hasIcon = showIcons && (!ItemKeyMapper.stack(node.key).isEmpty()
                || IngredientKey.isIngredientKey(node.key));
        if (hasIcon) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 2, y + 2, null);
        } else {
            String initial = shortInitial(ItemKeyMapper.displayName(node.key));
            graphics.drawString(font, initial,
                    x + MATERIAL / 2 - font.width(initial) / 2, y + 7,
                    0xFF202020, false);
        }
        if (showLabels) {
            drawCenteredLabel(graphics, font, ItemKeyMapper.displayName(node.key),
                    node.x, node.y + MATERIAL / 2.0 + 3);
        }
    }

    private void drawCenteredLabel(GuiGraphics graphics, Font font, String text,
                                   double centerX, double y) {
        String label = font.plainSubstrByWidth(text, 84);
        graphics.drawString(font, label,
                (int) Math.round(centerX - font.width(label) / 2.0),
                (int) Math.round(y), AeStyle.TEXT, false);
    }

    private static String shortInitial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        int first = name.codePointAt(0);
        return new String(Character.toChars(first)).toUpperCase();
    }

    private static void roundedFill(GuiGraphics graphics, int x, int y,
                                    int width, int height, int radius, int color) {
        for (int row = 0; row < height; row++) {
            int inset = 0;
            if (row < radius) {
                inset = radius - row;
            } else if (row >= height - radius) {
                inset = row - (height - radius) + 1;
            }
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }
}
