package com.jessetech.recipebalancer.core.solve;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code scipy.linprog(method="highs")} → commons-math3 {@code SimplexSolver} 的适配
 * （1.16.5 版：**反射加载随包附带的 math3**）。
 *
 * <p>为什么不直接 {@code new SimplexSolver()}：
 * 1.16.5 的 modlauncher 类加载器把 {@code org.apache.*} 视为「游戏库命名空间」，
 * 模组 jar 里同包的类只能作为资源被看到、{@code Class.forName} 却会
 * {@code ClassNotFoundException}（实测：{@code loader.getResource} 能拿到
 * {@code modjar://recipebalancer/org/apache/...}，但类就是加载不出来）。
 *
 * <p>因此构建时把 {@code commons-math3} 作为「jar 内嵌套 jar」放在
 * {@code /recipebalancer/lib/math3.jar}，运行期解压到临时目录，再用独立的
 * {@link URLClassLoader} 加载 —— 绕开命名空间限制，求解结果与 1.18.2 完全一致
 * （用的是同一份 math3 3.6.1 代码，只是换了加载方式）。
 */
public final class CommonsMath3LpSolver implements LpSolver {

    /** simplex 的 pivot/零判定容差。HiGHS 默认量级 1e-7~1e-9，这里先取更严的 1e-10。 */
    public static final double DEFAULT_EPSILON = 1e-10;

    public static final int DEFAULT_MAX_ITERATIONS = 200_000;

    /** 模组 jar 内的嵌套库路径。 */
    private static final String LIB_RESOURCE = "/recipebalancer/lib/math3.jar";

    private static volatile ClassLoader math3Loader;
    private static volatile Throwable loadFailure;
    private static volatile boolean loadAttempted;

    private final double epsilon;
    private final int maxIterations;

    public CommonsMath3LpSolver() {
        this(DEFAULT_EPSILON, DEFAULT_MAX_ITERATIONS);
    }

    public CommonsMath3LpSolver(double epsilon, int maxIterations) {
        this.epsilon = epsilon;
        this.maxIterations = maxIterations;
    }

    /** 求解库是否可用（启动自检用）。 */
    public static boolean available() {
        return loader() != null;
    }

    /** 加载失败的原因（可用时为 null）。 */
    public static Throwable failure() {
        return loadFailure;
    }

    /**
     * 取得 math3 的类加载器：优先解压 jar 内嵌套库，其次退回当前 classpath
     * （单测里 math3 是普通依赖，第二种路径就够）。
     */
    private static ClassLoader loader() {
        if (math3Loader != null) {
            return math3Loader;
        }
        synchronized (CommonsMath3LpSolver.class) {
            if (math3Loader != null) {
                return math3Loader;
            }
            if (loadAttempted) {
                return null;
            }
            loadAttempted = true;
            try {
                math3Loader = extractAndLoad();
            } catch (Throwable t) {
                loadFailure = t;
                math3Loader = null;
            }
            if (math3Loader == null) {
                // 测试/开发环境：math3 直接在 classpath 上
                try {
                    Class.forName("org.apache.commons.math3.optim.linear.SimplexSolver");
                    math3Loader = CommonsMath3LpSolver.class.getClassLoader();
                } catch (Throwable ignored) {
                    // 两种路径都不行，保持 null
                }
            }
            return math3Loader;
        }
    }

    private static ClassLoader extractAndLoad() throws Exception {
        InputStream in = CommonsMath3LpSolver.class.getResourceAsStream(LIB_RESOURCE);
        if (in == null) {
            return null;
        }
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "recipebalancer-lib");
        Files.createDirectories(dir);
        Path jar = dir.resolve("math3-3.6.1.jar");
        try (InputStream stream = in) {
            Files.copy(stream, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        // 父加载器用 null（bootstrap）：math3 只依赖 JDK，隔离最彻底
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
    }

    @Override
    public LpResult minimize(double[] objective, List<LpConstraint> constraints) {
        ClassLoader cl = loader();
        if (cl == null) {
            return LpResult.failure(LpResult.Status.FAILED,
                    "commons-math3 不可用" + (loadFailure == null ? "" : ": " + loadFailure));
        }
        int variableCount = objective.length;
        try {
            Class<?> objectiveClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.LinearObjectiveFunction", true, cl);
            Object function = objectiveClass
                    .getConstructor(double[].class, double.class)
                    .newInstance(objective.clone(), 0.0);

            Class<?> relationshipClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.Relationship", true, cl);
            Class<?> constraintClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.LinearConstraint", true, cl);
            List<Object> converted = new ArrayList<>(constraints.size());
            for (LpConstraint constraint : constraints) {
                if (constraint.coefficients().length != variableCount) {
                    throw new IllegalArgumentException("约束系数长度与目标函数不一致");
                }
                Object relationship = enumValue(relationshipClass,
                        constraint.relation() == Relation.LE ? "LEQ"
                                : constraint.relation() == Relation.GE ? "GEQ" : "EQ");
                converted.add(constraintClass
                        .getConstructor(double[].class, relationshipClass, double.class)
                        .newInstance(constraint.coefficients().clone(), relationship,
                                constraint.rhs()));
            }

            Class<?> setClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.LinearConstraintSet", true, cl);
            Object constraintSet = setClass
                    .getConstructor(Collection.class)
                    .newInstance(converted);

            Class<?> goalClass = Class.forName(
                    "org.apache.commons.math3.optim.nonlinear.scalar.GoalType", true, cl);
            Object goal = enumValue(goalClass, "MINIMIZE");

            Class<?> nonNegativeClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.NonNegativeConstraint", true, cl);
            Object nonNegative = nonNegativeClass
                    .getConstructor(boolean.class)
                    .newInstance(true);

            Class<?> solverClass = Class.forName(
                    "org.apache.commons.math3.optim.linear.SimplexSolver", true, cl);
            Object solver = solverClass
                    .getConstructor(double.class, int.class)
                    .newInstance(epsilon, maxIterations);

            Class<?> dataClass = Class.forName(
                    "org.apache.commons.math3.optim.OptimizationData", true, cl);
            // 用真正的 OptimizationData[] 承载参数：Object[] 与它不做协变，
            // 直接传 Object[] 会得到 IllegalArgumentException: argument type mismatch
            Object dataArray = java.lang.reflect.Array.newInstance(dataClass, 4);
            java.lang.reflect.Array.set(dataArray, 0, function);
            java.lang.reflect.Array.set(dataArray, 1, constraintSet);
            java.lang.reflect.Array.set(dataArray, 2, goal);
            java.lang.reflect.Array.set(dataArray, 3, nonNegative);
            Method optimize = solverClass.getMethod("optimize",
                    java.lang.reflect.Array.newInstance(dataClass, 0).getClass());
            Object pair = optimize.invoke(solver, (Object) dataArray);
            double[] point = (double[]) pair.getClass().getMethod("getPoint").invoke(pair);
            return LpResult.optimal(point);
        } catch (InvocationTargetException e) {
            return mapFailure(e.getCause());
        } catch (ReflectiveOperationException e) {
            return LpResult.failure(LpResult.Status.FAILED, "math3 反射调用失败: " + e);
        } catch (RuntimeException e) {
            return LpResult.failure(LpResult.Status.FAILED,
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /** 把 math3 的异常映射成我们的状态（异常类名判断，避免编译期依赖）。 */
    private static LpResult mapFailure(Throwable cause) {
        String name = cause == null ? "" : cause.getClass().getSimpleName();
        if ("NoFeasibleSolutionException".equals(name)) {
            return LpResult.failure(LpResult.Status.INFEASIBLE, "infeasible");
        }
        if ("UnboundedSolutionException".equals(name)) {
            return LpResult.failure(LpResult.Status.UNBOUNDED, "unbounded");
        }
        if ("TooManyIterationsException".equals(name)) {
            return LpResult.failure(LpResult.Status.FAILED, "too many iterations");
        }
        return LpResult.failure(LpResult.Status.FAILED,
                cause == null ? "unknown" : cause.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }
}
