package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.spi.Axis;

class CertifiedCurveRepairTest {
    @Test void invalidBlendIsRebuiltInsideTheSameBoundsWithIdenticalEndpoints() throws Exception {
        ReferenceControllerAxis x = axis(Axis.Type.X), z = axis(Axis.Type.Z);
        AxesLocation from = new AxesLocation(x, 0).put(new AxesLocation(z, 1));
        AxesLocation to = new AxesLocation(x, 100).put(new AxesLocation(z, 1));
        var bounds = new CertifiedTravelProfile.Bounds(new AxesLocation(x, 0).put(new AxesLocation(z, 0)),
                new AxesLocation(x, 100).put(new AxesLocation(z, 2)), () -> { });
        Motion bad = new Motion(null, from, to, 1, Motion.MotionOption.UncoordinatedMotion);
        bounds.constrain(bad);
        CertifiedCurveRepair.register(bad, bounds);
        bad.getAxesProfiles()[bad.getAxisIndex(z)].setLocationMax(-1);
        assertThrows(IllegalStateException.class, () -> bounds.validate(bad));
        List<Motion> path = new ArrayList<>(List.of(bad));
        assertTrue(CertifiedCurveRepair.rebuild(path));
        assertTrue(path.get(0).hasOption(Motion.MotionOption.CoordinatedMotion));
        assertTrue(from.matches(path.get(0).getLocation0()));
        assertTrue(to.matches(path.get(0).getLocation1()));
        assertDoesNotThrow(() -> bounds.validate(path.get(0)));
    }
    @Test void aChangedModelOrEndpointOutsideItsVolumeCannotBeBypassed() throws Exception {
        var x = axis(Axis.Type.X);
        var bounds = new CertifiedTravelProfile.Bounds(new AxesLocation(x, 0), new AxesLocation(x, 10),
                () -> { throw new IllegalStateException("model changed"); });
        Motion motion = new Motion(null, new AxesLocation(x, 0), new AxesLocation(x, 5), 1);
        CertifiedCurveRepair.register(motion, bounds);
        assertThrows(IllegalStateException.class, () -> CertifiedCurveRepair.rebuild(new ArrayList<>(List.of(motion))));
        assertFalse(CertifiedCurveRepair.rebuild(new ArrayList<>(List.of(new Motion(motion)))));
    }
    @Test void aQueuedRetreatAndFinalContactKeepTheirOriginalTargetsDuringRepair() throws Exception {
        var x = axis(Axis.Type.X);
        var z = axis(Axis.Type.Z);
        List<AxesLocation> points = List.of(
                new AxesLocation(x, 0).put(new AxesLocation(z, -12)),
                new AxesLocation(x, 0).put(new AxesLocation(z, -2)),
                new AxesLocation(x, 100).put(new AxesLocation(z, -2)),
                new AxesLocation(x, 100).put(new AxesLocation(z, -13)));
        var bounds = new CertifiedTravelProfile.Bounds(new AxesLocation(x, 0).put(new AxesLocation(z, -3)),
                new AxesLocation(x, 100).put(new AxesLocation(z, 2)), () -> { });
        List<Motion> path = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            path.add(new Motion(null, points.get(i - 1), points.get(i), 1, i == 2
                    ? Motion.MotionOption.UncoordinatedMotion : Motion.MotionOption.CoordinatedMotion));
        }
        bounds.constrain(path.get(1));
        CertifiedCurveRepair.register(path.get(1), bounds);
        assertTrue(CertifiedCurveRepair.rebuild(path));
        for (int i = 0; i < path.size(); i++) {
            assertTrue(points.get(i).matches(path.get(i).getLocation0()));
            assertTrue(points.get(i + 1).matches(path.get(i).getLocation1()));
        }
        for (int i = 0; i <= 100; i++) {
            var sample = path.get(1).getMomentaryLocation(path.get(1).getTime() * i / 100.);
            assertTrue(sample.getCoordinate(z) >= -3 && sample.getCoordinate(z) <= 2);
        }
        assertDoesNotThrow(() -> bounds.validate(path.get(1)));
    }
    private ReferenceControllerAxis axis(Axis.Type type) {
        var axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(new org.openpnp.machine.reference.driver.NullDriver());
        axis.setFeedratePerSecond(new Length(200, LengthUnit.Millimeters));
        axis.setAccelerationPerSecond2(new Length(1000, LengthUnit.Millimeters));
        axis.setJerkPerSecond3(new Length(10000, LengthUnit.Millimeters));
        return axis;
    }
}
