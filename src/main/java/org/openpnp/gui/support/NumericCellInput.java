package org.openpnp.gui.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;

/** Arithmetic for table input only. Never evaluates scripts or changes stored model parsing. */
public final class NumericCellInput {
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private NumericCellInput() {
    }

    public static String compact(String text) {
        if (text == null) {
            throw new IllegalArgumentException("请输入数字或算式");
        }
        StringBuilder result = new StringBuilder();
        text.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    public static boolean isFormula(String text) {
        String value = compact(text);
        return value.startsWith("=") || value.startsWith("+=") || value.startsWith("-=");
    }

    public static boolean isNumericType(Class<?> type) {
        return Number.class.isAssignableFrom(type) || type == LengthCellValue.class
                || type == RotationCellValue.class;
    }

    /** Text columns need an explicit '=' prefix, unless their model declares numeric text. */
    public static Object convert(String text, Object current, Class<?> type, boolean numericText) {
        if (type == String.class && !numericText && !isFormula(text)) {
            return text;
        }
        String value = compact(text);
        boolean add = value.startsWith("+=");
        boolean subtract = value.startsWith("-=");
        boolean relative = add || subtract;
        value = value.substring(relative ? 2 : value.startsWith("=") ? 1 : 0);

        if (type == LengthCellValue.class) {
            LengthCellValue old = (LengthCellValue) current;
            LengthUnit units = null;
            // A trailing unit applies to the entire expression, e.g. '(1 + 2) mm'.
            String normalized = value.replace('u', 'μ');
            int suffixLength = 0;
            for (LengthUnit candidate : LengthUnit.values()) {
                String suffix = candidate.getShortName();
                if (suffix.length() > suffixLength && normalized.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(suffix.toLowerCase(java.util.Locale.ROOT))) {
                    units = candidate;
                    suffixLength = suffix.length();
                }
            }
            value = normalized.substring(0, normalized.length() - suffixLength);
            BigDecimal result = evaluate(value);
            if (relative) {
                if (old == null || old.getLength() == null) {
                    throw new IllegalArgumentException("当前单元格没有可增减的数值");
                }
                Length oldLength = old.getLength();
                LengthUnit displayUnits = old.isDisplayNativeUnits() && oldLength.getUnits() != null
                        ? oldLength.getUnits() : Configuration.get().getSystemUnits();
                Length delta = new Length(finite(result), units == null ? displayUnits : units)
                        .convertToUnits(displayUnits);
                BigDecimal base = BigDecimal.valueOf(oldLength.changeUnitsIfUnspecified(displayUnits)
                        .convertToUnits(displayUnits).getValue());
                result = subtract ? base.subtract(BigDecimal.valueOf(delta.getValue()))
                        : base.add(BigDecimal.valueOf(delta.getValue()));
                units = displayUnits;
            }
            return new LengthCellValue(new Length(finite(result), units));
        }

        boolean percent = type == String.class && numericText && current != null
                && compact(current.toString()).endsWith("%");
        if (percent && value.endsWith("%")) {
            value = value.substring(0, value.length() - 1);
        }
        BigDecimal result = evaluate(value);
        if (relative) {
            BigDecimal base;
            if (current instanceof RotationCellValue) {
                base = BigDecimal.valueOf(((RotationCellValue) current).getRotation());
            }
            else if (current != null) {
                String original = compact(current.toString());
                if (percent) {
                    original = original.substring(0, original.length() - 1);
                }
                base = evaluate(original);
            }
            else {
                throw new IllegalArgumentException("当前单元格没有可增减的数值");
            }
            result = subtract ? base.subtract(result) : base.add(result);
        }
        if (type == RotationCellValue.class) {
            return new RotationCellValue(finite(result));
        }
        if (type == String.class) {
            finite(result);
            return result.stripTrailingZeros().toPlainString() + (percent ? "%" : "");
        }
        if (type == Integer.class) {
            return result.intValueExact();
        }
        if (type == Long.class) {
            return result.longValueExact();
        }
        if (type == Short.class) {
            return result.shortValueExact();
        }
        if (type == Byte.class) {
            return result.byteValueExact();
        }
        if (type == BigInteger.class) {
            return result.toBigIntegerExact();
        }
        if (type == BigDecimal.class) {
            return result;
        }
        if (type == Float.class) {
            float number = result.floatValue();
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("数值超出范围");
            }
            return number;
        }
        return finite(result);
    }

    private static double finite(BigDecimal value) {
        double number = value.doubleValue();
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("数值超出范围");
        }
        return number;
    }

    public static BigDecimal evaluate(String text) {
        String expression = compact(text);
        if (expression.length() > 512 || expression.isEmpty()) {
            throw new IllegalArgumentException("算式为空或过长");
        }
        Parser parser = new Parser(expression);
        BigDecimal result = parser.sum();
        if (parser.position != expression.length()) {
            throw new IllegalArgumentException("算式中有无法识别的内容");
        }
        return result;
    }

    private static final class Parser {
        private final String expression;
        private int position;

        private Parser(String expression) {
            this.expression = expression;
        }

        private boolean take(char symbol) {
            if (position < expression.length() && expression.charAt(position) == symbol) {
                position++;
                return true;
            }
            return false;
        }

        private BigDecimal sum() {
            BigDecimal result = product();
            while (position < expression.length()) {
                if (take('+')) {
                    result = result.add(product(), PRECISION);
                }
                else if (take('-')) {
                    result = result.subtract(product(), PRECISION);
                }
                else {
                    break;
                }
            }
            return result;
        }

        private BigDecimal product() {
            BigDecimal result = atom();
            while (position < expression.length()) {
                if (take('*')) {
                    result = result.multiply(atom(), PRECISION);
                }
                else if (take('/')) {
                    result = result.divide(atom(), PRECISION);
                }
                else {
                    break;
                }
            }
            return result;
        }

        private BigDecimal atom() {
            if (take('+')) {
                return atom();
            }
            if (take('-')) {
                return atom().negate();
            }
            if (take('(')) {
                BigDecimal result = sum();
                if (!take(')')) {
                    throw new IllegalArgumentException("算式缺少右括号");
                }
                return result;
            }
            int start = position;
            while (position < expression.length()
                    && (Character.isDigit(expression.charAt(position))
                            || expression.charAt(position) == '.')) {
                position++;
            }
            if (position < expression.length()
                    && (expression.charAt(position) == 'e' || expression.charAt(position) == 'E')) {
                position++;
                if (!take('+')) {
                    take('-');
                }
                while (position < expression.length() && Character.isDigit(expression.charAt(position))) {
                    position++;
                }
            }
            BigDecimal number = new BigDecimal(expression.substring(start, position), PRECISION);
            // Bound exponent expansion before values can reach toPlainString() or the model.
            if (Math.abs((long) number.scale()) > 1024) {
                throw new IllegalArgumentException("数值超出范围");
            }
            return number;
        }
    }
}
