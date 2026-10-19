/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;

class DynamicSafetyCollisionEngineTest {
    @Test
    void detectsCollisionWithAnotherHead() {
        SafetyWorkbenchModel model = baseModel();
        DynamicTool moving = tool("H1", "N1", new Waypoint(20, 20, 20),
                true, true, true);
        DynamicTool stationary = tool("H2", "N1", new Waypoint(100, 100, 20),
                false, false, false);

        ValidationResult result = DynamicSafetyCollisionEngine.validate(model,
                Arrays.asList(new Waypoint(20, 20, 20),
                        new Waypoint(100, 100, 20)),
                Arrays.asList(moving, stationary));

        assertFalse(result.isSafe());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> issue.contains("贴装头间碰撞")));
    }

    @Test
    void siblingNozzleSharesXyButKeepsIndependentZ() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject obstacle = model.createObject(ObjectType.KEEP_OUT);
        obstacle.name = "右侧禁入区";
        obstacle.x = 114;
        obstacle.y = 94;
        obstacle.z = 0;
        obstacle.width = 12;
        obstacle.depth = 12;
        obstacle.height = 20;
        obstacle.clearance = 0;
        model.addObject(obstacle);

        DynamicTool selected = tool("H1", "N1", new Waypoint(20, 100, 40),
                true, true, true);
        DynamicTool sibling = tool("H1", "N2", new Waypoint(40, 100, 10),
                true, true, false);
        List<Waypoint> path = Arrays.asList(
                new Waypoint(20, 100, 40),
                new Waypoint(100, 100, 5));

        ValidationResult result = DynamicSafetyCollisionEngine.validate(model,
                path, Arrays.asList(selected, sibling));

        assertFalse(result.isSafe());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> issue.contains("H1 · N2")
                        && issue.contains("右侧禁入区")));
    }

    @Test
    void calibratedTipCoordinatesDoNotSubtractThePhysicalNozzleLengthAgain() {
        ToolProfile profile = new ToolProfile();
        profile.zIsNozzleTip = true;
        profile.retractedZ = 15;
        profile.extendedZ = -15;
        DynamicTool nozzle = new DynamicTool("H1", "N1", "N1", new Waypoint(100, 100, 5.8),
                profile, 0.4, 0.8, 0.8, true, true, true, true);
        org.junit.jupiter.api.Assertions.assertEquals(0.8, nozzle.tipLengthAt(5.8), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(3.0, nozzle.minimumZAt(5.8), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(17.0, nozzle.bodyMinimumZAt(5.8), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-2.8, profile.getMinimumZOffset(5.8, 0.8), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(17.0, nozzle.bodyMinimumZAt(-15), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-17.8, nozzle.minimumZAt(-15), 1e-9);
    }

    @Test
    void nozzleEnvelopeChangesBetweenRetractedAndExtendedZ() {
        ToolProfile profile = new ToolProfile();
        profile.retractedZ = 10;
        profile.extendedZ = -30;
        profile.retractedTipLength = 8;
        profile.extendedTipLength = 40;

        assertTrue(profile.getTipLength(10) < profile.getTipLength(-10));
        assertTrue(profile.getTipLength(-10) < profile.getTipLength(-30));
        assertTrue(profile.getMinimumZOffset(-30, 4)
                < profile.getMinimumZOffset(10, 0));
    }

    @Test
    void narrowShaftCanUseCameraApertureWhileHeadBodyStaysAbove() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject camera = model.createObject(ObjectType.BOTTOM_CAMERA);
        camera.name = "底部相机";
        camera.x = 90;
        camera.y = 90;
        camera.z = 0;
        camera.width = 20;
        camera.depth = 20;
        camera.height = 10;
        camera.apertureWidth = 16;
        camera.apertureDepth = 16;
        camera.clearance = 0;
        model.addObject(camera);

        ToolProfile profile = new ToolProfile();
        profile.bodyRadiusX = 15;
        profile.bodyRadiusY = 15;
        profile.bodyMinZOffset = 30;
        profile.bodyMaxZOffset = 70;
        profile.shaftRadius = 2;
        profile.clearance = 1;
        profile.retractedZ = 0;
        profile.extendedZ = -30;
        profile.retractedTipLength = 8;
        profile.extendedTipLength = 38;
        DynamicTool nozzle = new DynamicTool("H1", "N1", "H1 · N1",
                new Waypoint(100, 100, 0), profile, 0, 0, 0,
                true, true, true, false);

        ValidationResult result = DynamicSafetyCollisionEngine.validate(model,
                Arrays.asList(new Waypoint(100, 100, 0)), Arrays.asList(nozzle));

        assertTrue(result.isSafe(), result.getIssues().toString());
    }

    @Test
    void transitAllowsLeavingSourceAndApproachingDestination() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject source = box(model, "起点飞达", 15, 15, 10, 10, 20);
        SafetyObject destination = box(model, "终点电路板", 175, 15, 10, 10, 20);
        source.type = ObjectType.FEEDER;
        destination.type = ObjectType.BOARD;
        ToolProfile profile = new ToolProfile();
        profile.bodyFollowsNozzleZ = true;
        profile.bodyRadiusX = profile.bodyRadiusY = 4;
        profile.clearance = 0;
        profile.retractedTipLength = profile.extendedTipLength = 5;
        DynamicTool nozzle = new DynamicTool("H1", "N1", "N1", new Waypoint(20, 20, 22),
                profile, 0, 0, 0, true, true, true, false);

        ValidationResult result = DynamicSafetyCollisionEngine.validateTransit(model,
                Arrays.asList(new Waypoint(20, 20, 22),
                        new Waypoint(20, 20, 30),
                        new Waypoint(100, 100, 40),
                        new Waypoint(180, 20, 30),
                        new Waypoint(180, 20, 22)),
                Arrays.asList(nozzle));

        assertTrue(result.isSafe(), result.getIssues().toString());
    }

    @Test
    void transitRejectsReenteringObstacleMidRoute() {
        SafetyWorkbenchModel model = baseModel();
        box(model, "中途障碍", 90, 90, 20, 20, 30);
        DynamicTool nozzle = tool("H1", "N1", new Waypoint(20, 20, 15),
                true, true, true);

        ValidationResult result = DynamicSafetyCollisionEngine.validateTransit(model,
                Arrays.asList(new Waypoint(20, 20, 15),
                        new Waypoint(100, 100, 15),
                        new Waypoint(180, 20, 15)),
                Arrays.asList(nozzle));

        assertFalse(result.isSafe());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> issue.contains("扫掠进入")));
    }

    private static SafetyObject box(SafetyWorkbenchModel model, String name,
            double x, double y, double width, double depth, double height) {
        SafetyObject object = model.createObject(ObjectType.KEEP_OUT);
        object.name = name;
        object.x = x;
        object.y = y;
        object.z = 0;
        object.width = width;
        object.depth = depth;
        object.height = height;
        object.clearance = 0;
        model.addObject(object);
        return object;
    }

    private static DynamicTool tool(String head, String nozzle, Waypoint reference,
            boolean followsX, boolean followsY, boolean followsZ) {
        return new DynamicTool(head, nozzle, head + " · " + nozzle,
                reference, 4, 4, -5, 10, 2, 8, 0x70889A,
                followsX, followsY, followsZ, false);
    }

    private static SafetyWorkbenchModel baseModel() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.getObjects().clear();
        model.setMinX(0);
        model.setMaxX(200);
        model.setMinY(0);
        model.setMaxY(200);
        model.setMinZ(-50);
        model.setMaxZ(200);
        model.setSampleStep(1);
        model.ensureToolProfile("H1", "N1", "H1 · N1");
        return model;
    }
}
