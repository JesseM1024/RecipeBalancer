package com.jessetech.recipebalancer.core.solve;

import java.util.List;

/**
 * LP 后端抽象：为替换求解器（ojAlgo / 未来 HiGHS-JNI）留缝。
 *
 * <p>**所有变量假定 >= 0**（对应 scipy {@code bounds=(0, None)}）。
 * 需要"变量固定为 0"（禁用配方、非白名单亏损量）时，由调用方**删列**实现——
 * commons-math3 没有任意上下界 API，不要在适配层里伪造 bounds。
 */
public interface LpSolver {

    LpResult minimize(double[] objective, List<LpConstraint> constraints);

    enum Relation { LE, GE, EQ }

    record LpConstraint(double[] coefficients, Relation relation, double rhs) {
    }

    record LpResult(Status status, double[] point, String message) {
        public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED, FAILED }

        public boolean success() {
            return status == Status.OPTIMAL;
        }

        public static LpResult optimal(double[] point) {
            return new LpResult(Status.OPTIMAL, point, "optimal");
        }

        public static LpResult failure(Status status, String message) {
            return new LpResult(status, new double[0], message);
        }
    }
}
