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
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
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
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Locatable.LocationOption;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.machine.reference.feeder.ReferenceSlotAutoFeeder;
import org.pmw.tinylog.Logger;

/**
 * Plans everything before issuing motion; an execution error must never trigger a retry path.
 */
public final class DynamicSafeMotionPlanner {

    private static final double EPSILON = 0.001;

    private static final AtomicLong ACCEPTED = new AtomicLong();

    private static final AtomicLong FALLBACKS = new AtomicLong();

    private static final ThreadLocal<Integer> NOZZLE_TIP_CHANGE_DEPTH = new ThreadLocal<>();

    /** Keeps the complete changer operation, including nested hooks, on its taught motion sequence. */
    public static final class NozzleTipChangeScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private NozzleTipChangeScope() {
            Integer depth = NOZZLE_TIP_CHANGE_DEPTH.get();
            NOZZLE_TIP_CHANGE_DEPTH.set(depth == null ? 1 : depth + 1);
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Nozzle-tip change scope must close on its owning thread");
            }
            if (!closed) {
                int depth = NOZZLE_TIP_CHANGE_DEPTH.get();
                if (depth == 1) {
                    NOZZLE_TIP_CHANGE_DEPTH.remove();
                }
                else {
                    NOZZLE_TIP_CHANGE_DEPTH.set(depth - 1);
                }
                closed = true;
            }
        }
    }

    public static NozzleTipChangeScope suspendForNozzleTipChange() {
        return new NozzleTipChangeScope();
    }

    private static boolean isNozzleTipChanging() {
        return NOZZLE_TIP_CHANGE_DEPTH.get() != null;
    }

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
        return tryMove(tool, destination, requestedSpeed, false, null);
    }

    public static boolean tryMoveToBottomVision(HeadMountable tool, Location destination, double requestedSpeed) throws Exception {
        return tryMove(tool, destination, requestedSpeed, true, null);
    }

    public static boolean tryMoveToPick(HeadMountable tool, Location destination, double speed, Feeder feeder) throws Exception {
        if (isNozzleTipChanging() || !DynamicSafeMotionSettings.isEnabled() || !(tool instanceof Nozzle)) {
            return false;
        }
        Location expected = pickLocation((Nozzle) tool, feeder);
        if (expected.getXyzDistanceTo(destination.convertToUnits(LengthUnit.Millimeters)) >= .05) {
            return false;
        }
        return tryMove(tool, destination, speed, false,
                new CoordinatedMotionValidator.FeederContact("feeder:" + feeder.getId() + ":body", point(expected)));
    }

    private static Location pickLocation(Nozzle nozzle, Feeder feeder) throws Exception {
        Location expected = feeder.getPickLocation().convertToUnits(LengthUnit.Millimeters);
        if (feeder.isPartHeightAbovePickLocation()) {
            expected = expected.add(new Location(LengthUnit.Millimeters, 0, 0,
                    nozzle.getSafePartHeight(feeder.getPart()).convertToUnits(LengthUnit.Millimeters).getValue(), 0));
        }
        return expected;
    }

    public static void retractAfterPick(Nozzle nozzle, Feeder feeder) throws Exception {
        // Feeder subclasses are still electric feeders. Safety remains enforced by the certified feeder geometry.
        boolean standardElectric = feeder instanceof ReferenceAutoFeeder
                || feeder instanceof ReferenceSlotAutoFeeder;
        if (DynamicSafeMotionSettings.isEnabled() && standardElectric
                && org.openpnp.model.Configuration.get().getScripting().hasNoScript("Nozzle.AfterPick")) {
            Location expected = pickLocation(nozzle, feeder);
            if (nozzle.getLocation().convertToUnits(LengthUnit.Millimeters).getXyzDistanceTo(expected) < .05
                    && tryLocalRetreat(nozzle, new CoordinatedMotionValidator.FeederContact(
                            "feeder:" + feeder.getId() + ":body", point(expected.convertToUnits(LengthUnit.Millimeters))))) {
                return;
            }
        }
        nozzle.moveToSafeZ();
    }

    public static void retractAfterPlace(Nozzle nozzle) throws Exception {
        if (DynamicSafeMotionSettings.isEnabled()
                && !nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace)
                && org.openpnp.model.Configuration.get().getScripting().hasNoScript("Job.Placement.Complete")
                && tryLocalRetreat(nozzle, null)) {
            return;
        }
        nozzle.moveToSafeZ();
    }

    private static boolean tryLocalRetreat(Nozzle tool, CoordinatedMotionValidator.FeederContact contact) throws Exception {
        if (isNozzleTipChanging() || tool.getClass() != ReferenceNozzle.class || !tool.getHead().getMachine().isEnabled()
                || !tool.getHead().getMachine().isHomed()) {
            return false;
        }
        SafetyWorkbenchModel live = SafetyWorkbenchStore.get();
        long revision = live.getRevision();
        long started = System.nanoTime();
        Plan plan;
        try {
            String issue = enablementIssue(live, tool.getHead().getMachine());
            if (!issue.isEmpty() || !hardwareIssue(tool, tool.getHead().getMachine(), tool.getLocation()).isEmpty()) {
                return false;
            }
            SafetyWorkbenchModel frozen = prepare(live).frozen;
            if (contact != null && frozen.getObjects().stream().noneMatch(object -> object.enabled
                    && object.obstacle && object.type == SafetyWorkbenchModel.ObjectType.FEEDER
                    && contact.sourceKey.equals(object.sourceKey))) {
                return declineRetreat(tool, "缺少与此飞达关联的已认证外形", started);
            }
            Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
            double safeZ = tool.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
            if (!finite(start) || !Double.isFinite(safeZ) || safeZ <= start.getZ() + EPSILON) {
                return false;
            }
            long deadline = started + 40_000_000L;
            List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(frozen, tool.getHead().getMachine(), tool);
            double exitZ = clearHeight(start, safeZ, start, frozen, tools, deadline, false);
            if (frozen.isFastTravelFloorEnabled() && Double.isFinite(exitZ)
                    && frozen.getFastTravelFloorZ() <= safeZ && start.getZ() < frozen.getFastTravelFloorZ()) {
                // Raise only as far as the operator-certified rapid-travel plane, not all the way to Safe Z.
                // The entire vertical sweep is still validated with the feeder contact and sibling nozzle.
                exitZ = Math.max(exitZ, frozen.getFastTravelFloorZ());
            }
            if (!Double.isFinite(exitZ) || exitZ >= safeZ - EPSILON || System.nanoTime() > deadline) {
                return declineRetreat(tool, "局部间隙不足或规划超时，保留完整安全 Z", started);
            }
            Location exit = start.derive(null, null, exitZ, null);
            ValidationResult swept = CoordinatedMotionValidator.validate(frozen,
                    Arrays.asList(point(start), point(exit)), tools, point(start), deadline, contact);
            if (!swept.isSafe()) {
                return declineRetreat(tool, swept.getIssues().get(0), started);
            }
            double fullSeconds = estimate(tool, start, Collections.singletonList(start.derive(null, null, safeZ, null)),
                    tool.getHead().getMachine().getSpeed());
            double localSeconds = estimate(tool, start, Collections.singletonList(exit), tool.getHead().getMachine().getSpeed());
            plan = new Plan(Collections.singletonList(exit), "局部安全退出：先抬离当前位置，剩余 Z 留给下一段 XY 联动",
                    fullSeconds, localSeconds);
        }
        catch (Exception declined) {
            Logger.debug(declined, "Local retreat declined before issuing motion.");
            return declineRetreat(tool, declined.getMessage(), started);
        }
        double planningMs = (System.nanoTime() - started) / 1e6;
        if (planningMs >= 40 || (plan.originalSeconds - plan.coordinatedSeconds) * 1000 <= planningMs + 5
                || SafetyWorkbenchStore.get() != live || revision != live.getRevision() || !live.isCertified()) {
            return false;
        }
        // Execution errors propagate. Never issue a second fallback after a partial move.
        Location exit = plan.locations.get(0);
        if (!raw(tool, tool.getLocation()).matches(raw(tool, exit))) {
            tool.moveTo(exit, tool.getHead().getMachine().getSpeed(), MotionOption.CoordinatedMotion);
        }
        DynamicMotionDiagnostics.recordRetraction(tool.getName(), plan.reason, planningMs,
                plan.originalSeconds, plan.coordinatedSeconds);
        lastReason = plan.reason;
        return true;
    }

    private static boolean declineRetreat(Nozzle tool, String reason, long started) {
        String message = "局部退出回退：" + reason;
        DynamicMotionDiagnostics.record(tool.getName(), message, false, (System.nanoTime() - started) / 1e6,
                Double.NaN, Double.NaN);
        return fallback(message);
    }

    private static boolean tryMove(HeadMountable tool, Location destination, double requestedSpeed, boolean imaging, CoordinatedMotionValidator.FeederContact contact) throws Exception {
        if (isNozzleTipChanging() || !DynamicSafeMotionSettings.isEnabled()) {
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
        Plan plan = preview(tool, destination, requestedSpeed, started + 40_000_000L, imaging, contact);
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
        return preview(tool, destination, speed, Long.MAX_VALUE, false, null);
    }

    public static Plan previewBottomVision(HeadMountable tool, Location destination, double speed) {
        if (isNozzleTipChanging()) {
            return rejected("更换吸嘴过程中保持原安全路径");
        }
        return preview(tool, destination, speed, Long.MAX_VALUE, true, null);
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

    private static Plan preview(HeadMountable tool, Location destination, double speed, long deadline, boolean imaging, CoordinatedMotionValidator.FeederContact contact) {
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
            return plan(tool, destination, speed, model, deadline, imaging, contact);
        } catch (Exception planningFailure) {
            Logger.debug(planningFailure, "3D coordinated planning declined before motion.");
            return rejected("规划条件不满足：" + planningFailure.getMessage());
        }
    }

    static Plan plan(HeadMountable tool, Location destination, double speed, SafetyWorkbenchModel model) throws Exception {
        return plan(tool, destination, speed, model, Long.MAX_VALUE, false, null);
    }

    private static Plan plan(HeadMountable tool, Location destination, double speed, SafetyWorkbenchModel model, long deadline, boolean imaging, CoordinatedMotionValidator.FeederContact contact) throws Exception {
        if (tool == null || tool.getClass() != ReferenceNozzle.class || destination == null || !Double.isFinite(speed) || speed <= 0) {
            return rejected("探针、特殊工具或无效坐标保持原路径");
        }
        Machine machine = tool.getHead().getMachine();
        String hardwareIssue = hardwareIssue(tool, machine, destination);
        if (!hardwareIssue.isEmpty()) {
            return rejected(hardwareIssue);
        }
        Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location target = destination.convertToUnits(LengthUnit.Millimeters);
        if (!finite(start) || !finite(target) || Math.hypot(target.getX() - start.getX(), target.getY() - start.getY()) < EPSILON) {
            return rejected("纯 Z、原地旋转或未指定的坐标保持原路径");
        }
        List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(model, machine, tool);
        double originalSeconds = estimateStock(tool, target, speed);
        // Optional fast-travel plane: above this calibrated *nozzle-reference* Z, enter pickup
        // or imaging with coordinated XY/Z. Below it, exit/enter vertically at the endpoint.
        // Never waive the full swept collision, sibling, feeder or soft-limit checks.
        Plan floorPlan = planTravelFloor(tool, start, target, speed, model, tools,
                originalSeconds, deadline, imaging, contact);
        if (floorPlan != null) {
            return floorPlan;
        }
        if (imaging) {
            // Imaging has no contact/release step. Do not insert an artificial
            // final vertical approach or require both cam nozzles in global Safe-Z.
            ValidationResult direct = CoordinatedMotionValidator.validate(model,
                    Arrays.asList(point(start), point(target)), tools, point(start), deadline);
            if (direct.isSafe()) {
                List<Location> path = Collections.singletonList(target);
                double seconds = estimate(tool, start, path, speed);
                if (Double.isFinite(seconds) && seconds + 0.001 < originalSeconds) {
                    return new Plan(path, "视觉直达：XY 行进中达到拍摄 Z，双吸嘴整段包络通过", originalSeconds, seconds);
                }
            }
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
        if (!Double.isFinite(safeZ)) {
            return rejected("所需巡航高度无效");
        }
        for (DynamicTool dynamic : tools) {
            if (dynamic.carryingPart && dynamic.carriedPartHeight <= 0) {
                return rejected("吸嘴物料高度未知");
            }
        }
        if (System.nanoTime() > deadline) {
            return rejected("规划超过 40 ms 预算，保持原安全 Z");
        }
        double sourceZ = clearHeight(start, safeZ, start, model, tools, deadline, false);
        double targetZ = clearHeight(target, safeZ, start, model, tools, deadline, !imaging);
        if (System.nanoTime() > deadline) {
            return rejected("规划超过 40 ms 预算，保持原安全 Z");
        }
        if (!Double.isFinite(sourceZ) || !Double.isFinite(targetZ)) {
            return rejected("安全高度仍有障碍或其他吸嘴未抬起");
        }
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
            ValidationResult result = CoordinatedMotionValidator.validate(model, points, tools, point(start), deadline, contact);
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

    /** A deliberately opt-in rapid plane; cannot overrule certified 3D obstacles or axis limits. */
    private static Plan planTravelFloor(HeadMountable tool, Location start, Location target, double speed,
            SafetyWorkbenchModel model, List<DynamicTool> tools, double stockSeconds,
            long deadline, boolean imaging, CoordinatedMotionValidator.FeederContact contact) throws Exception {
        if (!model.isFastTravelFloorEnabled() || System.nanoTime() > deadline) {
            return null;
        }
        // If a feeder body has not been measured, do not apply the NEW rapid-plane
        // shortcut there. Preserve the older planner's behavior as the fallback.
        if (contact != null && model.getObjects().stream().noneMatch(object -> object != null
                && object.enabled && object.obstacle && object.type == SafetyWorkbenchModel.ObjectType.FEEDER
                && contact.sourceKey.equals(object.sourceKey))) {
            return null;
        }
        double floor = model.getFastTravelFloorZ();
        if (!Double.isFinite(floor) || floor <= model.getMinZ() || floor >= model.getMaxZ()) {
            return null;
        }
        List<Location> candidate = new ArrayList<>();
        // On leaving a recessed feeder, lift in place before any lateral travel below the floor.
        if (start.getZ() < floor - EPSILON) {
            candidate.add(start.derive(null, null, floor, null));
        }
        // A placement/pick needs its last vertical 1 mm (or more); a non-contact camera does not.
        double approachZ = imaging ? Math.max(floor, target.getZ())
                : Math.max(floor, target.getZ() + 1.0);
        candidate.add(target.derive(null, null, approachZ, null));
        if (Math.abs(target.getZ() - approachZ) > EPSILON) {
            candidate.add(target);
        }
        candidate = compact(tool, start, candidate);
        if (candidate.isEmpty()) {
            return null;
        }
        Location previous = start;
        boolean hasLateralMotion = false;
        for (Location next : candidate) {
            if (Math.hypot(next.getX() - previous.getX(), next.getY() - previous.getY()) > EPSILON) {
                hasLateralMotion = true;
                if (Math.min(previous.getZ(), next.getZ()) < floor - EPSILON) {
                    return null; // Never travel sideways below the certified reference-Z floor.
                }
            }
            previous = next;
        }
        if (!hasLateralMotion) {
            return null;
        }
        List<Waypoint> points = new ArrayList<>();
        points.add(point(start));
        for (Location waypoint : candidate) {
            points.add(point(waypoint));
        }
        ValidationResult sweep = CoordinatedMotionValidator.validate(model, points, tools, point(start), deadline, contact);
        if (!sweep.isSafe() || System.nanoTime() > deadline) {
            return null; // Existing conservative geometry planner has the final say.
        }
        double seconds = estimate(tool, start, candidate, speed);
        if (!Double.isFinite(seconds) || seconds + 0.001 >= stockSeconds) {
            return null;
        }
        return new Plan(candidate, imaging
                ? "经认证 Z 快速通道：进视觉前联动 XY/Z，无取放末段"
                : "经认证 Z 快速通道：XY/Z 协调进料位，保留取放垂直段",
                stockSeconds, seconds);
    }

    private static String hardwareIssue(HeadMountable tool, Machine machine, Location destination) throws Exception {
        if (tool.getAxisX() == null || tool.getAxisY() == null || tool.getAxisZ() == null) {
            return "需要完整的 XYZ 轴映射";
        }
        if (!supportedZ(tool.getAxisZ())) {
            return "此 Z 轴变换尚不能验证，保持原路径";
        }
        // Only axes that physically move in this planned XYZ path determine the motion driver.
        // A separate feeder/actuator driver is irrelevant; an unchanged rotation axis may also
        // live on its own controller without rejecting a safe XYZ-only movement.
        AxesLocation mapped = tool.getMappedAxes(machine);
        List<Driver> drivers = new ArrayList<>();
        for (Axis.Type type : Arrays.asList(Axis.Type.X, Axis.Type.Y, Axis.Type.Z)) {
            for (Driver candidate : mapped.byType(type).getAxesDrivers(machine)) {
                if (!drivers.contains(candidate)) {
                    drivers.add(candidate);
                }
            }
        }
        if (drivers.size() != 1 || (!(drivers.get(0) instanceof GcodeDriver) && !(drivers.get(0) instanceof NullDriver))) {
            return "吸嘴 XYZ 控制轴须由同一协调运动控制器驱动：" + drivers;
        }
        Driver driver = drivers.get(0);
        boolean rotationMoves = destination != null && tool.getLocation() != null
                && Math.abs(destination.getRotation() - tool.getLocation().getRotation()) > EPSILON;
        if (rotationMoves) {
            for (Driver rotationDriver : mapped.byType(Axis.Type.Rotation).getAxesDrivers(machine)) {
                if (rotationDriver != driver) {
                    return "本次需要旋转，但旋转轴不在 XYZ 控制器上；保持原安全 Z";
                }
            }
        }
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
                    for (ControllerAxis axis : mapped.getControllerAxes()) {
                        // Do not require a stationary rotation axis to appear in the XYZ G1.
                        if (axis.getType() == Axis.Type.Rotation && !rotationMoves) {
                            continue;
                        }
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

    private static double clearHeight(Location endpoint, double safeZ, Location start, SafetyWorkbenchModel model, List<DynamicTool> tools, long deadline, boolean finalApproach) {
        double low = endpoint.getZ();
        if (clearAt(endpoint, start, model, tools, deadline)) {
            // Keep a short vertical final approach even on an unobstructed table.
            return Math.min(safeZ, low + (finalApproach ? 1 : 0));
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

    /** Simulate the original helper in raw axes, including all shared-Z head tools. */
    private static double estimateStock(HeadMountable tool, Location target, double speed) throws Exception {
        AxesLocation position = new AxesLocation(tool.getHead().getMachine());
        double seconds = 0;
        for (HeadMountable sibling : tool.getHead().getHeadMountables()) {
            Length safe = sibling.getEffectiveSafeZ();
            if (safe == null) {
                continue;
            }
            if (!sibling.isInSafeZZone(safe)) {
                throw new Exception("原安全 Z 不在安全区间：" + sibling.getName());
            }
            Location pose = sibling.toHeadMountableLocation(sibling.toTransformed(position, LocationOption.Quiet), LocationOption.Quiet);
            Length underside = pose.getLengthZ().add(sibling.getSafeZ()).subtract(safe);
            if (!sibling.isInSafeZZone(underside) && underside.compareTo(sibling.getSafeZ()) <= 0) {
                AxesLocation next = position.put(raw(sibling, pose.deriveLengths(null, null, safe, null)));
                seconds += stockSegmentTime(sibling, position, next, speed);
                position = next;
            }
        }
        Length exit = tool.getEffectiveSafeZ();
        if (exit != null && target.getLengthZ().compareTo(exit) > 0) {
            Length[] zone = tool.getSafeZZone();
            if (zone[1] != null) {
                exit = target.getLengthZ().compareTo(zone[1]) < 0 ? target.getLengthZ() : zone[1];
            }
        }
        Location travel = target.derive(null, null, exit == null ? Double.NaN
                : exit.convertToUnits(target.getUnits()).getValue(), null);
        for (Location location : Arrays.asList(travel, target)) {
            AxesLocation next = position.put(raw(tool, location));
            seconds += stockSegmentTime(tool, position, next, speed);
            position = next;
        }
        return seconds;
    }

    private static double stockSegmentTime(HeadMountable tool, AxesLocation first, AxesLocation last, double speed) {
        int flags = 0;
        if (tool.getHead().getMachine().getMotionPlanner() instanceof ReferenceAdvancedMotionPlanner
                && ((ReferenceAdvancedMotionPlanner) tool.getHead().getMachine().getMotionPlanner()).isAllowUncoordinated()
                && first.isInSafeZone() && last.isInSafeZone()) {
            flags = MotionOption.UncoordinatedMotion.flag() | MotionOption.LimitToSafeZone.flag()
                    | MotionOption.SynchronizeStraighten.flag();
        }
        return new Motion(tool, first, last, speed * tool.getHead().getMaxPartSpeed(), flags).getTime();
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
