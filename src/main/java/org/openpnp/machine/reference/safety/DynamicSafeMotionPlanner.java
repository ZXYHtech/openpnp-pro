/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceCamCounterClockwiseAxis;
import org.openpnp.machine.reference.axis.ReferenceCamClockwiseAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.corexy.CoreXYAdvancedMotionPlanner;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Locatable.LocationOption;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

/**
 * Plans everything before issuing motion; an execution error must never trigger a retry path.
 */
public final class DynamicSafeMotionPlanner {

    private static final double EPSILON = 0.001;

    private static final AtomicLong ACCEPTED = new AtomicLong();

    private static final AtomicLong FALLBACKS = new AtomicLong();

    private static volatile String lastReason = "等待下一段移动";
    private static volatile Prepared prepared;

    private static final class Prepared {
        final SafetyWorkbenchModel source, frozen;
        final long revision, certifiedRevision;
        final String issue;
        Prepared(SafetyWorkbenchModel source) {
            this.source = source;
            revision = source.getRevision();
            certifiedRevision = source.getCertifiedRevision();
            frozen = SafetyWorkbenchStore.snapshot(source);
            List<String> issues = SafetyModelValidator.validate(frozen);
            issue = issues.isEmpty() ? "" : issues.get(0);
        }
        boolean matches(SafetyWorkbenchModel model) {
            return source == model && revision == model.getRevision()
                    && certifiedRevision == model.getCertifiedRevision();
        }
    }

    private static Prepared prepare(SafetyWorkbenchModel model) {
        Prepared cached = prepared;
        if (cached == null || !cached.matches(model)) {
            cached = new Prepared(model);
            if (!cached.matches(model)) {
                throw new IllegalStateException("模型在复制期间已修改");
            }
            prepared = cached;
        }
        return cached;
    }

    public static final class Plan {

        private final List<Location> locations;

        private final String reason;

        private final double originalSeconds;

        private final double coordinatedSeconds;

        Plan(List<Location> locations, String reason, double originalSeconds, double coordinatedSeconds) {
            this.locations = Collections.unmodifiableList(new ArrayList<>(locations));
            this.reason = reason;
            this.originalSeconds = originalSeconds;
            this.coordinatedSeconds = coordinatedSeconds;
        }

        public boolean isOptimized() {
            return !locations.isEmpty();
        }

        public List<Location> getLocations() {
            return locations;
        }

        public String getReason() {
            return reason;
        }

        public double getOriginalSeconds() {
            return originalSeconds;
        }

        public double getCoordinatedSeconds() {
            return coordinatedSeconds;
        }
    }

    private DynamicSafeMotionPlanner() {
    }

    public static String getRuntimeSummary() {
        return "已联动 " + ACCEPTED.get() + " 段 / 回退 " + FALLBACKS.get() + " 段 · " + lastReason;
    }

    public static String enablementIssue(SafetyWorkbenchModel model, Machine machine) {
        if (model == null || !model.isCertified()) {
            return "请先核对 3D 模型并点击“检查并认证”";
        }
        Prepared cached = prepare(model);
        if (!cached.issue.isEmpty()) {
            return cached.issue;
        }
        int nozzles = 0;
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                nozzles++;
                SafetyWorkbenchModel.ToolProfile profile = model.findToolProfile(head.getId(), nozzle.getId());
                if (profile == null || !profile.enabled) {
                    return "请为“" + nozzle.getName() + "”建立并启用安全包络，再重新认证";
                }
            }
        }
        return nozzles == 0 ? "当前机器没有配置吸嘴" : "";
    }

    public static boolean tryMoveToLocationAtSafeZ(HeadMountable tool, Location destination, double requestedSpeed) throws Exception {
        if (!DynamicSafeMotionSettings.isEnabled()) {
            return false;
        }
        if (ineligible(tool, destination, requestedSpeed)) {
            DynamicMotionDiagnostics.skipped();
            return false;
        }
        if (tool == null || tool.getHead() == null || !tool.getHead().getMachine().isEnabled() || !tool.getHead().getMachine().isHomed()) {
            return fallback("机器尚未启用或回零");
        }
        SafetyWorkbenchModel model = SafetyWorkbenchStore.get();
        long revision = model.getRevision();
        long started = System.nanoTime();
        Plan plan = preview(tool, destination, requestedSpeed, started + 40_000_000L);
        double planningMs = (System.nanoTime() - started) / 1e6;
        if (!plan.isOptimized()) {
            DynamicMotionDiagnostics.record(tool.getName(), plan.reason, false, planningMs,
                    plan.originalSeconds, plan.coordinatedSeconds);
            return fallback(plan.reason);
        }
        if ((plan.originalSeconds - plan.coordinatedSeconds) * 1000 <= planningMs + 5) {
            DynamicMotionDiagnostics.record(tool.getName(), "预计节省不足以抵消规划开销", false, planningMs,
                    plan.originalSeconds, plan.coordinatedSeconds);
            return fallback("预计节省不足以抵消规划开销");
        }
        if (SafetyWorkbenchStore.get() != model || revision != model.getRevision() || !model.isCertified()) {
            DynamicMotionDiagnostics.record(tool.getName(), "模型在规划期间已修改", false, planningMs,
                    plan.originalSeconds, plan.coordinatedSeconds);
            return fallback("模型在规划期间已修改");
        }
        // No catch here: once the first command is issued, a partial failure is
        // propagated to the existing job recovery, never retried with Safe-Z.
        for (Location waypoint : plan.locations) {
            if (SafetyWorkbenchStore.get() != model || revision != model.getRevision() || !model.isCertified()) {
                throw new Exception("3D 模型在联动执行中发生变化，已停止下发后续轨迹。");
            }
            tool.moveTo(waypoint, requestedSpeed, MotionOption.CoordinatedMotion);
        }
        ACCEPTED.incrementAndGet();
        DynamicMotionDiagnostics.record(tool.getName(), plan.reason, true, planningMs,
                plan.originalSeconds, plan.coordinatedSeconds);
        lastReason = "整段扫掠检查通过";
        return true;
    }

    private static boolean fallback(String reason) {
        FALLBACKS.incrementAndGet();
        lastReason = reason;
        return false;
    }

    private static Plan rejected(String reason) {
        return new Plan(Collections.emptyList(), reason, Double.NaN, Double.NaN);
    }

    /**
     * Read-only preview: no homing, motion, actuation, camera capture or settings changes.
     */
    public static Plan preview(HeadMountable tool, Location destination, double speed) {
        return preview(tool, destination, speed, Long.MAX_VALUE);
    }

    private static boolean ineligible(HeadMountable tool, Location destination, double speed) {
        if (tool == null || tool.getClass() != ReferenceNozzle.class || tool.getHead() == null
                || destination == null || !Double.isFinite(speed) || speed <= 0) {
            return true;
        }
        try {
            Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location target = destination.convertToUnits(LengthUnit.Millimeters);
            return !finite(start) || !finite(target)
                    || Math.hypot(target.getX() - start.getX(), target.getY() - start.getY()) < EPSILON;
        }
        catch (Exception unavailable) { return false; } // preview will report the actual failure
    }

    private static Plan preview(HeadMountable tool, Location destination, double speed, long deadline) {
        try {
            if (ineligible(tool, destination, speed)) {
                return rejected("纯 Z、原地旋转、特殊工具或无效坐标保持原路径");
            }
            SafetyWorkbenchModel live = SafetyWorkbenchStore.get();
            String issue = enablementIssue(live, tool.getHead().getMachine());
            if (!issue.isEmpty()) {
                return rejected(issue);
            }
            // The workbench can be edited while the machine thread is planning.
            // Freeze geometry and reject a changed certification before execution.
            SafetyWorkbenchModel model = prepare(live).frozen;
            return plan(tool, destination, speed, model, deadline);
        } catch (Exception planningFailure) {
            Logger.debug(planningFailure, "3D coordinated planning declined before motion.");
            return rejected("规划条件不满足：" + planningFailure.getMessage());
        }
    }

    static Plan plan(HeadMountable tool, Location destination, double speed, SafetyWorkbenchModel model) throws Exception {
        return plan(tool, destination, speed, model, Long.MAX_VALUE);
    }

    private static Plan plan(HeadMountable tool, Location destination, double speed, SafetyWorkbenchModel model, long deadline) throws Exception {
        if (tool == null || tool.getClass() != ReferenceNozzle.class || destination == null || !Double.isFinite(speed) || speed <= 0) {
            return rejected("探针、特殊工具或无效坐标保持原路径");
        }
        Machine machine = tool.getHead().getMachine();
        String hardwareIssue = hardwareIssue(tool, machine);
        if (!hardwareIssue.isEmpty()) {
            return rejected(hardwareIssue);
        }
        Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location target = destination.convertToUnits(LengthUnit.Millimeters);
        if (!finite(start) || !finite(target) || Math.hypot(target.getX() - start.getX(), target.getY() - start.getY()) < EPSILON) {
            return rejected("纯 Z、原地旋转或未指定的坐标保持原路径");
        }
        Length safe = tool.getEffectiveSafeZ();
        if (safe == null) {
            return rejected("吸嘴没有有效安全 Z");
        }
        double effectiveZ = safe.convertToUnits(LengthUnit.Millimeters).getValue();
        double stockExitZ = effectiveZ;
        if (target.getZ() > effectiveZ) {
            Length[] zone = tool.getSafeZZone();
            if (zone != null && zone.length > 1 && zone[1] != null) {
                stockExitZ = Math.min(target.getZ(), zone[1].convertToUnits(LengthUnit.Millimeters).getValue());
            }
        }
        double safeZ = Math.max(stockExitZ, Math.max(start.getZ(), target.getZ()));
        if (!Double.isFinite(safeZ) || !tool.isInSafeZZone(new Length(safeZ, LengthUnit.Millimeters))) {
            return rejected("所需巡航高度不在安全 Z 区间");
        }
        List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(model, machine, tool);
        for (DynamicTool dynamic : tools) {
            if (dynamic.carryingPart && dynamic.carriedPartHeight <= 0) {
                return rejected("吸嘴物料高度未知");
            }
        }
        if (System.nanoTime() > deadline) {
            return rejected("规划超过 40 ms 预算，保持原安全 Z");
        }
        double sourceZ = clearHeight(start, safeZ, start, model, tools, deadline);
        double targetZ = clearHeight(target, safeZ, start, model, tools, deadline);
        if (System.nanoTime() > deadline) {
            return rejected("规划超过 40 ms 预算，保持原安全 Z");
        }
        if (!Double.isFinite(sourceZ) || !Double.isFinite(targetZ)) {
            return rejected("安全高度仍有障碍或其他吸嘴未抬起");
        }
        Length underside = start.getLengthZ().add(tool.getSafeZ()).subtract(safe);
        double stockDepartureZ = tool.isInSafeZZone(start.getLengthZ()) && tool.isInSafeZZone(underside) ? start.getZ() : effectiveZ;
        List<Location> original = compact(tool, start, Arrays.asList(start.derive(null, null, stockDepartureZ, null), target.derive(null, null, stockExitZ, null), target));
        double originalSeconds = estimate(tool, start, original, speed);
        String reason = "没有可缩短耗时的安全联动段";
        for (double fraction : new double[] { 0, 0.4, 0.25, 0.12 }) {
            if (System.nanoTime() > deadline) {
                return rejected("规划超过 40 ms 预算，保持原安全 Z");
            }
            Location departure = start.derive(null, null, sourceZ, null);
            Location ascentEnd = interpolate(start, target, fraction).derive(null, null, safeZ, target.getRotation());
            Location descentStart = interpolate(start, target, 1 - fraction).derive(null, null, safeZ, target.getRotation());
            if (Math.abs(sourceZ - safeZ) < EPSILON) {
                ascentEnd = departure;
            }
            if (Math.abs(targetZ - safeZ) < EPSILON) {
                descentStart = target.derive(null, null, safeZ, null);
            }
            Location approach = target.derive(null, null, targetZ, null);
            List<Location> path = compact(tool, start, fraction == 0
                    ? Arrays.asList(departure, approach, target)
                    : Arrays.asList(departure, ascentEnd, descentStart, approach, target));
            List<Waypoint> points = new ArrayList<>();
            points.add(point(start));
            for (Location location : path) {
                points.add(point(location));
            }
            ValidationResult result = CoordinatedMotionValidator.validate(model, points, tools, point(start), deadline);
            if (!result.isSafe()) {
                reason = result.getIssues().get(0);
                continue;
            }
            double seconds = estimate(tool, start, path, speed);
            if (Double.isFinite(seconds) && seconds + 0.001 < originalSeconds) {
                return new Plan(path, "联动通过，保留末段垂直取放", originalSeconds, seconds);
            }
        }
        return new Plan(Collections.emptyList(), reason, originalSeconds, originalSeconds);
    }

    private static String hardwareIssue(HeadMountable tool, Machine machine) throws Exception {
        if (tool.getAxisX() == null || tool.getAxisY() == null || tool.getAxisZ() == null) {
            return "需要完整的 XYZ 轴映射";
        }
        if (!supportedZ(tool.getAxisZ())) {
            return "此 Z 轴变换尚不能验证，保持原路径";
        }
        List<Driver> drivers = tool.getMappedAxes(machine).getAxesDrivers(machine);
        if (drivers.size() != 1 || (!(drivers.get(0) instanceof GcodeDriver) && !(drivers.get(0) instanceof NullDriver))) {
            return "XYZ/旋转须由同一协调运动控制器驱动";
        }
        Driver driver = drivers.get(0);
        if (driver instanceof GcodeDriver) {
            String command = ((GcodeDriver) driver).getCommand(tool, CommandType.MOVE_TO_COMMAND);
            boolean coordinatedLine = false;
            if (command != null) {
                for (String line : command.split("\\R")) {
                    line = line.split(";", 2)[0];
                    if (!line.matches("(?i).*\\bG0?[01]\\s.*")) {
                        continue;
                    }
                    boolean allAxes = true;
                    for (ControllerAxis axis : tool.getMappedAxes(machine).getControllerAxes()) {
                        String variable = driver.isUsingLetterVariables() ? axis.getLetter() : axis.getType().toString();
                        allAxes &= line.contains("{" + variable + ":") || line.contains("{" + variable + "}");
                    }
                    coordinatedLine |= allAxes;
                }
            }
            if (!coordinatedLine) {
                return "驱动未配置同一行 G0/G1 联动命令，保持原路径";
            }
        }
        if (machine.getMotionPlanner() instanceof CoreXYAdvancedMotionPlanner && ((CoreXYAdvancedMotionPlanner) machine.getMotionPlanner()).isCoreXYCompensationEnabled()) {
            return "CoreXY 附加补偿路径保持原安全 Z（保留精度补偿）";
        }
        for (ControllerAxis axis : tool.getMappedAxes(machine).getControllerAxes()) {
            if (axis instanceof ReferenceControllerAxis && ((ReferenceControllerAxis) axis).getBacklashCompensationMethod() != BacklashCompensationMethod.None && ((ReferenceControllerAxis) axis).getBacklashOffset().getValue() != 0) {
                return "轴回差附加路径保持原安全 Z（保留精度补偿）";
            }
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle other : head.getNozzles()) {
                if (other == tool) {
                    continue;
                }
                if (head == tool.getHead()) {
                    if (other.getAxisX() != tool.getAxisX() || other.getAxisY() != tool.getAxisY()) {
                        return "同头吸嘴的 XY 映射不同，保持原路径";
                    }
                    for (Axis zAxis : tool.getMappedAxes(machine).byType(Axis.Type.Z).getAxes()) {
                        if (other.getAxisZ() != null && other.getMappedAxes(machine).byType(Axis.Type.Z).getAxes().contains(zAxis)) {
                            if (!supportedZ(other.getAxisZ())) {
                                return "此共享 Z 轴变换尚不能验证，保持原路径";
                            }
                        }
                    }
                } else {
                    for (Axis axis : tool.getMappedAxes(machine).getAxes()) {
                        if (other.getMappedAxes(machine).getAxes().contains(axis)) {
                            return "不同贴装头共享运动轴，保持原路径";
                        }
                    }
                }
            }
        }
        return "";
    }

    private static boolean supportedZ(Axis axis) {
        if (axis instanceof ReferenceControllerAxis) {
            return true;
        }
        ReferenceCamCounterClockwiseAxis cam = axis instanceof ReferenceCamCounterClockwiseAxis
                ? (ReferenceCamCounterClockwiseAxis) axis
                : axis instanceof ReferenceCamClockwiseAxis ? ((ReferenceCamClockwiseAxis) axis).getCounterClockwiseAxis() : null;
        return cam != null && cam.getInputAxis() instanceof ReferenceControllerAxis
                && cam.getCamRadius().getValue() > 0 && Double.isFinite(cam.getCamRadius().getValue())
                && Double.isFinite(cam.getCamArmsAngle()) && cam.getCamArmsAngle() >= 0 && cam.getCamArmsAngle() <= 180;
    }

    private static double clearHeight(Location endpoint, double safeZ, Location start, SafetyWorkbenchModel model, List<DynamicTool> tools, long deadline) {
        double low = endpoint.getZ();
        if (clearAt(endpoint, start, model, tools, deadline)) {
            // Keep a short vertical final approach even on an unobstructed table.
            return Math.min(safeZ, low + 1);
        }
        if (!clearAt(endpoint.derive(null, null, safeZ, null), start, model, tools, deadline)) {
            return Double.NaN;
        }
        double high = safeZ;
        for (int index = 0; index < 12; index++) {
            double middle = (low + high) / 2;
            if (System.nanoTime() > deadline) {
                return Double.NaN;
            }
            if (clearAt(endpoint.derive(null, null, middle, null), start, model, tools, deadline)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return Math.min(safeZ, high + 0.5);
    }

    private static boolean clearAt(Location location, Location start, SafetyWorkbenchModel model, List<DynamicTool> tools, long deadline) {
        return CoordinatedMotionValidator.validate(model, Collections.singletonList(point(location)), tools, point(start), deadline).isSafe();
    }

    private static double estimate(HeadMountable tool, Location start, List<Location> path, double speed) throws Exception {
        double seconds = 0;
        AxesLocation previous = raw(tool, start);
        for (Location location : path) {
            AxesLocation next = raw(tool, location);
            Location restored = tool.toHeadMountableLocation(tool.toTransformed(new AxesLocation(tool.getHead().getMachine()).put(next),
                    LocationOption.Quiet), LocationOption.Quiet).convertToUnits(LengthUnit.Millimeters);
            if (restored.getXyzDistanceTo(location) > 0.01) {
                throw new Exception("轴变换无法准确到达联动航点");
            }
            if (!tool.getHead().getMachine().getMotionPlanner().isValidLocation(tool, next)) {
                throw new Exception("联动航点超出软限位");
            }
            seconds += new Motion(tool, previous, next, speed * tool.getHead().getMaxPartSpeed(), MotionOption.CoordinatedMotion).getTime();
            previous = next;
        }
        return seconds;
    }

    private static AxesLocation raw(HeadMountable tool, Location location) throws Exception {
        return tool.toRaw(tool.toHeadLocation(location, LocationOption.Quiet), LocationOption.Quiet);
    }

    private static List<Location> compact(HeadMountable tool, Location start, List<Location> locations) throws Exception {
        List<Location> result = new ArrayList<>();
        Location previous = start;
        for (Location location : locations) {
            // Cam-axis plateau coordinates may differ by only micrometres yet
            // represent a large motor/sibling movement. Compare raw axes.
            if (!raw(tool, previous).matches(raw(tool, location))) {
                result.add(location);
                previous = location;
            }
        }
        return result;
    }

    private static Location interpolate(Location a, Location b, double fraction) {
        return new Location(LengthUnit.Millimeters, a.getX() + (b.getX() - a.getX()) * fraction, a.getY() + (b.getY() - a.getY()) * fraction, a.getZ() + (b.getZ() - a.getZ()) * fraction, b.getRotation());
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ()) && Double.isFinite(location.getRotation());
    }

    private static Waypoint point(Location location) {
        return new Waypoint(location.getX(), location.getY(), location.getZ());
    }
}
