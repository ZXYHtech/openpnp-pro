/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;
import org.openpnp.model.Footprint;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Locatable.LocationOption;
import org.openpnp.machine.reference.axis.ReferenceCamCounterClockwiseAxis;
import org.openpnp.machine.reference.axis.ReferenceCamClockwiseAxis;

/**
 * Swept-volume checker for a complete multi-head machine. XY motion is shared
 * by all nozzles on the selected head, while Z follows only the selected
 * nozzle. Other heads remain stationary and are treated as collision objects.
 */
public final class DynamicSafetyCollisionEngine {
    public static final class DynamicTool {
        public final String headId;
        public final String toolId;
        public final String name;
        public final Waypoint reference;
        public final double radiusX;
        public final double radiusY;
        public final double lowerRadiusX;
        public final double lowerRadiusY;
        public final double minZOffset;
        public final double maxZOffset;
        public final double shaftRadius;
        public final double tipLength;
        public final int colorRgb;
        public final boolean followsX;
        public final boolean followsY;
        public final boolean followsZ;
        public final boolean carryingPart;
        public final boolean bodyFollowsNozzleZ;
        public final double bodyMinZOffset;
        public final double bodyMaxZOffset;
        public final double clearance;
        public final double retractedZ;
        public final double extendedZ;
        public final double retractedTipLength;
        public final double extendedTipLength;
        public final double carriedPartHeight;
        boolean selectedTool;
        boolean conservativeSegmentZ;
        public boolean zIsNozzleTip;
        java.util.function.BiFunction<Double, Double, DoubleUnaryOperator> segmentZ;
        DoubleUnaryOperator mappedZ;

        DynamicTool(String headId, String toolId, String name, Waypoint reference,
                double radiusX, double radiusY, double minZOffset, double maxZOffset,
                double shaftRadius, double tipLength, int colorRgb,
                boolean followsX, boolean followsY, boolean followsZ,
                boolean carryingPart) {
            this.headId = headId;
            this.toolId = toolId;
            this.name = name;
            this.reference = reference;
            this.radiusX = radiusX;
            this.radiusY = radiusY;
            lowerRadiusX = shaftRadius;
            lowerRadiusY = shaftRadius;
            this.minZOffset = minZOffset;
            this.maxZOffset = maxZOffset;
            this.shaftRadius = shaftRadius;
            this.tipLength = tipLength;
            this.colorRgb = colorRgb;
            this.followsX = followsX;
            this.followsY = followsY;
            this.followsZ = followsZ;
            selectedTool = followsZ;
            this.carryingPart = carryingPart;
            bodyFollowsNozzleZ = true;
            bodyMinZOffset = minZOffset;
            bodyMaxZOffset = maxZOffset;
            clearance = 0;
            retractedZ = reference.z;
            extendedZ = reference.z;
            retractedTipLength = tipLength;
            extendedTipLength = tipLength;
            carriedPartHeight = 0;
        }

        DynamicTool(String headId, String toolId, String name, Waypoint reference,
                ToolProfile profile, double carriedPartRadiusX,
                double carriedPartRadiusY, double carriedPartHeight,
                boolean followsX, boolean followsY, boolean followsZ,
                boolean carryingPart) {
            this.headId = headId;
            this.toolId = toolId;
            this.name = name;
            this.reference = reference;
            // Include the corners, not just the longer side of a rotating part.
            double sweptPartRadius = Math.hypot(carriedPartRadiusX, carriedPartRadiusY);
            radiusX = profile.getRadiusX(sweptPartRadius);
            radiusY = profile.getRadiusY(sweptPartRadius);
            lowerRadiusX = Math.max(profile.shaftRadius, sweptPartRadius)
                    + profile.clearance;
            lowerRadiusY = Math.max(profile.shaftRadius, sweptPartRadius)
                    + profile.clearance;
            minZOffset = profile.getMinimumZOffset(reference.z, carriedPartHeight);
            maxZOffset = profile.getMaximumZOffset();
            shaftRadius = profile.shaftRadius + profile.clearance;
            tipLength = profile.getTipLength(reference.z) + carriedPartHeight;
            colorRgb = profile.colorRgb;
            this.followsX = followsX;
            this.followsY = followsY;
            this.followsZ = followsZ;
            selectedTool = followsZ;
            this.carryingPart = carryingPart;
            bodyFollowsNozzleZ = profile.bodyFollowsNozzleZ;
            zIsNozzleTip = profile.zIsNozzleTip;
            bodyMinZOffset = profile.bodyMinZOffset;
            bodyMaxZOffset = profile.bodyMaxZOffset;
            clearance = profile.clearance;
            retractedZ = profile.retractedZ;
            extendedZ = profile.extendedZ;
            retractedTipLength = profile.retractedTipLength;
            extendedTipLength = profile.extendedTipLength;
            this.carriedPartHeight = Math.max(0, carriedPartHeight);
        }

        public boolean isMoving() {
            return followsX || followsY || followsZ;
        }

        public Waypoint at(Waypoint selectedStart, Waypoint selectedPosition) {
            return new Waypoint(
                    reference.x + (followsX
                            ? selectedPosition.x - selectedStart.x : 0),
                    reference.y + (followsY
                            ? selectedPosition.y - selectedStart.y : 0),
                    mappedZ != null ? mappedZ.applyAsDouble(selectedPosition.z)
                            : reference.z + (followsZ ? selectedPosition.z - selectedStart.z : 0));
        }

        public double tipLengthAt(double nozzleZ) {
            if (zIsNozzleTip) {
                // getLocation() already includes nozzle-tip calibration. Only the
                // held component extends below that point; do not subtract a tip twice.
                return carriedPartHeight;
            }
            double denominator = extendedZ - retractedZ;
            double length;
            if (Math.abs(denominator) < 0.000001) {
                length = Math.max(retractedTipLength, extendedTipLength);
            }
            else {
                double fraction = (nozzleZ - retractedZ) / denominator;
                fraction = Math.max(0, Math.min(1, fraction));
                length = retractedTipLength
                        + (extendedTipLength - retractedTipLength) * fraction;
            }
            return length + carriedPartHeight;
        }

        public double bodyMinimumZAt(double nozzleZ) {
            double bodyReferenceZ = bodyFollowsNozzleZ ? nozzleZ : retractedZ;
            return bodyReferenceZ + bodyMinZOffset - clearance;
        }

        public double bodyMaximumZAt(double nozzleZ) {
            double bodyReferenceZ = bodyFollowsNozzleZ ? nozzleZ : retractedZ;
            return bodyReferenceZ + bodyMaxZOffset + clearance;
        }

        public double minimumZAt(double nozzleZ) {
            return Math.min(bodyMinimumZAt(nozzleZ),
                    nozzleZ - tipLengthAt(nozzleZ) - clearance);
        }

        public double maximumZAt(double nozzleZ) {
            return Math.max(bodyMaximumZAt(nozzleZ), nozzleZ + clearance);
        }
        public double radiusXAt(double minimumZ, double maximumZ, double nozzleZ) {
            return maximumZ < bodyMinimumZAt(nozzleZ) ? lowerRadiusX : radiusX;
        }

        public double radiusYAt(double minimumZ, double maximumZ, double nozzleZ) {
            return maximumZ < bodyMinimumZAt(nozzleZ) ? lowerRadiusY : radiusY;
        }
    }
    private DynamicSafetyCollisionEngine() {
    }

    public static List<DynamicTool> snapshot(SafetyWorkbenchModel model,
            Machine machine, HeadMountable selectedTool) throws Exception {
        if (selectedTool == null || selectedTool.getHead() == null) {
            return Collections.emptyList();
        }
        List<DynamicTool> tools = new ArrayList<>();
        Head selectedHead = selectedTool.getHead();
        Location selectedOrigin = selectedTool.getLocation().convertToUnits(LengthUnit.Millimeters);
        AxesLocation initialAxes = new AxesLocation(machine);
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                ToolProfile profile = model.findToolProfile(head.getId(), nozzle.getId());
                if (profile == null || !profile.enabled) {
                    throw new Exception("贴装头“" + head.getName() + "”的吸嘴“"
                            + nozzle.getName() + "”没有启用的 3D 安全包络。");
                }
                Location location = nozzle.getLocation()
                        .convertToUnits(LengthUnit.Millimeters);
                Part part = nozzle.getPart();
                double[] partSize = partSize(part);
                if (part != null && (!(partSize[0] > 0) || !(partSize[1] > 0)
                        || !Double.isFinite(partSize[0]) || !Double.isFinite(partSize[1]))) {
                    throw new Exception("吸嘴“" + nozzle.getName() + "”的物料“" + part.getId()
                            + "”外形尺寸未知；请在封装库填写本体宽/长（Body Width/Height），不是焊盘尺寸");
                }
                double partHeight = 0;
                if (part != null) {
                    // Use the same configured height upper bound as stock Safe-Z
                    // when the part height is unknown. Add through-board depth,
                    // which may hang below that bound. Never guess body XY size.
                    double through = part.getThroughBoardDepth().convertToUnits(LengthUnit.Millimeters).getValue();
                    Length heightBound = part.isPartHeightUnknown()
                            ? (nozzle.getNozzleTip() == null ? null : nozzle.getNozzleTip().getMaxPartHeight()) : part.getHeight();
                    double bodyHeight = heightBound == null ? Double.NaN
                            : heightBound.convertToUnits(LengthUnit.Millimeters).getValue();
                    partHeight = bodyHeight + through;
                    if (!Double.isFinite(bodyHeight) || bodyHeight <= 0 || !Double.isFinite(partHeight)
                            || !Double.isFinite(through) || through < 0) {
                        throw new Exception("吸嘴“" + nozzle.getName() + "”的物料“" + part.getId()
                                + "”安全高度无效；请填写物料高度，或配置吸嘴尖最大物料高度作为保守上界，并检查穿板深度");
                    }
                }
                boolean sameHead = head == selectedHead;
                boolean sharedZ = sameHead && selectedTool.getMappedAxes(machine).byType(Axis.Type.Z).getAxes().stream()
                        .anyMatch(axis -> nozzle.getMappedAxes(machine).byType(Axis.Type.Z).getAxes().contains(axis));
                DynamicTool dynamic = new DynamicTool(head.getId(), nozzle.getId(),
                        head.getName() + " · " + nozzle.getName(),
                        new Waypoint(location.getX(), location.getY(), location.getZ()),
                        profile, partSize[0] / 2.0, partSize[1] / 2.0,
                        partHeight, sameHead, sameHead, nozzle == selectedTool || sharedZ,
                        part != null);
                dynamic.selectedTool = nozzle == selectedTool;
                boolean cam = selectedTool.getAxisZ() instanceof ReferenceCamCounterClockwiseAxis
                        || selectedTool.getAxisZ() instanceof ReferenceCamClockwiseAxis;
                dynamic.conservativeSegmentZ = sameHead && cam;
                if (sameHead && cam && (nozzle == selectedTool || sharedZ)) {
                    // Coordinated G1 interpolates controller coordinates, not
                    // the sinusoidal nozzle Z. Freeze endpoint transforms once
                    // per segment and resolve both nozzles from that same motor.
                    dynamic.segmentZ = (from, to) -> {
                        try {
                            AxesLocation rawFrom = selectedTool.toRaw(selectedTool.toHeadLocation(
                                    selectedOrigin.derive(null, null, from, null), LocationOption.Quiet),
                                    LocationOption.Quiet).byType(Axis.Type.Z);
                            AxesLocation rawTo = selectedTool.toRaw(selectedTool.toHeadLocation(
                                    selectedOrigin.derive(null, null, to, null), LocationOption.Quiet),
                                    LocationOption.Quiet).byType(Axis.Type.Z);
                            AxesLocation delta = rawTo.subtract(rawFrom);
                            return fraction -> {
                                try {
                                    AxesLocation interpolated = initialAxes.put(rawFrom.add(delta.multiply(fraction)));
                                    return nozzle.toHeadMountableLocation(nozzle.toTransformed(interpolated,
                                            LocationOption.Quiet), LocationOption.Quiet)
                                            .convertToUnits(LengthUnit.Millimeters).getZ();
                                } catch (Exception failure) {
                                    throw new IllegalStateException("无法验证凸轮联动轨迹", failure);
                                }
                            };
                        } catch (Exception failure) {
                            throw new IllegalStateException("无法建立凸轮联动轨迹", failure);
                        }
                    };
                }
                if (sharedZ && nozzle != selectedTool) {
                    dynamic.mappedZ = selectedZ -> {
                        try {
                            Location selected = selectedOrigin.derive(null, null, selectedZ, null);
                            AxesLocation axes = selectedTool.toRaw(selectedTool.toHeadLocation(selected, LocationOption.Quiet), LocationOption.Quiet);
                            return nozzle.toHeadMountableLocation(nozzle.toTransformed(initialAxes.put(axes), LocationOption.Quiet),
                                    LocationOption.Quiet).convertToUnits(LengthUnit.Millimeters).getZ();
                        }
                        catch (Exception failure) {
                            throw new IllegalStateException("无法验证共用 Z 轴的吸嘴位置", failure);
                        }
                    };
                }
                tools.add(dynamic);
            }
        }
        return tools;
    }

    public static ValidationResult validate(SafetyWorkbenchModel model,
            List<Waypoint> selectedPath, List<DynamicTool> tools) {
        Set<String> issues = new LinkedHashSet<>();
        List<Waypoint> samples = sample(selectedPath,
                Math.max(0.1, model.getSampleStep()));
        if (samples.isEmpty()) {
            issues.add("轨迹至少需要一个有效点。");
            return new ValidationResult(new ArrayList<>(issues), samples);
        }
        if (tools == null || tools.isEmpty()) {
            issues.add("没有可用于碰撞检查的贴装头/吸嘴安全包络。");
            return new ValidationResult(new ArrayList<>(issues), samples);
        }
        Waypoint selectedStart = samples.get(0);
        for (Waypoint selectedPosition : samples) {
            List<ToolPose> poses = new ArrayList<>();
            for (DynamicTool tool : tools) {
                ToolPose pose = new ToolPose(tool,
                        tool.at(selectedStart, selectedPosition));
                poses.add(pose);
                if (!tool.isMoving()) {
                    continue;
                }
                checkBounds(model, pose, issues);
                for (SafetyObject object : model.getObjects()) {
                    if (object != null && object.enabled && object.obstacle
                            && intersects(pose, object)) {
                        issues.add("“" + tool.name + "”进入“" + object.name
                                + "”的安全包络。");
                    }
                }
            }
            for (int a = 0; a < poses.size(); a++) {
                for (int b = a + 1; b < poses.size(); b++) {
                    ToolPose first = poses.get(a);
                    ToolPose second = poses.get(b);
                    if (first.tool.headId.equals(second.tool.headId)
                            || (!first.tool.isMoving() && !second.tool.isMoving())) {
                        continue;
                    }
                    if (intersects(first, second)) {
                        issues.add("贴装头间碰撞风险：“" + first.tool.name + "”与“"
                                + second.tool.name + "”的安全包络相交。");
                    }
                }
            }
        }
        return new ValidationResult(new ArrayList<>(issues), samples);
    }

    /**
     * Validation for a pick/place transit. Contact already present at the source
     * may be left and contact at the destination may be approached, but a tool
     * may not enter an obstacle in the middle of the route. This distinguishes
     * an intentional feeder/board endpoint from crossing the same named object
     * during an accelerated diagonal move.
     */
    public static ValidationResult validateTransit(SafetyWorkbenchModel model,
            List<Waypoint> selectedPath, List<DynamicTool> tools) {
        Waypoint anchor = selectedPath == null || selectedPath.isEmpty() ? null : selectedPath.get(0);
        return CoordinatedMotionValidator.validate(model, selectedPath, tools, anchor);
    }

    /** Validate monotone profile intervals using each nozzle's real controller-axis transform. */
    public static ValidationResult validateRawTransit(SafetyWorkbenchModel model, Machine machine,
            HeadMountable selected, List<AxesLocation> samples) throws Exception {
        List<DynamicTool> tools = snapshot(model, machine, selected);
        if (samples.size() < 2 || samples.size() > 20000)
            { throw new Exception("连续飞拍轨迹采样数量无效"); }
        List<Waypoint> previous = null;
        ValidationResult result = null;
        for (AxesLocation raw : samples) {
            List<Waypoint> current = new ArrayList<>();
            for (DynamicTool tool : tools) {
                Nozzle nozzle = machine.getHead(tool.headId).getNozzle(tool.toolId);
                Location pose = nozzle.toHeadMountableLocation(nozzle.toTransformed(raw, LocationOption.Quiet),
                        LocationOption.Quiet).convertToUnits(LengthUnit.Millimeters);
                current.add(new Waypoint(pose.getX(), pose.getY(), pose.getZ()));
            }
            if (previous != null) {
                result = CoordinatedMotionValidator.validateToolInterval(model, tools, previous, current);
                if (!result.isSafe()) { return result; }
            }
            previous = current;
        }
        return result;
    }

    private static double[] partSize(Part part) {
        if (part == null || part.getPackage() == null
                || part.getPackage().getFootprint() == null) {
            return new double[] { 0, 0 };
        }
        Footprint footprint = part.getPackage().getFootprint();
        Length width = new Length(footprint.getBodyWidth(), footprint.getUnits());
        Length depth = new Length(footprint.getBodyHeight(), footprint.getUnits());
        return new double[] {
                Math.abs(width.convertToUnits(LengthUnit.Millimeters).getValue()),
                Math.abs(depth.convertToUnits(LengthUnit.Millimeters).getValue())
        };
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

    private static void checkBounds(SafetyWorkbenchModel model, ToolPose pose,
            Set<String> issues) {
        if (pose.minX() < model.getMinX() || pose.maxX() > model.getMaxX()) {
            issues.add("“" + pose.tool.name + "”超出工作台 X 轴安全范围。");
        }
        if (pose.minY() < model.getMinY() || pose.maxY() > model.getMaxY()) {
            issues.add("“" + pose.tool.name + "”超出工作台 Y 轴安全范围。");
        }
        if (pose.minZ() < model.getMinZ() || pose.maxZ() > model.getMaxZ()) {
            issues.add("“" + pose.tool.name + "”超出工作台 Z 轴安全范围。");
        }
    }

    private static boolean intersects(ToolPose first, ToolPose second) {
        return overlaps(first.minX(), first.maxX(), second.minX(), second.maxX())
                && overlaps(first.minY(), first.maxY(), second.minY(), second.maxY())
                && overlaps(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
    }

    private static boolean intersects(ToolPose tool, SafetyObject object) {
        double clearance = Math.max(0, object.clearance);
        double objectMinX = object.x - clearance;
        double objectMaxX = object.x + Math.max(0, object.width) + clearance;
        double objectMinY = object.y - clearance;
        double objectMaxY = object.y + Math.max(0, object.depth) + clearance;
        double objectMinZ = object.z - clearance;
        double objectMaxZ = object.z + Math.max(0, object.height) + clearance;
        if (!overlaps(tool.minZ(), tool.maxZ(), objectMinZ, objectMaxZ)) {
            return false;
        }
        double crossRadiusX = tool.tool.radiusXAt(
                objectMinZ, objectMaxZ, tool.point.z);
        double crossRadiusY = tool.tool.radiusYAt(
                objectMinZ, objectMaxZ, tool.point.z);
        double toolMinX = tool.point.x - crossRadiusX;
        double toolMaxX = tool.point.x + crossRadiusX;
        double toolMinY = tool.point.y - crossRadiusY;
        double toolMaxY = tool.point.y + crossRadiusY;

        if ((object.type == SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA
                || object.type == SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER)
                && object.apertureWidth > 0 && object.apertureDepth > 0) {
            double apertureMinX = object.x + (object.width - object.apertureWidth) / 2.0;
            double apertureMaxX = apertureMinX + object.apertureWidth;
            double apertureMinY = object.y + (object.depth - object.apertureDepth) / 2.0;
            double apertureMaxY = apertureMinY + object.apertureDepth;
            if (toolMinX >= apertureMinX + clearance
                    && toolMaxX <= apertureMaxX - clearance
                    && toolMinY >= apertureMinY + clearance
                    && toolMaxY <= apertureMaxY - clearance) {
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
            double nx = radiusX == 0 ? 0 : (closestX - centerX) / radiusX;
            double ny = radiusY == 0 ? 0 : (closestY - centerY) / radiusY;
            return nx * nx + ny * ny <= 1.0;
        }
        if (object.shape == Shape.POLYGON && object.polygon != null
                && object.polygon.size() >= 3) {
            return polygonIntersectsRectangle(object, toolMinX - clearance,
                    toolMaxX + clearance, toolMinY - clearance,
                    toolMaxY + clearance);
        }
        return overlaps(toolMinX, toolMaxX, objectMinX, objectMaxX)
                && overlaps(toolMinY, toolMaxY, objectMinY, objectMaxY);
    }
    private static boolean polygonIntersectsRectangle(SafetyObject object,
            double minX, double maxX, double minY, double maxY) {
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
        return false;
    }

    private static boolean pointInPolygon(SafetyObject object, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = object.polygon.size() - 1;
                i < object.polygon.size(); j = i++) {
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

    private static boolean overlaps(double minA, double maxA,
            double minB, double maxB) {
        return maxA >= minB && maxB >= minA;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ToolPose {
        private final DynamicTool tool;
        private final Waypoint point;

        ToolPose(DynamicTool tool, Waypoint point) {
            this.tool = tool;
            this.point = point;
        }

        double minX() {
            return point.x - tool.radiusX;
        }

        double maxX() {
            return point.x + tool.radiusX;
        }

        double minY() {
            return point.y - tool.radiusY;
        }

        double maxY() {
            return point.y + tool.radiusY;
        }

        double minZ() {
            return tool.minimumZAt(point.z);
        }

        double maxZ() {
            return tool.maximumZAt(point.z);
        }
    }
}
