/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.function.DoubleFunction;

import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

/** Resolves the viewing-plane scale from the 3D workbench surface under a camera pixel. */
public final class SafetyWorkbenchVisionScale {
    public enum Status {
        Applied,
        Disabled,
        ModelNotCertified,
        CameraNotSupported,
        CameraNotCalibrated,
        NoSurface
    }

    public static final class Resolution {
        private final Status status;
        private final Location unitsPerPixel;
        private final SafetyObject object;
        private final double surfaceZ;
        private final double targetX;
        private final double targetY;

        private Resolution(Status status, Location unitsPerPixel, SafetyObject object,
                double surfaceZ, double targetX, double targetY) {
            this.status = status;
            this.unitsPerPixel = unitsPerPixel;
            this.object = object;
            this.surfaceZ = surfaceZ;
            this.targetX = targetX;
            this.targetY = targetY;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isApplied() {
            return status == Status.Applied;
        }

        public Location getUnitsPerPixel() {
            return unitsPerPixel;
        }

        public SafetyObject getObject() {
            return object;
        }

        public double getSurfaceZ() {
            return surfaceZ;
        }

        public double getTargetX() {
            return targetX;
        }

        public double getTargetY() {
            return targetY;
        }
    }

    private SafetyWorkbenchVisionScale() {
    }

    public static Resolution resolve(SafetyWorkbenchModel model, Camera camera,
            Location cameraLocation, double pixelOffsetX, double pixelOffsetY) {
        Location fallback = camera.getUnitsPerPixelAtZ();
        if (model == null || !model.isVisionHeightCompensationEnabled()) {
            return fallback(Status.Disabled, fallback);
        }
        if (!model.isCertified()) {
            return fallback(Status.ModelNotCertified, fallback);
        }
        if (camera.getLooking() != Camera.Looking.Down) {
            return fallback(Status.CameraNotSupported, fallback);
        }
        if (!camera.isUnitsPerPixelAtZCalibrated()) {
            return fallback(Status.CameraNotCalibrated, fallback);
        }
        if (cameraLocation == null) {
            return fallback(Status.CameraNotSupported, fallback);
        }
        return resolveSurfaces(model, cameraLocation, pixelOffsetX, pixelOffsetY,
                z -> camera.getUnitsPerPixel(new Length(z, LengthUnit.Millimeters)), fallback);
    }

    static Resolution resolveSurfaces(SafetyWorkbenchModel model, Location cameraLocation,
            double pixelOffsetX, double pixelOffsetY,
            DoubleFunction<Location> unitsPerPixelAtZ, Location fallback) {
        Location center = cameraLocation.convertToUnits(LengthUnit.Millimeters);
        SafetyObject best = null;
        Location bestUnitsPerPixel = null;
        double bestSurfaceZ = -Double.MAX_VALUE;
        double bestTargetX = Double.NaN;
        double bestTargetY = Double.NaN;
        for (SafetyObject object : model.getObjects()) {
            if (!isUsableSurface(object)) {
                continue;
            }
            double surfaceZ = object.z + object.height;
            Location unitsPerPixel;
            try {
                unitsPerPixel = unitsPerPixelAtZ.apply(surfaceZ);
            }
            catch (RuntimeException exception) {
                continue;
            }
            if (!isUsableScale(unitsPerPixel)) {
                continue;
            }
            Location scaleMm = unitsPerPixel.convertToUnits(LengthUnit.Millimeters);
            double targetX = center.getX() + pixelOffsetX * scaleMm.getX();
            double targetY = center.getY() + pixelOffsetY * scaleMm.getY();
            if (!contains(object, targetX, targetY) || insideCentralOpening(object,
                    targetX, targetY)) {
                continue;
            }
            if (best == null || surfaceZ > bestSurfaceZ) {
                best = object;
                bestUnitsPerPixel = unitsPerPixel;
                bestSurfaceZ = surfaceZ;
                bestTargetX = targetX;
                bestTargetY = targetY;
            }
        }
        if (best == null) {
            return fallback(Status.NoSurface, fallback);
        }
        return new Resolution(Status.Applied, bestUnitsPerPixel, best, bestSurfaceZ,
                bestTargetX, bestTargetY);
    }

    private static Resolution fallback(Status status, Location unitsPerPixel) {
        return new Resolution(status, unitsPerPixel, null, Double.NaN, Double.NaN, Double.NaN);
    }

    private static boolean isUsableScale(Location scale) {
        return scale != null && Double.isFinite(scale.getX()) && Double.isFinite(scale.getY())
                && scale.getX() > 0 && scale.getY() > 0;
    }

    private static boolean isUsableSurface(SafetyObject object) {
        return object != null && object.enabled && !object.type.isReferencePoint()
                && object.type != ObjectType.PLACEMENT_HEAD
                && object.type != ObjectType.KEEP_OUT
                && Double.isFinite(object.x) && Double.isFinite(object.y)
                && Double.isFinite(object.z) && Double.isFinite(object.width)
                && Double.isFinite(object.depth) && Double.isFinite(object.height)
                && object.width > 0 && object.depth > 0 && object.height >= 0;
    }

    private static boolean contains(SafetyObject object, double x, double y) {
        switch (object.shape) {
            case CYLINDER:
                double radiusX = object.width / 2.0;
                double radiusY = object.depth / 2.0;
                double normalizedX = (x - object.x - radiusX) / radiusX;
                double normalizedY = (y - object.y - radiusY) / radiusY;
                return normalizedX * normalizedX + normalizedY * normalizedY <= 1.0;
            case POLYGON:
                return pointInPolygon(object, x, y);
            case BOX:
            default:
                return x >= object.x && x <= object.x + object.width
                        && y >= object.y && y <= object.y + object.depth;
        }
    }

    private static boolean pointInPolygon(SafetyObject object, double x, double y) {
        if (object.polygon == null || object.polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int index = 0, previous = object.polygon.size() - 1;
                index < object.polygon.size(); previous = index++) {
            Point2 currentPoint = object.polygon.get(index);
            Point2 previousPoint = object.polygon.get(previous);
            double currentX = object.x + currentPoint.x;
            double currentY = object.y + currentPoint.y;
            double previousX = object.x + previousPoint.x;
            double previousY = object.y + previousPoint.y;
            boolean crosses = (currentY > y) != (previousY > y)
                    && x < (previousX - currentX) * (y - currentY)
                            / (previousY - currentY) + currentX;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean insideCentralOpening(SafetyObject object, double x, double y) {
        if (!SafetyWorkbenchGeometry.hasCentralOpening(object)) {
            return false;
        }
        double apertureX = object.x + (object.width - object.apertureWidth) / 2.0;
        double apertureY = object.y + (object.depth - object.apertureDepth) / 2.0;
        return x >= apertureX && x <= apertureX + object.apertureWidth
                && y >= apertureY && y <= apertureY + object.apertureDepth;
    }
}
