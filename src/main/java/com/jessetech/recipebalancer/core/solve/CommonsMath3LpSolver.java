package com.jessetech.recipebalancer.core.solve;

import org.apache.commons.math3.exception.MathRuntimeException;
import org.apache.commons.math3.exception.TooManyIterationsException;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NoFeasibleSolutionException;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.linear.UnboundedSolutionException;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code scipy.linprog(method="highs")} → commons-math3 {@code SimplexSolver} 的适配。
 *
 * <p>变量全部非负（{@code restrictToNonNegative=true}），固定为 0 的变量由调用方删列。
 * 数值容差与迭代上限可通过构造器覆盖——S-04 对拍时用于挑选合适的 epsilon。
 */
public final class CommonsMath3LpSolver implements LpSolver {

    /** simplex 的 pivot/零判定容差。HiGHS 默认量级 1e-7~1e-9，这里先取更严的 1e-10。 */
    public static final double DEFAULT_EPSILON = 1e-10;

    public static final int DEFAULT_MAX_ITERATIONS = 200_000;

    private final double epsilon;
    private final int maxIterations;

    public CommonsMath3LpSolver() {
        this(DEFAULT_EPSILON, DEFAULT_MAX_ITERATIONS);
    }

    public CommonsMath3LpSolver(double epsilon, int maxIterations) {
        this.epsilon = epsilon;
        this.maxIterations = maxIterations;
    }

    @Override
    public LpResult minimize(double[] objective, List<LpConstraint> constraints) {
        int variableCount = objective.length;
        LinearObjectiveFunction function = new LinearObjectiveFunction(objective.clone(), 0.0);
        List<LinearConstraint> converted = new ArrayList<>(constraints.size());
        for (LpConstraint constraint : constraints) {
            if (constraint.coefficients().length != variableCount) {
                throw new IllegalArgumentException("约束系数长度与目标函数不一致");
            }
            converted.add(new LinearConstraint(constraint.coefficients().clone(),
                    toRelationship(constraint.relation()), constraint.rhs()));
        }

        try {
            PointValuePair solution = new SimplexSolver(epsilon, maxIterations)
                    .optimize(function, new LinearConstraintSet(converted),
                            GoalType.MINIMIZE, new NonNegativeConstraint(true));
            return LpResult.optimal(solution.getPoint());
        } catch (NoFeasibleSolutionException e) {
            return LpResult.failure(LpResult.Status.INFEASIBLE, "infeasible");
        } catch (UnboundedSolutionException e) {
            return LpResult.failure(LpResult.Status.UNBOUNDED, "unbounded");
        } catch (TooManyIterationsException e) {
            return LpResult.failure(LpResult.Status.FAILED, "too many iterations");
        } catch (MathRuntimeException e) {
            return LpResult.failure(LpResult.Status.FAILED, e.getMessage());
        }
    }

    private static Relationship toRelationship(Relation relation) {
        return switch (relation) {
            case LE -> Relationship.LEQ;
            case GE -> Relationship.GEQ;
            case EQ -> Relationship.EQ;
        };
    }
}
