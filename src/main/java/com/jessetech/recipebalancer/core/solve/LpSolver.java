package com.jessetech.recipebalancer.core.solve;

import java.util.List;
import java.util.Objects;

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

    /** 一条线性约束（1.16.5 线是 Java 8，不能写 record）。 */
    final class LpConstraint {

        private final double[] coefficients;
        private final Relation relation;
        private final double rhs;

        public LpConstraint(double[] coefficients, Relation relation, double rhs) {
            this.coefficients = coefficients;
            this.relation = relation;
            this.rhs = rhs;
        }

        public double[] coefficients() {
            return coefficients;
        }

        public Relation relation() {
            return relation;
        }

        public double rhs() {
            return rhs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LpConstraint)) {
                return false;
            }
            LpConstraint other = (LpConstraint) o;
            // 与 record 的语义保持一致：数组按引用比较（record 用的是 Objects.equals）
            return Objects.equals(coefficients, other.coefficients)
                    && relation == other.relation
                    && Double.compare(rhs, other.rhs) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(coefficients, relation, rhs);
        }

        @Override
        public String toString() {
            return "LpConstraint[relation=" + relation + ", rhs=" + rhs
                    + ", coefficients=" + java.util.Arrays.toString(coefficients) + "]";
        }
    }

    /** 求解结果（1.16.5 线是 Java 8，不能写 record）。 */
    final class LpResult {

        public enum Status { OPTIMAL, INFEASIBLE, UNBOUNDED, FAILED }

        private final Status status;
        private final double[] point;
        private final String message;

        public LpResult(Status status, double[] point, String message) {
            this.status = status;
            this.point = point;
            this.message = message;
        }

        public Status status() {
            return status;
        }

        public double[] point() {
            return point;
        }

        public String message() {
            return message;
        }

        public boolean success() {
            return status == Status.OPTIMAL;
        }

        public static LpResult optimal(double[] point) {
            return new LpResult(Status.OPTIMAL, point, "optimal");
        }

        public static LpResult failure(Status status, String message) {
            return new LpResult(status, new double[0], message);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LpResult)) {
                return false;
            }
            LpResult other = (LpResult) o;
            return status == other.status
                    && Objects.equals(point, other.point)
                    && Objects.equals(message, other.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, point, message);
        }

        @Override
        public String toString() {
            return "LpResult[status=" + status + ", point=" + java.util.Arrays.toString(point)
                    + ", message=" + message + "]";
        }
    }
}
