package com.jessetech.recipebalancer.mc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 本模组的统一客户端配置（NeoForge {@code ModConfigSpec}，文件 {@code config/recipebalancer-client.toml}）。
 *
 * <p>包含流程图视觉参数与 JEI 导入行为。NeoForge 的约定是「一个 spec = 一份配置文件」，
 * 新增配置项一律加在这里，不要再注册第二份 spec。
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

    /** JEI 导入时是否把源配方耗时预填到耗时格（总开关）。 */
    public static final ModConfigSpec.BooleanValue PREFILL_RECIPE_TIME;
    /** 是否允许按方法名/字段名反射猜测没有专门适配的模组耗时（默认关闭）。 */
    public static final ModConfigSpec.BooleanValue PREFILL_RECIPE_TIME_REFLECTION;

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

        builder.comment("JEI 导入行为。",
                        "JEI import behaviour.")
                .push("jei");
        PREFILL_RECIPE_TIME = builder
                .comment("从 JEI 写样板时，把源配方的耗时预填到耗时格（可随后手动修改）。",
                        "Pre-fill the encoding time slot with the source recipe's duration when importing from JEI.",
                        "推断失败时保持默认 1t，不会阻塞导入。",
                        "Falls back to 1t when the duration cannot be inferred.")
                .define("prefillRecipeTime", true);
        PREFILL_RECIPE_TIME_REFLECTION = builder
                .comment("允许通过方法名/字段名反射猜测其他模组的配方耗时（默认关闭）。",
                        "Allow guessing other mods' recipe durations by reflected method/field names (off by default).",
                        "只影响没有专门适配的模组；原版烹饪与已知模组始终可读。",
                        "Only affects mods without a dedicated rule; vanilla cooking and known mods keep working.")
                .define("prefillRecipeTimeReflection", false);
        builder.pop();

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

    /** 耗时预填总开关；配置尚未加载时按默认值（开）处理，避免功能因配置异常而失效。 */
    public static boolean prefillRecipeTime() {
        try {
            return PREFILL_RECIPE_TIME.get();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** 反射猜测开关；配置尚未加载时按默认值（关）处理。 */
    public static boolean prefillRecipeTimeReflection() {
        try {
            return PREFILL_RECIPE_TIME_REFLECTION.get();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
