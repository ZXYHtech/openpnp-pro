package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;

class NumericCellInputTest {
    @BeforeEach
    void configuration() {
        Configuration.initialize();
        Configuration.get().setSystemUnits(LengthUnit.Millimeters);
    }

    @Test
    void ignoresSpacesTabsAndFullWidthSpacesInsideNumbersAndFormulas() {
        assertEquals(12.8, convert(" 1 2 . 5　+\t0. 3 ", 0.0, Double.class));
        assertEquals(12.8, convert("1\u00a02.5\u202f+0.3", 0.0, Double.class));
    }

    @Test
    void decimalsPrecedenceParenthesesUnaryAndScientificNotation() {
        assertEquals(0, new BigDecimal("0.3").compareTo(NumericCellInput.evaluate("0.1 + 0.2")));
        assertEquals(-7.0, convert("= -( 1 + 2 ) * 3 + 2", 0.0, Double.class));
        assertEquals(0.003, convert("1 e - 3 + 2 e - 3", 0.0, Double.class));
    }

    @Test
    void relativeOperationsUseOriginalValueAndMinusAloneIsAbsolute() {
        assertEquals(1.3, convert("+ = 0. 3", 1.0, Double.class));
        assertEquals(0.7, convert("- = 0.3", 1.0, Double.class));
        assertEquals(-0.3, convert("-0.3", 1.0, Double.class));
    }

    @Test
    void integerColumnsAreExactAndRejectOverflow() {
        assertEquals(12, convert("10 + 2", 0, Integer.class));
        assertThrows(ArithmeticException.class, () -> convert("1 / 2", 0, Integer.class));
        assertThrows(ArithmeticException.class, () -> convert("2147483647 + 1", 0, Integer.class));
        assertEquals(9007199254740993L, convert("9007199254740992 + 1", 0L, Long.class));
    }

    @Test
    void absoluteLengthKeepsUnspecifiedUnitsAndAcceptsExplicitUnits() {
        LengthCellValue result = (LengthCellValue) convert("1 2 + 0.5 mm", length(1, LengthUnit.Inches),
                LengthCellValue.class);
        assertEquals(12.5, result.getLength().getValue());
        assertEquals(LengthUnit.Millimeters, result.getLength().getUnits());
        result = (LengthCellValue) convert("1 + 2", length(1, LengthUnit.Inches), LengthCellValue.class);
        assertEquals(null, result.getLength().getUnits());
        result = (LengthCellValue) convert("1 0 0 u m", length(1, LengthUnit.Millimeters), LengthCellValue.class);
        assertEquals(LengthUnit.Microns, result.getLength().getUnits());
    }

    @Test
    void relativeLengthConvertsDeltaAndUsesDisplayedUnits() {
        LengthCellValue result = (LengthCellValue) convert("+= 2.54 mm", length(1, LengthUnit.Inches),
                LengthCellValue.class);
        assertEquals(1.1, result.getLength().getValue(), 1e-12);
        assertEquals(LengthUnit.Inches, result.getLength().getUnits());
        LengthCellValue displayedInMm = new LengthCellValue(new Length(1, LengthUnit.Inches), false);
        result = (LengthCellValue) convert("-= 0.4", displayedInMm, LengthCellValue.class);
        assertEquals(25, result.getLength().getValue(), 1e-12);
        assertEquals(LengthUnit.Millimeters, result.getLength().getUnits());
    }

    @Test
    void angleAndNumericPercentTextWorkWithoutRoundingTheOriginal() {
        RotationCellValue result = (RotationCellValue) convert("+= 0.00001",
                new RotationCellValue(1.23456), RotationCellValue.class);
        assertEquals(1.23457, result.getRotation(), 1e-12);
        assertEquals("45%", NumericCellInput.convert("-= 5 %", "50.0%", String.class, true));
        assertEquals("80%", NumericCellInput.convert("50 + 30 %", "50.0%", String.class, true));
    }

    @Test
    void textNeedsAnExplicitFormulaAndPreservesWhitespaceAndLeadingZeros() {
        assertEquals(" 01 02 - 3 ", convert(" 01 02 - 3 ", "old", String.class));
        assertEquals("3.5", convert("= 1.5 + 2", "", String.class));
        assertEquals("12", convert("+= 2", "1 0", String.class));
    }

    @Test
    void invalidInputsAreRejectedWithoutScriptExecutionOrPartialParsing() {
        String[] invalid = {"", "1/0", "(1+2", "1+2)", "1mm+2mm", "NaN", "Infinity", "1e309",
                "System.exit(0)", "1;2", "1+", "1e99999999", "1.2.3", "0x10", "1f"};
        for (String input : invalid) {
            assertThrows(RuntimeException.class, () -> convert(input, 0.0, Double.class), input);
        }
        assertThrows(RuntimeException.class, () -> convert("+= 1", null, Double.class));
        assertThrows(RuntimeException.class, () -> convert("+= 1", "ABC", String.class));
        assertTrue(NumericCellInput.isFormula(" + = 1"));
    }

    private Object convert(String input, Object current, Class<?> type) {
        return NumericCellInput.convert(input, current, type, false);
    }

    private LengthCellValue length(double value, LengthUnit units) {
        return new LengthCellValue(new Length(value, units), true);
    }
}
