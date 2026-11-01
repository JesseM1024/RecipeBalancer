package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * JEI 的运行期能力探测。
 *
 * <p>这里不判断版本号，而是直接问 JVM「这个类/方法在不在」，结果只算一次，之后都是布尔判断。
 * 之所以按能力而不是按版本：本地只有 8 个 jar（15.0.0.12 / 15.3.0.8 / 15.12.2.51 /
 * 15.20.0.127 / 15.21.0.148 / 15.48.0.179 / 15.56.0.205 / 15.74.0.233），
 * 只能把界限「夹住」，拿不到精确的引入版本（例：配方页按钮 API 在 15.21.0.148 没有、
 * 15.48.0.179 有，真正的引入版本落在 (15.21.0.148, 15.48.0.179] 之间，未验证）。
 * 按能力探测则可以覆盖这些未知区间，也能跨版本自愈。
 *
 * <p><b>本类不直接引用任何 {@code mezz.jei.*} 类型</b>，因此未装 JEI 时也能安全初始化；
 * 常驻代码（主类、界面、core）只允许通过本类与 {@link JeiRuntimeHolder} 判断 JEI 状态。
 */
public final class JeiCapabilities {

    private static final String JEI_MOD_ID = "jei";

    /** JEI 是否已安装。 */
    private static final boolean INSTALLED = detectInstalled();
    /** JEI 版本号字符串（仅用于日志/提示；探测不到时为空串）。 */
    private static final String VERSION = detectVersion();

    /** IRecipeButtonControllerFactory + IAdvancedRegistration#addRecipeButtonFactory（实测 15.21 无 / 15.48 有）。 */
    private static final boolean RECIPE_BUTTONS = probeRecipeButtonApi();
    /** IScreenHandler#getClickableIngredientUnderMouse(IClickableIngredientFactory, ...)（同一批样本的界限）。 */
    private static final boolean CLICKABLE_FACTORY = probeClickableFactory();
    /** IJeiRuntime#getBookmarkManager()（实测 15.56 无 / 15.74 有）。 */
    private static final boolean BOOKMARK_MANAGER = probeMethod(
            "mezz.jei.api.runtime.IJeiRuntime", "getBookmarkManager");
    /** ITooltipBuilder（实测 15.3 无 / 15.12 有）。 */
    private static final boolean TOOLTIP_BUILDER = probeClass(
            "mezz.jei.api.gui.builder.ITooltipBuilder");
    /** JEI 配方页自带的「收藏此配方」按钮（内部类；实测 15.3 无 / 15.12 有，15.74 改名）。 */
    private static final boolean RECIPE_BOOKMARK_BUTTON = probeAnyClass(
            "mezz.jei.gui.recipes.RecipeBookmarkButton",
            "mezz.jei.gui.recipes.RecipeBookmarkButtonController",
            "mezz.jei.gui.bookmarks.RecipeBookmark");

    static {
        if (FMLEnvironment.dist.isClient() && RecipeBalancerMod.LOGGER.isDebugEnabled()) {
            RecipeBalancerMod.LOGGER.debug(
                    "[RecipeBalancer] JEI 探测: installed={} version={} buttons={} clickFactory={} bookmarkManager={} tooltipBuilder={}",
                    INSTALLED, VERSION, RECIPE_BUTTONS, CLICKABLE_FACTORY,
                    BOOKMARK_MANAGER, TOOLTIP_BUILDER);
        }
    }

    private JeiCapabilities() {
    }

    /** JEI 是否已安装（不代表 runtime 已就绪）。 */
    public static boolean installed() {
        return INSTALLED;
    }

    public static String version() {
        return VERSION;
    }

    /** 是否支持「配方页按钮」官方 API（实测 15.21 无 / 15.48 有，精确界限未验证）。 */
    public static boolean supportsRecipeButtons() {
        return INSTALLED && RECIPE_BUTTONS;
    }

    /** 是否支持 {@code IScreenHandler} 上的悬停配料重载（与上一项同一批样本）。 */
    public static boolean supportsClickableFactory() {
        return INSTALLED && CLICKABLE_FACTORY;
    }

    /** 是否能从 runtime 直接拿到书签管理器（实测 15.56 无 / 15.74 有；该 API 也没有「列出全部」）。 */
    public static boolean supportsBookmarkManager() {
        return INSTALLED && BOOKMARK_MANAGER;
    }

    /** 是否有 {@code ITooltipBuilder}（实测 15.3 无 / 15.12 有）。 */
    public static boolean supportsTooltipBuilder() {
        return INSTALLED && TOOLTIP_BUILDER;
    }

    /**
     * JEI 自己是否在配方页画「收藏此配方」按钮。
     *
     * <p>用途只有一个：装饰器自绘按钮时避开 JEI 那一列，免得被盖住。
     * 依据是 JEI 内部类（本地实测：15.3.0.8 没有、15.12.2.51 起有，15.74 改了类名）——
     * 这是内部实现，不属于公开 API，探测失败一律按「没有」处理（最多是位置不理想，不会出错）。
     */
    public static boolean hasJeiRecipeBookmarkButton() {
        return INSTALLED && RECIPE_BOOKMARK_BUTTON;
    }

    /** 给日志用的一行能力摘要。 */
    public static String describe() {
        if (!INSTALLED) {
            return "JEI 未安装";
        }
        return "JEI " + VERSION
                + "（配方页按钮 API=" + RECIPE_BUTTONS
                + ", 悬停工厂=" + CLICKABLE_FACTORY
                + ", 书签管理器=" + BOOKMARK_MANAGER
                + ", 自带配方收藏按钮=" + RECIPE_BOOKMARK_BUTTON + "）";
    }

    private static boolean detectInstalled() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(JEI_MOD_ID);
        } catch (RuntimeException | LinkageError e) {
            // 非游戏环境（纯 JVM 测试）或 ModList 尚未初始化
            return false;
        }
    }

    private static String detectVersion() {
        try {
            ModList modList = ModList.get();
            if (modList == null) {
                return "";
            }
            Optional<? extends ModContainer> container = modList.getModContainerById(JEI_MOD_ID);
            if (container.isEmpty()) {
                return "";
            }
            Object version = container.get().getModInfo().getVersion();
            return version == null ? "" : version.toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    private static boolean probeRecipeButtonApi() {
        return probeClass("mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory")
                && probeMethod("mezz.jei.api.registration.IAdvancedRegistration",
                "addRecipeButtonFactory", probeClassOrNull(
                        "mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory"));
    }

    /**
     * {@code IScreenHandler} 是否带「鼠标下配料」重载（实测 15.21 无 / 15.48 有）。
     *
     * <p>按方法名 + 参数里出现 {@code IClickableIngredientFactory} 判断，
     * 不写死参数个数/顺序：不同版本的重载参数有 Screen 泛型擦除差异。
     */
    private static boolean probeClickableFactory() {
        Class<?> factory = probeClassOrNull("mezz.jei.api.gui.builder.IClickableIngredientFactory");
        Class<?> screenHandler = probeClassOrNull("mezz.jei.api.gui.handlers.IScreenHandler");
        if (factory == null || screenHandler == null) {
            return false;
        }
        for (Method method : screenHandler.getMethods()) {
            if (!"getClickableIngredientUnderMouse".equals(method.getName())) {
                continue;
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType == factory) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean probeClass(String name) {
        return probeClassOrNull(name) != null;
    }

    /** 任一候选类存在即返回 true（用于 JEI 内部类名跨版本改名的情况）。 */
    private static boolean probeAnyClass(String... names) {
        for (String name : names) {
            if (probeClassOrNull(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> probeClassOrNull(String name) {
        try {
            return Class.forName(name, false, JeiCapabilities.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static boolean probeMethod(String owner, String method, Class<?>... parameterTypes) {
        Class<?> type = probeClassOrNull(owner);
        if (type == null) {
            return false;
        }
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType == null) {
                return false;
            }
        }
        try {
            Method found = type.getMethod(method, parameterTypes);
            return found != null;
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
