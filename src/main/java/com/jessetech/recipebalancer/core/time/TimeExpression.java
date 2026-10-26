package com.jessetech.recipebalancer.core.time;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 耗时表达式：数字与 {@code + - * / ^}、括号、一元正负号。
 *
 * <p>示例：{@code "10"}、{@code "2*20"}、{@code "(1+1)^3"}、{@code "1/0.5"}。
 * 结果按 {@link TimeUnit} 换算成 tick；写入场景时再换算成秒（schema 与 Python 保持同构）。
 */
public final class TimeExpression {

    private static final int MAX_LENGTH = 128;

    private TimeExpression() {
    }

    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("表达式过长");
        }
        Parser parser = new Parser(expression);
        double value = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("表达式存在多余字符: " + parser.rest());
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("表达式结果不是有限数");
        }
        return value;
    }

    /** 表达式按指定单位解释，返回 tick。 */
    public static double toTicks(String expression, TimeUnit unit) {
        double value = evaluate(expression);
        if (!(value > 0)) {
            throw new IllegalArgumentException("耗时必须为正数");
        }
        double ticks = unit.toTicks(value);
        if (!Double.isFinite(ticks) || ticks <= 0) {
            throw new IllegalArgumentException("耗时超出范围");
        }
        return ticks;
    }

    /** 展示：自动选单位，最多六位小数（去掉末尾 0），保留 0.001t 这类小值。 */
    public static String formatTicks(double ticks) {
        TimeUnit unit = TimeUnit.bestFor(ticks);
        return formatNumber(unit.fromTicks(ticks)) + " " + unit.label();
    }

    /** 精确 tick 展示（tooltip / 二级预览用）：保留 double 的完整十进制，不量化。 */
    public static String formatExactTicks(double ticks) {
        return formatEditableNumber(ticks) + " t";
    }

    /** 一级界面展示：3 位小数足够；编辑数据请用 {@link #formatEditableNumber(double)}。 */
    public static String formatNumber(double value) {
        String text = String.format(Locale.ROOT, "%.3f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.isEmpty() || "-0".equals(text) ? "0" : text;
    }

    /**
     * 编辑框里的数值：不截断小数，保持 double 的实际十进制表示。
     * 例如 0.00000000001 → "0.00000000001"，而不是 "0"。
     */
    public static String formatEditableNumber(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static final class Parser {
        private final String input;
        private int pos;

        private Parser(String input) {
            this.input = input;
        }

        private boolean atEnd() {
            return pos >= input.length();
        }

        private String rest() {
            return input.substring(Math.min(pos, input.length()));
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private boolean consume(char expected) {
            skipWhitespace();
            if (!atEnd() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (consume('+')) {
                    value += parseTerm();
                } else if (consume('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseUnary();
            while (true) {
                if (consume('*')) {
                    value *= parseUnary();
                } else if (consume('/')) {
                    double divisor = parseUnary();
                    if (divisor == 0.0) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseUnary() {
            if (consume('+')) {
                return parseUnary();
            }
            if (consume('-')) {
                return -parseUnary();
            }
            return parsePower();
        }

        /** 右结合：2^3^2 = 2^(3^2)。 */
        private double parsePower() {
            double base = parsePrimary();
            if (consume('^')) {
                return Math.pow(base, parseUnary());
            }
            return base;
        }

        private double parsePrimary() {
            skipWhitespace();
            if (consume('(')) {
                double value = parseExpression();
                if (!consume(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (!atEnd()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                        || ((c == '+' || c == '-') && pos > start
                        && (input.charAt(pos - 1) == 'e' || input.charAt(pos - 1) == 'E'))) {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("这里需要一个数字");
            }
            String text = input.substring(start, pos);
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字: " + text);
            }
        }
    }
}
