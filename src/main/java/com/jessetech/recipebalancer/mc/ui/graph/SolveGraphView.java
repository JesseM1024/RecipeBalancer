package com.jessetech.recipebalancer.mc.ui.graph;

import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.config.GraphAnimationConfig;
import com.jessetech.recipebalancer.mc.i18n.Lang;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import com.mojang.blaze3d.vertex.PoseStack;
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
    /** 物质图标显示阈值：缩放低于它时退回首字母，避免糊成一团。 */
    private static final double MATERIAL_ICON_ZOOM = 0.37;
    /** 配方图标显示阈值。 */
    private static final double RECIPE_ICON_ZOOM = 0.28;
    /** 节点标签的最小屏幕字号（相对原始大小）；缩得再小也只缩到 50%。 */
    private static final float MIN_LABEL_TEXT_SCALE = 0.5f;
    private double nodeGap = 38.0;
    private double edgeNaturalLength = 84.0;
    private double autoStepSeconds = 1.0 / 100.0;
    private long autoDurationNanos = 2_000_000_000L;
    private double autoMaxMove = 6.0;

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
    private String nameLanguage;
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
        final long machines;
        String displayName;
        String initial;
        String label;
        boolean hasIcon;
        int index;
        double x;
        double y;

        private GNode(boolean recipe, String key, int recipeId,
                      Category category, long machines) {
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
        final String label;
        boolean reciprocal;

        private GEdge(GNode from, GNode to, double flow, String material) {
            this.from = from;
            this.to = to;
            this.flow = flow;
            this.material = material;
            this.label = TimeExpression.formatCompact(flow) + "/t";
        }
    }

    /** 双向边使用的二次贝塞尔弧。 */
    private static final class EdgeCurve {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final double cx;
        final double cy;

        private EdgeCurve(double x1, double y1, double x2, double y2,
                          double cx, double cy) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.cx = cx;
            this.cy = cy;
        }

        double pointX(double t) {
            double mt = 1.0 - t;
            return mt * mt * x1 + 2 * mt * t * cx + t * t * x2;
        }

        double pointY(double t) {
            double mt = 1.0 - t;
            return mt * mt * y1 + 2 * mt * t * cy + t * t * y2;
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
        GraphAnimationConfig.Settings settings = GraphAnimationConfig.get();
        this.nodeGap = settings.nodeGap();
        this.edgeNaturalLength = settings.edgeLength();
        this.nameLanguage = Lang.languageCode();
        build();
        layout();
    }

    /** 切换语言后重新解析节点名称（key 里固化的名称可能还是旧语言）。 */
    private void refreshNamesIfNeeded() {
        String code = Lang.languageCode();
        if (code.equals(nameLanguage)) {
            return;
        }
        nameLanguage = code;
        for (GNode node : nodes) {
            node.displayName = ItemKeyMapper.displayName(node.key);
            node.initial = node.key.isBlank() ? "?" : shortInitial(node.displayName);
            node.label = null;
        }
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
                    (long) Math.ceil(rate * recipe.time()));
            node.displayName = ItemKeyMapper.displayName(recipe.equType());
            node.initial = node.key.isBlank() ? "?" : shortInitial(node.displayName);
            node.hasIcon = !node.key.isBlank() && !ItemKeyMapper.stack(node.key).isEmpty();
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
        for (GEdge edge : edges) {
            edge.reciprocal = hasReverse(edge);
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
            node.displayName = ItemKeyMapper.displayName(key);
            node.initial = shortInitial(node.displayName);
            node.hasIcon = !ItemKeyMapper.stack(key).isEmpty()
                    || IngredientKey.isIngredientKey(key);
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
        // 圆盘最大半径；只作为外边界，不再作为“必须贴近”的圆环。
        double targetRadius = Math.max(95, 42 + Math.sqrt(nodes.size()) * 30.0);
        for (int i = 0; i < nodes.size(); i++) {
            // 均匀圆盘采样：sqrt(u) 保证面积均匀，中心也会有节点。
            double angle = 2 * Math.PI * random.nextDouble();
            double r = targetRadius * Math.sqrt(random.nextDouble());
            nodes.get(i).x = Math.cos(angle) * r;
            nodes.get(i).y = Math.sin(angle) * r;
        }

        // 势能最小化：
        // 1) 节点斥力 + 最短距离约束；2) 边弹簧（固定自然长度）；
        // 3) 向心/圆环势能；4) 碰撞松弛；5) 交叉/穿节点的局部能量搜索。
        double k = 52.0;
        double restLength = edgeNaturalLength;
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
                // 向心势能保持圆盘填充；碰撞力负责把内部节点推开。
                dx[i] -= node.x / dist * dist * 0.045;
                dy[i] -= node.y / dist * dist * 0.045;
                // 只在超出最大半径时施加向内边界力；内部不再有径向目标。
                if (dist > targetRadius) {
                    double over = (dist - targetRadius) * 0.14;
                    dx[i] -= node.x / dist * over;
                    dy[i] -= node.y / dist * over;
                }
                double length = Math.max(1.0, Math.hypot(dx[i], dy[i]));
                double step = Math.min(length, temperature);
                node.x += dx[i] / length * step;
                node.y += dy[i] / length * step;
            }
        }

        collisionRelax();
        minimizeCrossings(targetRadius);
        collisionRelax();
        // pyvis 的 forceAtlas2 只负责把图铺开，不能保证边不交叉。
        // 这里再做一次全局交叉能量退火，允许节点跳出单点局部最优。
        optimizeGlobalCrossings(targetRadius);
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

    private double minDistance(GNode a, GNode b) {
        return (a.recipe ? RECIPE : MATERIAL) / 2.0
                + (b.recipe ? RECIPE : MATERIAL) / 2.0 + nodeGap;
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
        int passes = edges.size() <= 180 ? 6 : 3;
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
        // 向心项，不把节点吸到环上；超出圆盘才额外惩罚。
        cost += dist * dist * 0.0012;
        if (dist > targetRadius) {
            cost += (dist - targetRadius) * (dist - targetRadius) * 0.02;
        }

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
                boolean crossing = segmentsIntersect(ax, ay, bx, by,
                        other.from.x, other.from.y, other.to.x, other.to.y);
                if (crossing) {
                    cost += 6000.0;
                } else {
                    // 不交叉但几乎平行/重叠的箭头也要重罚，避免视觉上叠成一条。
                    double gap = segmentDistance(ax, ay, bx, by,
                            other.from.x, other.from.y, other.to.x, other.to.y);
                    if (gap < 14.0) {
                        cost += (14.0 - gap) * 180.0;
                    }
                }
            }
            for (GNode check : nodes) {
                if (check == node || check == edge.from || check == edge.to) {
                    continue;
                }
                if (pointSegmentDistance(check.x, check.y, ax, ay, bx, by) < 14.0) {
                    cost += 1500.0;
                }
            }
        }
        return cost;
    }

    /**
     * 全局布局能量：边交叉、近平行重叠、边穿节点、节点重叠、边自然长度、圆盘向心。
     * 与 localCost 不同，这里每次评估整张图，用于跳出单节点局部最优。
     */
    private double totalLayoutCost(double targetRadius) {
        double cost = 0.0;
        for (GNode node : nodes) {
            double dist = Math.hypot(node.x, node.y);
            cost += dist * dist * 0.0012;
            if (dist > targetRadius) {
                double over = dist - targetRadius;
                cost += over * over * 0.02;
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            GNode a = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                GNode b = nodes.get(j);
                double d = Math.hypot(a.x - b.x, a.y - b.y);
                double min = minDistance(a, b);
                if (d < min) {
                    double gap = min - d;
                    cost += gap * gap * 12.0;
                }
            }
        }
        for (GEdge edge : edges) {
            double length = Math.hypot(edge.to.x - edge.from.x,
                    edge.to.y - edge.from.y);
            double lengthGap = length - edgeNaturalLength;
            cost += lengthGap * lengthGap * 0.001;
        }
        for (int i = 0; i < edges.size(); i++) {
            GEdge a = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                GEdge b = edges.get(j);
                if (a.from == b.from || a.from == b.to
                        || a.to == b.from || a.to == b.to) {
                    continue;
                }
                if (segmentsIntersect(a.from.x, a.from.y, a.to.x, a.to.y,
                        b.from.x, b.from.y, b.to.x, b.to.y)) {
                    cost += 6000.0;
                } else {
                    double gap = segmentDistance(a.from.x, a.from.y, a.to.x, a.to.y,
                            b.from.x, b.from.y, b.to.x, b.to.y);
                    if (gap < 14.0) {
                        cost += (14.0 - gap) * 180.0;
                    }
                }
            }
            for (GNode node : nodes) {
                if (node == a.from || node == a.to) {
                    continue;
                }
                if (pointSegmentDistance(node.x, node.y,
                        a.from.x, a.from.y, a.to.x, a.to.y) < 14.0) {
                    cost += 1500.0;
                }
            }
        }
        return cost;
    }

    /**
     * 保留圆盘视觉，但把全局退火改成“单节点增量能量”：
     * 移动一个节点只重算与它相关的边/节点，不再每次扫描整张图。
     */
    private void optimizeGlobalCrossings(double targetRadius) {
        if (nodes.size() < 4 || edges.size() < 2 || edges.size() > 240) {
            return;
        }
        Random random = new Random(0x0C0FFEEL);
        int iterations = edges.size() <= 120 ? 900 : 450;
        double temperature = 2500.0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            GNode node = nodes.get(random.nextInt(nodes.size()));
            double oldX = node.x;
            double oldY = node.y;
            double before = localNodeCost(node, targetRadius);
            if (random.nextDouble() < 0.12) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = targetRadius * Math.sqrt(random.nextDouble());
                node.x = Math.cos(angle) * radius;
                node.y = Math.sin(angle) * radius;
            } else {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = 8.0 + random.nextDouble() * 52.0;
                node.x += Math.cos(angle) * radius;
                node.y += Math.sin(angle) * radius;
            }
            double after = localNodeCost(node, targetRadius);
            double delta = after - before;
            boolean accept = delta <= 0.0
                    || random.nextDouble() < Math.exp(-delta / temperature);
            if (!accept) {
                node.x = oldX;
                node.y = oldY;
            }
            temperature = Math.max(2.0, temperature * 0.994);
        }
    }

    /** 只计算与指定节点位置相关的能量，避免全局 O(E²) 重算。 */
    private double localNodeCost(GNode node, double targetRadius) {
        double cost = 0.0;
        double dist = Math.max(1.0, Math.hypot(node.x, node.y));
        cost += dist * dist * 0.0012;
        if (dist > targetRadius) {
            cost += (dist - targetRadius) * (dist - targetRadius) * 0.02;
        }
        for (GNode other : nodes) {
            if (other == node) {
                continue;
            }
            double gap = minDistance(node, other)
                    - Math.hypot(node.x - other.x, node.y - other.y);
            if (gap > 0) {
                cost += gap * gap * 12.0;
            }
        }
        for (GEdge edge : edges) {
            boolean incident = edge.from == node || edge.to == node;
            if (!incident) {
                if (pointSegmentDistance(node.x, node.y,
                        edge.from.x, edge.from.y, edge.to.x, edge.to.y) < 14.0) {
                    cost += 1500.0;
                }
                continue;
            }

            double length = Math.hypot(edge.to.x - edge.from.x,
                    edge.to.y - edge.from.y);
            double lengthGap = length - edgeNaturalLength;
            cost += lengthGap * lengthGap * 0.001;

            for (GNode check : nodes) {
                if (check == edge.from || check == edge.to) {
                    continue;
                }
                if (pointSegmentDistance(check.x, check.y,
                        edge.from.x, edge.from.y, edge.to.x, edge.to.y) < 14.0) {
                    cost += 1500.0;
                }
            }

            for (GEdge other : edges) {
                if (other == edge || other.from == edge.from || other.from == edge.to
                        || other.to == edge.from || other.to == edge.to) {
                    continue;
                }
                if (segmentsIntersect(edge.from.x, edge.from.y,
                        edge.to.x, edge.to.y, other.from.x, other.from.y,
                        other.to.x, other.to.y)) {
                    cost += 6000.0;
                } else {
                    double gap = segmentDistance(edge.from.x, edge.from.y,
                            edge.to.x, edge.to.y, other.from.x, other.from.y,
                            other.to.x, other.to.y);
                    if (gap < 14.0) {
                        cost += (14.0 - gap) * 180.0;
                    }
                }
            }
        }
        return cost;
    }

    /**
     * 两两交换位置的全局下降：解决“两个节点交换后才不交叉”的局部最优。
     * 只在节点/边数较少时启用，避免初始化过慢。
     */
    private void refineBySwaps(double targetRadius) {
        if (nodes.size() > 60 || edges.size() > 160) {
            return;
        }
        double current = totalLayoutCost(targetRadius);
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    GNode a = nodes.get(i);
                    GNode b = nodes.get(j);
                    double ax = a.x;
                    double ay = a.y;
                    a.x = b.x;
                    a.y = b.y;
                    b.x = ax;
                    b.y = ay;
                    double next = totalLayoutCost(targetRadius);
                    if (next + 1e-6 < current) {
                        current = next;
                        changed = true;
                    } else {
                        b.x = a.x;
                        b.y = a.y;
                        a.x = ax;
                        a.y = ay;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    private static double segmentDistance(double ax, double ay, double bx, double by,
                                          double cx, double cy, double dx, double dy) {
        return Math.min(
                Math.min(pointSegmentDistance(ax, ay, cx, cy, dx, dy),
                        pointSegmentDistance(bx, by, cx, cy, dx, dy)),
                Math.min(pointSegmentDistance(cx, cy, ax, ay, bx, by),
                        pointSegmentDistance(dx, dy, ax, ay, bx, by)));
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
                double rest = edgeNaturalLength;
                move += (rest - dist) * 0.075;
            }
            move = Math.max(-6.0, Math.min(6.0, move));
            other.x += ux * move;
            other.y += uy * move;
        }
        // 3 层内（无论进出方向）的节点：相连边过长时把两端拉近。
        pullLinkedNeighborhood(dragged);
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

    /**
     * 从被拖节点出发做无向 BFS，收集 3 层内节点；
     * 对其中过长的边施加拉近力，避免拖动时把间接相连的箭头拉得过长。
     */
    private void pullLinkedNeighborhood(GNode dragged) {
        Map<GNode, Integer> depth = new HashMap<>();
        depth.put(dragged, 0);
        List<GNode> frontier = new ArrayList<>();
        frontier.add(dragged);
        for (int level = 1; level <= 3 && !frontier.isEmpty(); level++) {
            List<GNode> next = new ArrayList<>();
            for (GNode current : frontier) {
                for (GEdge edge : edges) {
                    GNode other = edge.from == current ? edge.to
                            : edge.to == current ? edge.from : null;
                    if (other != null && !depth.containsKey(other)) {
                        depth.put(other, level);
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }

        double maxLength = edgeNaturalLength + nodeGap;
        for (GEdge edge : edges) {
            if (!depth.containsKey(edge.from) || !depth.containsKey(edge.to)) {
                continue;
            }
            GNode a = edge.from;
            GNode b = edge.to;
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dist = Math.max(0.001, Math.hypot(dx, dy));
            if (dist <= maxLength) {
                continue;
            }
            double min = minDistance(a, b);
            // 超过两倍自然长度视为严重拉长：取消 4px/帧限速，
            // 按超出量比例快速拉回。
            boolean extreme = dist > edgeNaturalLength * 2.0;
            double pull = extreme
                    ? (dist - maxLength) * 0.5
                    : Math.min(4.0, (dist - maxLength) * 0.12);
            pull = Math.min(pull, Math.max(0.0, dist - min));
            if (pull <= 0.0) {
                continue;
            }
            double ux = dx / dist;
            double uy = dy / dist;
            boolean aAnchored = a == dragged;
            boolean bAnchored = b == dragged;
            if (aAnchored) {
                b.x -= ux * pull;
                b.y -= uy * pull;
            } else if (bAnchored) {
                a.x += ux * pull;
                a.y += uy * pull;
            } else {
                a.x += ux * pull * 0.5;
                a.y += uy * pull * 0.5;
                b.x -= ux * pull * 0.5;
                b.y -= uy * pull * 0.5;
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
                double spring = (dist - edgeNaturalLength) * 0.025;
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
        GraphAnimationConfig.Settings settings = GraphAnimationConfig.get();
        autoStepSeconds = 1.0 / settings.stepsPerSecond();
        autoDurationNanos = (long) (settings.durationSeconds() * 1_000_000_000L);
        autoMaxMove = settings.stepDistance();
        autoAnchor = chooseAutoAnchor();
        autoSettling = true;
        autoSettleEndNanos = now + autoDurationNanos;
        autoSettleLastNanos = now;
        autoSettleAccumulator = 0.0;
        autoSettleStepCounter = 0;
    }

    private void updateAutoSettle(long now) {
        if (!autoSettling) {
            return;
        }
        GraphAnimationConfig.Settings live = GraphAnimationConfig.get();
        autoStepSeconds = 1.0 / live.stepsPerSecond();
        autoMaxMove = live.stepDistance();
        double dt = Math.max(0.0, Math.min(0.05,
                (now - autoSettleLastNanos) / 1_000_000_000.0));
        autoSettleLastNanos = now;
        autoSettleAccumulator += dt;
        int guard = 0;
        while (autoSettleAccumulator >= autoStepSeconds
                && now < autoSettleEndNanos && guard < 6) {
            autoSettleAccumulator -= autoStepSeconds;
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
        settleStep(false, autoMaxMove);
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
        refreshNamesIfNeeded();
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
            graphics.drawString(font, Lang.t("没有有效配方可绘制"), areaX + 8, areaY + 24,
                    AeStyle.MUTED, false);
            graphics.disableScissor();
            return;
        }

        double viewMinX = (-panX) / zoom - 120;
        double viewMaxX = (areaW - panX) / zoom + 120;
        double viewMinY = (-panY) / zoom - 120;
        double viewMaxY = (areaH - panY) / zoom + 120;
        // 节点标签始终显示（小缩放由 drawCenteredLabel 兜底字号）；
        // 图标按各自阈值退回首字母。
        boolean showEdgeLabels = zoom >= 0.33;
        boolean showRecipeIcons = zoom > RECIPE_ICON_ZOOM;
        boolean showMaterialIcons = zoom >= MATERIAL_ICON_ZOOM;

        graphics.pose().pushPose();
        graphics.pose().translate(areaX + panX, areaY + panY, 0);
        graphics.pose().scale((float) zoom, (float) zoom, 1.0f);
        drawEdges(graphics, font, viewMinX, viewMaxX, viewMinY, viewMaxY, showEdgeLabels);
        for (GNode node : nodes) {
            if (node.x < viewMinX || node.x > viewMaxX
                    || node.y < viewMinY || node.y > viewMaxY) {
                continue;
            }
            drawNode(graphics, font, node, showRecipeIcons, showMaterialIcons);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
        // 节点图标走独立缓冲；先 flush 再画图例，避免图标盖住图例。
        graphics.flush();

        drawLegend(graphics, font, areaX, areaY);
        graphics.drawString(font, Lang.t("拖动平移 · 滚轮缩放 · 流量单位 /t"),
                areaX + 4, areaY + areaH - 12, AeStyle.MUTED, false);
        drawCameraHud(graphics, font, areaX, areaY, areaW);
    }

    /** 实时显示缩放比例和当前可见世界范围。 */
    private void drawCameraHud(GuiGraphics graphics, Font font,
                               int areaX, int areaY, int areaW) {
        String zoomText = Math.round(zoom * 100.0) + "%";
        long viewWidth = Math.round(areaW / zoom);
        long viewHeight = Math.round(lastAreaH / zoom);
        String text = Lang.t("缩放 %s · 视野 %s×%s",
                zoomText, viewWidth, viewHeight);
        int x = areaX + areaW - font.width(text) - 6;
        graphics.drawString(font, text, x, areaY + 4, AeStyle.MUTED, false);
    }

    /** 鼠标悬浮：配方/物质/物流详细 tooltip。 */
    public void renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (nodes.isEmpty() || lastAreaW <= 0
                || mouseX < lastAreaX || mouseX >= lastAreaX + lastAreaW
                || mouseY < lastAreaY || mouseY >= lastAreaY + lastAreaH) {
            return;
        }
        refreshNamesIfNeeded();
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
            lines.add(Lang.c(nodeLabel(edge.from) + " → " + nodeLabel(edge.to))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Lang.c("流量: %s /t",
                    TimeExpression.formatEditableNumber(edge.flow)));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private GEdge hoveredEdge(double wx, double wy) {
        GEdge best = null;
        double bestDistance = 7.0;
        for (GEdge edge : edges) {
            double distance;
            EdgeCurve curve = curveFor(edge);
            if (curve != null) {
                distance = Double.MAX_VALUE;
                double prevX = curve.x1;
                double prevY = curve.y1;
                int segments = 14;
                for (int i = 1; i <= segments; i++) {
                    double t = i / (double) segments;
                    double x = curve.pointX(t);
                    double y = curve.pointY(t);
                    distance = Math.min(distance,
                            pointSegmentDistance(wx, wy, prevX, prevY, x, y));
                    prevX = x;
                    prevY = y;
                }
            } else {
                distance = pointSegmentDistance(wx, wy,
                        edge.from.x, edge.from.y, edge.to.x, edge.to.y);
            }
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
        lines.add(Lang.c("R%s · %s", node.recipeId,
                ItemKeyMapper.displayName(recipe.equType())).withStyle(ChatFormatting.BOLD));
        double perSecond = result.recipeRates().get(node.recipeId);
        lines.add(Lang.c("设备并行: %s  设备数: %s",
                        TimeExpression.formatEditableNumber(perSecond * recipe.time()),
                        node.machines)
                .withStyle(ChatFormatting.GRAY));
        double ratePerTick = perSecond / 20.0;
        lines.add(Lang.c("运行速率: %s /t",
                TimeExpression.formatEditableNumber(ratePerTick)));
        lines.add(Lang.c("输入 (/t):").withStyle(ChatFormatting.GREEN));
        for (Map.Entry<String, Double> entry : recipe.sourceList().entrySet()) {
            lines.add(Lang.c("  - %s × %s", ItemKeyMapper.displayName(entry.getKey()),
                    TimeExpression.formatEditableNumber(entry.getValue() * ratePerTick)));
        }
        lines.add(Lang.c("输出 (/t):").withStyle(ChatFormatting.GOLD));
        for (Map.Entry<String, Double> entry : recipe.productList().entrySet()) {
            lines.add(Lang.c("  + %s × %s", ItemKeyMapper.displayName(entry.getKey()),
                    TimeExpression.formatEditableNumber(entry.getValue() * ratePerTick)));
        }
        return lines;
    }

    private List<Component> materialTooltip(GNode node) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(ItemKeyMapper.displayName(node.key))
                .withStyle(ChatFormatting.BOLD));
        lines.add(Lang.c("类别: %s", categoryLabel(node.category))
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
        lines.add(Lang.c("生成: %s /t  消耗: %s /t",
                TimeExpression.formatEditableNumber(produced),
                TimeExpression.formatEditableNumber(consumed)));
        if (node.category == Category.TARGET) {
            Double target = targets.get(node.key);
            if (target != null) {
                lines.add(Lang.c("目标: %s /t",
                                TimeExpression.formatEditableNumber(target))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
        if (node.category == Category.INPUT) {
            Double amount = result.externalInputs().get(node.key);
            if (amount != null) {
                lines.add(Lang.c("外部输入: %s /t",
                                TimeExpression.formatEditableNumber(amount / 20.0))
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        if (node.category == Category.STARTUP) {
            Double amount = result.bootstrapInventory().get(node.key);
            if (amount != null) {
                lines.add(Lang.c("启动库存: %s",
                                TimeExpression.formatEditableNumber(amount))
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (node.category == Category.BYPRODUCT) {
            Double amount = result.finalOutputs().get(node.key);
            if (amount != null) {
                lines.add(Lang.c("副产物: %s /t",
                                TimeExpression.formatEditableNumber(amount / 20.0))
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
        return Lang.t(switch (category) {
            case INPUT -> "外部输入";
            case STARTUP -> "启动库存";
            case BYPRODUCT -> "副产物";
            case TARGET -> "最终目标";
            default -> "中间产物";
        });
    }

    private void drawLegend(GuiGraphics graphics, Font font, int areaX, int areaY) {
        int x = areaX + 4;
        int y = areaY + 4;
        String[] labels = {
                Lang.t("配方"), Lang.t("输入"), Lang.t("中间"),
                Lang.t("启动"), Lang.t("副产"), Lang.t("目标")
        };
        int[] colors = {
                RECIPE_FILL, INPUT, INTERMEDIATE, STARTUP, BYPRODUCT, TARGET
        };
        int gap = 8;
        int total = 8;
        for (String label : labels) {
            total += font.width(label) + gap;
        }
        // 深色半透明底；提高 z，确保节点/物品图标在其下方。
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(x, y, x + total, y + 17, 0xC02E2E2E);
        int cursor = x + 4;
        for (int i = 0; i < labels.length; i++) {
            int width = font.width(labels[i]);
            graphics.drawString(font, labels[i], cursor, y + 2, 0xFFFFFFFF, false);
            // 与节点一致的色条画在文字下方。
            graphics.fill(cursor, y + 12, cursor + width, y + 15, colors[i]);
            cursor += width + gap;
        }
        graphics.pose().popPose();
        graphics.flush();
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
        EdgeCurve curve = curveFor(edge);
        if (curve != null) {
            drawCurvedEdge(consumer, matrix, edge, curve);
            return;
        }
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

    private void drawCurvedEdge(VertexConsumer consumer, Matrix4f matrix,
                                GEdge edge, EdgeCurve curve) {
        int thickness = 1 + Math.min(4, (int) Math.sqrt(Math.max(0.0, edge.flow)) * 2);
        int segments = 14;
        double prevX = curve.x1;
        double prevY = curve.y1;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = curve.pointX(t);
            double y = curve.pointY(t);
            addLine(consumer, matrix, prevX, prevY, x, y, thickness, EDGE);
            prevX = x;
            prevY = y;
        }
        // 弧线末端的切线方向，用于箭头。
        double tx = curve.x2 - curve.cx;
        double ty = curve.y2 - curve.cy;
        double len = Math.max(1e-6, Math.hypot(tx, ty));
        tx /= len;
        ty /= len;
        double backX = curve.x2 - tx * 7;
        double backY = curve.y2 - ty * 7;
        double px = -ty;
        double py = tx;
        addLine(consumer, matrix, curve.x2, curve.y2,
                backX + px * 3.5, backY + py * 3.5, thickness + 1, EDGE);
        addLine(consumer, matrix, curve.x2, curve.y2,
                backX - px * 3.5, backY - py * 3.5, thickness + 1, EDGE);
    }

    /**
     * 只有“A→B 且 B→A”同时存在时才返回弧线；普通边返回 null。
     * 弧线按节点 index 选定统一的基准方向，使两条反向边分别弯向两侧。
     */
    private EdgeCurve curveFor(GEdge edge) {
        if (edge.from == edge.to || !edge.reciprocal) {
            return null;
        }
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

        GNode canonicalFrom = edge.from.index <= edge.to.index ? edge.from : edge.to;
        GNode canonicalTo = canonicalFrom == edge.from ? edge.to : edge.from;
        double cdx = canonicalTo.x - canonicalFrom.x;
        double cdy = canonicalTo.y - canonicalFrom.y;
        double cdist = Math.max(1.0, Math.hypot(cdx, cdy));
        double perpX = -cdy / cdist;
        double perpY = cdx / cdist;
        double side = edge.from == canonicalFrom ? 1.0 : -1.0;
        double offset = Math.min(38.0, Math.max(16.0, dist * 0.18));
        double cx = (x1 + x2) / 2 + perpX * offset * side;
        double cy = (y1 + y2) / 2 + perpY * offset * side;
        return new EdgeCurve(x1, y1, x2, y2, cx, cy);
    }

    private boolean hasReverse(GEdge edge) {
        for (GEdge other : edges) {
            if (other.from == edge.to && other.to == edge.from) {
                return true;
            }
        }
        return false;
    }

    private void drawEdgeLabel(GuiGraphics graphics, Font font, GEdge edge) {
        EdgeCurve curve = curveFor(edge);
        double labelCenterX;
        double labelCenterY;
        if (curve != null) {
            labelCenterX = curve.pointX(0.5);
            labelCenterY = curve.pointY(0.5);
        } else {
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
            labelCenterX = (x1 + x2) / 2 - uy * 4;
            labelCenterY = (y1 + y2) / 2 + ux * 4;
        }

        String label = edge.label;
        int width = font.width(label);
        graphics.drawString(font, label,
                (int) (labelCenterX - width / 2.0),
                (int) (labelCenterY - 4),
                0xFF666666, false);
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
                          boolean showRecipeIcons, boolean showMaterialIcons) {
        if (node.recipe) {
            drawRecipeNode(graphics, font, node, showRecipeIcons);
        } else {
            drawMaterialNode(graphics, font, node, showMaterialIcons);
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
                                boolean showIcons) {
        int x = (int) Math.round(node.x) - RECIPE / 2;
        int y = (int) Math.round(node.y) - RECIPE / 2;
        graphics.fill(x - 1, y - 1, x + RECIPE + 1, y + RECIPE + 1, BORDER);
        graphics.fill(x, y, x + RECIPE, y + RECIPE, RECIPE_FILL);
        graphics.fill(x, y, x + RECIPE, y + 1, 0x40FFFFFF);
        String id = "R" + node.recipeId;
        graphics.drawString(font, id, x + 3, y + 3, 0xFF3A2A10, false);
        String count = "x" + TimeExpression.formatCompact((double) node.machines);
        graphics.drawString(font, count,
                x + RECIPE / 2 - font.width(count) / 2,
                y + RECIPE - 10, 0xFF3A2A10, false);

        if (showIcons && node.hasIcon) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 12, y + 12, null);
        } else {
            String initial = node.initial;
            graphics.drawString(font, initial, x + RECIPE / 2 - font.width(initial) / 2,
                    y + 16, 0xFF3A2A10, false);
        }
        drawCenteredLabel(graphics, font, node, node.x, node.y + RECIPE / 2.0 + 3);
    }

    private void drawMaterialNode(GuiGraphics graphics, Font font, GNode node,
                                  boolean showIcons) {
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

        if (showIcons && node.hasIcon) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 2, y + 2, null);
        } else {
            String initial = node.initial;
            graphics.drawString(font, initial,
                    x + MATERIAL / 2 - font.width(initial) / 2, y + 7,
                    0xFF202020, false);
        }
        drawCenteredLabel(graphics, font, node, node.x, node.y + MATERIAL / 2.0 + 3);
    }

    private void drawCenteredLabel(GuiGraphics graphics, Font font, GNode node,
                                   double centerX, double y) {
        if (node.label == null) {
            node.label = font.plainSubstrByWidth(node.displayName, 84);
        }
        // 整张图按 zoom 缩放；这里反向补偿，使屏幕上字号不小于 50%（缩得再小也不再变小）。
        float textScale = (float) Math.max(1.0, MIN_LABEL_TEXT_SCALE / zoom);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0);
        pose.scale(textScale, textScale, 1.0f);
        graphics.drawString(font, node.label, Math.round(-font.width(node.label) / 2.0f), 0,
                AeStyle.TEXT, false);
        pose.popPose();
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
        if (radius <= 0 || radius * 2 >= height) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        // 中间整段一次 fill；上下只画圆角行，视觉结果与原逐行绘制一致。
        int bodyTop = y + radius;
        int bodyBottom = y + height - radius;
        if (bodyBottom > bodyTop) {
            graphics.fill(x, bodyTop, x + width, bodyBottom, color);
        }
        for (int row = 0; row < radius; row++) {
            int inset = radius - row;
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
        for (int row = height - radius; row < height; row++) {
            int inset = row - (height - radius) + 1;
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }
}
