package com.jessetech.recipebalancer.mc.ui.graph;

import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.config.GraphLayoutConfig;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5 求解流程图：
 * <ul>
 *   <li>橙色方形 = 配方（设备图标/首字母）；</li>
 *   <li>绿/蓝/红/黄/紫圆角矩形 = 外部输入/中间产物/启动库存/副产物/最终目标；</li>
 *   <li>箭头按每 tick 物流绘制，线宽随流量变化；</li>
 *   <li>分层布局（列 = 工艺深度，列内重心排序），回边画成弧线；支持拖动平移和滚轮缩放。</li>
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
    /** 物质配色：深色调，浅灰底上区分度够；同一种物质所有边颜色一致。 */
    private static final int[] EDGE_PALETTE = {
            0xFF2F5D8A, 0xFF1F6F6B, 0xFF3A6B35, 0xFF6E6B1F,
            0xFF7A4B2A, 0xFF8A3A3A, 0xFF5B3F7A, 0xFF2F6E8A,
            0xFF6E5A2A, 0xFF4A4A5A, 0xFF276B4F, 0xFF7A3F5B
    };
    /** 物质图标显示阈值：缩放低于它时退回首字母，避免糊成一团。 */
    private static final double MATERIAL_ICON_ZOOM = 0.37;
    /** 配方图标显示阈值。 */
    private static final double RECIPE_ICON_ZOOM = 0.28;
    /** 节点标签的最小屏幕字号（相对原始大小）；缩得再小也只缩到 50%。 */
    private static final float MIN_LABEL_TEXT_SCALE = 0.5f;
    /** 同列节点间距（设置里可调）；分层布局下用于列内行距。 */
    private double nodeGap = 38.0;
    /** 列间距基准；分层布局下用于列与列之间的距离。 */
    private double edgeNaturalLength = 84.0;

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

    private GNode draggingNode;
    private double dragOffsetX;
    private double dragOffsetY;
    /** 弹性拖拽：受影响的邻居及其跟随权重，以及上一帧的锚点坐标。 */
    private final Map<GNode, Double> elasticWeights = new HashMap<>();
    private double lastDragX;
    private double lastDragY;
    /** 本帧这条边是否压淡（悬停某节点时，无关的边降到很淡）。 */
    private boolean dimEdges;
    /** 上一帧悬停的节点（由 renderTooltip 更新），用于给边做聚焦压暗。 */
    private GNode hoveredNode;
    /** 上一帧悬停的边，用于画黄灰警示描边。 */
    private GEdge hoveredEdgeRef;
    /** 点击选中的节点：高亮常驻，与悬停高亮叠加，同时只允许一个。 */
    private GNode selectedNode;
    /** 当前布局模式与两种模式的坐标缓存（切回来时直接读，不重算）。 */
    private LayoutMode layoutMode = LayoutMode.LAYERED;
    private final Map<GNode, double[]> layeredPositions = new HashMap<>();
    private final Map<GNode, double[]> forcePositions = new HashMap<>();
    /** 悬停描边的两种颜色（工业警示：黄 + 深灰）与动画速度。 */
    private static final int ANT_YELLOW = 0xFFFFD24A;
    private static final int ANT_GRAY = 0xFF4A4A4A;

    /** 连线样式与缩放范围（配置界面可调，每帧刷新一次）。 */
    private double edgeSaturation = 0.5;
    private double edgeOpacity = 1.0;
    private double dimOpacity = 0.1;
    private double forceIdealFactor = 0.3;
    private double minEdgeLength = 96.0;
    private double zoomMin = 0.1;
    private double zoomMax = 3.0;

    /** 压暗无关边：只改透明度，形状和位置不变。 */
    private int shade(int color) {
        return dimEdges ? fade(color, (int) Math.round(255 * dimOpacity)) : color;
    }

    /** 每帧读一次配置快照，配置界面保存后立即生效。 */
    private void refreshStyle() {
        GraphLayoutConfig.Settings settings = GraphLayoutConfig.current();
        nodeGap = settings.nodeGap();
        edgeNaturalLength = settings.edgeLength();
        edgeSaturation = settings.edgeSaturation();
        edgeOpacity = settings.edgeOpacity();
        dimOpacity = settings.dimOpacity();
        forceIdealFactor = settings.forceIdeal();
        minEdgeLength = settings.minEdgeLength();
        zoomMin = settings.zoomMin();
        zoomMax = settings.zoomMax();
    }

    /** 普通连线的基础不透明度。 */
    private int baseAlpha() {
        return (int) Math.round(255 * edgeOpacity);
    }

    /** 该边所属物质的颜色（同一物质恒定）。 */
    private static int edgeColor(GEdge edge) {
        String material = edge.material == null ? "" : edge.material;
        return EDGE_PALETTE[Math.floorMod(material.hashCode(), EDGE_PALETTE.length)];
    }

    /** 同色但指定透明度（0-255）：总线主干用 55%，入靶段与箭头用 75%。 */
    private static int fade(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** 降低饱和度：向同亮度灰混合，强度来自配置（0 = 原色，1 = 纯灰）。 */
    private int desaturate(int color) {
        double keep = 1.0 - Math.max(0.0, Math.min(1.0, edgeSaturation));
        if (keep >= 0.999) {
            return color;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int gray = (int) (r * 0.299 + g * 0.587 + b * 0.114);
        return 0xFF000000
                | ((int) (r * keep + gray * edgeSaturation) << 16)
                | ((int) (g * keep + gray * edgeSaturation) << 8)
                | (int) (b * keep + gray * edgeSaturation);
    }

    /** 压暗成深色，给流量文字用，保证在浅灰底上清晰。 */
    private static int darken(int color) {
        int r = (int) (((color >> 16) & 0xFF) * 0.72);
        int g = (int) (((color >> 8) & 0xFF) * 0.72);
        int b = (int) ((color & 0xFF) * 0.72);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

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
        /** 回边：分层时被打断的环边，画面里从右往左，用弧线绘制。 */
        boolean back;
        /** 总线边在目标边界上的落点偏移：同目标多条边分散开，箭头不会堆在一点。 */
        double portOffsetY;
        /** 总线边竖直段的横向错开量，避免多条竖线重合。 */
        double dropOffset;

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
        GraphLayoutConfig.Settings settings = GraphLayoutConfig.current();
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

    /**
     * 分层布局（工业流程图的标准画法）：
     * <ul>
     *   <li>列 = 物料在工艺链里的深度：外部输入在最左、最终目标在最右；</li>
     *   <li>列内节点用「重心法」多轮排序，压低连线交叉；</li>
     *   <li>DFS 找回边（循环亏损/再循环），回边不参与分层，渲染时画成弧线。</li>
     * </ul>
     *
     * <p>相比原来的力导向布局，分层布局结果稳定、不会因为多个配方共用同种物质而糊成一团。
     */
    private void layout() {
        if (nodes.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).index = i;
        }
        layoutLayered();
        layoutMode = LayoutMode.LAYERED;
        rememberPositions();
        refreshBounds();
    }

    /** 仅计算分层布局的坐标（可在切换布局时复用）。 */
    private void layoutLayered() {
        Map<GNode, List<GEdge>> outgoing = new HashMap<>();
        for (GEdge edge : edges) {
            outgoing.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(edge);
        }
        markBackEdges(outgoing);
        int[] layerOf = assignLayers(outgoing);
        List<List<GNode>> layers = groupByLayer(layerOf);
        orderLayers(layers);
        placeLayers(layers);
    }

    /** 重算内容包围盒，并让下一帧重新适配视野。 */
    private void refreshBounds() {
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
        assignBusPorts();
        cameraReady = false;
    }

    /** 布局模式：默认分层（列=工艺深度），可切换回弹性（力导向）视图。 */
    public enum LayoutMode {
        LAYERED, FORCE
    }

    public LayoutMode layoutMode() {
        return layoutMode;
    }

    /**
     * 切换布局模式：切到弹性时若从未算过才现算一次，之后来回切换都直接读缓存，
     * 拖拽过的位置也会被记住。
     */
    public void toggleLayoutMode() {
        if (nodes.isEmpty()) {
            return;
        }
        rememberPositions();
        layoutMode = layoutMode == LayoutMode.LAYERED ? LayoutMode.FORCE : LayoutMode.LAYERED;
        Map<GNode, double[]> cache = layoutMode == LayoutMode.FORCE
                ? forcePositions : layeredPositions;
        if (cache.isEmpty()) {
            if (layoutMode == LayoutMode.FORCE) {
                layoutForce();
            } else {
                layoutLayered();
            }
        } else {
            applyPositions(cache);
        }
        // 缓存里的坐标可能被拖拽过：恢复后再修一次重叠（最小边长只约束拖拽）
        separateOverlaps(12);
        refreshBounds();
    }

    /** 把当前坐标存进当前模式的缓存（拖拽后的位置也一并保住）。 */
    private void rememberPositions() {
        Map<GNode, double[]> cache = layoutMode == LayoutMode.LAYERED
                ? layeredPositions : forcePositions;
        cache.clear();
        for (GNode node : nodes) {
            cache.put(node, new double[]{node.x, node.y});
        }
    }

    private void applyPositions(Map<GNode, double[]> cache) {
        for (GNode node : nodes) {
            double[] at = cache.get(node);
            if (at != null) {
                node.x = at[0];
                node.y = at[1];
            }
        }
    }

    /**
     * 弹性（力导向）布局：先把节点铺在一个圆盘上，再做「斥力 + 弹簧」迭代。
     * 只在首次从分层切到弹性时执行一次，结果进缓存。
     */
    private void layoutForce() {
        int n = nodes.size();
        // 初始圆盘收紧：只给迭代一个起点，不再决定最终尺度
        double radius = Math.max(110.0, Math.sqrt(n) * 34.0);
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2.0 * i / n;
            nodes.get(i).x = Math.cos(angle) * radius;
            nodes.get(i).y = Math.sin(angle) * radius;
        }
        // 初始化只按 forceIdeal 决定尺度；minEdgeLength 只用于拖拽约束，不参与初始布局
        double ideal = Math.max(40.0, edgeNaturalLength * forceIdealFactor);
        double[] pushX = new double[n];
        double[] pushY = new double[n];
        int iterations = n > 200 ? 140 : 220;
        for (int iter = 0; iter < iterations; iter++) {
            Arrays.fill(pushX, 0.0);
            Arrays.fill(pushY, 0.0);
            for (int i = 0; i < n; i++) {
                GNode a = nodes.get(i);
                for (int j = i + 1; j < n; j++) {
                    GNode b = nodes.get(j);
                    double vx = a.x - b.x;
                    double vy = a.y - b.y;
                    double dist = Math.max(0.01, Math.hypot(vx, vy));
                    double force = ideal * ideal / dist;
                    double ux = vx / dist;
                    double uy = vy / dist;
                    pushX[i] += ux * force;
                    pushY[i] += uy * force;
                    pushX[j] -= ux * force;
                    pushY[j] -= uy * force;
                }
            }
            for (GEdge edge : edges) {
                GNode a = edge.from;
                GNode b = edge.to;
                double vx = a.x - b.x;
                double vy = a.y - b.y;
                double dist = Math.max(0.01, Math.hypot(vx, vy));
                double force = dist * dist / ideal;
                double ux = vx / dist;
                double uy = vy / dist;
                pushX[a.index] -= ux * force;
                pushY[a.index] -= uy * force;
                pushX[b.index] += ux * force;
                pushY[b.index] += uy * force;
            }
            // 位移限幅（退火），避免节点被弹出视野
            double limit = Math.max(2.0, ideal * 0.35 * (1.0 - iter / (double) iterations));
            for (int i = 0; i < n; i++) {
                double len = Math.hypot(pushX[i], pushY[i]);
                if (len < 1e-6) {
                    continue;
                }
                double step = Math.min(limit, len);
                GNode node = nodes.get(i);
                node.x += pushX[i] / len * step;
                node.y += pushY[i] / len * step;
            }
        }
        // 收敛后再推掉残余重叠：只修间距，不改整体形状
        separateOverlaps(24);
    }

    /** 消除节点重叠：按「外接半径 + 间距」互推，斜向贴角也不会叠在一起。 */
    private void separateOverlaps(int iterations) {
        for (int iter = 0; iter < iterations; iter++) {
            boolean moved = false;
            for (int i = 0; i < nodes.size(); i++) {
                GNode a = nodes.get(i);
                for (int j = i + 1; j < nodes.size(); j++) {
                    GNode b = nodes.get(j);
                    double vx = b.x - a.x;
                    double vy = b.y - a.y;
                    double dist = Math.max(0.01, Math.hypot(vx, vy));
                    double min = spacingRadius(a) + spacingRadius(b) + 6.0;
                    if (dist >= min) {
                        continue;
                    }
                    double push = (min - dist) * 0.3;
                    double ux = vx / dist;
                    double uy = vy / dist;
                    a.x -= ux * push;
                    a.y -= uy * push;
                    b.x += ux * push;
                    b.y += uy * push;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
    }

    /** 迭代 DFS 找回边（指向当前栈上节点的边）；回边不参与分层。 */
    private void markBackEdges(Map<GNode, List<GEdge>> outgoing) {
        Map<GNode, Integer> state = new HashMap<>();
        for (GNode start : nodes) {
            if (state.containsKey(start)) {
                continue;
            }
            Deque<GNode> path = new ArrayDeque<>();
            Deque<Integer> cursor = new ArrayDeque<>();
            path.push(start);
            cursor.push(0);
            state.put(start, 1);
            while (!path.isEmpty()) {
                GNode node = path.peek();
                List<GEdge> out = outgoing.getOrDefault(node, List.of());
                int next = cursor.pop();
                if (next >= out.size()) {
                    state.put(node, 2);
                    path.pop();
                    continue;
                }
                cursor.push(next + 1);
                GEdge edge = out.get(next);
                Integer seen = state.get(edge.to);
                if (seen != null && seen == 1) {
                    edge.back = true;
                } else if (seen == null) {
                    state.put(edge.to, 1);
                    path.push(edge.to);
                    cursor.push(0);
                }
            }
        }
    }

    /** 最长路径分层：只走非回边，去掉回边后一定是 DAG。 */
    private int[] assignLayers(Map<GNode, List<GEdge>> outgoing) {
        int[] layer = new int[nodes.size()];
        int[] indegree = new int[nodes.size()];
        for (GEdge edge : edges) {
            if (!edge.back) {
                indegree[edge.to.index]++;
            }
        }
        Deque<GNode> queue = new ArrayDeque<>();
        for (GNode node : nodes) {
            if (indegree[node.index] == 0) {
                queue.add(node);
            }
        }
        while (!queue.isEmpty()) {
            GNode node = queue.poll();
            for (GEdge edge : outgoing.getOrDefault(node, List.of())) {
                if (edge.back) {
                    continue;
                }
                GNode to = edge.to;
                layer[to.index] = Math.max(layer[to.index], layer[node.index] + 1);
                if (--indegree[to.index] == 0) {
                    queue.add(to);
                }
            }
        }
        return layer;
    }

    private List<List<GNode>> groupByLayer(int[] layerOf) {
        int maxLayer = 0;
        for (int value : layerOf) {
            maxLayer = Math.max(maxLayer, value);
        }
        List<List<GNode>> layers = new ArrayList<>();
        for (int i = 0; i <= maxLayer; i++) {
            layers.add(new ArrayList<>());
        }
        for (GNode node : nodes) {
            layers.get(layerOf[node.index]).add(node);
        }
        return layers;
    }

    /** 重心法：反复用相邻层的平均序位给列内节点排序，压低交叉数。 */
    private void orderLayers(List<List<GNode>> layers) {
        if (layers.size() < 2) {
            return;
        }
        Map<GNode, List<GNode>> neighbours = new HashMap<>();
        for (GEdge edge : edges) {
            if (edge.back || edge.from == edge.to) {
                continue;
            }
            neighbours.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(edge.to);
            neighbours.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(edge.from);
        }
        Map<GNode, Double> position = new HashMap<>();
        for (List<GNode> layer : layers) {
            for (int i = 0; i < layer.size(); i++) {
                position.put(layer.get(i), (double) i);
            }
        }
        for (int pass = 0; pass < 8; pass++) {
            if (pass % 2 == 0) {
                for (int i = 1; i < layers.size(); i++) {
                    sortByBarycenter(layers.get(i), neighbours, position);
                }
            } else {
                for (int i = layers.size() - 2; i >= 0; i--) {
                    sortByBarycenter(layers.get(i), neighbours, position);
                }
            }
            for (List<GNode> layer : layers) {
                for (int i = 0; i < layer.size(); i++) {
                    position.put(layer.get(i), (double) i);
                }
            }
        }
    }

    private void sortByBarycenter(List<GNode> layer, Map<GNode, List<GNode>> neighbours,
                                  Map<GNode, Double> position) {
        Map<GNode, Double> score = new HashMap<>();
        for (GNode node : layer) {
            List<GNode> around = neighbours.get(node);
            double own = position.getOrDefault(node, 0.0);
            if (around == null || around.isEmpty()) {
                score.put(node, own);
                continue;
            }
            double sum = 0;
            int count = 0;
            for (GNode other : around) {
                Double at = position.get(other);
                if (at != null) {
                    sum += at;
                    count++;
                }
            }
            score.put(node, count == 0 ? own : sum / count);
        }
        layer.sort((a, b) -> {
            int cmp = Double.compare(score.get(a), score.get(b));
            return cmp != 0 ? cmp : Integer.compare(a.index, b.index);
        });
    }

    /** 列间距固定，列内自上而下堆叠并整体垂直居中。 */
    private void placeLayers(List<List<GNode>> layers) {
        double columnGap = Math.max(72.0, edgeNaturalLength * 0.82);
        double rowGap = Math.max(16.0, nodeGap * 0.6);
        double cursor = 0;
        for (List<GNode> layer : layers) {
            double maxWidth = 0;
            for (GNode node : layer) {
                maxWidth = Math.max(maxWidth, node.recipe ? RECIPE : MATERIAL);
            }
            double centerX = cursor + maxWidth / 2.0;
            double y = 0;
            for (int i = 0; i < layer.size(); i++) {
                GNode node = layer.get(i);
                double half = (node.recipe ? RECIPE : MATERIAL) / 2.0;
                if (i > 0) {
                    y += rowGap;
                }
                y += half;
                node.x = centerX;
                node.y = y;
                y += half;
            }
            double shift = y / 2.0;
            for (GNode node : layer) {
                node.y -= shift;
            }
            cursor += maxWidth + columnGap;
        }
    }













    /** 鼠标命中节点；没有则返回 null。 */
    public boolean beginNodeDrag(double screenX, double screenY) {
        if (lastAreaW <= 0) {
            return false;
        }
        GNode hit = nodeAt(screenX, screenY);
        if (hit == null) {
            return false;
        }
        double worldX = (screenX - lastAreaX - panX) / zoom;
        double worldY = (screenY - lastAreaY - panY) / zoom;
        draggingNode = hit;
        dragOffsetX = worldX - hit.x;
        dragOffsetY = worldY - hit.y;
        lastDragX = hit.x;
        lastDragY = hit.y;
        // 弹性布局下拖动会带着邻居一起走；分层布局保持静态
        if (layoutMode == LayoutMode.FORCE) {
            collectElasticNeighbors(hit);
        } else {
            elasticWeights.clear();
        }
        // 左键点中新节点即选中（替换旧的）；拖空白处不会取消选中
        selectedNode = hit;
        return true;
    }

    /** 右键：点空白处才取消选中；点在节点上保留选中。 */
    public void clearSelectionOnRightClick(double screenX, double screenY) {
        if (nodeAt(screenX, screenY) == null) {
            selectedNode = null;
        }
    }

    /** 屏幕坐标下的节点命中（没有则返回 null）。 */
    private GNode nodeAt(double screenX, double screenY) {
        if (lastAreaW <= 0) {
            return null;
        }
        double worldX = (screenX - lastAreaX - panX) / zoom;
        double worldY = (screenY - lastAreaY - panY) / zoom;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            GNode node = nodes.get(i);
            double half = (node.recipe ? RECIPE : MATERIAL) / 2.0 + 3;
            if (Math.abs(worldX - node.x) <= half && Math.abs(worldY - node.y) <= half) {
                return node;
            }
        }
        return null;
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
        // 两种布局都限制拖拽：不能把节点拖到别人身上，也不允许连着两点贴太近
        resolveDragConstraints(3);
        if (layoutMode == LayoutMode.FORCE && !elasticWeights.isEmpty()) {
            // 邻居按权重跟随位移，再松弛两步，拖动时是「牵着走」的弹性手感
            double dx = draggingNode.x - lastDragX;
            double dy = draggingNode.y - lastDragY;
            if (Math.abs(dx) + Math.abs(dy) > 0.01) {
                pullNeighbours(dx, dy);
                relaxElastic(2);
            }
            lastDragX = draggingNode.x;
            lastDragY = draggingNode.y;
        }
    }

    public void endNodeDrag() {
        if (layoutMode == LayoutMode.FORCE && !elasticWeights.isEmpty()) {
            // 松手后多松弛几轮，邻居自然回到平衡位置（分层布局保持静态，不回弹）
            relaxElastic(10);
        }
        // 两种布局都在落点再做一次约束，避免压在别的节点上
        resolveDragConstraints(12);
        elasticWeights.clear();
        draggingNode = null;
        // 位置变了，总线边的端口重新分配，避免箭头又挤到一起
        assignBusPorts();
    }

    /** 收集被拖节点 2 跳内的邻居：1 跳跟随 55%，2 跳 25%。 */
    private void collectElasticNeighbors(GNode anchor) {
        elasticWeights.clear();
        Map<GNode, List<GNode>> links = adjacency();
        Map<GNode, Integer> depth = new HashMap<>();
        Deque<GNode> queue = new ArrayDeque<>();
        depth.put(anchor, 0);
        queue.add(anchor);
        while (!queue.isEmpty()) {
            GNode node = queue.poll();
            int current = depth.get(node);
            if (current >= 2) {
                continue;
            }
            for (GNode other : links.getOrDefault(node, List.of())) {
                if (depth.containsKey(other)) {
                    continue;
                }
                depth.put(other, current + 1);
                queue.add(other);
            }
        }
        for (Map.Entry<GNode, Integer> entry : depth.entrySet()) {
            if (entry.getKey() == anchor) {
                continue;
            }
            elasticWeights.put(entry.getKey(), entry.getValue() == 1 ? 0.55 : 0.25);
        }
    }

    private Map<GNode, List<GNode>> adjacency() {
        Map<GNode, List<GNode>> links = new HashMap<>();
        for (GEdge edge : edges) {
            links.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(edge.to);
            links.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(edge.from);
        }
        return links;
    }

    /** 邻居跟随被拖节点的位移（按跳数衰减）。 */
    private void pullNeighbours(double dx, double dy) {
        for (Map.Entry<GNode, Double> entry : elasticWeights.entrySet()) {
            double weight = entry.getValue();
            GNode node = entry.getKey();
            node.x += dx * weight;
            node.y += dy * weight;
        }
    }

    /** 弹性松弛：邻居被弹簧拉向相连节点，同时与其它节点互斥，避免重叠。 */
    private void relaxElastic(int iterations) {
        if (elasticWeights.isEmpty()) {
            return;
        }
        // 与 layoutForce 使用同一个理想边长，拖拽手感与整体尺度一致
        double ideal = Math.max(minEdgeLength, edgeNaturalLength * forceIdealFactor);
        Map<GNode, List<GNode>> links = adjacency();
        for (int iter = 0; iter < iterations; iter++) {
            for (Map.Entry<GNode, Double> entry : elasticWeights.entrySet()) {
                GNode node = entry.getKey();
                double weight = entry.getValue();
                double fx = 0;
                double fy = 0;
                for (GNode other : links.getOrDefault(node, List.of())) {
                    double vx = other.x - node.x;
                    double vy = other.y - node.y;
                    double dist = Math.max(1.0, Math.hypot(vx, vy));
                    double force = (dist - ideal) * 0.28;
                    fx += vx / dist * force;
                    fy += vy / dist * force;
                }
                for (GNode other : nodes) {
                    if (other == node) {
                        continue;
                    }
                    double vx = node.x - other.x;
                    double vy = node.y - other.y;
                    double dist = Math.max(1.0, Math.hypot(vx, vy));
                    double min = spacingRadius(node) + spacingRadius(other) + 6.0;
                    if (dist >= min) {
                        continue;
                    }
                    double force = (min - dist) * 0.6;
                    fx += vx / dist * force;
                    fy += vy / dist * force;
                }
                double len = Math.hypot(fx, fy);
                if (len < 1e-6) {
                    continue;
                }
                double step = Math.min(2.0 + 4.0 * weight, len);
                node.x += fx / len * step;
                node.y += fy / len * step;
            }
        }
    }

    /** 布局用的外接半径：方形/八边形节点按对角半径算，避免斜向贴角。 */
    private static double spacingRadius(GNode node) {
        return (node.recipe ? RECIPE : MATERIAL) / 2.0 * 1.42;
    }

    /**
     * 拖拽时的约束：被拖节点不能压到别的节点上，与它相连的节点也不能近于最小边长。
     *
     * <p>重叠时优先把被拖节点推出（它会稍微落后于光标，手感上像被挡住），
     * 相连边长不足时两端各让一半，被拖节点只让 35%，避免和鼠标「打架」。
     */
    private void resolveDragConstraints(int iterations) {
        GNode anchor = draggingNode;
        if (anchor == null || nodes.size() < 2) {
            return;
        }
        for (int iter = 0; iter < iterations; iter++) {
            boolean moved = false;
            for (GNode other : nodes) {
                if (other == anchor) {
                    continue;
                }
                double min = spacingRadius(anchor) + spacingRadius(other) + 6.0;
                double vx = anchor.x - other.x;
                double vy = anchor.y - other.y;
                double dist = Math.hypot(vx, vy);
                if (dist >= min) {
                    continue;
                }
                double ux = dist > 1e-6 ? vx / dist : 1.0;
                double uy = dist > 1e-6 ? vy / dist : 0.0;
                double push = min - dist;
                anchor.x += ux * push;
                anchor.y += uy * push;
                moved = true;
            }
            for (GEdge edge : edges) {
                GNode other;
                if (edge.from == anchor) {
                    other = edge.to;
                } else if (edge.to == anchor) {
                    other = edge.from;
                } else {
                    continue;
                }
                if (other == anchor) {
                    continue;
                }
                double min = Math.max(minEdgeLength,
                        spacingRadius(anchor) + spacingRadius(other) + 6.0);
                double vx = other.x - anchor.x;
                double vy = other.y - anchor.y;
                double dist = Math.hypot(vx, vy);
                if (dist >= min) {
                    continue;
                }
                double ux = dist > 1e-6 ? vx / dist : 1.0;
                double uy = dist > 1e-6 ? vy / dist : 0.0;
                double push = (min - dist) * 0.5;
                other.x += ux * push;
                other.y += uy * push;
                anchor.x -= ux * push * 0.35;
                anchor.y -= uy * push * 0.35;
                moved = true;
            }
            if (!moved) {
                break;
            }
        }
    }














    public void resetCamera(int areaW, int areaH) {
        if (nodes.isEmpty()) {
            return;
        }
        cameraAreaW = areaW;
        cameraAreaH = areaH;
        // 自动适配视野，上下限来自配置（zoomMin / zoomMax）
        double fit = Math.min((areaW - 24) / contentW, (areaH - 24) / contentH);
        zoom = Math.max(zoomMin, Math.min(zoomMax, fit));
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
        double next = Math.max(zoomMin, Math.min(zoomMax, zoom * factor));
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
        refreshStyle();
        refreshNamesIfNeeded();
        if (!cameraReady || cameraAreaW != areaW || cameraAreaH != areaH) {
            resetCamera(areaW, areaH);
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
        // 悬停高亮：黄灰相间的描边 + 顶层的名称/流量字幕（带 50% 白底）
        drawHoverOutline(graphics);
        drawHoverCaption(graphics, font);
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

    /**
     * 悬停高亮：沿节点外框或边路径画「黄—灰」相间的滚动描边（marching ants），
     * 类似工业警示带，随缩放自动保持屏幕上恒定的段长与粗细。
     */
    private void drawHoverOutline(GuiGraphics graphics) {
        if (hoveredNode == null && hoveredEdgeRef == null && selectedNode == null) {
            return;
        }
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f matrix = graphics.pose().last().pose();
        double scale = Math.max(0.15, zoom);
        double dash = 6.0 / scale;
        double thickness = 1.8 / scale;
        // 静态描边：不做时间动画（按秒重置相位会产生肉眼可见的跳变）
        double phase = 0.0;
        // 选中与悬停可以同时存在；同一个节点只画一次
        if (selectedNode != null) {
            drawNodeAnts(consumer, matrix, selectedNode, dash, thickness, phase);
        }
        if (hoveredNode != null && hoveredNode != selectedNode) {
            drawNodeAnts(consumer, matrix, hoveredNode, dash, thickness, phase);
        }
        if (hoveredEdgeRef != null) {
            double[] points = hoverPath(hoveredEdgeRef);
            for (int i = 0; i + 3 < points.length; i += 2) {
                dashedLine(consumer, matrix, points[i], points[i + 1],
                        points[i + 2], points[i + 3], dash, phase, thickness);
            }
        }
    }

    /** 沿节点外框画一圈黄灰警示虚线。 */
    private void drawNodeAnts(VertexConsumer consumer, Matrix4f matrix, GNode node,
                              double dash, double thickness, double phase) {
        double scale = 6.0 / Math.max(1e-6, dash);
        double half = (node.recipe ? RECIPE : MATERIAL) / 2.0 + 2.5 / scale;
        double x = node.x;
        double y = node.y;
        dashedLine(consumer, matrix, x - half, y - half, x + half, y - half,
                dash, phase, thickness);
        dashedLine(consumer, matrix, x + half, y - half, x + half, y + half,
                dash, phase, thickness);
        dashedLine(consumer, matrix, x + half, y + half, x - half, y + half,
                dash, phase, thickness);
        dashedLine(consumer, matrix, x - half, y + half, x - half, y - half,
                dash, phase, thickness);
    }

    /**
     * 悬停字幕：把节点名称 / 边流量提到最上层（z=210，盖过图标与描边），
     * 底下垫一层约 50% 不透明度的白底，文字在密集连线里也读得清。
     * 选中的节点也常驻一份字幕（与悬停互不覆盖）。
     */
    private void drawHoverCaption(GuiGraphics graphics, Font font) {
        if (hoveredNode != null) {
            drawNodeCaption(graphics, font, hoveredNode);
        } else if (hoveredEdgeRef != null) {
            drawEdgeCaption(graphics, font, hoveredEdgeRef);
        }
        if (selectedNode != null && selectedNode != hoveredNode) {
            drawNodeCaption(graphics, font, selectedNode);
        }
    }

    private void drawNodeCaption(GuiGraphics graphics, Font font, GNode node) {
        if (node.label == null) {
            node.label = font.plainSubstrByWidth(node.displayName, 84);
        }
        String text = node.label;
        int width = font.width(text);
        double y = node.y + (node.recipe ? RECIPE : MATERIAL) / 2.0 + 3;
        // 与普通标签一致的“最小字号”补偿
        float textScale = (float) Math.max(1.0, MIN_LABEL_TEXT_SCALE / zoom);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(node.x, y, 210);
        pose.scale(textScale, textScale, 1.0f);
        graphics.fill(-width / 2 - 2, -1, width / 2 + 2, 9, 0x80FFFFFF);
        graphics.drawString(font, text, -width / 2, 0, AeStyle.TEXT, false);
        pose.popPose();
    }

    private void drawEdgeCaption(GuiGraphics graphics, Font font, GEdge edge) {
        String text = edge.label;
        int width = font.width(text);
        double[] anchor = edgeLabelAnchor(edge, font);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(anchor[0], anchor[1], 210);
        graphics.fill(-2, -1, width + 2, 9, 0x80FFFFFF);
        graphics.drawString(font, text, 0, 0, darken(edgeColor(edge)), false);
        pose.popPose();
    }

    /** 边的描边折线：先按弧线（回边/双向边）采样，再按总线三段，最后才是直线。 */
    private double[] hoverPath(GEdge edge) {
        // 顺序必须和 drawEdgeGeometry 一致：弧线优先，否则高亮会和实际画出来的线不一样
        EdgeCurve curve = curveFor(edge);
        if (curve != null) {
            int segments = 12;
            double[] points = new double[(segments + 1) * 2];
            points[0] = curve.x1;
            points[1] = curve.y1;
            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                points[i * 2] = curve.pointX(t);
                points[i * 2 + 1] = curve.pointY(t);
            }
            return points;
        }
        if (isBusEdge(edge)) {
            return busPath(edge);
        }
        return new double[]{edge.from.x, edge.from.y, edge.to.x, edge.to.y};
    }

    /** 黄灰相间的虚线：按相位偏移交替着色，相位随时间前移形成滚动效果。 */
    private static void dashedLine(VertexConsumer consumer, Matrix4f matrix,
                                   double x1, double y1, double x2, double y2,
                                   double dash, double phase, double thickness) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        for (double start = -phase; start < length; start += dash) {
            double a = Math.max(0.0, start);
            double b = Math.min(length, start + dash);
            if (b - a < 0.6) {
                continue;
            }
            int index = (int) Math.floor((start + phase * 2.0) / dash);
            int color = (index & 1) == 0 ? ANT_YELLOW : ANT_GRAY;
            addLine(consumer, matrix, x1 + ux * a, y1 + uy * a,
                    x1 + ux * b, y1 + uy * b, thickness, color);
        }
    }

    /** 鼠标悬浮：配方/物质/物流详细 tooltip。 */
    public void renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        // 先清空悬停状态：鼠标移出图区时描边要立刻消失，不能残留上一帧的高亮
        hoveredNode = null;
        hoveredEdgeRef = null;
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
        // 记下来给下一帧画边用：悬停节点时其它边会压淡
        hoveredNode = hovered;
        hoveredEdgeRef = null;
        if (hovered != null) {
            graphics.renderComponentTooltip(font,
                    hovered.recipe ? recipeTooltip(hovered) : materialTooltip(hovered),
                    mouseX, mouseY);
            return;
        }
        GEdge edge = hoveredEdge(wx, wy);
        hoveredEdgeRef = edge;
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
            } else if (isBusEdge(edge)) {
                // 总线边是折线，命中测试要贴合实际路径，否则 tooltip 会错位
                double[] path = busPath(edge);
                distance = Math.min(
                        pointSegmentDistance(wx, wy, path[0], path[1], path[2], path[3]),
                        Math.min(
                                pointSegmentDistance(wx, wy, path[2], path[3], path[4], path[5]),
                                pointSegmentDistance(wx, wy, path[4], path[5], path[6], path[7])));
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
        // 选中/悬停某个节点时，只把它相关的边点亮，其它边压到很淡——线太多时靠这一下看清
        // 跨多列的「总线」边先画（压在下层），相邻列的本地流线后画（在上层），
        // 这样局部工艺链一眼可见，长距离输送不会盖住它们。
        for (GEdge edge : edges) {
            if (edgeVisible(edge, minX, maxX, minY, maxY) && isBusEdge(edge)) {
                dimEdges = shouldDim(edge);
                drawEdgeGeometry(consumer, matrix, edge);
            }
        }
        for (GEdge edge : edges) {
            if (edgeVisible(edge, minX, maxX, minY, maxY) && !isBusEdge(edge)) {
                dimEdges = shouldDim(edge);
                drawEdgeGeometry(consumer, matrix, edge);
            }
        }
        dimEdges = false;
        graphics.flush();

        if (!showLabels) {
            return;
        }
        for (GEdge edge : edges) {
            if (!edgeVisible(edge, minX, maxX, minY, maxY)) {
                continue;
            }
            if (edge == hoveredEdgeRef) {
                // 悬停中的那条由顶层字幕负责绘制，避免同一个位置画两遍
                continue;
            }
            dimEdges = shouldDim(edge);
            drawEdgeLabel(graphics, font, edge);
        }
        dimEdges = false;
    }

    /** 有选中/悬停节点时，与它们都无关的边压淡显示。 */
    private boolean shouldDim(GEdge edge) {
        if (selectedNode == null && hoveredNode == null) {
            return false;
        }
        return !touches(selectedNode, edge) && !touches(hoveredNode, edge);
    }

    private static boolean touches(GNode node, GEdge edge) {
        return node != null && (edge.from == node || edge.to == node);
    }

    private static boolean edgeVisible(GEdge edge, double minX, double maxX,
                                       double minY, double maxY) {
        double left = Math.min(edge.from.x, edge.to.x);
        double right = Math.max(edge.from.x, edge.to.x);
        double top = Math.min(edge.from.y, edge.to.y);
        double bottom = Math.max(edge.from.y, edge.to.y);
        return right >= minX && left <= maxX && bottom >= minY && top <= maxY;
    }

    /**
     * 跨多列的边：不画斜线，改走「水平—竖直—水平」的总线（bus），
     * 同一物质发出的多条长边会自然重叠成一根主干，到目标前再垂直落下，
     * 避免满屏斜线穿过整个产线。
     */
    private boolean isBusEdge(GEdge edge) {
        // 只有分层布局才走总线；弹性布局保持原来的直连/弧线视图
        if (layoutMode != LayoutMode.LAYERED) {
            return false;
        }
        // 回边一律画弧线（循环回流），不参与总线路由，否则绘制与高亮会对不上
        if (edge.from == edge.to || edge.back) {
            return false;
        }
        return Math.abs(edge.to.x - edge.from.x) > edgeNaturalLength * 1.35
                && Math.abs(edge.to.y - edge.from.y) > 6;
    }

    /**
     * 总线边的三段折线坐标：{x1, sy, dropX, sy, dropX, tipY, x2, tipY}。
     * 同一目标的多条边按 {@code portOffsetY}/{@code dropOffset} 分散，绘制与命中测试共用。
     */
    private static double[] busPath(GEdge edge) {
        double sx = edge.from.x;
        double sy = edge.from.y;
        double ex = edge.to.x;
        double ey = edge.to.y;
        double dir = ex >= sx ? 1.0 : -1.0;
        double fromRadius = (edge.from.recipe ? RECIPE : MATERIAL) * 0.55;
        double toRadius = (edge.to.recipe ? RECIPE : MATERIAL) * 0.55;
        double x1 = sx + dir * fromRadius;
        double x2 = ex - dir * toRadius;
        double dropX = x2 - dir * (10.0 + edge.dropOffset);
        double tipY = ey + edge.portOffsetY;
        return new double[]{x1, sy, dropX, sy, dropX, tipY, x2, tipY};
    }

    private void drawBusEdge(VertexConsumer consumer, Matrix4f matrix, GEdge edge) {
        double[] path = busPath(edge);
        double dir = edge.to.x >= edge.from.x ? 1.0 : -1.0;
        if ((path[2] - path[0]) * dir < 6) {
            // 距离太短就退回斜线
            drawStraightEdge(consumer, matrix, edge);
            return;
        }
        // 总线边也随流量加粗，但上限比普通连线低（长线容易互相重叠，太粗会糊成一片）
        int thickness = 1 + Math.min(4, (int) (Math.sqrt(Math.max(0.0, edge.flow)) * 2));
        int color = desaturate(edgeColor(edge));
        // 主干 55% 透明度，入靶段与箭头 75%，视觉上层层递进
        int trunk = shade(fade(color, (int) Math.round(140 * edgeOpacity)));
        int tip = shade(fade(color, (int) Math.round(191 * edgeOpacity)));
        addLine(consumer, matrix, path[0], path[1], path[2], path[3], thickness, trunk);
        addLine(consumer, matrix, path[2], path[3], path[4], path[5], thickness, trunk);
        addLine(consumer, matrix, path[4], path[5], path[6], path[7], thickness, tip);
        addArrow(consumer, matrix, path[6], path[7], dir, 0, thickness, tip);
    }

    /**
     * 给总线边分配端口：同一目标的多条边沿其边界均匀散开，竖线也横向错位，
     * 否则几十个箭头会挤在同一个点上。
     */
    private void assignBusPorts() {
        Map<GNode, List<GEdge>> incoming = new HashMap<>();
        for (GEdge edge : edges) {
            if (isBusEdge(edge)) {
                incoming.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(edge);
            }
        }
        for (Map.Entry<GNode, List<GEdge>> entry : incoming.entrySet()) {
            List<GEdge> list = entry.getValue();
            list.sort((a, b) -> Double.compare(a.from.y, b.from.y));
            double radius = (entry.getKey().recipe ? RECIPE : MATERIAL) * 0.5;
            int count = list.size();
            for (int i = 0; i < count; i++) {
                GEdge edge = list.get(i);
                double t = count == 1 ? 0.0 : (i / (double) (count - 1) - 0.5) * 2.0;
                edge.portOffsetY = t * radius * 0.72;
                edge.dropOffset = (i % 6) * 5.0;
            }
        }
    }

    private static void addArrow(VertexConsumer consumer, Matrix4f matrix,
                                 double x2, double y2, double ux, double uy,
                                 int thickness, int color) {
        double backX = x2 - ux * 7;
        double backY = y2 - uy * 7;
        double px = -uy;
        double py = ux;
        addLine(consumer, matrix, x2, y2, backX + px * 3.5, backY + py * 3.5,
                thickness + 1, color);
        addLine(consumer, matrix, x2, y2, backX - px * 3.5, backY - py * 3.5,
                thickness + 1, color);
    }

    private void drawEdgeGeometry(VertexConsumer consumer, Matrix4f matrix, GEdge edge) {
        EdgeCurve curve = curveFor(edge);
        if (curve != null) {
            drawCurvedEdge(consumer, matrix, edge, curve);
            return;
        }
        if (isBusEdge(edge)) {
            drawBusEdge(consumer, matrix, edge);
            return;
        }
        drawStraightEdge(consumer, matrix, edge);
    }

    private void drawStraightEdge(VertexConsumer consumer, Matrix4f matrix, GEdge edge) {
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
        int color = shade(fade(desaturate(edgeColor(edge)), baseAlpha()));
        addLine(consumer, matrix, x1, y1, x2, y2, thickness, color);
        addArrow(consumer, matrix, x2, y2, ux, uy, thickness, color);
    }

    private void drawCurvedEdge(VertexConsumer consumer, Matrix4f matrix,
                                GEdge edge, EdgeCurve curve) {
        int thickness = 1 + Math.min(4, (int) Math.sqrt(Math.max(0.0, edge.flow)) * 2);
        int color = shade(fade(desaturate(edgeColor(edge)), baseAlpha()));
        int segments = 14;
        double prevX = curve.x1;
        double prevY = curve.y1;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double x = curve.pointX(t);
            double y = curve.pointY(t);
            addLine(consumer, matrix, prevX, prevY, x, y, thickness, color);
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
                backX + px * 3.5, backY + py * 3.5, thickness + 1, color);
        addLine(consumer, matrix, curve.x2, curve.y2,
                backX - px * 3.5, backY - py * 3.5, thickness + 1, color);
    }

    /**
     * 只有“A→B 且 B→A”同时存在时才返回弧线；普通边返回 null。
     * 弧线按节点 index 选定统一的基准方向，使两条反向边分别弯向两侧。
     */
    private EdgeCurve curveFor(GEdge edge) {
        if (edge.from == edge.to) {
            return null;
        }
        if (!edge.reciprocal && !edge.back) {
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
        // 回边（再循环）弯得更明显，避免和正向流线叠在一起
        double offset = edge.back
                ? Math.min(64.0, Math.max(26.0, dist * 0.24))
                : Math.min(38.0, Math.max(16.0, dist * 0.18));
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
        // 流量文字用该物质的本色（压深一档），和箭头同色，方便顺着线找来源
        int color = dimEdges ? fade(0xFF808080, (int) Math.round(255 * dimOpacity)) : darken(edgeColor(edge));
        double[] anchor = edgeLabelAnchor(edge, font);
        // 用 pose 平移到锚点再画 (0,0)：与悬停字幕的定位方式完全一致，
        // 否则一个取整、一个用浮点平移，会错开半像素出现"重影"
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(anchor[0], anchor[1], 0);
        graphics.drawString(font, edge.label, 0, 0, color, false);
        pose.popPose();
    }

    /** 流量文字的左上角（世界坐标）：绘制与悬停字幕共用同一套定位。 */
    private double[] edgeLabelAnchor(GEdge edge, Font font) {
        int width = font.width(edge.label);
        if (isBusEdge(edge)) {
            double[] path = busPath(edge);
            double dir = edge.to.x >= edge.from.x ? 1.0 : -1.0;
            // 贴在竖线「靠源的一侧」：向右输送时文字在竖线左边，避免伸进目标节点下面被盖住
            double x = dir > 0 ? path[2] - 5 - width : path[2] + 5;
            if (dir > 0 && x < path[0] + 2) {
                x = path[2] + 5;
            }
            return new double[]{x, (path[3] + path[5]) / 2 - 4};
        }
        EdgeCurve curve = curveFor(edge);
        if (curve != null) {
            return new double[]{curve.pointX(0.5) - width / 2.0, curve.pointY(0.5) - 4};
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
        double labelX = (x1 + x2) / 2 - uy * 4 - width / 2.0;
        double labelY = (y1 + y2) / 2 + ux * 4 - 4;
        return new double[]{labelX, labelY};
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
