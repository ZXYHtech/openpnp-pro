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

    static final class FeederContact {
        final String sourceKey;
        final Waypoint pick;
        final boolean board;
        FeederContact(String sourceKey, Waypoint pick) {
            this(sourceKey, pick, false);
        }
        FeederContact(String sourceKey, Waypoint pick, boolean board) {
            this.sourceKey = sourceKey;
            this.pick = pick;
            this.board = board;
        }
        boolean permits(SafetyObject object, Waypoint point) {
            return (board ? object.type == ObjectType.BOARD && sourceKey.equals(object.id)
                    : object.type == ObjectType.FEEDER && (sourceKey.equals(object.sourceKey)
                            || sourceKey.startsWith("feeder:") && object.sourceKey.startsWith("feeder-bank:")
                            && pick.x >= object.x && pick.x <= object.x + object.width
                            && pick.y >= object.y && pick.y <= object.y + object.depth))
                    && Math.abs(point.x - pick.x) < .05 && Math.abs(point.y - pick.y) < .05
                    && Math.abs(point.z - pick.z) < .05;
        }
    }

    static ValidationResult validate(SafetyWorkbenchModel model, List<Waypoint> path, List<DynamicTool> tools, Waypoint anchor) {
        return validate(model, path, tools, anchor, Long.MAX_VALUE);
    }

    static ValidationResult validate(SafetyWorkbenchModel model, List<Waypoint> path, List<DynamicTool> tools, Waypoint anchor, long deadline) {
        return validate(model, path, tools, anchor, deadline, null);
    }

    static ValidationResult validate(SafetyWorkbenchModel model, List<Waypoint> path, List<DynamicTool> tools, Waypoint anchor, long deadline, FeederContact contact) {
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
            // A cam envelope uses the full segment Z range at every XY interval.
            // Resolve those identical axis transforms once per segment, not once per sample.
            List<Waypoint> segmentStarts = new ArrayList<>();
            List<Waypoint> segmentEnds = new ArrayList<>();
            List<java.util.function.DoubleUnaryOperator> segmentZ = new ArrayList<>();
            for (DynamicTool tool : tools) {
                segmentZ.add(tool.segmentZ == null ? null : tool.segmentZ.apply(first.z, last.z));
                segmentStarts.add(tool.conservativeSegmentZ && tool.segmentZ == null ? tool.at(anchor, first) : null);
                segmentEnds.add(tool.conservativeSegmentZ && tool.segmentZ == null ? tool.at(anchor, last) : null);
            }
            if (System.nanoTime() > deadline) {
                return new ValidationResult(Collections.singletonList("联动规划超时，尚未下发运动"), samples);
            }
            // A clear bounding volume proves the whole segment safe. This is
            // stricter than all fine intervals, so only a PASS can short-circuit.
            // Ambiguous volumes still receive the original 0.5 mm sweep below.
            if (distance > 2 && tools.stream().allMatch(t -> t.mappedZ == null || t.segmentZ != null)) {
                List<Waypoint> before = new ArrayList<>();
                List<Waypoint> after = new ArrayList<>();
                for (int i = 0; i < tools.size(); i++) {
                    DynamicTool tool = tools.get(i);
                    Waypoint a = tool.at(anchor, first);
                    Waypoint b = tool.at(anchor, last);
                    before.add(segmentZ.get(i) == null ? a : new Waypoint(a.x, a.y, segmentZ.get(i).applyAsDouble(0)));
                    after.add(segmentZ.get(i) == null ? b : new Waypoint(b.x, b.y, segmentZ.get(i).applyAsDouble(1)));
                }
                if (validateToolInterval(model, tools, before, after).isSafe()) {
                    samples.add(last);
                    continue;
                }
            }
            for (int index = 1; index < fractions.size(); index++) {
                if ((index & 31) == 1 && System.nanoTime() > deadline) {
                    return new ValidationResult(Collections.singletonList("联动规划超时，尚未下发运动"), samples);
                }
                Waypoint a = Waypoint.interpolate(first, last, fractions.get(index - 1));
                Waypoint b = Waypoint.interpolate(first, last, fractions.get(index));
                samples.add(b);
                List<Envelope> envelopes = new ArrayList<>();
                for (int toolIndex = 0; toolIndex < tools.size(); toolIndex++) {
                    DynamicTool tool = tools.get(toolIndex);
                    Waypoint poseA;
                    Waypoint poseB;
                    if (segmentZ.get(toolIndex) != null) {
                        // Supported cam transforms are monotone in controller
                        // position. Endpoint Z bounds cover this entire interval;
                        // Envelope also includes tip-extension breakpoints.
                        double za = segmentZ.get(toolIndex).applyAsDouble(fractions.get(index - 1));
                        double zb = segmentZ.get(toolIndex).applyAsDouble(fractions.get(index));
                        if (!Double.isFinite(za) || !Double.isFinite(zb)) {
                            return new ValidationResult(Collections.singletonList("凸轮联动坐标无效"), samples);
                        }
                        poseA = new Waypoint(tool.reference.x + (tool.followsX ? a.x - anchor.x : 0),
                                tool.reference.y + (tool.followsY ? a.y - anchor.y : 0), za);
                        poseB = new Waypoint(tool.reference.x + (tool.followsX ? b.x - anchor.x : 0),
                                tool.reference.y + (tool.followsY ? b.y - anchor.y : 0), zb);
                    }
                    else if (tool.conservativeSegmentZ) {
                        // Cam Z is monotone in raw motor position, but nonlinear
                        // in time. Cover the complete segment Z range at every XY
                        // interval instead of assuming linear physical Z motion.
                        poseA = new Waypoint(tool.reference.x + (tool.followsX ? a.x - anchor.x : 0),
                                tool.reference.y + (tool.followsY ? a.y - anchor.y : 0), segmentStarts.get(toolIndex).z);
                        poseB = new Waypoint(tool.reference.x + (tool.followsX ? b.x - anchor.x : 0),
                                tool.reference.y + (tool.followsY ? b.y - anchor.y : 0), segmentEnds.get(toolIndex).z);
                    }
                    else {
                        poseA = tool.at(anchor, a);
                        poseB = tool.at(anchor, b);
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
                        if (envelope.intersects(object) && !terminalContact(envelope, object, first, last, path.get(0), path.get(path.size() - 1), segment == 1, segment == path.size() - 1, contact)) {
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

    private static boolean terminalContact(Envelope envelope, SafetyObject object, Waypoint first, Waypoint last, Waypoint source, Waypoint target, boolean firstSegment, boolean lastSegment, FeederContact contact) {
        // Only the selected tip may leave/approach a feeder or board, vertically.
        // Never waive contact during a diagonal or for a sibling nozzle.
        if (!envelope.tool.selectedTool || (object.type != ObjectType.BOARD && object.type != ObjectType.FEEDER && object.type != ObjectType.LOOSE_PARTS_FEEDER) || Math.abs(first.x - last.x) > EPSILON || Math.abs(first.y - last.y) > EPSILON || envelope.bodyMinZ <= object.z + object.height + object.clearance) {
            return false;
        }
        if (firstSegment && last.z > first.z && sameXy(first, source) && envelope.tool.minimumZAt(envelope.b.z) >= envelope.tool.minimumZAt(envelope.a.z)
                && (source.z >= object.z + object.height - EPSILON || contact != null && contact.permits(object, source))) {
            return true;
        }
        return lastSegment && last.z < first.z && sameXy(last, target) && envelope.tool.minimumZAt(envelope.b.z) <= envelope.tool.minimumZAt(envelope.a.z)
                && (target.z >= object.z + object.height - EPSILON || contact != null && contact.permits(object, target));
    }

    /** Each interval covers all XYZ combinations, not only a straight line between samples. */
    static ValidationResult validateToolInterval(SafetyWorkbenchModel model, List<DynamicTool> tools,
            List<Waypoint> before, List<Waypoint> after) {
        Set<String> issues = new LinkedHashSet<>();
        List<Envelope> envelopes = new ArrayList<>();
        if (tools.isEmpty() || tools.size() != before.size() || tools.size() != after.size())
            { return new ValidationResult(Collections.singletonList("缺少完整工具轨迹"), before); }
        for (int i = 0; i < tools.size(); i++) {
            Waypoint a = before.get(i), b = after.get(i);
            if (!Double.isFinite(a.x + a.y + a.z + b.x + b.y + b.z))
                { return new ValidationResult(Collections.singletonList("工具轨迹坐标无效"), before); }
            Envelope envelope = new Envelope(tools.get(i), a, b);
            envelopes.add(envelope);
            if (!envelope.tool.isMoving()) { continue; }
            if (!envelope.inBounds(model)) { issues.add(envelope.tool.name + "超出安全范围"); }
            for (SafetyObject object : model.getObjects())
                { if (object != null && object.enabled && object.obstacle && envelope.intersects(object))
                    { issues.add(envelope.tool.name + "的连续飞拍包络进入" + object.name); } }
        }
        for (int i = 0; i < envelopes.size(); i++) { for (int j = i + 1; j < envelopes.size(); j++) {
            Envelope a = envelopes.get(i), b = envelopes.get(j);
            if (a.tool.headId.equals(b.tool.headId)) {
                if (a.partIntersects(b) || b.partIntersects(a)) { issues.add("同头器件包络重叠"); }
            } else if ((a.tool.isMoving() || b.tool.isMoving()) && a.intersects(b)) {
                issues.add("贴装头间包络重叠");
            }
        } }
        return new ValidationResult(new ArrayList<>(issues), after);
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
            if (model.isXyBoundsAreTravelLimits()) {
                // Axis travel limits are not physical walls. Sibling offsets and
                // the housing may overhang the travel rectangle. Real obstacles
                // still use the entire envelope, and native raw-axis soft limits
                // are checked before issuing each planned motion.
                return (!tool.selectedTool || (Math.min(a.x, b.x) >= model.getMinX()
                        && Math.max(a.x, b.x) <= model.getMaxX()
                        && Math.min(a.y, b.y) >= model.getMinY()
                        && Math.max(a.y, b.y) <= model.getMaxY()))
                        && minZ >= model.getMinZ() && maxZ <= model.getMaxZ();
            }
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
