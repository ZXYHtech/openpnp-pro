package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MotionProfileCrossingTest {
    @Test void monotonicJerkSegmentsWithNoVelocityRootsStillExposeEveryReachablePosition() {
        for (int sign : new int[] { 1, -1 }) {
            MotionProfile profile = new MotionProfile(0, sign * 100, sign * 50, sign * 20, 0, 0,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    4000, 20000, 20000, 400000, 0, Double.POSITIVE_INFINITY, 0);
            profile.solve();
            assertNull(profile.checkValidity());
            for (int sample = 1; sample < 1000; sample++) {
                double position = sign * sample / 10.;
                Double forward = profile.getForwardCrossingTime(position, false);
                Double backward = profile.getBackwardCrossingTime(position, false);
                assertNotNull(forward, "Missing reachable forward crossing at " + position);
                assertNotNull(backward, "Missing reachable backward crossing at " + position);
                assertEquals(position, profile.getMomentaryLocation(forward), 1e-6);
                assertEquals(position, profile.getMomentaryLocation(backward), 1e-6);
            }
        }
    }
}
