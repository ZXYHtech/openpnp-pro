package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;

class FlyingVisionMotionTest {
    private Location pose(double x, double y, double z, double angle) {
        return new Location(LengthUnit.Millimeters, x, y, z, angle);
    }

    @Test
    void corridorRequiresAnImagingNozzleAndRejectsRemoteOrRotatingEntries() {
        Location from = pose(225, 102, 5.8, 0);
        assertTrue(FlyingVisionMotion.inCameraCorridor(from, pose(224, 102, 5.8, 0), true));
        assertTrue(FlyingVisionMotion.inCameraCorridor(pose(247, 102.4, 15, 90), pose(224, 102, 5.8, 90), true));
        assertFalse(FlyingVisionMotion.inCameraCorridor(from, pose(224, 102, 5.8, 0), false));
        assertFalse(FlyingVisionMotion.inCameraCorridor(pose(137, 5, 15, 0), from, true));
        assertFalse(FlyingVisionMotion.inCameraCorridor(from, pose(224, 102, 5.8, 90), true));
        assertFalse(FlyingVisionMotion.inCameraCorridor(from, pose(Double.NaN, 102, 5.8, 0), true));
    }

    @Test
    void positiveXOrderUsesHeadCoordinatesAndPreservesOverlappingOrDistantWindows() {
        assertTrue(FlyingVisionMotion.reverseOrder(225, 102, 203, 102.4, 1, 1, 1));
        assertFalse(FlyingVisionMotion.reverseOrder(203, 102.4, 225, 102, 1, 1, 1));
        assertFalse(FlyingVisionMotion.reverseOrder(225, 102, 224, 102.4, 1, 1, 1));
        assertFalse(FlyingVisionMotion.reverseOrder(225, 102, 203, 112, 1, 1, 1));
        assertFalse(FlyingVisionMotion.reverseOrder(Double.NaN, 102, 203, 102, 1, 1, 1));
        assertFalse(FlyingVisionMotion.reverseOrder(225, 102, 203, 102.4, 1, 1, -1));
        assertTrue(FlyingVisionMotion.reverseOrder(203, 102.4, 225, 102, 1, 1, -1));
    }

    @Test
    void negativePassCompensationKeepsTheMeasuredSignAndRejectsReverseFrames() {
        assertTrue(FlyingVisionMotion.forwardDelta(.04, 1));
        assertFalse(FlyingVisionMotion.forwardDelta(-.04, 1));
        assertTrue(FlyingVisionMotion.forwardDelta(-.04, -1));
        assertFalse(FlyingVisionMotion.forwardDelta(.04, -1));
        assertFalse(FlyingVisionMotion.forwardDelta(Double.NaN, -1));
    }

    @Test
    void includingEntryAndNextBoardPreventsAnExpensiveOrderReversal() {
        Location start = pose(247, 102, 0, 0);
        Location n1 = pose(236, 102, 0, 0);
        Location n2 = pose(214, 102.4, 0, 0);
        Location[] board = {pose(150, 150, 0, 0)};
        double ordinary = FlyingVisionMotion.routeDistance(start, n1, n2, board, 1, 1, 1);
        double reversed = FlyingVisionMotion.routeDistance(start, n2, n1, board, 1, 1, 1);
        double negative = FlyingVisionMotion.routeDistance(start, n1, n2, board, 1, 1, -1);
        assertTrue(reversed > ordinary);
        assertTrue(negative < ordinary);
    }

    @Test
    void staleCertificationOrChangedStartNeverSubmitsADirectMove() throws Exception {
        AtomicInteger moved = new AtomicInteger();
        Location from = pose(225, 102, 5.8, 0);
        Nozzle nozzle = (Nozzle)Proxy.newProxyInstance(Nozzle.class.getClassLoader(), new Class<?>[] {Nozzle.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLocation")) {
                        return from;
                    }
                    if (method.getName().equals("moveTo")) {
                        moved.incrementAndGet();
                    }
                    throw new AssertionError("No hardware call expected: " + method.getName());
                });
        assertFalse(FlyingVisionMotion.submitIfCurrent(nozzle, from, pose(224, 102, 5.8, 0), () -> false));
        assertFalse(FlyingVisionMotion.submitIfCurrent(nozzle, pose(226, 102, 5.8, 0), from, () -> true));
        assertEquals(0, moved.get());
    }

    @Test
    void certifiedEntryUsesNativeCoordinatedMotionFlag() throws Exception {
        Location from = pose(225, 102, 5.8, 0);
        AtomicInteger moved = new AtomicInteger();
        Machine machine = (Machine)Proxy.newProxyInstance(Machine.class.getClassLoader(), new Class<?>[] {Machine.class},
                (proxy, method, args) -> {
                    assertEquals("getSpeed", method.getName());
                    return .8;
                });
        Head head = (Head)Proxy.newProxyInstance(Head.class.getClassLoader(), new Class<?>[] {Head.class},
                (proxy, method, args) -> machine);
        Nozzle nozzle = (Nozzle)Proxy.newProxyInstance(Nozzle.class.getClassLoader(), new Class<?>[] {Nozzle.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLocation")) {
                        return from;
                    }
                    if (method.getName().equals("getHead")) {
                        return head;
                    }
                    if (method.getName().equals("moveTo")) {
                        assertEquals(.8, args[1]);
                        assertTrue(java.util.Arrays.asList((org.openpnp.model.Motion.MotionOption[])args[2])
                                .contains(org.openpnp.model.Motion.MotionOption.CoordinatedMotion));
                        moved.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        assertTrue(FlyingVisionMotion.submitIfCurrent(nozzle, from, pose(224, 102, 5.8, 0), () -> true));
        assertEquals(1, moved.get());
    }

    @Test
    void nozzleTipChangingAlsoBlocksCameraCorridorPreview() {
        try (var ignored = org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.suspendForNozzleTipChange()) {
            var plan = org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.previewBottomVision(null, pose(0,0,0,0), 1);
            assertFalse(plan.isOptimized());
            assertTrue(plan.getReason().contains("更换吸嘴"));
        }
    }
}
