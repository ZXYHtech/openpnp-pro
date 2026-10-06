/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVisionScale.Resolution;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVisionScale.Status;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

public class SafetyWorkbenchVisionScaleTest {
    @Test
    public void selectsTheSurfaceIntersectedAtItsOwnViewingScale() {
        SafetyWorkbenchModel model = configuredModel();
        SafetyObject board = surface(model, ObjectType.BOARD,
                "PCB 台", 80, 80, 0, 40, 40, 10);
        SafetyObject feeder = surface(model, ObjectType.FEEDER,
                "高位飞达", 120, 80, 0, 40, 40, 30);
        model.certify("test");
        Camera camera = calibratedTopCamera(new Location(LengthUnit.Millimeters,
                100, 100, 100, 0));

        Resolution center = SafetyWorkbenchVisionScale.resolve(model, camera,
                camera.getLocation(), 0, 0);
        Resolution toTheRight = SafetyWorkbenchVisionScale.resolve(model, camera,
                camera.getLocation(), 300, 0);

        assertTrue(center.isApplied());
        assertSame(board, center.getObject());
        assertEquals(10, center.getSurfaceZ(), 0.000001);
        assertEquals(0.093333, center.getUnitsPerPixel().getX(), 0.00001);
        assertTrue(toTheRight.isApplied());
        assertSame(feeder, toTheRight.getObject());
        assertEquals(30, toTheRight.getSurfaceZ(), 0.000001);
        assertEquals(124, toTheRight.getTargetX(), 0.000001);
        assertEquals(0.08, toTheRight.getUnitsPerPixel().getX(), 0.000001);
    }

    @Test
    public void doesNotApplyUntilTheCameraHasTwoHeightCalibration() {
        SafetyWorkbenchModel model = configuredModel();
        surface(model, ObjectType.BOARD, "PCB 台", 80, 80, 0, 40, 40, 10);
        model.certify("test");
        Camera camera = mock(Camera.class);
        Location fallback = new Location(LengthUnit.Millimeters, 0.1, 0.1, 0, 0);
        when(camera.getUnitsPerPixelAtZ()).thenReturn(fallback);
        when(camera.getLooking()).thenReturn(Camera.Looking.Down);
        when(camera.isUnitsPerPixelAtZCalibrated()).thenReturn(false);

        Resolution result = SafetyWorkbenchVisionScale.resolve(model, camera,
                new Location(LengthUnit.Millimeters, 100, 100, 100, 0), 0, 0);

        assertFalse(result.isApplied());
        assertEquals(Status.CameraNotCalibrated, result.getStatus());
        assertSame(fallback, result.getUnitsPerPixel());
    }

    @Test
    public void ignoresTheOpeningInABottomCameraBody() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject cameraBody = surface(model, ObjectType.BOTTOM_CAMERA,
                "底部相机", 80, 80, 0, 40, 40, 25);
        cameraBody.apertureWidth = 20;
        cameraBody.apertureDepth = 20;
        Location fallback = new Location(LengthUnit.Millimeters, 0.1, 0.1, 0, 0);

        Resolution result = SafetyWorkbenchVisionScale.resolveSurfaces(model,
                new Location(LengthUnit.Millimeters, 100, 100, 100, 0), 0, 0,
                z -> new Location(LengthUnit.Millimeters, 0.08, 0.08, z, 0), fallback);

        assertEquals(Status.NoSurface, result.getStatus());
    }

    @Test
    public void selectsTheHighestVisibleSurfaceWhenObjectsOverlap() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject board = surface(model, ObjectType.BOARD,
                "PCB 台", 80, 80, 0, 40, 40, 10);
        SafetyObject partTray = surface(model, ObjectType.LOOSE_PARTS_FEEDER,
                "板上托盘", 90, 90, 10, 20, 20, 8);
        Location fallback = new Location(LengthUnit.Millimeters, 0.1, 0.1, 0, 0);

        Resolution result = SafetyWorkbenchVisionScale.resolveSurfaces(model,
                new Location(LengthUnit.Millimeters, 100, 100, 100, 0), 0, 0,
                z -> new Location(LengthUnit.Millimeters, 0.1, 0.1, z, 0), fallback);

        assertSame(partTray, result.getObject());
        assertEquals(18, result.getSurfaceZ(), 0.000001);
        assertFalse(result.getObject() == board);
    }

    private static SafetyWorkbenchModel configuredModel() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.ensureToolProfile("H1", "N1", "N1");
        model.setVisionHeightCompensationEnabled(true);
        return model;
    }

    private static SafetyObject surface(SafetyWorkbenchModel model, ObjectType type,
            String name, double x, double y, double z, double width, double depth,
            double height) {
        SafetyObject object = model.createObject(type);
        object.name = name;
        object.x = x;
        object.y = y;
        object.z = z;
        object.width = width;
        object.depth = depth;
        object.height = height;
        model.addObject(object);
        return object;
    }

    private static Camera calibratedTopCamera(Location cameraLocation) {
        Camera camera = mock(Camera.class);
        Location fallback = new Location(LengthUnit.Millimeters, 0.1, 0.1, 0, 0);
        when(camera.getUnitsPerPixelAtZ()).thenReturn(fallback);
        when(camera.getLooking()).thenReturn(Camera.Looking.Down);
        when(camera.isUnitsPerPixelAtZCalibrated()).thenReturn(true);
        when(camera.getLocation()).thenReturn(cameraLocation);
        when(camera.getUnitsPerPixel(any(Length.class))).thenAnswer(invocation -> {
            Length height = invocation.getArgument(0);
            double z = height.convertToUnits(LengthUnit.Millimeters).getValue();
            double scale = 0.1 - z / 1500.0;
            return new Location(LengthUnit.Millimeters, scale, scale, z, 0);
        });
        return camera;
    }
}
