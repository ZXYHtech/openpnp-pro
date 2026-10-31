package org.openpnp.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.openpnp.spi.HeadMountable;

/** Constrain native smooth motion to a previously certified Cartesian volume in controller axes. */
public final class CertifiedTravelProfile implements AutoCloseable {
    public static final class Bounds {
        public final AxesLocation minimum, maximum;
        private final Runnable guard;
        public Bounds(AxesLocation minimum, AxesLocation maximum, Runnable guard) {
            this.minimum = minimum;
            this.maximum = maximum;
            this.guard = guard;
        }
        public void constrain(Motion motion) {
            int index = 0;
            for (var axis : motion.getLocation1().getControllerAxes()) {
                double low = minimum.getCoordinate(axis), high = maximum.getCoordinate(axis);
                double first = motion.getLocation0().getCoordinate(axis), last = motion.getLocation1().getCoordinate(axis);
                if (!Double.isFinite(low) || !Double.isFinite(high) || first < low - 1e-6 || first > high + 1e-6
                        || last < low - 1e-6 || last > high + 1e-6) {
                    throw new IllegalStateException("平滑联动控制轴超出已认证空间：" + axis.getName());
                }
                MotionProfile profile = motion.getAxesProfiles()[index++];
                profile.setLocationMin(Math.max(profile.getLocationMin(), low));
                profile.setLocationMax(Math.min(profile.getLocationMax(), high));
            }
        }
        public void validate(Motion motion) {
            guard.run();
            int index = 0;
            for (var axis : motion.getLocation1().getControllerAxes()) {
                MotionProfile profile = motion.getAxesProfiles()[index++];
                if (profile.checkValidity() != null || profile.getLowerSBoundary() < minimum.getCoordinate(axis) - 1e-5
                        || profile.getHigherSBoundary() > maximum.getCoordinate(axis) + 1e-5) {
                    throw new IllegalStateException("平滑曲线越出已认证包络，尚未发送运动：" + axis.getName());
                }
            }
        }
    }

    private static final ThreadLocal<CertifiedTravelProfile> ACTIVE = new ThreadLocal<>();
    private static final Map<Motion, Bounds> CERTIFIED = Collections.synchronizedMap(new WeakHashMap<>());
    private final HeadMountable tool;
    private final Bounds bounds;

    public CertifiedTravelProfile(HeadMountable tool, Bounds bounds) {
        if (ACTIVE.get() != null) { throw new IllegalStateException("Nested certified travel"); }
        this.tool = tool;
        this.bounds = bounds;
        ACTIVE.set(this);
    }
    public static boolean active() { return ACTIVE.get() != null; }
    @Override public void close() { ACTIVE.remove(); }

    /** Called before a motion is appended; a failed proof cannot leave a command queued. */
    public static void annotate(Motion motion) {
        CertifiedTravelProfile request = ACTIVE.get();
        if (request == null) { return; }
        if (motion.getHeadMountable() != request.tool) { throw new IllegalStateException("平滑联动工具改变"); }
        request.bounds.guard.run();
        request.bounds.constrain(motion);
        CERTIFIED.put(motion, request.bounds);
    }

    /** Called after native profile optimisation, before any driver receives commands. */
    public static void validate(List<Motion> motions) {
        for (Motion motion : motions) {
            Bounds bounds = CERTIFIED.get(motion);
            if (bounds != null) { bounds.validate(motion); }
        }
    }
}
