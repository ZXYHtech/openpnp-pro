/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;

public class SafetyCollisionEngineTest {
    @Test
    public void detectsObstacleBetweenSafeEndpoints() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject obstacle = model.createObject(ObjectType.FEEDER);
        obstacle.name = "中央飞达";
        obstacle.x = 80;
        obstacle.y = 80;
        obstacle.z = 0;
        obstacle.width = 40;
        obstacle.depth = 40;
        obstacle.height = 35;
        obstacle.clearance = 2;
        model.addObject(obstacle);

        ValidationResult result = SafetyCollisionEngine.validate(model, Arrays.asList(
                new Waypoint(40, 40, 10),
                new Waypoint(160, 160, 10)));

        assertFalse(result.isSafe());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> issue.contains("中央飞达")));
    }

    @Test
    public void acceptsPathAboveObstacle() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject obstacle = model.createObject(ObjectType.FEEDER);
        obstacle.x = 80;
        obstacle.y = 80;
        obstacle.z = 0;
        obstacle.width = 40;
        obstacle.depth = 40;
        obstacle.height = 35;
        model.addObject(obstacle);

        ValidationResult result = SafetyCollisionEngine.validate(model, Arrays.asList(
                new Waypoint(40, 40, 100),
                new Waypoint(160, 160, 100)));

        assertTrue(result.isSafe(), result.getIssues().toString());
    }

    @Test
    public void bottomCameraAperturePermitsCenteredNozzleEnvelope() {
        SafetyWorkbenchModel model = baseModel();
        SafetyObject camera = model.createObject(ObjectType.BOTTOM_CAMERA);
        camera.x = 80;
        camera.y = 80;
        camera.z = 0;
        camera.width = 40;
        camera.depth = 40;
        camera.height = 25;
        camera.apertureWidth = 30;
        camera.apertureDepth = 30;
        camera.clearance = 1;
        model.addObject(camera);

        ValidationResult result = SafetyCollisionEngine.validate(model, Arrays.asList(
                new Waypoint(100, 100, 10)));

        assertTrue(result.isSafe(), result.getIssues().toString());
    }

    @Test
    public void geometryChangeInvalidatesCertification() {
        SafetyWorkbenchModel model = baseModel();
        model.certify("test");
        assertTrue(model.isCertified());
        model.setMaxX(250);
        assertFalse(model.isCertified());
    }

    private static SafetyWorkbenchModel baseModel() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.getObjects().clear();
        model.setMinX(0);
        model.setMaxX(200);
        model.setMinY(0);
        model.setMaxY(200);
        model.setMinZ(-50);
        model.setMaxZ(300);
        model.setToolRadiusX(5);
        model.setToolRadiusY(5);
        model.setToolMinZOffset(-5);
        model.setToolMaxZOffset(10);
        model.setSampleStep(1);
        model.ensureToolProfile("head-1", "N1", "N1");
        return model;
    }
}
