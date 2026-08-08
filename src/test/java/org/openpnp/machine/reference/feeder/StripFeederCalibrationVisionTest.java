package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class StripFeederCalibrationVisionTest {
    @Test
    void selectsNearestHoleFromLongestRegularRowAndIgnoresPocketCircles() throws Exception {
        StripFeederCalibrationVision.Selection selection = StripFeederCalibrationVision.select(
                Arrays.asList(mm(0, 0), mm(4.02, 0.08), mm(7.98, -0.04), mm(12.01, 0.03),
                        mm(4, 5.5), mm(8, 5.7), mm(6, 3)),
                mm(7.7, 0.5), 4.0);

        assertEquals(7.98, selection.location.getX(), 0.000001);
        assertEquals(-0.04, selection.location.getY(), 0.000001);
        assertEquals(4, selection.supportCount);
    }

    @Test
    void acceptsAConsistentTwoHoleViewButRejectsUnrelatedCircles() throws Exception {
        StripFeederCalibrationVision.Selection selection = StripFeederCalibrationVision.select(
                Arrays.asList(mm(10, 20), mm(14.1, 20.1)), mm(10.2, 19.9), 4.0);
        assertEquals(2, selection.supportCount);

        assertThrows(Exception.class, () -> StripFeederCalibrationVision.select(
                Arrays.asList(mm(0, 0), mm(3, 2), mm(7, 5)), mm(0, 0), 4.0));
    }

    @Test
    void singleHoleFallbackMustRemainCloseToExpectedPoint() throws Exception {
        assertEquals(1, StripFeederCalibrationVision.select(
                Arrays.asList(mm(1, 1)), mm(1.5, 1.2), 4.0).supportCount);
        assertThrows(Exception.class, () -> StripFeederCalibrationVision.select(
                Arrays.asList(mm(10, 10)), mm(0, 0), 4.0));
    }

    @Test
    void rejectsARegularNeighboringHoleRowOutsideTheMicroAdjustmentWindow() {
        assertThrows(Exception.class, () -> StripFeederCalibrationVision.select(
                Arrays.asList(mm(0, 0), mm(4, 0), mm(8, 0)), mm(1.5, 0), 4.0));
    }

    private static Location mm(double x, double y) {
        return new Location(LengthUnit.Millimeters, x, y, 0, 0);
    }
}
