package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Entry;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Policy;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Status;

class FlyingVisionEvaluationTest {

    private Entry good() {
        Entry row = new Entry();
        row.trials = 3;
        row.staticMs = 200;
        row.flyingMs = 150;
        row.maxPositionMm = 0.015;
        row.maxAngleDeg = 0.12;
        row.driftMm = 0.003;
        return row;
    }

    @Test()
    void enablesOnlyWhenQualityAndSpeedBothPass() {
        Entry row = good();
        FlyingVisionEvaluation.decide(row, new Policy());
        assertEquals(Status.PASSED, row.status);
        assertTrue(row.enabled);
    }

    @Test()
    void accurateButSlowerFlightKeepsFastStaticVision() {
        Entry row = good();
        row.flyingMs = 250;
        row.enabled = true;
        FlyingVisionEvaluation.decide(row, new Policy());
        assertEquals(Status.NO_GAIN, row.status);
        assertFalse(row.enabled);
    }

    @Test()
    void requiresAllRepetitionsAndRejectsDriftEvenIfFlightLooksClose() {
        Entry row = good();
        row.trials = 2;
        FlyingVisionEvaluation.decide(row, new Policy());
        assertEquals(Status.FAILED, row.status);
        row = good();
        row.driftMm = 0.031;
        FlyingVisionEvaluation.decide(row, new Policy());
        assertEquals(Status.FAILED, row.status);
    }

    @Test()
    void badAngleAndNonFiniteMetricsCannotBeEnabled() {
        for (double value : new double[]{0.51, Double.NaN, Double.POSITIVE_INFINITY}) {
            Entry row = good();
            row.maxAngleDeg = value;
            FlyingVisionEvaluation.decide(row, new Policy());
            assertEquals(Status.FAILED, row.status);
            assertFalse(row.enabled);
        }
    }

    @Test()
    void automaticEnableCanBeDisabledWithoutLosingPassingResult() {
        Entry row = good();
        Policy p = new Policy();
        p.autoEnable = false;
        FlyingVisionEvaluation.decide(row, p);
        assertEquals(Status.PASSED, row.status);
        assertFalse(row.enabled);
    }

    @Test()
    void bracketComparisonConvertsUnitsAndMeasuresRotationExplicitly() throws Exception {
        Entry row = new Entry();
        FlyingVisionEvaluation.compare(row, new Location(LengthUnit.Inches, 0.01, 0, 0, 179.9), new Location(LengthUnit.Millimeters, 0.264, 0, 0, -179.9), new Location(LengthUnit.Millimeters, 0.254, 0, 0, 179.9));
        assertEquals(0.01, row.maxPositionMm, 1.0E-9);
        assertEquals(0.2, row.maxAngleDeg, 1.0E-8);
        assertEquals(0, row.driftMm, 1.0E-9);
    }

    @Test()
    void movingComponentIsRejectedByStaticBracket() {
        Entry row = good();
        assertThrows(Exception.class, ()->FlyingVisionEvaluation.compare(row, new Location(LengthUnit.Millimeters, 0, 0, 0, 0), new Location(LengthUnit.Millimeters, 0, 0, 0, 0), new Location(LengthUnit.Millimeters, 0, 0, 0, 1)));
    }

    @Test()
    void contextsShareByPackageButKeepNozzlesHeightsAndAnglesSeparate() {
        String a = FlyingVisionEvaluation.key("0603", "N1", "tip1", 5.8, 0.04);
        assertEquals(a, FlyingVisionEvaluation.key("0603", "N1", "tip1", 5.8, 360.05));
        assertNotEquals(a, FlyingVisionEvaluation.key("0402", "N1", "tip1", 5.8, 0.04));
        assertNotEquals(a, FlyingVisionEvaluation.key("0603", "N2", "tip1", 5.8, 0.04));
        assertNotEquals(a, FlyingVisionEvaluation.key("0603", "N1", "tip2", 5.8, 0.04));
        assertNotEquals(a, FlyingVisionEvaluation.key("0603", "N1", "tip1", 5.5, 0.04));
        assertNotEquals(a, FlyingVisionEvaluation.key("0603", "N1", "tip1", 5.8, 90.04));
    }

    @Test()
    void policyCannotSkipRepeatedValidationOrAcceptInvalidNumbers() {
        Policy p = new Policy();
        p.repeats = 1;
        assertThrows(IllegalArgumentException.class, ()->FlyingVisionEvaluation.validatePolicy(p));
        p.repeats = 3;
        p.maxPositionMm = Double.NaN;
        assertThrows(IllegalArgumentException.class, ()->FlyingVisionEvaluation.validatePolicy(p));
    }

    private FlyingVisionRegistration.Model reference(double z, double angle) {
        var m = new FlyingVisionRegistration.Model();
        m.signature = "current";
        m.imageFile = "reference.png";
        m.z = z;
        m.angle = angle;
        m.xx = 0.04;
        m.yy = -0.04;
        m.validationError = 0.005;
        return m;
    }

    @Test
    void oneHeldRepresentativeCoversPackageAnglesButNeverAnotherNozzleOrHeight() {
        Entry first = new Entry();
        first.packageId = "0603";
        first.nozzleId = "N1";
        first.tipId = "tip";
        first.partId = "10nF";
        first.z = 5.8;
        Entry second = new Entry();
        second.packageId = "0603";
        second.nozzleId = "N1";
        second.tipId = "tip";
        second.partId = "47pF";
        second.z = 5.8;
        second.angle = 90;
        assertTrue(FlyingVisionEvaluation.sameRepresentativeGroup(first, second));
        second.nozzleId = "N2";
        assertFalse(FlyingVisionEvaluation.sameRepresentativeGroup(first, second));
        second.nozzleId = "N1";
        second.z = 5.4;
        assertFalse(FlyingVisionEvaluation.sameRepresentativeGroup(first, second));
    }

    @Test()
    void referenceBankKeepsMultipleAnglesAndHeightsWithoutOverwriting() {
        var zero = reference(5.8, 0);
        var ninety = reference(5.8, 90);
        var low = reference(5.4, 0);
        var models = Arrays.asList(zero, ninety, low);
        assertSame(zero, FlyingVisionRegistration.select(models, "current", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 0.05)));
        assertSame(ninety, FlyingVisionRegistration.select(models, "current", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 90.05)));
        assertSame(low, FlyingVisionRegistration.select(models, "current", new Location(LengthUnit.Millimeters, 0, 0, 5.4, 0)));
        assertNull(FlyingVisionRegistration.select(models, "changed", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 0)));
        assertNull(FlyingVisionRegistration.select(models, "current", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 45)));
    }

    @Test()
    void damagedReferenceCannotAuthorizeFlight() {
        var bad = reference(5.8, 0);
        bad.z = Double.NaN;
        assertNull(FlyingVisionRegistration.select(Arrays.asList(bad), "current", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 0)));
        bad.z = 5.8;
        bad.imageFile = "../outside.png";
        assertNull(FlyingVisionRegistration.select(Arrays.asList(bad), "current", new Location(LengthUnit.Millimeters, 0, 0, 5.8, 0)));
    }
}
