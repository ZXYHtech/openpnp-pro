package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class FeederAutoAlignConfidenceTest {
    @Test
    public void combinesGlobalAndPerFeederPercentagePoints() {
        assertEquals(90.0, FeederAutoAlignConfidence.resolvePercent(80, 10), 0.0001);
        assertEquals(0.90, FeederAutoAlignConfidence.resolveRatio(80, 10), 0.0001);
        assertEquals(99.5, FeederAutoAlignConfidence.resolvePercent(95, 10), 0.0001);
        assertEquals(50.0, FeederAutoAlignConfidence.resolvePercent(60, -30), 0.0001);
    }

    @Test
    public void usesOneAbsoluteOverrideWithoutPercentageMath() {
        assertEquals(80.0,
                FeederAutoAlignConfidence.resolveOverridePercent(80, null), 0.0001);
        assertEquals(90.0,
                FeederAutoAlignConfidence.resolveOverridePercent(80, 90.0), 0.0001);
        assertEquals(0.995,
                FeederAutoAlignConfidence.resolveOverrideRatio(80, 120.0), 0.0001);
        assertEquals(0.50,
                FeederAutoAlignConfidence.resolveOverrideRatio(80, 20.0), 0.0001);
    }

    @Test
    public void rejectsNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> FeederAutoAlignConfidence.resolvePercent(Double.NaN, 0));
    }
}
