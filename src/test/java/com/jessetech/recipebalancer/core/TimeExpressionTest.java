package com.jessetech.recipebalancer.core;

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
    void formatsBestUnit() {
        assertEquals("10 t", TimeExpression.formatTicks(10));
        assertEquals("1 秒", TimeExpression.formatTicks(20));
        assertEquals("1 分", TimeExpression.formatTicks(1200));
        assertEquals("1 时", TimeExpression.formatTicks(72000));
        assertEquals("1.5 秒", TimeExpression.formatTicks(30));
    }
}
