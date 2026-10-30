package com.jessetech.recipebalancer.mc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 流程图配置（NeoForge {@code ModConfigSpec}，文件 {@code config/recipebalancer-client.toml}）。
 *
 * <p>模组列表里的「Config」按钮由 NeoForge 自带编辑器打开（见 {@code RecipeBalancerMod}
 * 注册的 {@code IConfigScreenFactory -> ConfigurationScreen}），保存后立即生效。
 */
public final class GraphLayoutConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue NODE_GAP;
    public static final ModConfigSpec.DoubleValue EDGE_LENGTH;
    public static final ModConfigSpec.DoubleValue FORCE_IDEAL;
    public static final ModConfigSpec.DoubleValue MIN_EDGE_LENGTH;
    public static final ModConfigSpec.DoubleValue EDGE_SATURATION;
    public static final ModConfigSpec.DoubleValue EDGE_OPACITY;
    public static final ModConfigSpec.DoubleValue DIM_OPACITY;
    public static final ModConfigSpec.DoubleValue ZOOM_MIN;
    public static final ModConfigSpec.DoubleValue ZOOM_MAX;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        NODE_GAP = builder
                .comment("同列相邻节点之间的额外行距（像素，分层布局用）。调大更松散。",
                        "Extra vertical gap between nodes of the same column, in pixels (layered layout).")
                .defineInRange("nodeGap", 32.0, 0.0, 200.0);
        EDGE_LENGTH = builder
                .comment("列与列之间的距离基准（像素）。调大列更分散，连线更长。",
                        "Base distance between columns, in pixels. Larger spreads the graph out.")
                .defineInRange("edgeLength", 128.0, 20.0, 400.0);
        FORCE_IDEAL = builder
                .comment("弹性布局的理想边长系数（相对 edgeLength）。调小节点更紧凑，调大更松散。",
                        "Ideal edge length factor for the force layout (relative to edgeLength).")
                .defineInRange("forceIdeal", 0.3, 0.15, 1.5);
        MIN_EDGE_LENGTH = builder
                .comment("弹性布局中相连节点的最小中心距（像素）。太小会让两个节点贴在一起、箭头看不见。",
                        "Minimum center distance between connected nodes in the force layout (px).")
                .defineInRange("minEdgeLength", 96.0, 16.0, 200.0);
        EDGE_SATURATION = builder
                .comment("连线向灰色混合的强度：0 = 保持物质本色，1 = 完全灰。",
                        "How much edge colors are desaturated: 0 = full color, 1 = gray.")
                .defineInRange("edgeSaturation", 0.5, 0.0, 1.0);
        EDGE_OPACITY = builder
                .comment("普通连线的基础不透明度（0.1-1）。总线主干与入靶段按比例同步变淡。",
                        "Base opacity of normal edges (0.1-1). Bus trunks and tips scale with it.")
                .defineInRange("edgeOpacity", 1.0, 0.1, 1.0);
        DIM_OPACITY = builder
                .comment("高亮某个节点时，其余连线被压低到的不透明度（0-1）。",
                        "Opacity applied to unrelated edges while a node is highlighted.")
                .defineInRange("dimOpacity", 0.1, 0.0, 1.0);
        ZOOM_MIN = builder
                .comment("流程图缩放下限（0.05-1）。自动适配视野与滚轮缩放都不低于它。",
                        "Minimum zoom of the graph view.")
                .defineInRange("zoomMin", 0.1, 0.05, 1.0);
        ZOOM_MAX = builder
                .comment("流程图缩放上限（0.5-6）。自动适配视野与滚轮缩放都不超过它。",
                        "Maximum zoom of the graph view.")
                .defineInRange("zoomMax", 3.0, 0.5, 6.0);
        SPEC = builder.build();
    }

    private GraphLayoutConfig() {
    }

    /**
     * @param nodeGap        同列节点间距附加量（像素）
     * @param edgeLength     列间距基准（像素）
     * @param forceIdeal     弹性布局理想边长系数（相对 edgeLength）
     * @param minEdgeLength  弹性布局中相连节点的最小中心距
     * @param edgeSaturation 连线降饱和强度（0 = 原色，1 = 纯灰）
     * @param edgeOpacity    连线基础不透明度
     * @param dimOpacity     高亮节点时其余连线的不透明度
     * @param zoomMin        缩放下限
     * @param zoomMax        缩放上限
     */
    public record Settings(double nodeGap, double edgeLength, double forceIdeal,
                           double minEdgeLength, double edgeSaturation, double edgeOpacity,
                           double dimOpacity, double zoomMin, double zoomMax) {
    }

    /** 当前配置快照（每帧取一次，配置界面保存后立即反映）。 */
    public static Settings current() {
        return new Settings(NODE_GAP.get(), EDGE_LENGTH.get(), FORCE_IDEAL.get(),
                MIN_EDGE_LENGTH.get(), EDGE_SATURATION.get(), EDGE_OPACITY.get(),
                DIM_OPACITY.get(), ZOOM_MIN.get(), ZOOM_MAX.get());
    }
}
