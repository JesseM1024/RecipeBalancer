package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.util.Map;

/**
 * JEI 运行期能力探测（1.12.2 / JEI 4.7.8 ~ 4.24）。
 *
 * <p>1.12.2 的 JEI 4.x 不是一代接口：拖拽、普通界面面板让位、全局处理器、书签 API
 * 都在不同小版本引入。编译基线是 4.8.5.160，但加载下限是 4.7.8.95 ——
 * 低版本必须"少注册、别崩"，因此每一项能力都用 {@code Class.forName} 探测。
 *
 * <p>版本事实（逐 jar {@code javap} 核实，详见 docs/JEI-COMPAT-1.12.2.md）：
 * <ul>
 *   <li>4.8.4.131：{@code IGhostIngredientHandler} / {@code IGuiScreenHandler} / {@code IGuiProperties}；</li>
 *   <li>4.8.5.160：{@code GuiIngredient#getRect}；</li>
 *   <li>4.12.0.216：{@code IIngredientType}；</li>
 *   <li>4.13.1.227：书签内部类 {@code BookmarkList} / {@code BookmarkOverlay}；</li>
 *   <li>4.14.0.232：{@code IGlobalGuiHandler}；</li>
 *   <li>4.15.0.276：{@code IBookmarkOverlay}；</li>
 *   <li>4.16.1.301：{@code IIngredientHelper$IFocusFactory}。</li>
 * </ul>
 *
 * <p><b>本类不直接引用任何 {@code mezz.jei.*} 类型</b>，因此未装 JEI 时也能安全初始化。
 */
public final class JeiCapabilities {

    /** 编译基线：低于这个版本时聊天栏提示升级。 */
    public static final String FULL_API_VERSION = "4.8.5.160";

    /** 能加载的最低 JEI 版本（4.7.8.95 起 {@code IModPlugin} 的形态与本模组一致）。 */
    public static final String MIN_VERSION = "4.7.8";

    /** 书签导入的最低 JEI 版本（{@code IBookmarkOverlay} / {@code IJeiRuntime#getBookmarkOverlay()}）。 */
    public static final String BOOKMARK_API_VERSION = "4.15.0.276";

    private static final String JEI_MOD_ID = "jei";

    private static final boolean INSTALLED = classPresent("mezz.jei.api.IModPlugin");
    private static final String VERSION = detectVersion();

    private static final boolean SCREEN_HANDLERS = classPresent("mezz.jei.api.gui.IGuiScreenHandler");
    private static final boolean INGREDIENT_TYPE = classPresent("mezz.jei.api.recipe.IIngredientType");
    private static final boolean GLOBAL_GUI_HANDLER = classPresent("mezz.jei.api.gui.IGlobalGuiHandler");
    private static final boolean BOOKMARK_API = classPresent("mezz.jei.api.IBookmarkOverlay");
    private static final boolean FOCUS_FACTORY =
            classPresent("mezz.jei.api.ingredients.IIngredientHelper$IFocusFactory");

    static {
        if (INSTALLED) {
            RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 探测: version={} screenHandlers={} "
                            + "ingredientType={} globalHandler={} bookmarkApi={} focusFactory={}",
                    VERSION, SCREEN_HANDLERS, INGREDIENT_TYPE, GLOBAL_GUI_HANDLER,
                    BOOKMARK_API, FOCUS_FACTORY);
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

    /** 一行描述，用于启动日志。 */
    public static String describe() {
        return "JEI " + VERSION + (hasFullApi() ? "（完整能力）" : "（低版本，能力降级）");
    }

    /** 4.8.4+：可以注册普通 GuiScreen 的面板让位与拖拽 handler。 */
    public static boolean hasScreenHandlers() {
        return SCREEN_HANDLERS;
    }

    /** 4.12+：{@code IIngredientType} 存在（本模组仍走 Class 系 API，仅用于诊断）。 */
    public static boolean hasIngredientType() {
        return INGREDIENT_TYPE;
    }

    /** 4.14+：{@code IGlobalGuiHandler} 存在。 */
    public static boolean hasGlobalGuiHandler() {
        return GLOBAL_GUI_HANDLER;
    }

    /** 4.15+：公开的书签 API 存在（类存在 + 版本达标才算）。 */
    public static boolean hasBookmarkApi() {
        return INSTALLED && BOOKMARK_API && versionAtLeast(BOOKMARK_API_VERSION);
    }

    /** 4.16+：公开的焦点工厂存在。 */
    public static boolean hasFocusFactory() {
        return FOCUS_FACTORY;
    }

    /** 编译基线及以上：本模组按完整能力运行。 */
    public static boolean hasFullApi() {
        return INSTALLED && SCREEN_HANDLERS && versionAtLeast(FULL_API_VERSION);
    }

    /** 当前 JEI 版本是否 >= 给定版本（{@code 4.8} 视为 {@code 4.8.0}）。 */
    public static boolean versionAtLeast(String required) {
        return compareVersions(VERSION, required) >= 0;
    }

    /**
     * 逐段数字比较版本号（缺失段按 0）。
     *
     * @return 负数表示 {@code left < right}
     */
    public static int compareVersions(String left, String right) {
        if (left == null || right == null) {
            return -1;
        }
        String[] a = left.trim().split("[^0-9]+");
        String[] b = right.trim().split("[^0-9]+");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = number(a, i);
            int y = number(b, i);
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, JeiCapabilities.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String detectVersion() {
        try {
            if (!Loader.isModLoaded(JEI_MOD_ID)) {
                return "未安装";
            }
            Map<String, ModContainer> mods = Loader.instance().getIndexedModList();
            ModContainer container = mods.get(JEI_MOD_ID);
            if (container == null) {
                return "未知";
            }
            String version = container.getVersion();
            return version == null ? "未知" : version;
        } catch (Throwable t) {
            return "未知";
        }
    }
}
