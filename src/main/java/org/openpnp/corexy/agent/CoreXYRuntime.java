package org.openpnp.corexy.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Motion;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.CoordinateAxis;
import org.openpnp.spi.HeadMountable;

public final class CoreXYRuntime {
    private static final Map<AbstractMotionPlanner, PlannerState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Method ADD_MOTION = findAddMotion();

    private static volatile CoreXYConfig config;
    private static volatile String openPnpVersion = "unknown";
    private static volatile String lastError = "not installed";

    public static final class PlanContext {
        private org.openpnp.corexy.precision.PrecisionRuntime.Plan precisionPlan;
        public final AxesLocation logicalCurrent;
        public final AxesLocation logicalTarget;
        public final AxesLocation physicalCurrent;
        public final AxesLocation physicalTarget;
        public final AxesLocation previousOffset;
        public final AxesLocation proposedOffset;
        public final int proposedAState;
        public final int proposedBState;
        final CoreXYSurfaceModel.Vector proposedAModelOffset;
        final CoreXYSurfaceModel.Vector proposedBModelOffset;
        final double proposedBModelSpeed;
        final double proposedBModelDistance;
        public final double proposedSpatialBWeight;
        public final boolean apply;
        public final boolean stateChanged;
        int queueSize;
        AxesLocation stockBacklash;

        PlanContext(AxesLocation logicalCurrent, AxesLocation logicalTarget,
                AxesLocation physicalCurrent, AxesLocation physicalTarget,
                AxesLocation previousOffset, AxesLocation proposedOffset,
                int proposedAState, int proposedBState,
                CoreXYSurfaceModel.Vector proposedAModelOffset,
                CoreXYSurfaceModel.Vector proposedBModelOffset,
                double proposedBModelSpeed, double proposedBModelDistance,
                double proposedSpatialBWeight,
                boolean apply, boolean stateChanged) {
            this.logicalCurrent = logicalCurrent;
            this.logicalTarget = logicalTarget;
            this.physicalCurrent = physicalCurrent;
            this.physicalTarget = physicalTarget;
            this.previousOffset = previousOffset;
            this.proposedOffset = proposedOffset;
            this.proposedAState = proposedAState;
            this.proposedBState = proposedBState;
            this.proposedAModelOffset = proposedAModelOffset;
            this.proposedBModelOffset = proposedBModelOffset;
            this.proposedBModelSpeed = proposedBModelSpeed;
            this.proposedBModelDistance = proposedBModelDistance;
            this.proposedSpatialBWeight = proposedSpatialBWeight;
            this.apply = apply;
            this.stateChanged = stateChanged;
        }
    }

    private static final class PlannerState {
        int aState;
        int bState;
        int homingDepth;
        AxesLocation activeOffset = new AxesLocation();
        CoreXYSurfaceModel.Vector modeledAOffset = zeroVector();
        CoreXYSurfaceModel.Vector modeledBOffset = zeroVector();
        double bModelSpeed;
        double bModelDistance;
        double spatialBWeight;
    }

    private CoreXYRuntime() {
    }

    static synchronized void configure(Path configPath, String detectedOpenPnpVersion) throws Exception {
        configure(configPath, detectedOpenPnpVersion, false);
    }

    static synchronized void configureImmediate(Path configPath,
            String detectedOpenPnpVersion) throws Exception {
        configure(configPath, detectedOpenPnpVersion, true);
    }

    private static void configure(Path configPath, String detectedOpenPnpVersion,
            boolean allowActiveCompensation) throws Exception {
        CoreXYConfig loaded = CoreXYConfig.load(configPath);
        org.openpnp.corexy.LegacyCompensationInterlock.assertLegacyLoadAllowed(loaded.enabled || loaded.dryRun);
        if (org.openpnp.corexy.precision.PrecisionRuntime.isActive()) {
            // Original Startup/reload scripts can reread a disabled profile; never reset the live model or Home state.
            config = loaded;
            openPnpVersion = detectedOpenPnpVersion;
            lastError = "";
            return;
        }
        boolean activeCompensation = hasActiveCompensation();
        if (activeCompensation && !allowActiveCompensation) {
            throw new IllegalStateException(
                    "Cannot reload CoreXY configuration while an offset is active. Home first.");
        }
        if (activeCompensation) {
            log("Immediate configuration switch accepted with an active offset; "
                    + "runtime A/B state will be cleared without a compensating move.");
        }
        try {
            boolean versionMismatch = !loaded.requiredOpenPnpVersion.equals(
                    detectedOpenPnpVersion);
            if (loaded.versionPolicy == CoreXYConfig.VersionPolicy.STRICT
                    && versionMismatch) {
                throw new IllegalStateException("OpenPnP version mismatch. Required "
                        + loaded.requiredOpenPnpVersion + ", detected " + detectedOpenPnpVersion + ".");
            }
            validateLoadedMachine(loaded);
            config = loaded;
            openPnpVersion = detectedOpenPnpVersion;
            lastError = "";
            STATES.clear();
            log("Configuration loaded: " + loaded.source
                    + ", enabled=" + loaded.enabled + ", dry-run=" + loaded.dryRun
                    + ", version-policy=" + loaded.versionPolicy.configValue);
            if (loaded.versionPolicy == CoreXYConfig.VersionPolicy.COMPATIBLE_API
                    && versionMismatch) {
                log("OpenPnP build differs from the last verified build "
                        + loaded.requiredOpenPnpVersion + "; compatible API checks passed for "
                        + detectedOpenPnpVersion + ".");
            }
        }
        catch (Exception e) {
            config = null;
            STATES.clear();
            setError(e);
            throw e;
        }
    }

    public static PlanContext beforePlan(AbstractMotionPlanner planner, HeadMountable hm,
            double speed, AxesLocation current, AxesLocation target) {
        org.openpnp.corexy.precision.PrecisionRuntime.observeHomeMotion(planner, current, target);
        if (org.openpnp.corexy.precision.PrecisionRuntime.isActive()) {
            org.openpnp.corexy.precision.PrecisionRuntime.Plan precision =
                    org.openpnp.corexy.precision.PrecisionRuntime.beforePlan(planner, hm, speed, current, target);
            PlanContext context = new PlanContext(current, target, precision.current, precision.target,
                    precision.previousOffset, precision.proposedOffset, 0, 0, zeroVector(), zeroVector(),
                    0, 0, 0, false, false);
            context.precisionPlan = precision;
            return context;
        }
        CoreXYConfig localConfig = config;
        PlannerState state = state(planner);
        AxesLocation previous = state.activeOffset;
        if (state.homingDepth > 0) {
            return unchanged(current, target, new AxesLocation(), state);
        }
        org.openpnp.corexy.LegacyCompensationInterlock.assertProductionAllowed();
        if (localConfig == null) {
            return unchanged(current, target, previous, state);
        }

        boolean operational = localConfig.enabled || localConfig.dryRun;
        if (!operational || (localConfig.requireHomed && !planner.isHomed())) {
            if (!previous.isEmpty()) {
                throw fail("CoreXY offset is active while compensation is not operational.", null);
            }
            return unchanged(current, target, new AxesLocation(), state);
        }

        Axis xAxis = planner.getMachine().getAxis(localConfig.xAxisId);
        Axis yAxis = planner.getMachine().getAxis(localConfig.yAxisId);
        validateAxes(xAxis, yAxis);

        AxesLocation segment = current.motionSegmentTo(target);
        double deltaX = segment.getCoordinate(xAxis);
        double deltaY = segment.getCoordinate(yAxis);
        double deltaA = deltaX + deltaY;
        double deltaB = deltaX - deltaY;
        int nextA = updateDirection(state.aState, deltaA, localConfig.thresholdA);
        int nextB = updateDirection(state.bState, deltaB, localConfig.thresholdB);
        CoreXYSurfaceModel.Vector nextAOffset;
        CoreXYSurfaceModel.Vector nextBOffset;
        double nextBModelSpeed = state.bModelSpeed;
        double nextBModelDistance = state.bModelDistance;
        double nextSpatialBWeight = state.spatialBWeight;
        AxesLocation predicted;
        if (localConfig.mode ==
                CoreXYConfig.Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET) {
            nextAOffset = dynamicOffset(state.modeledAOffset, localConfig.surfaceA,
                    speed, deltaA, localConfig.thresholdA);
            if (Math.abs(deltaB) >= localConfig.thresholdB && deltaB != 0.0) {
                if (deltaB > 0.0) {
                    nextBModelSpeed = speed;
                    nextBModelDistance = Math.abs(deltaB);
                }
                else {
                    nextBModelSpeed = 0.0;
                    nextBModelDistance = 0.0;
                }
            }
            double targetX = target.getCoordinate(xAxis);
            double targetY = target.getCoordinate(yAxis);
            nextSpatialBWeight = localConfig.spatialB.weight(targetX, targetY);
            if (nextB > 0 && nextBModelDistance > 0.0) {
                nextBOffset = localConfig.spatialB.predict(
                        nextBModelSpeed, nextBModelDistance, targetX, targetY);
            }
            else {
                nextBOffset = zeroVector();
            }
            nextBOffset = limitBForSafety(
                    nextAOffset, nextBOffset, localConfig.maxOffset);
            predicted = offsetFor(xAxis, yAxis, nextAOffset, nextBOffset);
        }
        else if (localConfig.mode == CoreXYConfig.Mode.DISTANCE_SPEED_STATE_OFFSET) {
            nextAOffset = dynamicOffset(state.modeledAOffset, localConfig.surfaceA,
                    speed, deltaA, localConfig.thresholdA);
            nextBOffset = dynamicOffset(state.modeledBOffset, localConfig.surfaceB,
                    speed, deltaB, localConfig.thresholdB);
            predicted = offsetFor(xAxis, yAxis, nextAOffset, nextBOffset);
        }
        else {
            nextAOffset = fixedOffset(localConfig.aPositive,
                    localConfig.aNegative, nextA);
            nextBOffset = fixedOffset(localConfig.bPositive,
                    localConfig.bNegative, nextB);
            predicted = offsetFor(xAxis, yAxis, nextAOffset, nextBOffset);
        }
        boolean apply = localConfig.enabled && !localConfig.dryRun;
        AxesLocation proposed = apply ? predicted : new AxesLocation();
        boolean changed = nextA != state.aState || nextB != state.bState
                || !same(nextAOffset, state.modeledAOffset)
                || !same(nextBOffset, state.modeledBOffset)
                || Math.abs(nextSpatialBWeight - state.spatialBWeight) > 1e-12;

        if (apply || localConfig.dryRun) {
            AxesLocation mainOffset = apply ? previous : new AxesLocation();
            AxesLocation finalOffset = apply ? proposed : predicted;
            validatePhysicalEndpoint(planner, hm, target.add(mainOffset), xAxis, yAxis,
                    localConfig.softLimitMargin, "main move at previous state");
            validatePhysicalEndpoint(planner, hm, target.add(finalOffset), xAxis, yAxis,
                    localConfig.softLimitMargin, "compensated endpoint");
        }
        if (!apply && changed) {
            log("Dry-run mode=" + localConfig.mode.configValue
                    + ", speed=" + decimal(speed)
                    + ", dA=" + decimal(deltaA) + ", dB=" + decimal(deltaB)
                    + " -> A=" + direction(nextA) + ", B=" + direction(nextB)
                    + (localConfig.mode ==
                            CoreXYConfig.Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET
                                    ? ", spatial-B-weight="
                                            + decimal(nextSpatialBWeight)
                                    : "")
                    + ", predicted=" + predicted);
        }

        return new PlanContext(
                current,
                target,
                current.add(previous),
                target.add(previous),
                previous,
                proposed,
                nextA,
                nextB,
                nextAOffset,
                nextBOffset,
                nextBModelSpeed,
                nextBModelDistance,
                nextSpatialBWeight,
                apply,
                changed);
    }

    public static AxesLocation afterPlan(AbstractMotionPlanner planner, HeadMountable hm,
            double speed, MotionOption[] options, AxesLocation stockEnd,
            PlanContext context) {
        if (context.precisionPlan != null) {
            return org.openpnp.corexy.precision.PrecisionRuntime.afterPlan(
                    planner, hm, speed, options, stockEnd, context.precisionPlan);
        }
        PlannerState state = state(planner);
        AxesLocation compensatedEnd = !context.apply ? stockEnd : stockEnd
                .subtract(context.previousOffset)
                .add(context.proposedOffset);
        if (context.apply && !same(context.previousOffset, context.proposedOffset)) {
            CoreXYConfig localConfig = requireConfig();
            invokeAddMotion(planner, hm, Math.min(speed, localConfig.speedFactor),
                    stockEnd, compensatedEnd, Motion.optionFlags(options));
            log("Applied state A=" + direction(context.proposedAState)
                    + ", B=" + direction(context.proposedBState)
                    + ", offset=" + context.proposedOffset);
        }
        // Commit state only after the extra motion was successfully enqueued.
        state.aState = context.proposedAState;
        state.bState = context.proposedBState;
        state.modeledAOffset = context.proposedAModelOffset;
        state.modeledBOffset = context.proposedBModelOffset;
        state.bModelSpeed = context.proposedBModelSpeed;
        state.bModelDistance = context.proposedBModelDistance;
        state.spatialBWeight = context.proposedSpatialBWeight;
        state.activeOffset = context.apply ? context.proposedOffset : new AxesLocation();
        return compensatedEnd;
    }

    /** Stock OpenPnP has no native transaction wrapper. Advice delegates the same rollback here. */
    public static void beginTransaction(AbstractMotionPlanner planner, PlanContext context) {
        try {
            java.lang.reflect.Field queue = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
            java.lang.reflect.Field backlash = AbstractMotionPlanner.class.getDeclaredField("lastDirectionalBacklashOffset");
            queue.setAccessible(true);
            backlash.setAccessible(true);
            context.queueSize = ((java.util.List<?>) queue.get(planner)).size();
            context.stockBacklash = (AxesLocation) backlash.get(planner);
        }
        catch (ReflectiveOperationException failure) {
            throw fail("Cannot start a stock motion transaction.", failure);
        }
    }

    public static void rollbackTransaction(AbstractMotionPlanner planner, PlanContext context) {
        if (context == null || context.stockBacklash == null) {
            return;
        }
        try {
            java.lang.reflect.Field queue = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
            java.lang.reflect.Field backlash = AbstractMotionPlanner.class.getDeclaredField("lastDirectionalBacklashOffset");
            queue.setAccessible(true);
            backlash.setAccessible(true);
            java.util.List<?> motions = (java.util.List<?>) queue.get(planner);
            while (motions.size() > context.queueSize) {
                motions.remove(motions.size() - 1);
            }
            backlash.set(planner, context.stockBacklash);
        }
        catch (ReflectiveOperationException failure) {
            throw fail("Cannot roll back the stock motion queue.", failure);
        }
    }

    public static AxesLocation adjustGlobalOffsets(AbstractMotionPlanner planner, AxesLocation logical) {
        AxesLocation active = activeOffset(planner);
        return new AxesLocation(logical.getAxes(),
                axis -> logical.getLengthCoordinate(axis).add(active.getLengthCoordinate(axis)));
    }

    public static void restoreGlobalOffsetCoordinates(AxesLocation logical) {
        if (logical != null) {
            logical.setToCoordinates();
        }
    }

    public static void correctReportedCoordinates(AbstractMotionPlanner planner) {
        AxesLocation active = activeOffset(planner);
        for (Axis axis : active.getAxes()) {
            if (axis instanceof CoordinateAxis) {
                CoordinateAxis coordinateAxis = (CoordinateAxis) axis;
                coordinateAxis.setLengthCoordinate(coordinateAxis.getLengthCoordinate()
                        .subtract(active.getLengthCoordinate(axis)));
            }
        }
        int settling = org.openpnp.corexy.precision.PrecisionRuntime.getSettlingMillis(planner);
        if (settling > 0 && planner.isHomed()) {
            try { Thread.sleep(settling); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail("Compensation settling was interrupted.", e);
            }
        }
    }

    public static void reset(AbstractMotionPlanner planner, String reason) {
        org.openpnp.corexy.precision.PrecisionRuntime.reset(planner);
        PlannerState state = state(planner);
        // unhome() is called inside ReferenceMachine.home(); preserve the outer bypass.
        clearState(state);
        log("Runtime state reset: " + reason);
    }

    public static void beginHome(AbstractMotionPlanner planner) {
        org.openpnp.corexy.precision.PrecisionRuntime.beginHome(planner);
        PlannerState state = state(planner);
        state.homingDepth++;
        clearState(state);
        log("Home bypass entered; depth=" + state.homingDepth);
    }

    public static void endHome(AbstractMotionPlanner planner) {
        org.openpnp.corexy.precision.PrecisionRuntime.endHome(planner);
        PlannerState state = state(planner);
        if (state.homingDepth > 0) {
            state.homingDepth--;
        }
        clearState(state);
        log("Home bypass exited; depth=" + state.homingDepth
                + "; runtime state reset");
    }

    /** Runs the final marker alignment before the machine publishes homed=true. */
    public static void beforeMachineHomed(AbstractMotionPlanner planner) throws Exception {
        if (state(planner).homingDepth <= 0 || !planner.isHomed()) {
            return;
        }
        try {
            org.openpnp.corexy.CoreXYIntegration.beforeHomeCompletion(planner);
        }
        catch (Exception failure) {
            planner.unhome();
            planner.getMachine().setHomed(false);
            throw failure;
        }
    }

    private static void clearState(PlannerState state) {
        state.aState = 0;
        state.bState = 0;
        state.activeOffset = new AxesLocation();
        state.modeledAOffset = zeroVector();
        state.modeledBOffset = zeroVector();
        state.bModelSpeed = 0.0;
        state.bModelDistance = 0.0;
        state.spatialBWeight = 0.0;
    }

    static String status() {
        if (org.openpnp.corexy.precision.PrecisionRuntime.isActive()) {
            return "installed=true\nconfigured=true\nenabled=true\nmode=advanced-precision\nlayers="
                    + org.openpnp.corexy.precision.PrecisionRuntime.getModel().getLayers()
                    + "\nlegacy-enabled=false\nlegacy-suspended=true";
        }
        CoreXYConfig localConfig = config;
        StringBuilder text = new StringBuilder();
        text.append("installed=true");
        text.append("\nopenpnp-version=").append(openPnpVersion);
        if (localConfig == null) {
            text.append("\nconfigured=false");
            text.append("\nerror=").append(lastError);
            return text.toString();
        }
        text.append("\nconfigured=true");
        text.append("\nconfig=").append(localConfig.source);
        text.append("\nmode=").append(localConfig.mode.configValue);
        text.append("\nversion-policy=").append(localConfig.versionPolicy.configValue);
        text.append("\nlast-verified-version=").append(
                localConfig.requiredOpenPnpVersion);
        text.append("\nenabled=").append(localConfig.enabled);
        text.append("\ndry-run=").append(localConfig.dryRun);
        text.append("\nrequire-homed=").append(localConfig.requireHomed);
        synchronized (STATES) {
            for (Map.Entry<AbstractMotionPlanner, PlannerState> entry : STATES.entrySet()) {
                PlannerState state = entry.getValue();
                text.append("\nplanner=").append(entry.getKey().getClass().getName());
                text.append("\nhomed=").append(entry.getKey().isHomed());
                text.append("\nhoming-bypass=").append(state.homingDepth > 0);
                text.append("\nA-state=").append(direction(state.aState));
                text.append("\nB-state=").append(direction(state.bState));
                text.append("\nactive-offset=").append(state.activeOffset);
                text.append("\nmodeled-A-offset=(")
                        .append(decimal(state.modeledAOffset.x)).append(",")
                        .append(decimal(state.modeledAOffset.y)).append(")");
                text.append("\nmodeled-B-offset=(")
                        .append(decimal(state.modeledBOffset.x)).append(",")
                        .append(decimal(state.modeledBOffset.y)).append(")");
                if (localConfig.mode ==
                        CoreXYConfig.Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET) {
                    text.append("\nspatial-B-weight=")
                            .append(decimal(state.spatialBWeight));
                    text.append("\nspatial-B-query-speed=")
                            .append(decimal(state.bModelSpeed));
                    text.append("\nspatial-B-query-distance=")
                            .append(decimal(state.bModelDistance));
                }
                break;
            }
        }
        return text.toString();
    }

    public static boolean isLegacyEnabled() {
        return config != null && config.enabled && !config.dryRun;
    }

    public static boolean isLegacyDryRun() {
        return config != null && config.dryRun;
    }

    public static Path getLegacyConfigurationPath() {
        return config == null ? null : config.source;
    }

    public static void requireNeutralLegacy() {
        synchronized (STATES) {
            for (PlannerState state : STATES.values()) {
                if (!same(state.activeOffset, new AxesLocation())) {
                    throw new IllegalStateException("旧 CoreXY 补偿仍有活动偏移，请先 Home 再切换。");
                }
            }
        }
    }

    public static synchronized void suspendLegacy() {
        requireNeutralLegacy();
        config = null;
        STATES.clear();
        lastError = "旧插件补偿已由实验版自动停用。";
    }

    static void setError(Throwable error) {
        lastError = error == null ? "unknown error" : error.toString();
        log("ERROR: " + lastError);
    }

    private static PlanContext unchanged(AxesLocation current, AxesLocation target,
            AxesLocation previous, PlannerState state) {
        return new PlanContext(current, target, current.add(previous), target.add(previous),
                previous, previous, state.aState, state.bState,
                state.modeledAOffset, state.modeledBOffset,
                state.bModelSpeed, state.bModelDistance, state.spatialBWeight,
                false, false);
    }

    private static int updateDirection(int previous, double movement, double threshold) {
        if (movement != 0.0 && Math.abs(movement) >= threshold) {
            return movement > 0.0 ? 1 : -1;
        }
        return previous;
    }

    private static CoreXYSurfaceModel.Vector dynamicOffset(
            CoreXYSurfaceModel.Vector previous, CoreXYSurfaceModel model,
            double speed, double movement, double threshold) {
        if (Math.abs(movement) < threshold || movement == 0) {
            return previous;
        }
        return movement > 0 ? model.predict(speed, Math.abs(movement)) : zeroVector();
    }

    private static CoreXYSurfaceModel.Vector fixedOffset(
            CoreXYConfig.Offset positive, CoreXYConfig.Offset negative, int state) {
        CoreXYConfig.Offset value = state > 0 ? positive : negative;
        return state == 0 ? zeroVector() : new CoreXYSurfaceModel.Vector(value.x, value.y);
    }

    private static CoreXYSurfaceModel.Vector limitBForSafety(
            CoreXYSurfaceModel.Vector a, CoreXYSurfaceModel.Vector b,
            double maximum) {
        if (Math.hypot(a.x + b.x, a.y + b.y) <= maximum + 1e-12) {
            return b;
        }
        if (Math.hypot(a.x, a.y) > maximum + 1e-12) {
            throw fail("CoreXY A offset exceeds the runtime safety limit.", null);
        }
        double low = 0.0;
        double high = 1.0;
        for (int iteration = 0; iteration < 60; iteration++) {
            double scale = (low + high) * 0.5;
            double magnitude = Math.hypot(
                    a.x + scale * b.x, a.y + scale * b.y);
            if (magnitude <= maximum) {
                low = scale;
            }
            else {
                high = scale;
            }
        }
        CoreXYSurfaceModel.Vector limited =
                new CoreXYSurfaceModel.Vector(low * b.x, low * b.y);
        log("Spatial B offset limited by safety.max-offset.mm; scale="
                + decimal(low));
        return limited;
    }

    private static AxesLocation offsetFor(Axis xAxis, Axis yAxis,
            CoreXYSurfaceModel.Vector a, CoreXYSurfaceModel.Vector b) {
        return new AxesLocation(xAxis, a.x + b.x)
                .put(new AxesLocation(yAxis, a.y + b.y));
    }

    private static void validateAxes(Axis xAxis, Axis yAxis) {
        if (!(xAxis instanceof ControllerAxis) || !(yAxis instanceof ControllerAxis)
                || xAxis == yAxis
                || ((ControllerAxis) xAxis).getDriver() == null
                || ((ControllerAxis) xAxis).getDriver() != ((ControllerAxis) yAxis).getDriver()) {
            throw fail("Configured X and Y must be different controller axes on the same driver.", null);
        }
    }

    private static void validateLoadedMachine(CoreXYConfig loaded) {
        if (!Configuration.isInstanceInitialized()
                || Configuration.get().getMachine() == null) {
            throw fail("OpenPnP machine configuration is not loaded.", null);
        }
        Object planner = Configuration.get().getMachine().getMotionPlanner();
        if (!(planner instanceof AbstractMotionPlanner)) {
            throw fail("CoreXY Agent requires an AbstractMotionPlanner.", null);
        }
        try {
            Method embedded = planner.getClass().getMethod("isCoreXYCompensationEnabled");
            if ((loaded.enabled || loaded.dryRun) && Boolean.TRUE.equals(embedded.invoke(planner))) {
                throw fail("Embedded CoreXY compensation was detected in this OpenPnP build. "
                        + "Do not combine it with the Agent.", null);
            }
        }
        catch (NoSuchMethodException expectedForOfficialOpenPnp) {
            // The official planner has no embedded CoreXY API.
        }
        catch (ReflectiveOperationException unreadable) {
            throw fail("Cannot inspect the existing CoreXY planner.", unreadable);
        }
        AbstractMotionPlanner motionPlanner = (AbstractMotionPlanner) planner;
        validateAxes(
                motionPlanner.getMachine().getAxis(loaded.xAxisId),
                motionPlanner.getMachine().getAxis(loaded.yAxisId));
    }

    private static void validatePhysicalEndpoint(AbstractMotionPlanner planner, HeadMountable hm,
            AxesLocation endpoint, Axis xAxis, Axis yAxis, double margin, String purpose) {
        if (!planner.isValidLocation(hm, endpoint)) {
            throw fail("CoreXY " + purpose + " violates an OpenPnP soft limit.", null);
        }
        validateAxisMargin(endpoint, xAxis, margin, purpose);
        validateAxisMargin(endpoint, yAxis, margin, purpose);
    }

    private static void validateAxisMargin(AxesLocation endpoint, Axis axis,
            double margin, String purpose) {
        if (!(axis instanceof ReferenceControllerAxis)) {
            return;
        }
        ReferenceControllerAxis referenceAxis = (ReferenceControllerAxis) axis;
        double coordinate = endpoint.getCoordinate(axis);
        if (referenceAxis.isSoftLimitLowEnabled()) {
            double low = referenceAxis.getSoftLimitLow()
                    .convertToUnits(LengthUnit.Millimeters).getValue();
            if (coordinate < low + margin) {
                throw fail("CoreXY " + purpose + " is inside the low soft-limit margin of axis "
                        + referenceAxis.getName() + ".", null);
            }
        }
        if (referenceAxis.isSoftLimitHighEnabled()) {
            double high = referenceAxis.getSoftLimitHigh()
                    .convertToUnits(LengthUnit.Millimeters).getValue();
            if (coordinate > high - margin) {
                throw fail("CoreXY " + purpose + " is inside the high soft-limit margin of axis "
                        + referenceAxis.getName() + ".", null);
            }
        }
    }

    private static PlannerState state(AbstractMotionPlanner planner) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(planner, ignored -> new PlannerState());
        }
    }

    private static AxesLocation activeOffset(AbstractMotionPlanner planner) {
        if (org.openpnp.corexy.precision.PrecisionRuntime.isActive()) {
            return org.openpnp.corexy.precision.PrecisionRuntime.activeOffset(planner);
        }
        return state(planner).activeOffset;
    }

    private static boolean hasActiveCompensation() {
        if (org.openpnp.corexy.precision.PrecisionRuntime.isActive()) {
            throw new IllegalStateException("Home and disable the precision profile before loading a legacy profile.");
        }
        synchronized (STATES) {
            for (PlannerState state : STATES.values()) {
                if (!state.activeOffset.isEmpty()
                        && !same(state.activeOffset, new AxesLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CoreXYConfig requireConfig() {
        CoreXYConfig value = config;
        if (value == null) {
            throw fail("CoreXY configuration is not loaded.", null);
        }
        return value;
    }

    private static Method findAddMotion() {
        try {
            Method method = AbstractMotionPlanner.class.getDeclaredMethod(
                    "addMotion",
                    HeadMountable.class,
                    double.class,
                    AxesLocation.class,
                    AxesLocation.class,
                    int.class);
            method.setAccessible(true);
            return method;
        }
        catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void invokeAddMotion(AbstractMotionPlanner planner, HeadMountable hm,
            double speed, AxesLocation start, AxesLocation end, int options) {
        try {
            ADD_MOTION.invoke(planner, hm, speed, start, end, options);
        }
        catch (IllegalAccessException | InvocationTargetException e) {
            throw fail("Unable to add CoreXY compensation motion.", e);
        }
    }

    public static void appendPrecisionMotion(AbstractMotionPlanner planner, HeadMountable hm,
            double speed, AxesLocation from, AxesLocation to, int flags) {
        invokeAddMotion(planner, hm, speed, from, to, flags);
    }

    private static boolean same(AxesLocation first, AxesLocation second) {
        return first.matches(second) && second.matches(first);
    }

    private static boolean same(CoreXYSurfaceModel.Vector first,
            CoreXYSurfaceModel.Vector second) {
        return Math.abs(first.x - second.x) <= 1e-12
                && Math.abs(first.y - second.y) <= 1e-12;
    }

    private static CoreXYSurfaceModel.Vector zeroVector() {
        return new CoreXYSurfaceModel.Vector(0, 0);
    }

    private static IllegalStateException fail(String message, Throwable cause) {
        lastError = message;
        return new IllegalStateException(message, cause);
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.US, "%.6f", value);
    }

    private static String direction(int value) {
        return value > 0 ? "+" : value < 0 ? "-" : "0";
    }

    private static void log(String message) {
        System.out.println("[OpenPnP-CoreXY] " + message);
    }
}
