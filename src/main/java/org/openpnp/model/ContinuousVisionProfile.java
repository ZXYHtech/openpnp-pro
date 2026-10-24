package org.openpnp.model;

import java.util.List;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;

/** A bounded camera pass: constant controller X velocity between two imaging windows. */
public final class ContinuousVisionProfile implements AutoCloseable {
    public interface Validator { void validate(List<Motion> motions) throws Exception; }
    private static final ThreadLocal<ContinuousVisionProfile> ACTIVE = new ThreadLocal<>();
    private final List<Motion> expected;
    private final double velocity;
    private final Validator validator;
    private boolean consumed;

    public ContinuousVisionProfile(List<Motion> expected, double velocity, Validator validator) {
        if (ACTIVE.get() != null) { throw new IllegalStateException("Nested continuous camera motion"); }
        this.expected = expected;
        this.velocity = velocity;
        this.validator = validator;
        ACTIVE.set(this);
        ContinuousVisionCompensation.begin(expected);
    }

    @Override public void close() {
        ACTIVE.remove();
        ContinuousVisionCompensation.end();
    }

    /** Called before any driver commands are emitted. No ordinary job motion is changed. */
    public static boolean optimizeIfRequested(List<Motion> motions) throws Exception {
        ContinuousVisionProfile request = ACTIVE.get();
        if (request == null) { return false; }
        List<Motion> certifiedTargets = ContinuousVisionCompensation.expected(request.expected);
        if (request.consumed || motions.size() != request.expected.size())
            { throw new Exception("连续飞拍队列发生改变，未发送运动"); }
        for (int i = 0; i < motions.size(); i++) {
            Motion actual = motions.get(i), expected = certifiedTargets.get(i);
            if (actual.getHeadMountable() != expected.getHeadMountable()
                    || !actual.getLocation0().matches(expected.getLocation0())
                    || !actual.getLocation1().matches(expected.getLocation1()))
                { throw new Exception("连续飞拍起点或轴映射发生改变，未发送运动"); }
        }
        solve(motions, request.velocity);
        request.validator.validate(motions);
        request.consumed = true;
        return true;
    }

    public static void solve(List<Motion> motions, double velocity) throws Exception {
        if (motions.size() < 3 || !Double.isFinite(velocity) || velocity <= 0 || velocity > 50)
            { throw new Exception("无效的连续飞拍速度或轨迹"); }
        for (int segment = 0; segment < motions.size(); segment++) {
            Motion motion = motions.get(segment);
            List<ControllerAxis> axes = new java.util.ArrayList<>(motion.getLocation1().getControllerAxes());
            for (ControllerAxis axis : axes) {
                if (axis.getType() == Axis.Type.Rotation
                        && Math.abs(motion.getLocation1().getCoordinate(axis)
                                - motion.getLocation0().getCoordinate(axis)) > .000001) {
                    throw new Exception("连续飞拍前必须完成两头旋转定位");
                }
            }
            int x = -1;
            for (int i = 0; i < axes.size(); i++) { if (axes.get(i).getType() == Axis.Type.X) {
                if (x >= 0) { throw new Exception("连续飞拍需要唯一的控制器 X 轴"); }
                x = i;
            } }
            if (x < 0) { throw new Exception("缺少控制器 X 轴"); }
            MotionProfile[] profiles = motion.getAxesProfiles();
            MotionProfile original = profiles[x];
            double delta = original.getLocation(7) - original.getLocation(0);
            if (delta <= 0) { throw new Exception("连续双头飞拍仅支持单调 +X 轨迹"); }
            double entry = segment == 0 ? 0 : velocity;
            double exit = segment == motions.size() - 1 ? 0 : velocity;
            double duration = segment == 0 || segment == motions.size() - 1 ? 0 : delta / velocity;
            profiles[x] = profile(original, entry, exit, duration, velocity);
            duration = profiles[x].getTime();
            if (segment == 0 || segment == motions.size() - 1) {
                for (int i = 0; i < profiles.length; i++) {
                    if (i != x) {
                        duration = Math.max(duration, profile(profiles[i], 0, 0, 0,
                                profiles[i].getVelocityMax()).getTime());
                    }
                }
                profiles[x] = profile(original, entry, exit, duration, velocity);
            }
            for (int i = 0; i < profiles.length; i++) { if (i != x)
                { profiles[i] = profile(profiles[i], 0, 0, duration, profiles[i].getVelocityMax()); } }
            for (MotionProfile p : profiles) {
                if (Math.abs(p.getTime() - duration) > .00001)
                    { throw new Exception("轴无法在连续飞拍窗口内完成运动"); }
                double d = p.getLocation(7) - p.getLocation(0);
                if (d >= 0 && p.getLowerVBoundary() < -.00001
                        || d <= 0 && p.getHigherVBoundary() > .00001)
                    { throw new Exception("连续飞拍轴出现折返"); }
            }
        }
    }

    static MotionProfile profile(MotionProfile limits, double entry, double exit,
            double duration, double maxVelocity) throws Exception {
        double a = limits.getLocation(0), b = limits.getLocation(7);
        MotionProfile p = new MotionProfile(a, b, entry, exit, 0, 0,
                Math.max(limits.getLocationMin(), Math.min(a, b)),
                Math.min(limits.getLocationMax(), Math.max(a, b)),
                Math.min(limits.getVelocityMax(), maxVelocity), limits.getEntryAccelerationMax(),
                limits.getExitAccelerationMax(), limits.getJerkMax(), duration,
                duration > 0 ? duration + .000001 : Double.POSITIVE_INFINITY, 0);
        p.solve();
        if (p.checkValidity() != null) { throw new Exception("连续飞拍曲线无效：" + p.checkValidity()
                + " from=" + a + " to=" + b + " duration=" + duration + " solved=" + p.getTime()
                + " entry=" + entry + " exit=" + exit); }
        return p;
    }
}
