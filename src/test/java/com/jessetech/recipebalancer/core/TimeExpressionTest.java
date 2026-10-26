package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.time.RateUnit;
import com.jessetech.recipebalancer.core.time.TimeExpression;
import com.jessetech.recipebalancer.core.time.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 耗时表达式与单位换算（t / 秒 / 分 / 时）。 */
class TimeExpressionTest {

    @Test
    void evaluatesPrecedenceAndParentheses() {
        assertEquals(7.0, TimeExpression.evaluate("1+2*3"), 1e-12);
        assertEquals(9.0, TimeExpression.evaluate("(1+2)*3"), 1e-12);
        assertEquals(512.0, TimeExpression.evaluate("2^3^2"), 1e-12);
        assertEquals(-4.0, TimeExpression.evaluate("-2^2"), 1e-12);
        assertEquals(0.5, TimeExpression.evaluate("1/2"), 1e-12);
        assertEquals(2.5, TimeExpression.evaluate("1.5+1"), 1e-12);
    }

    @Test
    void convertsUnitsToTicks() {
        assertEquals(10.0, TimeExpression.toTicks("10", TimeUnit.TICK), 1e-12);
        assertEquals(200.0, TimeExpression.toTicks("10", TimeUnit.SECOND), 1e-12);
        assertEquals(1200.0, TimeExpression.toTicks("1", TimeUnit.MINUTE), 1e-12);
        assertEquals(72000.0, TimeExpression.toTicks("1", TimeUnit.HOUR), 1e-12);
        assertEquals(30.0, TimeExpression.toTicks("3*0.5", TimeUnit.SECOND), 1e-12);
    }

    @Test
    void rejectsInvalidExpressions() {
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.evaluate(""));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.evaluate("1/0"));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.evaluate("(1+2"));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.evaluate("1+"));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.evaluate("abc"));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.toTicks("0", TimeUnit.TICK));
        assertThrows(IllegalArgumentException.class, () -> TimeExpression.toTicks("-1", TimeUnit.TICK));
    }

    @Test
    void convertsRateUnitsFromPerTick() {
        assertEquals(20.0, RateUnit.SECOND.fromPerTick(1.0), 1e-12);
        assertEquals(1.0 / 20.0, RateUnit.SECOND.toPerTick(1.0), 1e-12);
        assertEquals(1200.0, RateUnit.MINUTE.fromPerTick(1.0), 1e-12);
        assertEquals(72000.0, RateUnit.HOUR.fromPerTick(1.0), 1e-12);
    }

    @Test
    void formatsBestUnit() {
        assertEquals("10 t", TimeExpression.formatTicks(10));
        assertEquals("1 秒", TimeExpression.formatTicks(20));
        assertEquals("1 分", TimeExpression.formatTicks(1200));
        assertEquals("1 时", TimeExpression.formatTicks(72000));
        assertEquals("1.5 秒", TimeExpression.formatTicks(30));
        // 一级展示 3 位；编辑值保留 double 的完整十进制
        assertEquals("0.001 t", TimeExpression.formatTicks(0.001));
        assertEquals("0.001", TimeExpression.formatNumber(0.001));
        assertEquals("0", TimeExpression.formatNumber(0.00000000001));
        assertEquals("0.00000000001", TimeExpression.formatEditableNumber(0.00000000001));
        assertEquals("0.00000000001 t", TimeExpression.formatExactTicks(0.00000000001));

        // 一级紧凑显示：μ/m/k/M/G/T
        assertEquals("30k", TimeExpression.formatCompact(30000));
        assertEquals("100k", TimeExpression.formatCompact(100000));
        assertEquals("1M", TimeExpression.formatCompact(1000000));
        assertEquals("500μ", TimeExpression.formatCompact(0.0005));
        assertEquals("1n", TimeExpression.formatCompact(1e-9));
        assertEquals("1p", TimeExpression.formatCompact(1e-12));
        assertEquals("1f", TimeExpression.formatCompact(1e-15));
        assertEquals("1P", TimeExpression.formatCompact(1e15));
        assertEquals("1E", TimeExpression.formatCompact(1e18));
    }
}
