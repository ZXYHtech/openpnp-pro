package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class TaskFeederInspectionSupportTest {
    @Test
    public void correctionPreservesZAndRotationWhileMatchingMeasuredPickXy() {
        Location rotatedBase = new Location(LengthUnit.Millimeters, 10, 20, 7.5, 90);
        Location predictedPick = new Location(LengthUnit.Millimeters, 10, 22, -30, 90);
        Location measuredPick = new Location(LengthUnit.Millimeters, 25, 50, 999, -45);

        Location corrected = TaskFeederInspectionSupport.calculateCorrectedBase(rotatedBase,
                predictedPick, measuredPick);

        assertEquals(25, corrected.getX(), 0.000001);
        assertEquals(48, corrected.getY(), 0.000001);
        assertEquals(7.5, corrected.getZ(), 0.000001);
        assertEquals(90, corrected.getRotation(), 0.000001);
    }

    @Test
    public void correctionConvertsMeasuredUnitsBeforeApplyingXy() {
        Location base = new Location(LengthUnit.Millimeters, 5, 6, 3, 270);
        Location predicted = new Location(LengthUnit.Millimeters, 7, 8, 0, 0);
        Location measuredInches = new Location(LengthUnit.Inches, 1, 2, 4, 180);

        Location corrected = TaskFeederInspectionSupport.calculateCorrectedBase(base, predicted,
                measuredInches);

        assertEquals(23.4, corrected.getX(), 0.000001);
        assertEquals(48.8, corrected.getY(), 0.000001);
        assertEquals(3, corrected.getZ(), 0.000001);
        assertEquals(270, corrected.getRotation(), 0.000001);
    }

    @Test
    public void sprocketHoleTranslationPreservesEndpointZAndRotation() {
        Location hole = new Location(LengthUnit.Millimeters, 10, 20, -7, 33);

        Location corrected = TaskFeederInspectionSupport.translateXy(hole, 1.25, -0.5,
                LengthUnit.Millimeters);

        assertEquals(11.25, corrected.getX(), 0.000001);
        assertEquals(19.5, corrected.getY(), 0.000001);
        assertEquals(-7, corrected.getZ(), 0.000001);
        assertEquals(33, corrected.getRotation(), 0.000001);
    }
}
