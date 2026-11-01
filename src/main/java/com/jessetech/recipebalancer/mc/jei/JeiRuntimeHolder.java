package com.jessetech.recipebalancer.mc.jei;

/**
 * JEI 运行时状态查询（1.20.1 分支：JEI 集成已停用）。
 *
 * <p>本分支不依赖 JEI：{@link #installed()} 与 {@link #ready()} 恒为 {@code false}，
 * 界面据此把所有 JEI 入口降级为「暂不可用」提示。
 *
 * <p>恢复 JEI 时从 main 分支取回本类的完整实现（持有 {@code IJeiRuntime} 引用）。
 */
public final class JeiRuntimeHolder {

    private JeiRuntimeHolder() {
    }

    /** JEI 是否已安装 —— 1.20.1 分支恒为 false（JEI 快捷功能停用）。 */
    public static boolean installed() {
        return false;
    }

    /** JEI 是否可用（已安装且插件已拿到 runtime）—— 1.20.1 分支恒为 false。 */
    public static boolean ready() {
        return false;
    }
}
