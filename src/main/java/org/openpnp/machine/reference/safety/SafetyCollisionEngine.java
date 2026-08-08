/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;

/**
 * Conservative swept-volume checker. It samples every segment, not only its
 * endpoints, and checks the configured tool envelope against machine bounds and
 * all enabled obstacles.
 */
public final class SafetyCollisionEngine {
    public static class Waypoint {
        public final double x;
        public final double y;
        public final double z;

        public Waypoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static Waypoint interpolate(Waypoint a, Waypoint b, double t) {
            return new Waypoint(a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t);
        }
    }

    public static class ValidationResult {
        private final List<String> issues;
        private final List<Waypoint> sampledPath;

        ValidationResult(List<String> issues, List<Waypoint> sampledPath) {
            this.issues = Collections.unmodifiableList(issues);
            this.sampledPath = Collections.unmodifiableList(sampledPath);
        }

        public boolean isSafe() {
            return issues.isEmpty();
        }

        public List<String> getIssues() {
            return issues;
        }

        public List<Waypoint> getSampledPath() {
            return sampledPath;
        }
    }

    private SafetyCollisionEngine() {
    }

    public static ValidationResult validate(SafetyWorkbenchModel model,
            List<Waypoint> waypoints) {
        Set<String> issues = new LinkedHashSet<>();
        List<Waypoint> samples = sample(waypoints, Math.max(0.1, model.getSampleStep()));
        if (samples.isEmpty()) {
            issues.add("轨迹至少需要一个有效点。");
        }
        for (Waypoint point : samples) {
            checkBounds(model, point, issues);
            for (SafetyObject object : model.getObjects()) {
                if (object != null && object.enabled && object.obstacle
                        && intersects(model, point, object)) {
                    issues.add("轨迹进入“" + object.name + "”的安全包络。");
                }
            }
        }
        return new ValidationResult(new ArrayList<>(issues), samples);
    }

    private static List<Waypoint> sample(List<Waypoint> waypoints, double step) {
        List<Waypoint> samples = new ArrayList<>();
        if (waypoints == null || waypoints.isEmpty()) {
            return samples;
        }
        samples.add(waypoints.get(0));
        for (int index = 1; index < waypoints.size(); index++) {
            Waypoint start = waypoints.get(index - 1);
            Waypoint end = waypoints.get(index);
            double dx = end.x - start.x;
            double dy = end.y - start.y;
            double dz = end.z - start.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int count = Math.max(1, (int) Math.ceil(distance / step));
            for (int sample = 1; sample <= count; sample++) {
                samples.add(Waypoint.interpolate(start, end, sample / (double) count));
            }
        }
        return samples;
    }

    private static void checkBounds(SafetyWorkbenchModel model, Waypoint point,
            Set<String> issues) {
        if (point.x - model.getToolRadiusX() < model.getMinX()
                || point.x + model.getToolRadiusX() > model.getMaxX()) {
            issues.add("工具头包络超出工作台 X 轴安全范围。");
        }
        if (point.y - model.getToolRadiusY() < model.getMinY()
                || point.y + model.getToolRadiusY() > model.getMaxY()) {
            issues.add("工具头包络超出工作台 Y 轴安全范围。");
        }
        if (point.z + model.getToolMinZOffset() < model.getMinZ()
                || point.z + model.getToolMaxZOffset() > model.getMaxZ()) {
            issues.add("工具头包络超出工作台 Z 轴安全范围。");
        }
    }

    private static boolean intersects(SafetyWorkbenchModel model, Waypoint point,
            SafetyObject object) {
        double clearance = Math.max(0, object.clearance);
        double toolMinX = point.x - model.getToolRadiusX();
        double toolMaxX = point.x + model.getToolRadiusX();
        double toolMinY = point.y - model.getToolRadiusY();
        double toolMaxY = point.y + model.getToolRadiusY();
        double toolMinZ = point.z + model.getToolMinZOffset();
        double toolMaxZ = point.z + model.getToolMaxZOffset();

        double objectMinX = object.x - clearance;
        double objectMaxX = object.x + Math.max(0, object.width) + clearance;
        double objectMinY = object.y - clearance;
        double objectMaxY = object.y + Math.max(0, object.depth) + clearance;
        double objectMinZ = object.z - clearance;
        double objectMaxZ = object.z + Math.max(0, object.height) + clearance;

        if (!overlaps(toolMinZ, toolMaxZ, objectMinZ, objectMaxZ)) {
            return false;
        }

        if ((object.type == SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA
                || object.type == SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER)
                && object.apertureWidth > 0 && object.apertureDepth > 0) {
            double apertureMinX = object.x + (object.width - object.apertureWidth) / 2.0;
            double apertureMaxX = apertureMinX + object.apertureWidth;
            double apertureMinY = object.y + (object.depth - object.apertureDepth) / 2.0;
            double apertureMaxY = apertureMinY + object.apertureDepth;
            boolean completelyInsideAperture = toolMinX >= apertureMinX + clearance
                    && toolMaxX <= apertureMaxX - clearance
                    && toolMinY >= apertureMinY + clearance
                    && toolMaxY <= apertureMaxY - clearance;
            if (completelyInsideAperture) {
                return false;
            }
        }

        if (object.shape == Shape.CYLINDER) {
            double radiusX = Math.max(0, object.width) / 2.0 + clearance;
            double radiusY = Math.max(0, object.depth) / 2.0 + clearance;
            double centerX = object.x + object.width / 2.0;
            double centerY = object.y + object.depth / 2.0;
            double closestX = clamp(centerX, toolMinX, toolMaxX);
            double closestY = clamp(centerY, toolMinY, toolMaxY);
            double normalizedX = radiusX == 0 ? 0 : (closestX - centerX) / radiusX;
            double normalizedY = radiusY == 0 ? 0 : (closestY - centerY) / radiusY;
            return normalizedX * normalizedX + normalizedY * normalizedY <= 1.0;
        }

        if (object.shape == Shape.POLYGON && object.polygon != null
                && object.polygon.size() >= 3) {
            return polygonIntersectsRectangle(object, toolMinX - clearance,
                    toolMaxX + clearance, toolMinY - clearance, toolMaxY + clearance);
        }

        return overlaps(toolMinX, toolMaxX, objectMinX, objectMaxX)
                && overlaps(toolMinY, toolMaxY, objectMinY, objectMaxY);
    }

    private static boolean polygonIntersectsRectangle(SafetyObject object, double minX,
            double maxX, double minY, double maxY) {
        double[][] corners = {
                { minX, minY }, { minX, maxY }, { maxX, minY }, { maxX, maxY }
        };
        for (double[] corner : corners) {
            if (pointInPolygon(object, corner[0], corner[1])) {
                return true;
            }
        }
        for (Point2 point : object.polygon) {
            double x = object.x + point.x;
            double y = object.y + point.y;
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                return true;
            }
        }
        for (int index = 0; index < object.polygon.size(); index++) {
            Point2 a = object.polygon.get(index);
            Point2 b = object.polygon.get((index + 1) % object.polygon.size());
            double ax = object.x + a.x;
            double ay = object.y + a.y;
            double bx = object.x + b.x;
            double by = object.y + b.y;
            if (segmentIntersectsRectangle(ax, ay, bx, by, minX, maxX, minY, maxY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointInPolygon(SafetyObject object, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = object.polygon.size() - 1; i < object.polygon.size(); j = i++) {
            double xi = object.x + object.polygon.get(i).x;
            double yi = object.y + object.polygon.get(i).y;
            double xj = object.x + object.polygon.get(j).x;
            double yj = object.y + object.polygon.get(j).y;
            if ((yi > y) != (yj > y)
                    && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean segmentIntersectsRectangle(double ax, double ay, double bx,
            double by, double minX, double maxX, double minY, double maxY) {
        return segmentsIntersect(ax, ay, bx, by, minX, minY, maxX, minY)
                || segmentsIntersect(ax, ay, bx, by, maxX, minY, maxX, maxY)
                || segmentsIntersect(ax, ay, bx, by, maxX, maxY, minX, maxY)
                || segmentsIntersect(ax, ay, bx, by, minX, maxY, minX, minY);
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        double d1 = direction(cx, cy, dx, dy, ax, ay);
        double d2 = direction(cx, cy, dx, dy, bx, by);
        double d3 = direction(ax, ay, bx, by, cx, cy);
        double d4 = direction(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double direction(double ax, double ay, double bx, double by,
            double cx, double cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private static boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return maxA >= minB && maxB >= minA;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
