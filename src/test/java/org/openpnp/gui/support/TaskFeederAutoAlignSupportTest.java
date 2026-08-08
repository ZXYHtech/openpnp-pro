package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class TaskFeederAutoAlignSupportTest {
    @Test
    public void twoHoleCorrectionPreservesConfiguredZAndRotation() throws Exception {
        Location oldReference = mm(10, 20, -12, 15);
        Location oldLast = mm(50, 20, -11, 25);
        Location detectedReference = mm(10.4, 19.7, 999, 0);
        Location detectedLast = mm(50.4, 19.7, 888, 0);

        TaskFeederAutoAlignSupport.Result result = TaskFeederAutoAlignSupport.calculate(
                oldReference, oldLast, detectedReference, detectedLast, 4);

        assertEquals(10.4, result.correctedReference.getX(), 0.000001);
        assertEquals(19.7, result.correctedReference.getY(), 0.000001);
        assertEquals(-12, result.correctedReference.getZ(), 0.000001);
        assertEquals(15, result.correctedReference.getRotation(), 0.000001);
        assertEquals(-11, result.correctedLast.getZ(), 0.000001);
        assertEquals(25, result.correctedLast.getRotation(), 0.000001);
        assertEquals(10, result.holeIntervals);
    }

    @Test
    public void rejectsHolePairThatDoesNotMatchConfiguredPitch() {
        assertThrows(Exception.class, () -> TaskFeederAutoAlignSupport.calculate(
                mm(0, 0, 0, 0), mm(40, 0, 0, 0), mm(0, 0, 0, 0),
                mm(38.8, 0, 0, 0), 4));
    }

    @Test
    public void rejectsWrongNeighborOrReversedTapeLine() {
        assertThrows(Exception.class, () -> TaskFeederAutoAlignSupport.calculate(
                mm(0, 0, 0, 0), mm(40, 0, 0, 0), mm(0, 0, 0, 0),
                mm(-40, 0, 0, 0), 4));
    }

    @Test
    public void rejectsExcessiveAutomaticShift() {
        assertThrows(Exception.class, () -> TaskFeederAutoAlignSupport.calculate(
                mm(0, 0, 0, 0), mm(40, 0, 0, 0), mm(1.5, 0, 0, 0),
                mm(41.5, 0, 0, 0), 4));
    }

    private static Location mm(double x, double y, double z, double rotation) {
        return new Location(LengthUnit.Millimeters, x, y, z, rotation);
    }
}
