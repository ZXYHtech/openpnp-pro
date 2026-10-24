/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafetyWorkbenchViewTransformTest {
    @Test
    void topBottomAndExactSideViewsAreReachableWithoutSingularGroundEditing() {
        assertEquals(-Math.PI / 2, SafetyWorkbenchViewTransform.clampPitch(-4), .000001);
        assertEquals(Math.PI / 2, SafetyWorkbenchViewTransform.clampPitch(4), .000001);
        assertEquals(0, SafetyWorkbenchViewTransform.clampPitch(0), .000001);
        assertTrue(SafetyWorkbenchViewTransform.canMapGround(Math.PI / 2));
        assertTrue(SafetyWorkbenchViewTransform.canMapGround(-Math.PI / 2));
        org.junit.jupiter.api.Assertions.assertFalse(SafetyWorkbenchViewTransform.canMapGround(0));
        assertEquals(10, SafetyWorkbenchViewTransform.vertical(10, 8, Math.PI / 2), .000001);
        assertEquals(-10, SafetyWorkbenchViewTransform.vertical(10, 8, -Math.PI / 2), .000001);
        assertEquals(-8, SafetyWorkbenchViewTransform.vertical(10, 8, 0), .000001);
    }
    @Test
    void machineFrontViewMirrorsPhysicalXDirection() {
        double yaw = Math.toRadians(-35);
        double normal = SafetyWorkbenchViewTransform.horizontal(10, 0, yaw, false);
        double machineFront =
                SafetyWorkbenchViewTransform.horizontal(10, 0, yaw, true);

        assertTrue(normal > 0);
        assertTrue(machineFront < 0);
        assertEquals(-normal, machineFront, 0.000001);
    }

    @Test
    void projectionAndGroundMappingRoundTripInBothViews() {
        double yaw = Math.toRadians(27);
        for (boolean machineFrontView : new boolean[] {false, true}) {
            double horizontal = SafetyWorkbenchViewTransform.horizontal(
                    12.5, -8.25, yaw, machineFrontView);
            double depth = SafetyWorkbenchViewTransform.depth(12.5, -8.25, yaw);
            double[] world = SafetyWorkbenchViewTransform.worldDelta(
                    horizontal, depth, yaw, machineFrontView);

            assertEquals(12.5, world[0], 0.000001);
            assertEquals(-8.25, world[1], 0.000001);
        }
    }
}
