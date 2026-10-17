package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FixedMarkerSettlingTest {
    @Test
    void unreadableShortDelayFramesRejectOnlyThatCandidate() throws Exception {
        int selected = new SettlingOptimizer().select(delay -> {
            if (delay == 0) { throw new MarkerDetectionException("Frame is still in flight"); }
            return new double[] {.1, .2};
        }, .003, .05);
        assertEquals(25, selected);
    }

    @Test
    void longSettledReferenceFailureAndMotionErrorsStillAbort() {
        MarkerDetectionException reference = new MarkerDetectionException("Reference missing");
        assertSame(reference, assertThrows(MarkerDetectionException.class,
                () -> new SettlingOptimizer().select(delay -> { throw reference; }, .003, .05)));
        IllegalStateException motion = new IllegalStateException("Soft limit violation");
        assertSame(motion, assertThrows(IllegalStateException.class,
                () -> new SettlingOptimizer().select(delay -> {
                    if (delay == 0) { throw motion; }
                    return new double[] {0, 0};
                }, .003, .05)));
    }

    @Test
    void noDelayPassesWithoutEightValidConsistentMeasurements() {
        int[] longFrames = {0};
        assertThrows(IllegalStateException.class, () -> new SettlingOptimizer().select(delay -> {
            if (delay == 1000 && longFrames[0]++ < 8) { return new double[] {0, 0}; }
            throw new MarkerDetectionException("No usable candidate");
        }, .003, .05));
    }
}