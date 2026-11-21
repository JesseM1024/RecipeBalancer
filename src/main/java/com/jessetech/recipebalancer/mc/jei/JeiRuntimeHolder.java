package com.jessetech.recipebalancer.mc.jei;

/**
 * 持有 JEI 的 {@code IJeiRuntime} 与 {@code IIngredientRegistry}（都以 {@code Object} 存）。
 *
 * <p>本类<b>不引用任何 {@code mezz.jei.*} 类型</b>：未安装 JEI 时它也要能安全加载，
 * 常驻代码（主类、界面、core）只允许通过本类与 {@link JeiCapabilities} 判断 JEI 状态。
 *
 * <p>为什么还要单独存配料注册表：1.12.2 的 {@code IJeiRuntime} 没有
 * {@code getIngredientManager()}，配料注册表只能从 {@code IModRegistry#getIngredientRegistry()}
 * 在 {@code register(...)} 阶段拿到，因此运行期一直持有它。
 */
public final class JeiRuntimeHolder {

    private static volatile Object runtime;
    private static volatile Object ingredientRegistry;

    private JeiRuntimeHolder() {
    }

    /** runtime 卸载（JEI 重载/退出世界）时清空，避免持有失效对象。 */
    public static void clear() {
        set(null);
    }

    public static void set(Object value) {
        runtime = value;
    }

    public static Object get() {
        return runtime;
    }

    public static void setIngredientRegistry(Object value) {
        ingredientRegistry = value;
    }

    public static Object ingredientRegistry() {
        return ingredientRegistry;
    }

    /** JEI 已安装（不一定 runtime 就绪）。 */
    public static boolean installed() {
        return JeiCapabilities.installed();
    }

    /** runtime 已就绪，可以调用 JEI API。 */
    public static boolean ready() {
        return runtime != null;
    }

    /** 配料注册表已就绪。 */
    public static boolean ingredientsReady() {
        return ingredientRegistry != null;
    }
}
