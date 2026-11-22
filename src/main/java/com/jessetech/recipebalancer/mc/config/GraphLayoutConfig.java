package com.jessetech.recipebalancer.mc.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * 流程图配置（1.12.2 的 {@code Configuration}，文件 {@code config/recipebalancer-client.cfg}）。
 *
 * <p>1.12.2 没有 {@code ForgeConfigSpec}（1.13+ 才引入），键名/范围/默认值与其它版本线保持一致；
 * {@link Settings} 快照与 {@link #current()} 的签名不变，界面层无需改动。
 */
public final class GraphLayoutConfig {

    private static final String CATEGORY = "graph";

    private static final double NODE_GAP_DEFAULT = 32.0;
    private static final double EDGE_LENGTH_DEFAULT = 128.0;
    private static final double FORCE_IDEAL_DEFAULT = 0.3;
    private static final double MIN_EDGE_LENGTH_DEFAULT = 96.0;
    private static final double EDGE_SATURATION_DEFAULT = 0.5;
    private static final double EDGE_OPACITY_DEFAULT = 1.0;
    private static final double DIM_OPACITY_DEFAULT = 0.1;
    private static final double ZOOM_MIN_DEFAULT = 0.1;
    private static final double ZOOM_MAX_DEFAULT = 3.0;

    private static double nodeGap = NODE_GAP_DEFAULT;
    private static double edgeLength = EDGE_LENGTH_DEFAULT;
    private static double forceIdeal = FORCE_IDEAL_DEFAULT;
    private static double minEdgeLength = MIN_EDGE_LENGTH_DEFAULT;
    private static double edgeSaturation = EDGE_SATURATION_DEFAULT;
    private static double edgeOpacity = EDGE_OPACITY_DEFAULT;
    private static double dimOpacity = DIM_OPACITY_DEFAULT;
    private static double zoomMin = ZOOM_MIN_DEFAULT;
    private static double zoomMax = ZOOM_MAX_DEFAULT;

    /** JEI 导入时是否把源配方耗时预填到耗时格（总开关）。 */
    private static boolean prefillRecipeTime = true;
    /** 是否允许按方法名/字段名反射猜测没有专门适配的模组耗时。 */
    private static boolean prefillRecipeTimeReflection = true;
    /** 配方对象上没有时间时，是否运行时读机器实体的基础耗时。 */
    private static boolean prefillMachineBaseTime = true;

    private GraphLayoutConfig() {
    }

    /** 读入（或首次生成）配置文件；缺失/解析失败时保留默认值。 */
    public static synchronized void load(File configFile) {
        try {
            Configuration config = new Configuration(configFile);
            config.load();
            nodeGap = getDouble(config, "nodeGap", CATEGORY, NODE_GAP_DEFAULT, 0.0, 200.0,
                    "同列相邻节点之间的额外行距（像素，分层布局用）。调大更松散。\n"
                            + "Extra vertical gap between nodes of the same column, in pixels.");
            edgeLength = getDouble(config, "edgeLength", CATEGORY, EDGE_LENGTH_DEFAULT, 20.0, 400.0,
                    "列与列之间的距离基准（像素）。调大列更分散，连线更长。\n"
                            + "Base distance between columns, in pixels.");
            forceIdeal = getDouble(config, "forceIdeal", CATEGORY, FORCE_IDEAL_DEFAULT, 0.15, 1.5,
                    "弹性布局的理想边长系数（相对 edgeLength）。\n"
                            + "Ideal edge length factor for the force layout.");
            minEdgeLength = getDouble(config, "minEdgeLength", CATEGORY, MIN_EDGE_LENGTH_DEFAULT,
                    16.0, 200.0,
                    "弹性布局中相连节点的最小中心距（像素）。\n"
                            + "Minimum center distance between connected nodes in the force layout.");
            edgeSaturation = getDouble(config, "edgeSaturation", CATEGORY, EDGE_SATURATION_DEFAULT,
                    0.0, 1.0,
                    "连线向灰色混合的强度：0 = 保持物质本色，1 = 完全灰。\n"
                            + "How much edge colors are desaturated.");
            edgeOpacity = getDouble(config, "edgeOpacity", CATEGORY, EDGE_OPACITY_DEFAULT, 0.1, 1.0,
                    "普通连线的基础不透明度（0.1-1）。\n"
                            + "Base opacity of normal edges (0.1-1).");
            dimOpacity = getDouble(config, "dimOpacity", CATEGORY, DIM_OPACITY_DEFAULT, 0.0, 1.0,
                    "高亮某个节点时，其余连线被压低到的不透明度（0-1）。\n"
                            + "Opacity applied to unrelated edges while a node is highlighted.");
            zoomMin = getDouble(config, "zoomMin", CATEGORY, ZOOM_MIN_DEFAULT, 0.05, 1.0,
                    "流程图缩放下限（0.05-1）。\n" + "Minimum zoom of the graph view.");
            zoomMax = getDouble(config, "zoomMax", CATEGORY, ZOOM_MAX_DEFAULT, 0.5, 6.0,
                    "流程图缩放上限（0.5-6）。\n" + "Maximum zoom of the graph view.");
            prefillRecipeTime = getBoolean(config, "prefillRecipeTime", CATEGORY, true,
                    "从 JEI 写样板时，把源配方的耗时预填到耗时格（可随后手动修改）。\n"
                            + "Pre-fill the encoding time slot with the source recipe's duration when importing from JEI.");
            prefillRecipeTimeReflection = getBoolean(config, "prefillRecipeTimeReflection", CATEGORY, true,
                    "允许通过方法名/字段名反射猜测其他模组的配方耗时（默认开启）。\n"
                            + "Allow guessing other mods' recipe durations by reflected method/field names.");
            prefillMachineBaseTime = getBoolean(config, "prefillMachineBaseTime", CATEGORY, true,
                    "配方对象上没有耗时字段时，运行时实例化该类别的机器实体并读它的基础耗时。\n"
                            + "When the recipe object carries no duration, instantiate the category's machine and read its base time at runtime.");
            if (config.hasChanged()) {
                config.save();
            }
        } catch (RuntimeException ignored) {
            // 配置文件损坏时不让界面起不来，沿用默认值
        }
    }

    /** 1.12.2 的 Configuration 没有 getDouble：走 get(...) + Property#getDouble。 */
    private static double getDouble(Configuration config, String key, String category,
                                    double def, double min, double max, String comment) {
        return config.get(key, category, def, comment, min, max).getDouble(def);
    }

    /** 布尔项：走 get(...) + Property#getBoolean。 */
    private static boolean getBoolean(Configuration config, String key, String category,
                                      boolean def, String comment) {
        return config.get(key, category, def, comment).getBoolean(def);
    }

    /** 耗时预填总开关。 */
    public static boolean prefillRecipeTime() {
        return prefillRecipeTime;
    }

    /** 反射猜测开关。 */
    public static boolean prefillRecipeTimeReflection() {
        return prefillRecipeTimeReflection;
    }

    /** 机器基准耗时开关。 */
    public static boolean prefillMachineBaseTime() {
        return prefillMachineBaseTime;
    }

    /**
     * 流程图配置快照（1.12.2 线是 Java 8，不能写 record）。
     */
    public static final class Settings {

        private final double nodeGap;
        private final double edgeLength;
        private final double forceIdeal;
        private final double minEdgeLength;
        private final double edgeSaturation;
        private final double edgeOpacity;
        private final double dimOpacity;
        private final double zoomMin;
        private final double zoomMax;

        public Settings(double nodeGap, double edgeLength, double forceIdeal,
                        double minEdgeLength, double edgeSaturation, double edgeOpacity,
                        double dimOpacity, double zoomMin, double zoomMax) {
            this.nodeGap = nodeGap;
            this.edgeLength = edgeLength;
            this.forceIdeal = forceIdeal;
            this.minEdgeLength = minEdgeLength;
            this.edgeSaturation = edgeSaturation;
            this.edgeOpacity = edgeOpacity;
            this.dimOpacity = dimOpacity;
            this.zoomMin = zoomMin;
            this.zoomMax = zoomMax;
        }

        public double nodeGap() {
            return nodeGap;
        }

        public double edgeLength() {
            return edgeLength;
        }

        public double forceIdeal() {
            return forceIdeal;
        }

        public double minEdgeLength() {
            return minEdgeLength;
        }

        public double edgeSaturation() {
            return edgeSaturation;
        }

        public double edgeOpacity() {
            return edgeOpacity;
        }

        public double dimOpacity() {
            return dimOpacity;
        }

        public double zoomMin() {
            return zoomMin;
        }

        public double zoomMax() {
            return zoomMax;
        }
    }

    /** 当前配置快照（每帧取一次）。 */
    public static Settings current() {
        return new Settings(nodeGap, edgeLength, forceIdeal, minEdgeLength, edgeSaturation,
                edgeOpacity, dimOpacity, zoomMin, zoomMax);
    }
}
