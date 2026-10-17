package org.openpnp.corexy.precision;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Motion;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.HeadMountable;

/** Transactional advanced correction state. Model changes require an empty offset and Home. */
public final class PrecisionRuntime {
    private static final Map<AbstractMotionPlanner, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<AbstractMotionPlanner, State>());
    private static volatile PrecisionModel model;
    private static volatile AbstractMotionPlanner owner;
    public interface TemperatureSource {
        double read() throws Exception;
    }
    private static volatile TemperatureSource temperatureSource;
    public interface Guard {
        void check() throws Exception;
    }
    private static volatile Guard productionGuard, controllerInitializer;
    private static volatile String xId, yId;
    private static volatile double temperature = Double.NaN;
    private static volatile long temperatureTimestamp;
    private static volatile int settleMillis;
    private static volatile double speedFactor = .25;
    private static volatile double margin;

    private static final class State {
        ReversalState a = new ReversalState(), b = new ReversalState();
        AxesLocation offset = new AxesLocation();
        int homeDepth;
        boolean visualHoming;
        boolean retainHomedState;
        boolean controllerInitialized;
        boolean settlingPending;
        double xySpeed = .5, xyAcceleration = 1;
        java.util.List<double[]> homeTrace = new java.util.ArrayList<>();
    }
    public static final class Plan {
        public final AxesLocation current, target, previousOffset, proposedOffset;
        final ReversalState a, b;
        final boolean applied;
        double xySpeed, xyAcceleration;
        boolean xyMoved;
        Plan(AxesLocation current, AxesLocation target, AxesLocation previousOffset,
                AxesLocation proposedOffset, ReversalState a, ReversalState b, boolean applied) {
            this.current = current;
            this.target = target;
            this.previousOffset = previousOffset;
            this.proposedOffset = proposedOffset;
            this.a = a;
            this.b = b;
            this.applied = applied;
        }
    }

    public static final class Settings {
        private final PrecisionModel selected = model;
        private final AbstractMotionPlanner planner = owner;
        private final String axisX = xId, axisY = yId;
        private final int settling = settleMillis;
        private final double finalSpeed = speedFactor, softMargin = margin;
        private final TemperatureSource source = temperatureSource;
        private final Guard guard = productionGuard, initializer = controllerInitializer;
    }

    public static synchronized Settings settings() {
        return model == null ? null : new Settings();
    }

    public static void restore(Settings settings) {
        if (settings != null) {
            activate(settings.planner, settings.selected, settings.axisX, settings.axisY,
                    settings.settling, settings.finalSpeed, settings.softMargin);
            setTemperatureSource(settings.source);
            setProductionGuards(settings.guard, settings.initializer);
        }
    }

    private PrecisionRuntime() { }

    /** Production uses the machine's configured travel, including calibrated edge tool stations. */
    public static void activate(AbstractMotionPlanner planner, PrecisionModel candidate,
            String axisX, String axisY, int settling, double finalSpeed) {
        activate(planner, candidate, axisX, axisY, settling, finalSpeed, 0);
    }

    public static synchronized void activate(AbstractMotionPlanner planner, PrecisionModel candidate,
            String axisX, String axisY, int settling, double finalSpeed, double softMargin) {
        if (candidate == null || axisX == null || axisY == null || axisX.equals(axisY)
                || settling < 0 || settling > 5000 || !Double.isFinite(finalSpeed)
                || finalSpeed <= 0 || finalSpeed > 1 || !Double.isFinite(softMargin) || softMargin < 0) {
            throw new IllegalArgumentException("Invalid runtime profile.");
        }
        requireZeroOffset();
        candidate.validate();
        if (CoreXYRuntime.isLegacyEnabled()) {
            throw new IllegalStateException("Disable the original CoreXY profile before enabling an advanced profile.");
        }
        if (planner instanceof org.openpnp.corexy.CoreXYAdvancedMotionPlanner
                && ((org.openpnp.corexy.CoreXYAdvancedMotionPlanner) planner).isCoreXYCompensationEnabled()) {
            throw new IllegalStateException("Disable the original CoreXY planner correction before enabling an advanced profile.");
        }
        Axis x = planner.getMachine().getAxis(axisX), y = planner.getMachine().getAxis(axisY);
        if (!(x instanceof ControllerAxis) || !(y instanceof ControllerAxis)
                || ((ControllerAxis) x).getDriver() == null
                || ((ControllerAxis) x).getDriver() != ((ControllerAxis) y).getDriver()) {
            throw new IllegalArgumentException("CoreXY requires two Cartesian controller axes on one driver.");
        }
        for (Axis axis : new Axis[] {x, y}) {
            if (axis instanceof ReferenceControllerAxis
                    && ((ReferenceControllerAxis) axis).getBacklashCompensationMethod()
                        != ReferenceControllerAxis.BacklashCompensationMethod.None) {
                throw new IllegalStateException("Native axis backlash would duplicate CoreXY compensation.");
            }
        }
        owner = planner;
        model = candidate;
        xId = axisX;
        yId = axisY;
        settleMillis = settling;
        speedFactor = finalSpeed;
        margin = softMargin;
        State selectedState = state(planner);
        initializeState(selectedState, candidate);
    }

    public static synchronized void deactivate() { requireZeroOffset();
        model = null;
        owner = null;
        temperatureSource = null;
        productionGuard = null;
        controllerInitializer = null;
        temperatureTimestamp = 0;
        synchronized (STATES) {
            for (State state : STATES.values()) {
                clear(state);
            }
        }
        }
    public static boolean isActive() { return model != null;
        }
    public static PrecisionModel getModel() { return model;
        }
    public static void setTemperatureSource(TemperatureSource source) {
        temperatureSource = source;
        temperatureTimestamp = 0;
    }
    public static void setProductionGuards(Guard guard, Guard initializer) {
        productionGuard = guard;
        controllerInitializer = initializer;
    }
    public static void setTemperature(double value) {
        if (!Double.isFinite(value) || value < -50 || value > 150) { throw new IllegalArgumentException("Invalid temperature.");
            }
        temperature = value;
        temperatureTimestamp = System.nanoTime();
    }
    public static boolean needsTemperature() {
        return model != null && model.getLayers().contains(PrecisionModel.Layer.THERMAL_DRIFT);
    }

    public static Plan beforePlan(AbstractMotionPlanner planner, HeadMountable hm, double speed,
            AxesLocation current, AxesLocation target) {
        State s = state(planner);
        PrecisionModel selected = model;
        if (selected == null || planner != owner || (s.homeDepth > 0 && !s.retainHomedState)
                || !planner.isHomed()) {
            return new Plan(current, target, new AxesLocation(), new AxesLocation(), s.a, s.b, false);
        }
        if (ModelReferencedDatum.isRawStage()) {
            if (!s.offset.matches(AxesLocation.zero)) {
                throw new IllegalStateException("Raw datum sampling requires a neutral compensation offset.");
            }
            ReversalState a = s.a.copy(), b = s.b.copy();
            AxesLocation delta = current.motionSegmentTo(target);
            double dx = delta.getCoordinate(planner.getMachine().getAxis(xId));
            double dy = delta.getCoordinate(planner.getMachine().getAxis(yId));
            a.advance(dx + dy, selected.getTransitionA());
            b.advance(dx - dy, selected.getTransitionB());
            return new Plan(current, target, s.offset, s.offset, a, b, false);
        }
        try {
            if (productionGuard != null) {
                productionGuard.check();
            }
            if (!s.controllerInitialized && controllerInitializer != null) {
                controllerInitializer.check();
                s.controllerInitialized = true;
            }
        }
        catch (Exception failure) {
            throw new IllegalStateException("Calibrated configuration or controller state changed; Home and review the profile.", failure);
        }
        Axis x = planner.getMachine().getAxis(xId), y = planner.getMachine().getAxis(yId);
        if (x == null || y == null) { throw new IllegalStateException("Calibrated axes were removed.");
            }
        ReversalState a = s.a.copy(), b = s.b.copy();
        AxesLocation delta = current.motionSegmentTo(target);
        double dx = delta.getCoordinate(x), dy = delta.getCoordinate(y);
        boolean xyMoved = Math.abs(dx) + Math.abs(dy) > 1e-12;
        if (!xyMoved && s.offset.matches(AxesLocation.zero)) {
            return new Plan(current, target, s.offset, s.offset, s.a, s.b, false);
        }
        a.advance(dx + dy, selected.getTransitionA());
        b.advance(dx - dy, selected.getTransitionB());
        if (needsTemperature() && temperatureSource != null && (temperatureTimestamp == 0
                || System.nanoTime() - temperatureTimestamp > 5_000_000_000L)) {
            try {
                setTemperature(temperatureSource.read());
            }
            catch (Exception failure) {
                throw new IllegalStateException("Temperature telemetry failed.", failure);
            }
        }
        if (needsTemperature() && (temperatureTimestamp == 0
                || System.nanoTime() - temperatureTimestamp > 120_000_000_000L)) {
            throw new IllegalStateException("Temperature telemetry expired; compensation cannot be evaluated.");
        }
        double acceleration = x instanceof ReferenceControllerAxis
                ? ((ReferenceControllerAxis) x).getAccelerationPerSecond2().convertToUnits(LengthUnit.Millimeters).getValue() : 1;
        double xySpeed = xyMoved ? speed : s.xySpeed;
        acceleration = xyMoved ? acceleration : s.xyAcceleration;
        org.openpnp.model.Location pose = hm == null ? new org.openpnp.model.Location(LengthUnit.Millimeters)
                : hm.toHeadMountableLocation(hm.toTransformed(target)).convertToUnits(LengthUnit.Millimeters);
        PrecisionSample query = new PrecisionSample("runtime", hm == null ? "" : hm.getId(),
                target.getCoordinate(x), target.getCoordinate(y), pose.getZ(), pose.getRotation(), xySpeed, acceleration,
                temperature, 0, dx + dy, dx - dy, a.getTravel(), b.getTravel(), settleMillis,
                a.getDirection(), b.getDirection(), 0, 0);
        double[] correction = selected.predict(query, a.getValue(), b.getValue());
        AxesLocation proposed = new AxesLocation(x, correction[0]).put(new AxesLocation(y, correction[1]));
        validatePhysical(planner, hm, current.add(s.offset), "运动起点");
        validate(planner, hm, target.add(s.offset), x, y, "主运动终点");
        validate(planner, hm, target.add(proposed), x, y, "补偿终点");
        Plan plan = new Plan(current.add(s.offset), target.add(s.offset), s.offset, proposed, a, b, true);
        plan.xySpeed = xySpeed;
        plan.xyAcceleration = acceleration;
        plan.xyMoved = xyMoved;
        return plan;
    }

    public static AxesLocation afterPlan(AbstractMotionPlanner planner, HeadMountable hm, double speed,
            MotionOption[] options, AxesLocation stockEnd, Plan plan) {
        if (!plan.applied) {
            if (ModelReferencedDatum.isRawStage() && model != null && owner == planner) {
                state(planner).a = plan.a;
                state(planner).b = plan.b;
            }
            return stockEnd;
            }
        AxesLocation end = stockEnd.subtract(plan.previousOffset).add(plan.proposedOffset);
        validate(planner, hm, end, planner.getMachine().getAxis(xId), planner.getMachine().getAxis(yId), "最终路径终点");
        if (!plan.previousOffset.matches(plan.proposedOffset) || !plan.proposedOffset.matches(plan.previousOffset)) {
            CoreXYRuntime.appendPrecisionMotion(planner, hm, Math.min(speed, speedFactor), stockEnd,
                    end, Motion.optionFlags(options));
        }
        State s = state(planner);
        s.a = plan.a;
        s.b = plan.b;
        s.offset = plan.proposedOffset;
        s.xySpeed = plan.xySpeed;
        s.xyAcceleration = plan.xyAcceleration;
        s.settlingPending |= plan.xyMoved || !plan.previousOffset.matches(plan.proposedOffset);
        return end;
    }

    public static AxesLocation activeOffset(AbstractMotionPlanner planner) { return state(planner).offset;
        }
    public static int getSettlingMillis(AbstractMotionPlanner planner) {
        State state = state(planner);
        if (model == null || owner != planner || (state.homeDepth > 0 && !state.retainHomedState)
                || !state.settlingPending) {
            return 0;
        }
        state.settlingPending = false;
        return settleMillis;
        }
    public static void beginHome(AbstractMotionPlanner planner) {
        State s = state(planner);
        if (s.visualHoming) {
            throw new IllegalStateException("A new Home cannot begin during visual Home alignment.");
        }
        s.retainHomedState = false;
        if (s.homeDepth == 0 || s.homeDepth == 1) {
            s.homeTrace.clear();
        }
        s.homeDepth++;
        clear(s);
    }
    public static void endHome(AbstractMotionPlanner planner) {
        State s = state(planner);
        s.homeDepth = Math.max(0, s.homeDepth - 1);
        if (s.homeDepth == 0 && s.retainHomedState && planner.isHomed()
                && model != null && owner == planner) {
            // The final visual stage already replayed mechanical Home and then
            // made compensated moves. Preserve their offset and reversal state.
            s.retainHomedState = false;
            return;
        }
        clear(s);
        if (s.homeDepth == 0 && model != null && owner == planner) {
            initializeState(s, model);
        }
    }
    public static void reset(AbstractMotionPlanner planner) {
        State s = state(planner);
        s.retainHomedState = false;
        if (s.homeDepth == 0) {
            s.homeTrace.clear();
        }
        clear(s);
    }

    /**
     * Enables the loaded profile for the final visual phase of Home only.
     * Mechanical switch seeking remains uncompensated. Call complete() after
     * the marker has been aligned, coordinates rebased and motion has stopped.
     */
    public static CompensatedHomingScope compensatedHoming(AbstractMotionPlanner planner) {
        return new CompensatedHomingScope(planner);
    }

    public static final class CompensatedHomingScope implements AutoCloseable {
        private final AbstractMotionPlanner planner;
        private final State selected;
        private final int mechanicalDepth;
        private boolean complete;
        private boolean closed;

        private CompensatedHomingScope(AbstractMotionPlanner planner) {
            this.planner = planner;
            selected = state(planner);
            if (model == null || owner != planner || !planner.isHomed()
                    || selected.homeDepth != 1 || selected.visualHoming) {
                throw new IllegalStateException("Visual compensation requires the final phase of mechanical Home.");
            }
            mechanicalDepth = selected.homeDepth;
            initializeState(selected, model);
            selected.retainHomedState = false;
            selected.visualHoming = true;
            selected.homeDepth = 0;
        }

        public void complete() {
            if (closed || !planner.isHomed() || !selected.visualHoming || model == null || owner != planner) {
                throw new IllegalStateException("Visual Home alignment cannot be committed.");
            }
            complete = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            selected.visualHoming = false;
            selected.homeDepth = mechanicalDepth;
            selected.retainHomedState = complete && planner.isHomed() && model != null && owner == planner;
            if (!selected.retainHomedState) {
                // A failed or cancelled visual stage must never publish a usable Home.
                planner.unhome();
            }
        }
    }

    /** Uses a Home marker at an axis boundary after Home, without resetting the loaded model state. */
    public static ReferenceAlignmentScope referenceAlignment(AbstractMotionPlanner planner) {
        return new ReferenceAlignmentScope(planner);
    }

    public static final class ReferenceAlignmentScope implements AutoCloseable {
        private final State selected;
        private boolean closed;

        private ReferenceAlignmentScope(AbstractMotionPlanner planner) {
            selected = state(planner);
            if (model == null || owner != planner || !planner.isHomed()
                    || selected.homeDepth != 0 || selected.visualHoming) {
                throw new IllegalStateException("Reference alignment requires an active profile on a homed planner.");
            }
            selected.visualHoming = true;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                selected.visualHoming = false;
            }
        }
    }
    public static void observeHomeMotion(AbstractMotionPlanner planner, AxesLocation current, AxesLocation target) {
        State state = state(planner);
        if (state.homeDepth == 0 || state.retainHomedState) {
            return;
        }
        try {
            org.openpnp.spi.Camera camera = planner.getMachine().getDefaultHead().getDefaultCamera();
            Axis x = camera.getAxisX(), y = camera.getAxisY();
            double dx = target.getCoordinate(x) - current.getCoordinate(x);
            double dy = target.getCoordinate(y) - current.getCoordinate(y);
            if (Math.abs(dx) + Math.abs(dy) > 1e-12) {
                state.homeTrace.add(new double[] {dx, dy});
            }
        }
        catch (Exception unavailable) {
            state.homeTrace.clear();
        }
    }
    public static java.util.List<double[]> homeTrace(AbstractMotionPlanner planner) {
        java.util.List<double[]> result = new java.util.ArrayList<>();
        for (double[] segment : state(planner).homeTrace) {
            result.add(segment.clone());
        }
        return result;
    }
    private static void initializeState(State state, PrecisionModel model) {
        clear(state);
        state.a.initialize(model.getHomeA());
        state.b.initialize(model.getHomeB());
        for (double[] segment : state.homeTrace) {
            state.a.advance(segment[0] + segment[1], model.getTransitionA());
            state.b.advance(segment[0] - segment[1], model.getTransitionB());
        }
    }
    private static void clear(State s) { s.a.reset();
        s.b.reset();
        s.offset = new AxesLocation();
        s.controllerInitialized = false;
        s.settlingPending = false;
        s.xySpeed = .5;
        s.xyAcceleration = 1;
        }
    private static State state(AbstractMotionPlanner planner) {
        synchronized (STATES) { return STATES.computeIfAbsent(planner, p -> new State());
            }
    }
    private static void requireZeroOffset() {
        synchronized (STATES) {
            for (State s : STATES.values()) { if (!s.offset.matches(AxesLocation.zero)) {
                throw new IllegalStateException("Home before switching or disabling a compensation profile.");
            } }
        }
    }
    private static void validatePhysical(AbstractMotionPlanner planner, HeadMountable hm,
            AxesLocation location, String stage) {
        if (planner.isValidLocation(hm, location)) { return; }
        // location is already in controller coordinates: nozzle offsets/runout must not be applied again.
        for (ControllerAxis axis : location.getControllerAxes()) {
            if (!(axis instanceof ReferenceControllerAxis) || axis.getType() == Axis.Type.Rotation) { continue; }
            ReferenceControllerAxis ref = (ReferenceControllerAxis) axis;
            org.openpnp.model.Length coordinate = location.getLengthCoordinate(ref).convertToUnits(LengthUnit.Millimeters);
            for (boolean low : new boolean[] {true, false}) {
                boolean enabled = low ? ref.isSoftLimitLowEnabled() : ref.isSoftLimitHighEnabled();
                org.openpnp.model.Length limit = (low ? ref.getSoftLimitLow() : ref.getSoftLimitHigh())
                        .convertToUnits(LengthUnit.Millimeters);
                double excess = low ? limit.getValue() - coordinate.getValue() : coordinate.getValue() - limit.getValue();
                if (enabled && excess > 0 && !ref.coordinatesMatch(limit, coordinate)) {
                    throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                            "%s · %s：%s 轴实际坐标 %.5f mm 超过软限位%s %.5f mm，超出 %.5f mm。请检查换嘴/目标坐标与吸嘴偏置；补偿未被截断。",
                            hm == null ? "运动工具" : hm.getName(), stage, ref.getName(), coordinate.getValue(),
                            low ? "下限" : "上限", limit.getValue(), excess));
                }
            }
        }
        throw new IllegalStateException((hm == null ? "运动工具" : hm.getName()) + " · " + stage
                + "：补偿后的实际轴位置不满足机器限位，请检查轴配置。坐标：" + location);
    }

    private static void validate(AbstractMotionPlanner planner, HeadMountable hm, AxesLocation location,
            Axis x, Axis y, String stage) {
        validatePhysical(planner, hm, location, stage);
        // A Home fiducial may define the coordinate origin itself. Only the
        // optional production margin is relaxed; physical axis limits still apply.
        double effectiveMargin = state(planner).visualHoming ? 0 : margin;
        // No second floating-point comparison at zero margin: preserve native axis-resolution semantics.
        if (effectiveMargin == 0) { return; }
        for (Axis axis : new Axis[] {x, y}) { if (axis instanceof ReferenceControllerAxis) {
            ReferenceControllerAxis a = (ReferenceControllerAxis) axis;
            double coordinate = location.getCoordinate(axis);
            if ((a.isSoftLimitLowEnabled() && coordinate < a.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue() + effectiveMargin)
                    || (a.isSoftLimitHighEnabled() && coordinate > a.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue() - effectiveMargin)) {
                throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                        "%s · %s：%s 轴实际坐标 %.5f mm 进入显式设置的 %.3f mm 预留边距；机器软限位仍有效。",
                        hm == null ? "运动工具" : hm.getName(), stage, a.getName(), coordinate, effectiveMargin));
            }
        } }
    }
}
