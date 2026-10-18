/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;

/**
 * Continuous conservative envelopes, including the space between sampled positions.
 */
final class CoordinatedMotionValidator {

    private static final double EPSILON = 0.000001;

    private static final int MAX_INTERVALS = 20000;

    private CoordinatedMotionValidator() {
    }

    static ValidationResult validate(SafetyWorkbenchModel model, List<Waypoint> path, List<DynamicTool> tools, Waypoint anchor) {
        Set<String> issues = new LinkedHashSet<>();
        List<Waypoint> samples = new ArrayList<>();
        if (path == null || path.isEmpty() || tools == null || tools.isEmpty()) {
            return new ValidationResult(Collections.singletonList("缺少轨迹或吸嘴包络。"), samples);
        }
        for (Waypoint point : path) {
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                return new ValidationResult(Collections.singletonList("轨迹坐标无效。"), samples);
            }
        }
        samples.add(path.get(0));
        int intervalCount = 0;
        for (int segment = 1; segment < Math.max(2, path.size()); segment++) {
            Waypoint first = path.get(segment - 1);
            Waypoint last = path.get(Math.min(segment, path.size() - 1));
            double distance = Math.sqrt(square(last.x - first.x) + square(last.y - first.y) + square(last.z - first.z));
            int count = Math.max(1, (int) Math.ceil(distance / 0.5));
            if (count > MAX_INTERVALS - intervalCount) {
                issues.add("轨迹过长，保持原安全 Z 路径。");
                break;
            }
            intervalCount += count;
            // Tip extension is piecewise linear. Include its breakpoints so
            // an interval cannot miss a Z extremum.
            List<Double> fractions = new ArrayList<>();
            for (int index = 0; index <= count; index++) {
                fractions.add(index / (double) count);
            }
            if (last.z != first.z) {
                for (DynamicTool tool : tools) {
                    if (tool.followsZ) {
                        for (double z : new double[] { tool.retractedZ, tool.extendedZ }) {
                            double fraction = (z - tool.reference.z - first.z + anchor.z) / (last.z - first.z);
                            if (fraction > 0 && fraction < 1) {
                                fractions.add(fraction);
                            }
                        }
                    }
                }
            }
            Collections.sort(fractions);
            for (int index = 1; index < fractions.size(); index++) {
                Waypoint a = Waypoint.interpolate(first, last, fractions.get(index - 1));
                Waypoint b = Waypoint.interpolate(first, last, fractions.get(index));
                samples.add(b);
                List<Envelope> envelopes = new ArrayList<>();
                for (DynamicTool tool : tools) {
                    Waypoint poseA = tool.at(anchor, a);
                    Waypoint poseB = tool.at(anchor, b);
                    if (tool.conservativeSegmentZ) {
                        // Cam Z is monotone in raw motor position, but nonlinear
                        // in time. Cover the complete segment Z range at every XY
                        // interval instead of assuming linear physical Z motion.
                        poseA = new Waypoint(poseA.x, poseA.y, tool.at(anchor, first).z);
                        poseB = new Waypoint(poseB.x, poseB.y, tool.at(anchor, last).z);
                    }
                    Envelope envelope = new Envelope(tool, poseA, poseB);
                    envelopes.add(envelope);
                    if (!tool.isMoving()) {
                        continue;
                    }
                    if (!envelope.inBounds(model)) {
                        issues.add("“" + tool.name + "”超出工作台安全范围。");
                    }
                    for (SafetyObject object : model.getObjects()) {
                        if (object == null || !object.enabled || !object.obstacle) {
                            continue;
                        }
                        if (envelope.intersects(object) && !terminalContact(envelope, object, first, last, path.get(0), path.get(path.size() - 1), segment == 1, segment == path.size() - 1)) {
                            issues.add("“" + tool.name + "”的联动扫掠进入“" + object.name + "”。");
                        }
                    }
                }
                for (int aIndex = 0; aIndex < envelopes.size(); aIndex++) {
                    for (int bIndex = aIndex + 1; bIndex < envelopes.size(); bIndex++) {
                        Envelope aEnvelope = envelopes.get(aIndex);
                        Envelope bEnvelope = envelopes.get(bIndex);
                        if (aEnvelope.tool.headId.equals(bEnvelope.tool.headId)
                                && (aEnvelope.partIntersects(bEnvelope) || bEnvelope.partIntersects(aEnvelope))) {
                            issues.add("同头吸嘴物料干涉：“" + aEnvelope.tool.name + "”与“"
                                    + bEnvelope.tool.name + "”。");
                        }
                        if (!aEnvelope.tool.headId.equals(bEnvelope.tool.headId) && (aEnvelope.tool.isMoving() || bEnvelope.tool.isMoving()) && aEnvelope.intersects(bEnvelope)) {
                            issues.add("贴装头间碰撞风险：“" + aEnvelope.tool.name + "”与“" + bEnvelope.tool.name + "”。");
                        }
                    }
                }
                if (!issues.isEmpty()) {
                    return new ValidationResult(new ArrayList<>(issues), samples);
                }
            }
        }
        return new ValidationResult(new ArrayList<>(issues), samples);
    }

    private static boolean terminalContact(Envelope envelope, SafetyObject object, Waypoint first, Waypoint last, Waypoint source, Waypoint target, boolean firstSegment, boolean lastSegment) {
        // Only the selected tip may leave/approach a feeder or board, vertically.
        // Never waive contact during a diagonal or for a sibling nozzle.
        if (!envelope.tool.selectedTool || (object.type != ObjectType.BOARD && object.type != ObjectType.FEEDER && object.type != ObjectType.LOOSE_PARTS_FEEDER) || Math.abs(first.x - last.x) > EPSILON || Math.abs(first.y - last.y) > EPSILON || envelope.bodyMinZ <= object.z + object.height + object.clearance) {
            return false;
        }
        if (firstSegment && last.z > first.z && sameXy(first, source) && envelope.tool.minimumZAt(envelope.b.z) >= envelope.tool.minimumZAt(envelope.a.z) && source.z >= object.z + object.height - EPSILON) {
            return true;
        }
        return lastSegment && last.z < first.z && sameXy(last, target) && envelope.tool.minimumZAt(envelope.b.z) <= envelope.tool.minimumZAt(envelope.a.z) && target.z >= object.z + object.height - EPSILON;
    }

    private static boolean sameXy(Waypoint a, Waypoint b) {
        return Math.abs(a.x - b.x) < EPSILON && Math.abs(a.y - b.y) < EPSILON;
    }

    private static double square(double value) {
        return value * value;
    }

    private static boolean overlap(double a, double b, double c, double d) {
        return a <= d && b >= c;
    }

    private static final class Envelope {

        final DynamicTool tool;

        final double minX, maxX, minY, maxY, minZ, maxZ, bodyMinZ;
        final double tipMinZ, tipMaxZ;

        final Waypoint a, b;

        Envelope(DynamicTool tool, Waypoint a, Waypoint b) {
            this.tool = tool;
            this.a = a;
            this.b = b;
            minX = Math.min(a.x, b.x) - tool.radiusX;
            maxX = Math.max(a.x, b.x) + tool.radiusX;
            minY = Math.min(a.y, b.y) - tool.radiusY;
            maxY = Math.max(a.y, b.y) + tool.radiusY;
            double lowZ = Math.min(tool.minimumZAt(a.z), tool.minimumZAt(b.z));
            double highZ = Math.max(tool.maximumZAt(a.z), tool.maximumZAt(b.z));
            double lowTip = Math.min(a.z - tool.tipLengthAt(a.z), b.z - tool.tipLengthAt(b.z));
            double highTip = Math.max(a.z - tool.tipLengthAt(a.z), b.z - tool.tipLengthAt(b.z));
            for (double breakpoint : new double[] {tool.retractedZ, tool.extendedZ}) {
                if (breakpoint >= Math.min(a.z, b.z) && breakpoint <= Math.max(a.z, b.z)) {
                    lowZ = Math.min(lowZ, tool.minimumZAt(breakpoint));
                    highZ = Math.max(highZ, tool.maximumZAt(breakpoint));
                    lowTip = Math.min(lowTip, breakpoint - tool.tipLengthAt(breakpoint));
                    highTip = Math.max(highTip, breakpoint - tool.tipLengthAt(breakpoint));
                }
            }
            minZ = lowZ;
            maxZ = highZ;
            tipMinZ = lowTip;
            tipMaxZ = highTip;
            bodyMinZ = Math.min(tool.bodyMinimumZAt(a.z), tool.bodyMinimumZAt(b.z));
        }

        boolean inBounds(SafetyWorkbenchModel model) {
            return minX >= model.getMinX() && maxX <= model.getMaxX() && minY >= model.getMinY() && maxY <= model.getMaxY() && minZ >= model.getMinZ() && maxZ <= model.getMaxZ();
        }

        boolean intersects(Envelope other) {
            return overlap(minX, maxX, other.minX, other.maxX) && overlap(minY, maxY, other.minY, other.maxY) && overlap(minZ, maxZ, other.minZ, other.maxZ);
        }

        boolean partIntersects(Envelope other) {
            if (!tool.carryingPart) {
                return false;
            }
            double partLowZ = tipMinZ - tool.clearance;
            double partHighZ = tipMaxZ + tool.carriedPartHeight + tool.clearance;
            double otherRadiusX = partHighZ < other.bodyMinZ ? other.tool.lowerRadiusX : other.tool.radiusX;
            double otherRadiusY = partHighZ < other.bodyMinZ ? other.tool.lowerRadiusY : other.tool.radiusY;
            return overlap(partLowZ, partHighZ, other.minZ, other.maxZ)
                    && overlap(Math.min(a.x, b.x) - tool.lowerRadiusX, Math.max(a.x, b.x) + tool.lowerRadiusX,
                            Math.min(other.a.x, other.b.x) - otherRadiusX, Math.max(other.a.x, other.b.x) + otherRadiusX)
                    && overlap(Math.min(a.y, b.y) - tool.lowerRadiusY, Math.max(a.y, b.y) + tool.lowerRadiusY,
                            Math.min(other.a.y, other.b.y) - otherRadiusY, Math.max(other.a.y, other.b.y) + otherRadiusY);
        }

        boolean intersects(SafetyObject object) {
            double margin = object.clearance;
            double lowZ = object.z - margin;
            double highZ = object.z + object.height + margin;
            if (!overlap(minZ, maxZ, lowZ, highZ)) {
                return false;
            }
            double radiusX = highZ < bodyMinZ ? tool.lowerRadiusX : tool.radiusX;
            double radiusY = highZ < bodyMinZ ? tool.lowerRadiusY : tool.radiusY;
            double lowX = Math.min(a.x, b.x) - radiusX;
            double highX = Math.max(a.x, b.x) + radiusX;
            double lowY = Math.min(a.y, b.y) - radiusY;
            double highY = Math.max(a.y, b.y) + radiusY;
            if ((object.type == ObjectType.BOTTOM_CAMERA || object.type == ObjectType.NOZZLE_CHANGER) && object.apertureWidth > 0 && object.apertureDepth > 0) {
                double apertureX = object.x + (object.width - object.apertureWidth) / 2;
                double apertureY = object.y + (object.depth - object.apertureDepth) / 2;
                if (lowX >= apertureX + margin && highX <= apertureX + object.apertureWidth - margin && lowY >= apertureY + margin && highY <= apertureY + object.apertureDepth - margin) {
                    return false;
                }
            }
            // Bounding cylinders/polygons may reject a usable path; it cannot
            // miss a collision at an edge or between sampled positions.
            double objectMinX = object.x;
            double objectMaxX = object.x + object.width;
            double objectMinY = object.y;
            double objectMaxY = object.y + object.depth;
            if (object.shape == Shape.POLYGON && object.polygon != null) {
                for (Point2 point : object.polygon) {
                    objectMinX = Math.min(objectMinX, object.x + point.x);
                    objectMaxX = Math.max(objectMaxX, object.x + point.x);
                    objectMinY = Math.min(objectMinY, object.y + point.y);
                    objectMaxY = Math.max(objectMaxY, object.y + point.y);
                }
            }
            return overlap(lowX, highX, objectMinX - margin, objectMaxX + margin)
                    && overlap(lowY, highY, objectMinY - margin, objectMaxY + margin);
        }
    }
}
