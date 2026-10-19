package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;

class CoordinatedMotionValidatorTest {
    @Test
    void calibratedTipCanFlyThroughCameraOpeningWithSiblingFullyRetracted() {
        SafetyWorkbenchModel model = model();
        SafetyObject camera = box(model, ObjectType.BOTTOM_CAMERA, 65, 65, -20, 70, 70, 25);
        camera.apertureWidth = camera.apertureDepth = 28;
        camera.clearance = 3;
        ToolProfile profile = new ToolProfile();
        profile.zIsNozzleTip = true;
        profile.retractedZ = 15;
        profile.extendedZ = -15;
        profile.bodyMinZOffset = 8;
        Waypoint start = new Waypoint(96, 100, 5.8);
        DynamicTool selected = new DynamicTool("H1", "N1", "N1", start, profile,
                .4, .8, .8, true, true, true, true);
        DynamicTool sibling = new DynamicTool("H1", "N2", "N2", new Waypoint(117.8, 100, 15), profile,
                .4, .8, .8, true, true, false, true);
        assertTrue(CoordinatedMotionValidator.validate(model,
                Arrays.asList(start, new Waypoint(104, 100, 5.8)), Arrays.asList(selected, sibling), start).isSafe());
        assertFalse(CoordinatedMotionValidator.validate(model,
                Arrays.asList(start, new Waypoint(109, 100, 5.8)), Arrays.asList(selected, sibling), start).isSafe(),
                "The narrow tip must still collide with the edge of the optical opening");
        box(model, ObjectType.KEEP_OUT, 98, 98, 30, 4, 4, 5);
        assertFalse(CoordinatedMotionValidator.validate(model,
                Arrays.asList(start, new Waypoint(104, 100, 5.8)), Arrays.asList(selected, sibling), start).isSafe(),
                "Fixing the tip origin must not remove collisions with the fixed housing");
    }

    @Test
    void rawCamSweepClearsAnObstacleAfterRisingButStillRejectsAnEarlyCollision() {
        SafetyWorkbenchModel model = model();
        Waypoint start = new Waypoint(0, 0, 0);
        DynamicTool tool = tool(start);
        tool.conservativeSegmentZ = true;
        tool.segmentZ = (from, to) -> f -> 20 * Math.sin(Math.PI * f / 3);
        java.util.List<Waypoint> path = Arrays.asList(start, new Waypoint(100, 0, 17.320508));
        SafetyObject obstacle = box(model, ObjectType.KEEP_OUT, 70, -.1, 0, 1, .2, 5);
        assertTrue(CoordinatedMotionValidator.validate(model, path, Collections.singletonList(tool), start).isSafe());
        tool.segmentZ = null;
        assertFalse(CoordinatedMotionValidator.validate(model, path, Collections.singletonList(tool), start).isSafe(),
                "Old full-segment bounds rejected the already-cleared obstacle");
        tool.segmentZ = (from, to) -> f -> 20 * Math.sin(Math.PI * f / 3);
        obstacle.x = .23;
        obstacle.width = .001;
        assertFalse(CoordinatedMotionValidator.validate(model, path, Collections.singletonList(tool), start).isSafe(),
                "Continuous interval bounds must catch a thin obstacle between samples");
    }

    @Test
    void nonlinearCamCannotBeReplacedByLinearNozzleZAndSiblingIsStillChecked() {
        SafetyWorkbenchModel model = model();
        Waypoint start = new Waypoint(0, 0, 20);
        DynamicTool selected = tool(start);
        DynamicTool sibling = new DynamicTool("H1", "N2", "N2", new Waypoint(0, 10, 20),
                profile(), 0, 0, 0, true, true, true, false);
        sibling.selectedTool = false;
        sibling.segmentZ = (from, to) -> f -> 20 * Math.cos(Math.PI * f / 2);
        box(model, ObjectType.KEEP_OUT, 49.9, 9.9, 13.1, .2, .2, .2);
        assertFalse(CoordinatedMotionValidator.validate(model,
                Arrays.asList(start, new Waypoint(100, 0, 0)), Arrays.asList(selected, sibling), start).isSafe());
        sibling.segmentZ = (from, to) -> f -> Double.NaN;
        assertFalse(CoordinatedMotionValidator.validate(model,
                Arrays.asList(start, new Waypoint(100, 0, 0)), Arrays.asList(selected, sibling), start).isSafe());
    }

    @Test
    void camTransformsAreResolvedOncePerSegmentWithTheSameConservativeCollisionEnvelope() {
        SafetyWorkbenchModel model = model();
        Waypoint start = new Waypoint(0, 0, 20);
        DynamicTool tool = tool(start);
        tool.conservativeSegmentZ = true;
        java.util.concurrent.atomic.AtomicInteger transforms = new java.util.concurrent.atomic.AtomicInteger();
        tool.mappedZ = z -> { transforms.incrementAndGet();
        return z;
        };
        java.util.List<Waypoint> path = Arrays.asList(start, new Waypoint(300, 0, 10));
        assertTrue(CoordinatedMotionValidator.validate(model, path, Collections.singletonList(tool), start).isSafe());
        assertEquals(2, transforms.get(), "600+ intervals must not repeat full machine axis transforms");
        box(model, ObjectType.KEEP_OUT, 140, -.1, 15, 1, .2, 1);
        transforms.set(0);
        assertFalse(CoordinatedMotionValidator.validate(model, path, Collections.singletonList(tool), start).isSafe(),
                "The complete segment Z envelope must still reject this obstruction");
        assertEquals(2, transforms.get());
    }

    @Test
    void exceededPlanningBudgetCannotProduceAnApprovedPath() {
        Waypoint start = new Waypoint(0, 0, 20);
        ValidationResult result = CoordinatedMotionValidator.validate(model(),
                Arrays.asList(start, new Waypoint(300, 0, 10)), Collections.singletonList(tool(start)), start, 0);
        assertFalse(result.isSafe());
        assertTrue(result.getIssues().get(0).contains("预算"));
    }

    SafetyWorkbenchModel model() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.setMinX(-100);
        model.setMaxX(500);
        model.setMinY(-100);
        model.setMaxY(500);
        model.setMinZ(-100);
        model.setMaxZ(200);
        return model;
    }

    ToolProfile profile() {
        ToolProfile profile = new ToolProfile();
        profile.bodyRadiusX = profile.bodyRadiusY = .01;
        profile.bodyMinZOffset = 4;
        profile.bodyMaxZOffset = 8;
        profile.shaftRadius = .01;
        profile.clearance = 0;
        profile.retractedZ = 20;
        profile.extendedZ = 0;
        profile.retractedTipLength = profile.extendedTipLength = 1;
        return profile;
    }

    DynamicTool tool(Waypoint start) {
        return new DynamicTool("H1", "N1", "N1", start, profile(), 0, 0, 0, true, true, true, false);
    }

    SafetyObject box(SafetyWorkbenchModel model, ObjectType type, double x, double y, double z, double width, double depth, double height) {
        SafetyObject object = model.createObject(type);
        object.x = x;
        object.y = y;
        object.z = z;
        object.width = width;
        object.depth = depth;
        object.height = height;
        object.clearance = 0;
        model.addObject(object);
        return object;
    }

    @Test
    void continuousSweepCannotJumpOverThinObstacleBetweenSamples() {
        SafetyWorkbenchModel model = model();
        model.setSampleStep(5);
        box(model, ObjectType.KEEP_OUT, .23, -.1, 9, .001, .2, 10);
        Waypoint start = new Waypoint(0, 0, 10);
        ValidationResult result = DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(10, 0, 10)), Collections.singletonList(tool(start)));
        assertFalse(result.isSafe());
    }

    @Test
    void diagonalCannotLeaveOrApproachAnEndpointThroughItsSide() {
        SafetyWorkbenchModel model = model();
        box(model, ObjectType.FEEDER, -1, -1, 0, 2, 2, 10);
        Waypoint start = new Waypoint(0, 0, 10);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(20, 0, 20)), Collections.singletonList(tool(start))).isSafe());
    }

    @Test
    void keepOutAndCameraShellAreNeverWaivedAtEndpoints() {
        for (ObjectType type : new ObjectType[] { ObjectType.KEEP_OUT, ObjectType.BOTTOM_CAMERA }) {
            SafetyWorkbenchModel model = model();
            SafetyObject object = box(model, type, -1, -1, 0, 2, 2, 10);
            object.apertureWidth = object.apertureDepth = 0;
            Waypoint start = new Waypoint(0, 0, 10);
            assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(0, 0, 20), new Waypoint(20, 0, 20)), Collections.singletonList(tool(start))).isSafe(), type.toString());
        }
    }

    @Test
    void siblingCannotReceiveTheSelectedNozzlesEndpointExemption() {
        SafetyWorkbenchModel model = model();
        box(model, ObjectType.BOARD, 5, -1, 0, 2, 2, 10);
        Waypoint start = new Waypoint(0, 0, 10);
        DynamicTool sibling = new DynamicTool("H1", "N2", "N2", new Waypoint(6, 0, 10), profile(), 0, 0, 0, true, true, false, false);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(0, 0, 20), new Waypoint(20, 0, 20)), Arrays.asList(tool(start), sibling)).isSafe());
    }

    @Test
    void headBodyContactIsNeverWaivedAsPartContact() {
        SafetyWorkbenchModel model = model();
        box(model, ObjectType.BOARD, -1, -1, 0, 2, 2, 15);
        Waypoint start = new Waypoint(0, 0, 10);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(0, 0, 30), new Waypoint(20, 0, 30)), Collections.singletonList(tool(start))).isSafe());
    }

    @Test
    void rotatingRectangleUsesItsHalfDiagonalIncludingCorners() {
        DynamicTool carrying = new DynamicTool("H1", "N1", "N1", new Waypoint(0, 0, 20), profile(), 3, 4, 2, true, true, true, true);
        assertEquals(5, carrying.lowerRadiusX, 1e-9);
        assertEquals(5, carrying.lowerRadiusY, 1e-9);
    }

    @Test
    void customOutlineOutsideNominalSizeStillBlocksThePath() {
        SafetyWorkbenchModel model = model();
        SafetyObject object = box(model, ObjectType.CUSTOM, 0, 0, 0, 1, 1, 20);
        object.shape = Shape.POLYGON;
        object.polygon.addAll(Arrays.asList(new Point2(5, -1), new Point2(6, -1), new Point2(6, 1), new Point2(5, 1)));
        Waypoint start = new Waypoint(4, 0, 10);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model,
                Arrays.asList(start, new Waypoint(7, 0, 10)), Collections.singletonList(tool(start))).isSafe());
    }

    @Test
    void carriedPartCannotIntersectAnotherNozzleOnTheSameHead() {
        SafetyWorkbenchModel model = model();
        Waypoint start = new Waypoint(0, 0, 20);
        DynamicTool carrying = new DynamicTool("H1", "N1", "N1", start,
                profile(), 3, 4, 2, true, true, true, true);
        DynamicTool sibling = new DynamicTool("H1", "N2", "N2", new Waypoint(4, 0, 20),
                profile(), 0, 0, 0, true, true, false, false);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model,
                Arrays.asList(start, new Waypoint(20, 0, 20)), Arrays.asList(carrying, sibling)).isSafe());
    }

    @Test
    void coupledSiblingUsesItsWholeZRangeAcrossADiagonal() {
        SafetyWorkbenchModel model = model();
        box(model, ObjectType.KEEP_OUT, 49, 9, 10, 2, 2, 2);
        Waypoint start = new Waypoint(0, 0, 0);
        DynamicTool sibling = new DynamicTool("H1", "N2", "N2", new Waypoint(0, 10, 20),
                profile(), 0, 0, 0, true, true, true, false);
        sibling.selectedTool = false;
        sibling.conservativeSegmentZ = true;
        sibling.mappedZ = selectedZ -> 20 - selectedZ;
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model,
                Arrays.asList(start, new Waypoint(100, 0, 10)), Arrays.asList(tool(start), sibling)).isSafe());
    }

    @Test
    void invalidCoordinatesAndExcessivelyLongPathsAreRejected() {
        SafetyWorkbenchModel model = model();
        Waypoint start = new Waypoint(0, 0, 10);
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(Double.NaN, 0, 10)), Collections.singletonList(tool(start))).isSafe());
        assertFalse(DynamicSafetyCollisionEngine.validateTransit(model, Arrays.asList(start, new Waypoint(100000, 0, 10)), Collections.singletonList(tool(start))).isSafe());
    }
}
