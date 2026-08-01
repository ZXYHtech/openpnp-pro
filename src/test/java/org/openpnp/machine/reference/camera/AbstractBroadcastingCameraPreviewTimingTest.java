package org.openpnp.machine.reference.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AbstractBroadcastingCameraPreviewTimingTest {
    @Test
    void waitsOnlyForRemainderOfConfiguredFramePeriod() {
        assertEquals(23_333_333L,
                AbstractBroadcastingCamera.computePreviewWaitNanos(30, 10_000_000L));
        assertEquals(0,
                AbstractBroadcastingCamera.computePreviewWaitNanos(30, 40_000_000L));
    }

    @Test
    void zeroFpsRemainsEventDrivenAndInvalidValuesDoNotBlock() {
        assertEquals(-1,
                AbstractBroadcastingCamera.computePreviewWaitNanos(0, 10_000_000L));
        assertEquals(0,
                AbstractBroadcastingCamera.computePreviewWaitNanos(-1, 10_000_000L));
        assertEquals(0,
                AbstractBroadcastingCamera.computePreviewWaitNanos(
                        Double.POSITIVE_INFINITY, 10_000_000L));
    }
}
