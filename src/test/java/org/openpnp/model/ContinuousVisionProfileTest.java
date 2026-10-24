package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.spi.Axis;

class ContinuousVisionProfileTest {
    private ReferenceControllerAxis axis(Axis.Type type) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setFeedratePerSecond(new Length(1000, LengthUnit.Millimeters));
        axis.setAccelerationPerSecond2(new Length(1000, LengthUnit.Millimeters));
        axis.setJerkPerSecond3(new Length(10000, LengthUnit.Millimeters));
        return axis;
    }

    @Test void xNeverSlowsBetweenWindowsWhileCamCompletesItsFullStroke() throws Exception {
        ReferenceControllerAxis x = axis(Axis.Type.X), z = axis(Axis.Type.Z);
        List<Motion> route = new ArrayList<>();
        AxesLocation previous = new AxesLocation(x, 0).put(new AxesLocation(z, -60));
        for (double[] target : new double[][]{{4,-60},{6.4,-60},{26,60},{28.4,60},{34,0}}) {
            AxesLocation next = new AxesLocation(x, target[0]).put(new AxesLocation(z, target[1]));
            route.add(new Motion(null, previous, next, 1, Motion.MotionOption.UncoordinatedMotion));
            previous = next;
        }
        ContinuousVisionProfile.solve(route, 12);
        assertEquals(0, route.get(0).getMomentaryVelocity(0).getCoordinate(x), 1e-8);
        Motion last = route.get(4);
        assertEquals(0, last.getMomentaryVelocity(last.getTime()).getCoordinate(x), 1e-8);
        for (int i = 1; i <= 3; i++) {
            Motion motion = route.get(i);
            for (int step = 0; step <= 100; step++) {
                double t = motion.getTime()*step/100.;
                assertEquals(12, motion.getMomentaryVelocity(t).getCoordinate(x), 1e-5);
                double cam = motion.getMomentaryLocation(t).getCoordinate(z);
                assertTrue(cam >= -60.00001 && cam <= 60.00001);
            }
            for (MotionProfile profile : motion.getAxesProfiles()) { assertNull(profile.checkValidity()); }
        }
        assertEquals(60, route.get(2).getMomentaryLocation(route.get(2).getTime()).getCoordinate(z), 1e-8);
    }

    @Test void rotatingDuringExposureIsRejected() {
        ReferenceControllerAxis x = axis(Axis.Type.X), rotation = axis(Axis.Type.Rotation);
        List<Motion> route = new ArrayList<>();
        AxesLocation previous = new AxesLocation(x, 0).put(new AxesLocation(rotation, 0));
        for (int i = 1; i <= 3; i++) {
            AxesLocation next = new AxesLocation(x, i * 10).put(new AxesLocation(rotation, i * 10));
            route.add(new Motion(null, previous, next, 1, Motion.MotionOption.UncoordinatedMotion));
            previous = next;
        }
        Exception failure = assertThrows(Exception.class, () -> ContinuousVisionProfile.solve(route, 12));
        assertTrue(failure.getMessage().contains("旋转"));
    }

    @Test void impossibleCamTimingIsRejectedBeforeExecution() {
        MotionProfile limits = new MotionProfile(-60,60,0,0,0,0,-60,60,10,20,20,100,0,Double.POSITIVE_INFINITY,0);
        assertThrows(Exception.class, () -> ContinuousVisionProfile.profile(limits,0,0,.01,10));
    }

    @Test void ordinaryPlannerIsUnaffectedAndChangedQueueIsRejected() throws Exception {
        assertFalse(ContinuousVisionProfile.optimizeIfRequested(Collections.emptyList()));
        try (var scope = new ContinuousVisionProfile(Collections.emptyList(), 12, motions -> fail("Not certified"))) {
            assertThrows(Exception.class, () -> ContinuousVisionProfile.optimizeIfRequested(Collections.emptyList()));
        }
        assertFalse(ContinuousVisionProfile.optimizeIfRequested(Collections.emptyList()));
    }
}
