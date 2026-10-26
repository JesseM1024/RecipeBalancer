package com.jessetech.recipebalancer.mc.ui.graph;

import com.jessetech.recipebalancer.core.model.Constants;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.SolveResult;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.mc.itemmap.IngredientKey;
import com.jessetech.recipebalancer.mc.itemmap.ItemKeyMapper;
import com.jessetech.recipebalancer.mc.ui.AeStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
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

    private static final class GNode {
        final boolean recipe;
        final String key;
        final int recipeId;
        final Category category;
        final int machines;
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
        Random random = new Random(0x5EEDL);
        double radius = 60 + Math.min(170, nodes.size() * 2.4);
        for (int i = 0; i < nodes.size(); i++) {
            double angle = 2 * Math.PI * i / nodes.size() + random.nextDouble() * 0.3;
            double r = radius * (0.45 + random.nextDouble() * 0.55);
            nodes.get(i).x = Math.cos(angle) * r;
            nodes.get(i).y = Math.sin(angle) * r;
        }

        double k = 54.0;
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
                    double minDist = (a.recipe ? RECIPE : MATERIAL) / 2.0
                            + (b.recipe ? RECIPE : MATERIAL) / 2.0 + 12;
                    double force = k * k / dist;
                    if (dist < minDist) {
                        force += (minDist - dist) * 3.0;
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
                int i = nodes.indexOf(edge.from);
                int j = nodes.indexOf(edge.to);
                if (i < 0 || j < 0) {
                    continue;
                }
                double vx = edge.from.x - edge.to.x;
                double vy = edge.from.y - edge.to.y;
                double dist = Math.max(1.0, Math.hypot(vx, vy));
                double force = dist * dist / k;
                double ux = vx / dist;
                double uy = vy / dist;
                dx[i] -= ux * force;
                dy[i] -= uy * force;
                dx[j] += ux * force;
                dy[j] += uy * force;
            }

            double targetRadius = 58 + Math.sqrt(nodes.size()) * 21.0;
            for (int i = 0; i < nodes.size(); i++) {
                GNode node = nodes.get(i);
                double dist = Math.max(1.0, Math.hypot(node.x, node.y));
                // 向心 + 圆环约束：整体团成一个圆形簇
                dx[i] -= node.x / dist * dist * 0.06;
                dy[i] -= node.y / dist * dist * 0.06;
                double radial = (dist - targetRadius) * 0.075;
                dx[i] -= node.x / dist * radial;
                dy[i] -= node.y / dist * radial;
                double length = Math.max(1.0, Math.hypot(dx[i], dy[i]));
                double step = Math.min(length, temperature);
                node.x += dx[i] / length * step;
                node.y += dy[i] / length * step;
            }
        }

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
        graphics.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x10FFFFFF);
        graphics.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);

        if (nodes.isEmpty()) {
            graphics.drawString(font, "没有有效配方可绘制", areaX + 8, areaY + 24,
                    AeStyle.MUTED, false);
            graphics.disableScissor();
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(areaX + panX, areaY + panY, 0);
        graphics.pose().scale((float) zoom, (float) zoom, 1.0f);
        for (GEdge edge : edges) {
            drawEdge(graphics, font, edge);
        }
        for (GNode node : nodes) {
            drawNode(graphics, font, node);
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

    private void drawEdge(GuiGraphics graphics, Font font, GEdge edge) {
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
        drawLine(graphics, x1, y1, x2, y2, thickness, EDGE);
        // 箭头：两条回折线
        double backX = x2 - ux * 7;
        double backY = y2 - uy * 7;
        double px = -uy;
        double py = ux;
        drawLine(graphics, x2, y2, backX + px * 3.5, backY + py * 3.5,
                thickness + 1, EDGE);
        drawLine(graphics, x2, y2, backX - px * 3.5, backY - py * 3.5,
                thickness + 1, EDGE);

        String label = TimeExpression.formatCompact(edge.flow) + "/t";
        int width = font.width(label);
        float labelX = (float) ((x1 + x2) / 2 - width / 2.0 + px * 4);
        float labelY = (float) ((y1 + y2) / 2 - 4 + py * 4);
        graphics.drawString(font, label, (int) labelX, (int) labelY, 0xFF666666, false);
    }

    private static void drawLine(GuiGraphics graphics, double x1, double y1,
                                 double x2, double y2, int thickness, int color) {
        int steps = (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
        if (steps <= 0) {
            graphics.fill((int) x1, (int) y1, (int) x1 + thickness,
                    (int) y1 + thickness, color);
            return;
        }
        int half = thickness / 2;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            graphics.fill(x - half, y - half, x - half + thickness,
                    y - half + thickness, color);
        }
    }

    private void drawNode(GuiGraphics graphics, Font font, GNode node) {
        if (node.recipe) {
            drawRecipeNode(graphics, font, node);
        } else {
            drawMaterialNode(graphics, font, node);
        }
    }

    private void drawRecipeNode(GuiGraphics graphics, Font font, GNode node) {
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

        if (!node.key.isBlank() && !ItemKeyMapper.stack(node.key).isEmpty()) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 11, y + 15, null);
        } else {
            String initial = node.key.isBlank() ? "?" : shortInitial(
                    ItemKeyMapper.displayName(node.key));
            graphics.drawString(font, initial, x + RECIPE / 2 - font.width(initial) / 2,
                    y + 19, 0xFF3A2A10, false);
        }
        String name = node.key.isBlank() ? "配方" : ItemKeyMapper.displayName(node.key);
        drawCenteredLabel(graphics, font, name, node.x, node.y + RECIPE / 2.0 + 3);
    }

    private void drawMaterialNode(GuiGraphics graphics, Font font, GNode node) {
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

        boolean hasIcon = !ItemKeyMapper.stack(node.key).isEmpty()
                || IngredientKey.isIngredientKey(node.key);
        if (hasIcon) {
            ItemKeyMapper.renderIcon(graphics, font, node.key, x + 2, y + 2, null);
        } else {
            String initial = shortInitial(ItemKeyMapper.displayName(node.key));
            graphics.drawString(font, initial,
                    x + MATERIAL / 2 - font.width(initial) / 2, y + 7,
                    0xFF202020, false);
        }
        drawCenteredLabel(graphics, font, ItemKeyMapper.displayName(node.key),
                node.x, node.y + MATERIAL / 2.0 + 3);
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
