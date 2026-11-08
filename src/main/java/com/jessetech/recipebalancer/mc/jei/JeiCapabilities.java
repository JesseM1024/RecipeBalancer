package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * JEI 的运行期能力探测（1.18.2 / JEI 9.x ~ 10.x）。
 *
 * <p>这里不判断版本号，而是直接问 JVM「这个类/方法在不在」，结果只算一次，之后都是布尔判断。
 * 之所以按能力而不是按版本：本地样本从 9.4.1.113 到 10.81.0.1029，
 * 中间有些版本的引入点未知（例如配方页按钮 API 在 9.x 完全没有、10.x 才有），
 * 按能力探测可以覆盖这些区间，也能跨版本自愈。
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

    /** IRecipeButtonControllerFactory + IAdvancedRegistration#addRecipeButtonFactory（JEI 10.x 才有）。 */
    private static final boolean RECIPE_BUTTONS = probeRecipeButtonApi();
    /** IScreenHandler#getClickableIngredientUnderMouse(IClickableIngredientFactory, ...)（10.x 才有）。 */
    private static final boolean CLICKABLE_FACTORY = probeClickableFactory();
    /** IRecipeCategoryDecorator（10.x 才有）。 */
    private static final boolean CATEGORY_DECORATOR =
            probeClass("mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator");
    /** IJeiRuntime#getBookmarkManager()（10.x 才有）。 */
    private static final boolean BOOKMARK_MANAGER = probeMethod(
            "mezz.jei.api.runtime.IJeiRuntime", "getBookmarkManager");
    /** ITooltipBuilder（10.x 才有）。 */
    private static final boolean TOOLTIP_BUILDER = probeClass(
            "mezz.jei.api.gui.builder.ITooltipBuilder");

    static {
        if (FMLEnvironment.dist.isClient() && RecipeBalancerMod.LOGGER.isDebugEnabled()) {
            RecipeBalancerMod.LOGGER.debug(
                    "[RecipeBalancer] JEI 探测: installed={} version={} buttons={} clickFactory={} decorator={} bookmarkManager={}",
                    INSTALLED, VERSION, RECIPE_BUTTONS, CLICKABLE_FACTORY,
                    CATEGORY_DECORATOR, BOOKMARK_MANAGER);
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

    /** 是否支持「配方页按钮」官方 API（JEI 10.x）。 */
    public static boolean supportsRecipeButtons() {
        return INSTALLED && RECIPE_BUTTONS;
    }

    /** 是否支持 {@code IScreenHandler} 上的悬停配料重载（JEI 10.x）。 */
    public static boolean supportsClickableFactory() {
        return INSTALLED && CLICKABLE_FACTORY;
    }

    /** 是否有 {@code IRecipeCategoryDecorator}（JEI 10.x）。 */
    public static boolean supportsCategoryDecorator() {
        return INSTALLED && CATEGORY_DECORATOR;
    }

    /** 是否能从 runtime 直接拿到书签管理器（JEI 10.x；该 API 也没有「列出全部」）。 */
    public static boolean supportsBookmarkManager() {
        return INSTALLED && BOOKMARK_MANAGER;
    }

    /** 是否有 {@code ITooltipBuilder}（JEI 10.x）。 */
    public static boolean supportsTooltipBuilder() {
        return INSTALLED && TOOLTIP_BUILDER;
    }

    /** 给日志用的一行能力摘要。 */
    public static String describe() {
        if (!INSTALLED) {
            return "JEI 未安装";
        }
        return "JEI " + VERSION
                + "（配方页按钮 API=" + RECIPE_BUTTONS
                + ", 悬停工厂=" + CLICKABLE_FACTORY
                + ", 类别装饰器=" + CATEGORY_DECORATOR
                + ", 书签管理器=" + BOOKMARK_MANAGER + "）";
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
        Class<?> factory = probeClassOrNull(
                "mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory");
        return factory != null && probeMethod("mezz.jei.api.registration.IAdvancedRegistration",
                "addRecipeButtonFactory", factory);
    }

    /**
     * {@code IScreenHandler} 是否带「鼠标下配料」重载（JEI 10.x）。
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
            return type.getMethod(method, parameterTypes) != null;
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
