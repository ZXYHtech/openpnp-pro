package org.openpnp.corexy.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class CoreXYCalibrationPlannerTest {
    @Test
    void buildsNormalizedPilotDistancesFromAvailableRange() {
        Location primary = new Location(LengthUnit.Millimeters, 100, 100, 0, 0);

        CoreXYCalibrationPlanner.SamplingPlan plan = CoreXYCalibrationPlanner.createPlan(
                primary, "camera", "x", "y", 160.0, 0.75);

        assertEquals(120.0, plan.getRecommendedVirtualDistanceMm(), 1e-9);
        assertEquals(60.0, plan.getMaximumPhysicalAxisExcursionMm(), 1e-9);
        assertEquals(Arrays.asList(5.0, 14.4, 30.0, 54.0, 84.0, 120.0),
                plan.getDistancesMm());
        assertEquals(Arrays.asList(0.20, 0.75, 1.00), plan.getSpeedFactors());
    }

    @Test
    void scalesPilotDistancesForSmallerMachine() {
        assertEquals(Arrays.asList(3.0, 3.6, 7.5, 13.5, 21.0, 30.0),
                CoreXYCalibrationPlanner.buildPilotDistances(30.0));
    }

    @Test
    void refusesUnsafeSmallRange() {
        Location primary = new Location(LengthUnit.Millimeters, 5, 5, 0, 0);

        assertThrows(IllegalStateException.class,
                () -> CoreXYCalibrationPlanner.createPlan(
                        primary, "camera", "x", "y", 12.0, 0.75));
    }
}
