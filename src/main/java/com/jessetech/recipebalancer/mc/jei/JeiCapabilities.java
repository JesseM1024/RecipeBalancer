package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * JEI 运行期能力探测（1.16.5 / JEI 7.6 ~ 7.8）。
 *
 * <p>1.16.5 世代只有 JEI 7.x 一条线，接口在 7.6.1 ~ 7.8.1 之间没有变化：
 * 没有配方页按钮 API、没有 {@code IRecipeCategoryDecorator}、没有
 * {@code IClickableIngredientFactory}。这些能力探测保留下来只是为了日志可读，
 * 并给旧代码一个统一的「不支持」结论。
 *
 * <p><b>本类不直接引用任何 {@code mezz.jei.*} 类型</b>，因此未装 JEI 时也能安全初始化。
 */
public final class JeiCapabilities {

    private static final String JEI_MOD_ID = "jei";

    private static final boolean INSTALLED = detectInstalled();
    private static final String VERSION = detectVersion();

    static {
        if (FMLEnvironment.dist.isClient() && RecipeBalancerMod.LOGGER.isDebugEnabled()) {
            RecipeBalancerMod.LOGGER.debug("[RecipeBalancer] JEI 探测: installed={} version={}",
                    INSTALLED, VERSION);
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
        return "JEI " + VERSION + "（1.16.5 / 7.x 单代实现）";
    }

    /** 7.x 没有可点击配料工厂重载，恒为 false。 */
    public static boolean supportsClickableFactory() {
        return false;
    }

    /** 7.x 没有配方页按钮 API，恒为 false。 */
    public static boolean supportsRecipeButtons() {
        return false;
    }

    /** 7.x 没有 {@code IRecipeCategoryDecorator}，恒为 false。 */
    public static boolean supportsCategoryDecorator() {
        return false;
    }

    /** 7.x 的幽灵拖拽区域一律是绝对屏幕坐标。 */
    public static boolean ghostAreasGuiRelative() {
        return false;
    }

    private static boolean detectInstalled() {
        try {
            Class<?> plugin = Class.forName("mezz.jei.api.IModPlugin", false,
                    JeiCapabilities.class.getClassLoader());
            return plugin != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String detectVersion() {
        try {
            Optional<?> container = ModList.get().getModContainerById(JEI_MOD_ID);
            if (!container.isPresent()) {
                return "未安装";
            }
            Object value = container.get();
            Method method = value.getClass().getMethod("getModInfo");
            Object info = method.invoke(value);
            Method version = info.getClass().getMethod("getVersion");
            Object result = version.invoke(info);
            return result == null ? "未知" : result.toString();
        } catch (Throwable t) {
            return "未知";
        }
    }
}
