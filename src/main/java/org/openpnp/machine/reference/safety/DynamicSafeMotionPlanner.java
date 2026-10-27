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

    private static boolean strict(SafetyWorkbenchModel model) {
        return model.isFastTravelFloorEnabled() || DynamicSafeMotionSettings.isStrictEnabled();
    }

    private static long planningDeadline() {
        // A budget is a ceiling, not a delay. Allow cold JVM/JIT and shared-cam
        // transforms to complete instead of reverting after a 40 ms outlier.
        return System.nanoTime() + 250_000_000L;
    }

    private static final class Prepared {
        final SafetyWorkbenchModel source, frozen;
        final long revision, certifiedRevision;
        final String issue;
        final boolean strictMode;
        Prepared(SafetyWorkbenchModel source) {
            this.source = source;
            revision = source.getRevision();
            certifiedRevision = source.getCertifiedRevision();
            frozen = SafetyWorkbenchStore.snapshot(source);
            strictMode = strict(source);
            if (strictMode) {
                List<SafetyWorkbenchModel.SafetyObject> zones = new ArrayList<>();
                for (SafetyWorkbenchModel.SafetyObject object : frozen.getObjects()) {
                    if (object.enabled && object.obstacle && FastTravelClearance.restrictedZone(object)) {
                        SafetyWorkbenchModel.SafetyObject zone = object.copy();
                        zone.x = object.x;
                        zone.y = object.y;
                        zone.name = object.name;
                        zone.type = SafetyWorkbenchModel.ObjectType.KEEP_OUT;
                        zone.shape = SafetyWorkbenchModel.Shape.BOX;
                        zone.z = frozen.getMinZ();
                        zone.height = frozen.getMaxZ() - frozen.getMinZ();
                        zone.apertureWidth = 0;
                        zone.apertureDepth = 0;
                        zone.sourceKey = object.sourceKey + ":restricted-travel-zone";
                        zones.add(zone);
                    }
                }
                frozen.getObjects().addAll(zones);
            }
            List<String> issues = SafetyModelValidator.validate(frozen);
            issue = issues.isEmpty() ? "" : issues.get(0);
        }
        boolean matches(SafetyWorkbenchModel model) {
            return strictMode == strict(model) && source == model && revision == model.getRevision()
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

    private static CoordinatedMotionValidator.FeederContact boardContact(Location surface, Location tip) {
        Location base = surface.convertToUnits(LengthUnit.Millimeters);
        Location actual = tip.convertToUnits(LengthUnit.Millimeters);
        if (Math.hypot(base.getX() - actual.getX(), base.getY() - actual.getY()) > .05
                || actual.getZ() < base.getZ() - .05) { return null; }
        for (SafetyWorkbenchModel.SafetyObject object : SafetyWorkbenchStore.get().getObjects()) {
            if (object.enabled && object.obstacle && object.type == SafetyWorkbenchModel.ObjectType.BOARD
                    && base.getX() >= object.x && base.getX() <= object.x + object.width
                    && base.getY() >= object.y && base.getY() <= object.y + object.depth
                    && base.getZ() >= object.z && base.getZ() <= object.z + object.height) {
                return new CoordinatedMotionValidator.FeederContact(object.id, point(actual), true);
            }
        }
        return null;
    }

    public static boolean tryMoveToPlace(Nozzle nozzle, Location surface, Location tip, double speed) throws Exception {
        return tryMove(nozzle, tip, speed, false, boardContact(surface, tip));
    }

    public static void retractAfterPlace(Nozzle nozzle, Location surface, Location releasedAt) throws Exception {
        if (DynamicSafeMotionSettings.isEnabled() && nozzle.getPart() == null
                && nozzle.getLocation().convertToUnits(LengthUnit.Millimeters).getXyzDistanceTo(
                        releasedAt.convertToUnits(LengthUnit.Millimeters)) < .05
                && !nozzle.isPartOffEnabled(Nozzle.PartOffStep.AfterPlace)
                && org.openpnp.model.Configuration.get().getScripting().hasNoScript("Job.Placement.Complete")
                && tryLocalRetreat(nozzle, boardContact(surface, releasedAt))) { return; }
        retractAfterPlace(nozzle);
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

    public static boolean prepositionForFeed(Nozzle nozzle, Feeder feeder) throws Exception {
        if (!DynamicSafeMotionSettings.isEnabled() || isNozzleTipChanging()
                || nozzle.getClass() != ReferenceNozzle.class) { return false; }
        Machine machine = nozzle.getHead().getMachine();
        SafetyWorkbenchModel live = SafetyWorkbenchStore.get();
        if (!machine.isEnabled() || !machine.isHomed() || !live.isCertified()) { return false; }
        String issue = enablementIssue(live, machine);
        if (!issue.isEmpty()) { throw new Exception(issue); }
        long revision = live.getRevision();
        SafetyWorkbenchModel model = prepare(live).frozen;
        Length upper = nozzle.getEffectiveSafeZ();
        if (upper == null) { return false; }
        Location pick = pickLocation(nozzle, feeder);
        var contact = new CoordinatedMotionValidator.FeederContact("feeder:" + feeder.getId() + ":body", point(pick));
        if (model.getObjects().stream().noneMatch(object -> object.enabled && object.obstacle
                && object.type == SafetyWorkbenchModel.ObjectType.FEEDER && contact.permits(object, contact.pick))) {
            return false;
        }
        issue = hardwareIssue(nozzle, machine, pick);
        if (!issue.isEmpty()) { throw new Exception(issue); }
        Location start = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        double safeZ = upper.convertToUnits(LengthUnit.Millimeters).getValue();
        if (!machine.getMotionPlanner().isValidLocation(nozzle, raw(nozzle, start))) {
            throw new Exception("飞达预定位起点控制轴超出软限位");
        }
        long deadline = System.nanoTime() + 500_000_000L;
        List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(model, machine, nozzle);
        // Waiting is AT the pick XY, with clearance in Z only. Never ask the
        // general travel-plane planner for run-in points, detours or a Z tail.
        double z = clearHeight(pick.derive(null, null, pick.getZ() + 2, null), safeZ,
                start, model, tools, deadline, false);
        if (!Double.isFinite(z) || z > safeZ + EPSILON) {
            throw new Exception("飞达取料点上方未找到双嘴安全等待高度");
        }
        for (double waitZ : new double[] { z, safeZ }) {
            Location waiting = pick.derive(null, null, waitZ, null);
            ValidationResult check = CoordinatedMotionValidator.validate(model,
                    Arrays.asList(point(start), point(waiting)), tools, point(start), deadline);
            if (!check.isSafe()) { continue; }
            estimate(nozzle, start, Collections.singletonList(waiting), machine.getSpeed());
            if (SafetyWorkbenchStore.get() != live || live.getRevision() != revision || !live.isCertified()) {
                throw new Exception("飞达预定位期间安全模型改变");
            }
            if (!raw(nozzle, start).matches(raw(nozzle, waiting))) {
                nozzle.moveTo(waiting, machine.getSpeed(), MotionOption.CoordinatedMotion);
            }
            Logger.info("Feeder wait {} at pick XY ({}, {}), Z={} mm; feed completion releases Z-only pickup",
                    nozzle.getName(), pick.getX(), pick.getY(), waitZ);
            return true;
        }
        throw new Exception("飞达取料点上方直达预定位未通过双嘴扫掠检查，未下发绕行");
    }

    /** After feed: recheck a stale pose once, then descend or apply one bounded, swept local correction. */
    public static void descendAtPrepositionedFeeder(Nozzle nozzle, Feeder feeder) throws Exception {
        Machine machine = nozzle.getHead().getMachine();
        SafetyWorkbenchModel live = SafetyWorkbenchStore.get();
        String issue = enablementIssue(live, machine);
        if (!issue.isEmpty() || !machine.isEnabled() || !machine.isHomed()) {
            throw new Exception("飞达预定位下降条件改变：" + issue);
        }
        long revision = live.getRevision();
        SafetyWorkbenchModel model = prepare(live).frozen;
        for (int attempt = 0; attempt < 2; attempt++) {
            Location start = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            if (!machine.getMotionPlanner().isValidLocation(nozzle, raw(nozzle, start))) {
                throw new Exception("飞达预定位当前位置超出原生软限位，禁止自动恢复");
            }
            Location pick = pickLocation(nozzle, feeder);
            // The rotation planner may retain 360/720 degrees for a commanded zero.
            // Compare the nearest equivalent turn and keep that turn during descent;
            // still use native axis resolution for any actual XY or angular change.
            pick = pick.derive(null, null, null, pick.getRotation()
                    + 360 * Math.rint((start.getRotation() - pick.getRotation()) / 360));
            AxesLocation actual = raw(nozzle, start.derive(null, null, pick.getZ(), null));
            AxesLocation expected = raw(nozzle, pick);
            boolean samePose = actual.matches(expected);
            if (!samePose) {
                samePose = true;
                for (ControllerAxis axis : expected.getControllerAxes()) {
                    if (axis.coordinatesMatch(actual.getLengthCoordinate(axis), expected.getLengthCoordinate(axis))) { continue; }
                    double tolerance = 0;
                    if ((axis.getType() == Axis.Type.X || axis.getType() == Axis.Type.Y)
                            && axis.getDriver() instanceof GcodeDriver) {
                        GcodeDriver driver = (GcodeDriver) axis.getDriver();
                        String variable = driver.isUsingLetterVariables() ? axis.getLetter() : axis.getType().toString();
                        String command = driver.getCommand(nozzle, CommandType.MOVE_TO_COMMAND);
                        if (command != null && variable != null) {
                            var matcher = java.util.regex.Pattern.compile("\\{" + java.util.regex.Pattern.quote(variable)
                                    + "F?:[^}]*?%[^}]*?\\.(\\d+)f[^}]*\\}", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(command);
                            if (matcher.find()) {
                                double quantum = Math.pow(10, -Integer.parseInt(matcher.group(1)));
                                tolerance = Math.min(.01, new Length(quantum, driver.getUnits())
                                        .convertToUnits(LengthUnit.Millimeters).getValue());
                            }
                        }
                    }
                    if (Math.abs(actual.getCoordinate(axis) - expected.getCoordinate(axis)) > tolerance + 1e-9) {
                        samePose = false;
                        break;
                    }
                }
            }

            double xyError = Math.hypot(start.getX() - pick.getX(), start.getY() - pick.getY());
            boolean recoverable = !samePose && xyError < .05 && nozzle.getPart() == null
                    && actual.byType(Axis.Type.Rotation).matches(expected.byType(Axis.Type.Rotation))
                    && machine.isTask(Thread.currentThread());
            if (!samePose && attempt == 0 && nozzle.getPart() == null && machine.isTask(Thread.currentThread())) {
                // Feed completion may precede the queued head approach. Only on
                // mismatch, finish that existing motion and re-read its pose.
                machine.getMotionPlanner().waitForCompletion(null,
                        org.openpnp.spi.MotionPlanner.CompletionType.WaitForStillstand);
                if (SafetyWorkbenchStore.get() != live || live.getRevision() != revision || !live.isCertified()
                        || !machine.isEnabled() || !machine.isHomed()) {
                    throw new Exception("飞达预定位复核期间机器状态或安全模型改变");
                }
                Logger.info("Feeder preposition recheck {}: XY={} mm; approach completed before rereading",
                        nozzle.getName(), xyError);
                continue;
            }
            if (!samePose && !recoverable) {
                throw new Exception(String.format(java.util.Locale.ROOT,
                        "飞达预定位后 XY 或角度改变（XY %.4f mm，角度 %.4f°），已暂停；超出自动恢复范围",
                        xyError, start.getRotation() - pick.getRotation()));
            }
            List<Location> steps;
            if (recoverable) {
                // One local correction at the CURRENT safe waiting Z, followed
                // by vertical descent. No repeated feed, global detour or loop.
                steps = Arrays.asList(pick.derive(null, null, start.getZ(), null), pick);
            } else {
                pick = start.derive(null, null, pick.getZ(), null);
                steps = Collections.singletonList(pick);
            }
            List<Waypoint> points = new ArrayList<>();
            points.add(point(start));
            for (Location step : steps) { points.add(point(step)); }
            var contact = new CoordinatedMotionValidator.FeederContact("feeder:" + feeder.getId() + ":body", point(pick));
            ValidationResult check = CoordinatedMotionValidator.validate(model, points,
                    DynamicSafetyCollisionEngine.snapshot(model, machine, nozzle), point(start),
                    System.nanoTime() + 500_000_000L, contact);
            if (!check.isSafe()) {
                throw new Exception("飞达预定位自动恢复/下降检查未通过：" + String.join("；", check.getIssues()));
            }
            estimate(nozzle, start, steps, machine.getSpeed());
            if (SafetyWorkbenchStore.get() != live || live.getRevision() != revision || !live.isCertified()
                    || !machine.isEnabled() || !machine.isHomed()) {
                throw new Exception("飞达预定位后机器状态或安全模型改变");
            }
            if (recoverable) {
                Logger.info("Feeder preposition auto-correct {}: XY={} mm at Z={} mm; full swept path checked, feed not repeated",
                        nozzle.getName(), xyError, start.getZ());
            }
            for (Location step : steps) { nozzle.moveTo(step, machine.getSpeed(), MotionOption.CoordinatedMotion); }
            return;
        }
        throw new Exception("飞达预定位复核未完成，已暂停");
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
            if (issue.isEmpty()) { issue = hardwareIssue(tool, tool.getHead().getMachine(), tool.getLocation()); }
            if (!issue.isEmpty()) { return declineRetreat(tool, issue, started); }
            SafetyWorkbenchModel frozen = prepare(live).frozen;
            if (contact != null && !contact.board && frozen.getObjects().stream().noneMatch(object -> object.enabled
                    && object.obstacle && object.type == SafetyWorkbenchModel.ObjectType.FEEDER
                    && contact.permits(object, contact.pick))) {
                return declineRetreat(tool, "缺少与此飞达关联的已认证外形", started);
            }
            Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
            double safeZ = tool.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
            if (!finite(start) || !Double.isFinite(safeZ) || safeZ <= start.getZ() + EPSILON) {
                return false;
            }
            long deadline = planningDeadline();
            List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(frozen, tool.getHead().getMachine(), tool);
            double exitZ = clearHeight(start, safeZ, start, frozen, tools, deadline, false);
            if (frozen.isFastTravelFloorEnabled() && Double.isFinite(exitZ)
                    && frozen.getFastTravelFloorZ() <= safeZ && start.getZ() < frozen.getFastTravelFloorZ()) {
                // Raise only as far as the operator-certified rapid-travel plane, not all the way to Safe Z.
                // The entire vertical sweep is still validated with the feeder contact and sibling nozzle.
                exitZ = Math.max(exitZ, frozen.getFastTravelFloorZ());
            }
            if (!Double.isFinite(exitZ) || exitZ > safeZ + EPSILON || System.nanoTime() > deadline) {
                return declineRetreat(tool, "未找到双吸嘴同时安全的退出高度，或规划超时", started);
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
            if (strict(live)) { throw declined; }
            Logger.debug(declined, "Local retreat declined before issuing motion.");
            return declineRetreat(tool, declined.getMessage(), started);
        }
        double planningMs = (System.nanoTime() - started) / 1e6;
        if (SafetyWorkbenchStore.get() != live || revision != live.getRevision() || !live.isCertified()) {
            return declineRetreat(tool, "规划期间模型认证改变", started);
        }
        if (!strict(live) && (plan.originalSeconds - plan.coordinatedSeconds) * 1000 <= planningMs + 5) {
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

    private static boolean declineRetreat(Nozzle tool, String reason, long started) throws Exception {
        if (strict(SafetyWorkbenchStore.get())) {
            throw new Exception("局部退出已暂停（不回退）：" + reason);
        }
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
            if (strict(SafetyWorkbenchStore.get())) {
                throw new Exception("联动已暂停：机器尚未启用或回零");
            }
            return fallback("机器尚未启用或回零");
        }
        SafetyWorkbenchModel model = SafetyWorkbenchStore.get();
        long revision = model.getRevision();
        long started = System.nanoTime();
        Plan plan = preview(tool, destination, requestedSpeed, planningDeadline(), imaging, contact);
        if (!plan.isOptimized() && plan.reason.contains("规划超时")
                && SafetyWorkbenchStore.get() == model && revision == model.getRevision() && model.isCertified()) {
            // No commands have been issued. A single bounded replan tolerates
            // a JVM GC/scheduling pause without relaxing collision validation.
            plan = preview(tool, destination, requestedSpeed, System.nanoTime() + 500_000_000L, imaging, contact);
        }
        double planningMs = (System.nanoTime() - started) / 1e6;
        if (!plan.isOptimized()) {
            if (strict(model)) {
                throw new Exception("联动已暂停（不回退）：" + plan.reason);
            }
            DynamicMotionDiagnostics.record(tool.getName(), plan.reason, false, planningMs,
                    plan.originalSeconds, plan.coordinatedSeconds);
            return fallback(plan.reason);
        }
        if (!strict(model)
                && (plan.originalSeconds - plan.coordinatedSeconds) * 1000 <= planningMs + 5) {
            DynamicMotionDiagnostics.record(tool.getName(), "预计节省不足以抵消规划开销", false, planningMs,
                    plan.originalSeconds, plan.coordinatedSeconds);
            return fallback("预计节省不足以抵消规划开销");
        }
        if (SafetyWorkbenchStore.get() != model || revision != model.getRevision() || !model.isCertified()) {
            if (strict(model)) { throw new Exception("联动已暂停：模型认证或修订已改变"); }
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

    public static String continuousVisionIssue(HeadMountable tool) throws Exception {
        if (isNozzleTipChanging()) { return "更换吸嘴过程中禁止连续飞拍"; }
        if (!DynamicSafeMotionSettings.isEnabled() || tool == null || tool.getClass() != ReferenceNozzle.class)
            { return "连续飞拍需要启用 XYZ 联动并使用标准吸嘴"; }
        String issue = enablementIssue(SafetyWorkbenchStore.get(), tool.getHead().getMachine());
        return issue.isEmpty() ? hardwareIssue(tool, tool.getHead().getMachine(), tool.getLocation()) : issue;
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
            Plan result = plan(tool, destination, speed, model, deadline, imaging, contact);
            return System.nanoTime() > deadline ? rejected("联动规划超时，尚未下发运动") : result;
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
        if (!machine.getMotionPlanner().isValidLocation(tool, raw(tool, start))) {
            return rejected("起点控制轴坐标超出软限位");
        }
        List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(model, machine, tool);
        double originalSeconds = estimateStock(tool, target, speed);
        // Optional fast-travel plane: above this calibrated *nozzle-reference* Z, enter pickup
        // or imaging with coordinated XY/Z. Below it, exit/enter vertically at the endpoint.
        // Never waive the full swept collision, sibling, feeder or soft-limit checks.
        // Board entry first tries the locally required clearance with one
        // diagonal and a vertical contact stroke. A global-plane waypoint near
        // the PCB needlessly changes direction and can force a slow junction.
        Plan floorPlan = contact != null && contact.board ? null
                : planTravelFloor(tool, start, target, speed, model, tools,
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
                if (Double.isFinite(seconds) && (strict(model) || seconds + 0.001 < originalSeconds)) {
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
            return rejected("联动规划超时，尚未下发运动");
        }
        double sourceZ = clearHeight(start, safeZ, start, model, tools, deadline, false);
        double targetZ = clearHeight(target, safeZ, start, model, tools, deadline, !imaging);
        if (System.nanoTime() > deadline) {
            return rejected("联动规划超时，尚未下发运动");
        }
        if (!Double.isFinite(sourceZ) || !Double.isFinite(targetZ)) {
            Location failed = !Double.isFinite(sourceZ) ? start : target;
            ValidationResult detail = CoordinatedMotionValidator.validate(model,
                    Collections.singletonList(point(failed.derive(null, null, safeZ, null))),
                    tools, point(start), deadline);
            return rejected((!Double.isFinite(sourceZ) ? "起点" : "目标点")
                    + "未找到安全高度，位置=" + failed + "；检查高度=" + safeZ + " mm；"
                    + String.join("；", detail.getIssues()));
        }
        String reason = "没有可缩短耗时的安全联动段";
        if (contact != null && !contact.board && sourceZ < targetZ - EPSILON) {
            // A slightly higher arrival over the feeder can clear its leading
            // edge in one diagonal, without an intermediate rise/cruise junction.
            for (double lift : new double[] { 2.5, 5.0 }) {
                if (System.nanoTime() > deadline) { break; }
                double arrivalZ = Math.min(safeZ, targetZ + lift);
                List<Location> directPath = compact(tool, start, Arrays.asList(
                        start.derive(null, null, sourceZ, null),
                        target.derive(null, null, arrivalZ, null), target));
                List<Waypoint> directPoints = new ArrayList<>();
                directPoints.add(point(start));
                for (Location location : directPath) { directPoints.add(point(location)); }
                ValidationResult direct = CoordinatedMotionValidator.validate(model, directPoints,
                        tools, point(start), deadline, contact);
                if (!direct.isSafe()) { continue; }
                double seconds = estimate(tool, start, directPath, speed);
                if (Double.isFinite(seconds) && (strict(model) || seconds + .001 < originalSeconds)) {
                    return new Plan(directPath, "飞达直达联动：取消中途抬升折返，末段垂直取料",
                            originalSeconds, seconds);
                }
            }
        }
        // Prefer the clearance actually needed by the two endpoints. Going
        // all the way to Safe-Z creates an unnecessary up/flat/down reversal,
        // especially expensive with a shared cam. Every candidate is still swept.
        for (double cruiseZ : new double[] { Math.max(sourceZ, targetZ), safeZ }) {
        for (double fraction : new double[] { 0, 0.4, 0.25, 0.12 }) {
            if (System.nanoTime() > deadline) {
                return rejected("联动规划超时，尚未下发运动");
            }
            Location departure = start.derive(null, null, sourceZ, null);
            Location ascentEnd = interpolate(start, target, fraction).derive(null, null, cruiseZ, target.getRotation());
            Location descentStart = interpolate(start, target, 1 - fraction).derive(null, null, cruiseZ, target.getRotation());
            if (Math.abs(sourceZ - cruiseZ) < EPSILON) {
                ascentEnd = departure;
            }
            if (Math.abs(targetZ - cruiseZ) < EPSILON) {
                descentStart = target.derive(null, null, cruiseZ, null);
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
            if (Double.isFinite(seconds) && (strict(model) || seconds + 0.001 < originalSeconds)) {
                return new Plan(path, "联动通过，通行 Z=" + cruiseZ + " mm，保留末段垂直取放", originalSeconds, seconds);
            }
        }
        }
        if (contact != null && contact.board) {
            Plan alternate = planTravelFloor(tool, start, target, speed, model, tools,
                    originalSeconds, deadline, imaging, contact);
            if (alternate != null) { return alternate; }
        }
        return new Plan(Collections.emptyList(), reason, originalSeconds, originalSeconds);
    }

    /** A deliberately opt-in rapid plane; cannot overrule certified 3D obstacles or axis limits. */
    private static Plan planTravelFloor(HeadMountable tool, Location start, Location target, double speed,
            SafetyWorkbenchModel model, List<DynamicTool> tools, double stockSeconds,
            long deadline, boolean imaging, CoordinatedMotionValidator.FeederContact contact) throws Exception {
        if (!strict(model) || System.nanoTime() > deadline) {
            return null;
        }
        // If the feeder body was not measured, do not apply the NEW rapid shortcut there.
        // Preserve the older planner's unchanged fallback behavior.
        if (contact != null && !contact.board && model.getObjects().stream().noneMatch(object -> object != null
                && object.enabled && object.obstacle && object.type == SafetyWorkbenchModel.ObjectType.FEEDER
                && contact.permits(object, contact.pick))) {
            return null;
        }
        Length safeHeight = tool.getEffectiveSafeZ();
        if (safeHeight == null) { return null; }
        double floor = FastTravelClearance.referenceZ(model, tools,
                safeHeight.convertToUnits(LengthUnit.Millimeters).getValue());
        if (!Double.isFinite(floor) || floor <= model.getMinZ() || floor >= model.getMaxZ()) {
            return null;
        }
        List<List<Location>> proposals = new ArrayList<>();
        boolean clearSource = clearAt(start, start, model, tools, deadline);
        Location departure = start.getZ() < floor - EPSILON && !clearSource
                ? start.derive(null, null, floor, null) : null;
        double xyDistance = Math.hypot(target.getX() - start.getX(), target.getY() - start.getY());
        boolean verifiedCamera = imaging && target.getZ() < floor && hasCameraAperture(model, target);
        if (verifiedCamera && xyDistance >= 1.0) {
            // Only the final few mm within a measured camera aperture may descend below the
            // rapid-travel floor while XY is still moving. Check the ENTIRE swept tool envelope.
            double runIn = Math.min(5.0, xyDistance / 4.0);
            double fraction = runIn / xyDistance;
            Location entrance = target.derive(
                    target.getX() - (target.getX() - start.getX()) * fraction,
                    target.getY() - (target.getY() - start.getY()) * fraction,
                    floor, null);
            List<Location> cam = new ArrayList<>();
            if (departure != null) {
                cam.add(departure);
            }
            cam.add(entrance);
            cam.add(target);
            proposals.add(cam);
        }
        // Default: safe XY/Z approach down to the reference floor; when picking or placing,
        // keep the final vertical contact stroke. No lateral travel through feeder pockets.
        List<Location> normal = new ArrayList<>();
        if (departure != null) {
            normal.add(departure);
        }
        double approachZ = imaging ? Math.max(floor, target.getZ())
                : Math.max(floor, target.getZ() + 1.0);
        normal.add(target.derive(null, null, approachZ, null));
        if (Math.abs(target.getZ() - approachZ) > EPSILON) {
            normal.add(target);
        }
        proposals.add(normal);
        // Ordinary travel never crosses a changer, even above it. Offer four
        // conservative corridors around the union, expanded for both housings.
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double front = Double.POSITIVE_INFINITY, rear = Double.NEGATIVE_INFINITY;
        for (SafetyWorkbenchModel.SafetyObject object : model.getObjects()) {
            if (!object.enabled || !object.obstacle || !FastTravelClearance.restrictedZone(object)) { continue; }
            for (DynamicTool moving : tools) {
                if (!moving.isMoving()) { continue; }
                double dx = moving.reference.x - start.getX(), dy = moving.reference.y - start.getY();
                left = Math.min(left, object.x - object.clearance - moving.radiusX - dx - 1);
                right = Math.max(right, object.x + object.width + object.clearance + moving.radiusX - dx + 1);
                front = Math.min(front, object.y - object.clearance - moving.radiusY - dy - 1);
                rear = Math.max(rear, object.y + object.depth + object.clearance + moving.radiusY - dy + 1);
            }
        }
        if (Double.isFinite(left)) {
            double cruise = Math.max(floor, Math.max(start.getZ(), approachZ));
            double[] corridors = { left, right, front, rear };
            for (int corridorIndex = 0; corridorIndex < corridors.length; corridorIndex++) {
                double corridor = corridors[corridorIndex];
                boolean alongY = corridorIndex < 2;
                List<Location> detour = new ArrayList<>();
                detour.add(start.derive(null, null, cruise, null));
                detour.add(start.derive(alongY ? corridor : null, alongY ? null : corridor, cruise, null));
                detour.add(target.derive(alongY ? corridor : null, alongY ? null : corridor, cruise, null));
                detour.add(target.derive(null, null, cruise, null));
                detour.add(target);
                proposals.add(detour);
            }
        }
        for (List<Location> proposal : proposals) {
            if (System.nanoTime() > deadline) {
                break;
            }
            List<Location> candidate = compact(tool, start, proposal);
            if (candidate.isEmpty()) {
                continue;
            }
            Location previous = start;
            boolean hasLateralMotion = false;
            boolean safe = true;
            for (Location next : candidate) {
                double lateral = Math.hypot(next.getX() - previous.getX(), next.getY() - previous.getY());
                if (lateral > EPSILON) {
                    hasLateralMotion = true;
                    if (Math.min(previous.getZ(), next.getZ()) < floor - EPSILON) {
                        // Exception only for the last non-contact camera entry, through a
                        // specifically modelled aperture and limited to a 5 mm local descent.
                        boolean localCamera = verifiedCamera && proposal != normal && next == target
                                && previous.getZ() >= floor - EPSILON && lateral <= 5.0 + EPSILON;
                        boolean safeDeparture = previous == start && clearSource && next.getZ() >= floor - EPSILON;
                        if (!localCamera && !safeDeparture) {
                            safe = false;
                            break;
                        }
                    }
                }
                previous = next;
            }
            if (!safe || !hasLateralMotion) {
                continue;
            }
            List<Waypoint> points = new ArrayList<>();
            points.add(point(start));
            for (Location waypoint : candidate) {
                points.add(point(waypoint));
            }
            ValidationResult sweep = CoordinatedMotionValidator.validate(
                    model, points, tools, point(start), deadline, contact);
            if (!sweep.isSafe() || System.nanoTime() > deadline) {
                continue;
            }
            double seconds;
            try {
                seconds = estimate(tool, start, candidate, speed);
            } catch (Exception invalidCandidate) {
                // Another corridor may stay within the real controller travel limits.
                continue;
            }
            if (Double.isFinite(seconds) && (strict(model) || seconds + 0.001 < stockSeconds)) {
                String reason = proposals.indexOf(proposal) > proposals.indexOf(normal)
                        ? "安全平面联动：绕开换嘴专用区，保留末段垂直取放" : imaging
                        ? (proposal != normal
                                ? "已认证相机开口：低位最后 5 mm 联动 XY/Z"
                                : "已认证参考 Z：进入视觉前联动 XY/Z")
                        : "安全平面联动：提前到达提速终点，最后垂直取放";
                return new Plan(candidate, reason, stockSeconds, seconds);
            }
        }
        return null;
    }

    private static boolean hasCameraAperture(SafetyWorkbenchModel model, Location target) {
        for (SafetyWorkbenchModel.SafetyObject object : model.getObjects()) {
            if (object != null && object.enabled && object.obstacle
                    && object.type == SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA
                    && object.apertureWidth > 0 && object.apertureDepth > 0) {
                double xmin = object.x + (object.width - object.apertureWidth) / 2.0;
                double ymin = object.y + (object.depth - object.apertureDepth) / 2.0;
                if (target.getX() > xmin && target.getX() < xmin + object.apertureWidth
                        && target.getY() > ymin && target.getY() < ymin + object.apertureDepth) {
                    return true;
                }
            }
        }
        return false;
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

    static double clearHeight(Location endpoint, double safeZ, Location start, SafetyWorkbenchModel model, List<DynamicTool> tools, long deadline, boolean finalApproach) {
        double low = endpoint.getZ();
        if (clearAt(endpoint, start, model, tools, deadline)) {
            // Keep a short vertical final approach even on an unobstructed table.
            double approach = Math.min(safeZ, low + (finalApproach ? 1 : 0));
            return clearAt(endpoint.derive(null, null, approach, null), start, model, tools, deadline)
                    ? approach : low;
        }
        // Shared cam Z is not monotonically safer when the selected nozzle rises:
        // its sibling descends. Find the first clear bracket, even if full Safe-Z
        // is blocked. The final path is separately swept through every interval.
        double high = Double.NaN;
        int steps = Math.max(1, (int) Math.ceil((safeZ - low) / .5));
        for (int index = 1; index <= steps; index++) {
            if (System.nanoTime() > deadline) { return Double.NaN; }
            double candidate = Math.min(safeZ, endpoint.getZ() + index * .5);
            if (clearAt(endpoint.derive(null, null, candidate, null), start, model, tools, deadline)) {
                high = candidate;
                break;
            }
            low = candidate;
        }
        if (!Double.isFinite(high)) { return Double.NaN; }
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
        double padded = Math.min(safeZ, high + .5);
        return clearAt(endpoint.derive(null, null, padded, null), start, model, tools, deadline)
                ? padded : high;
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
