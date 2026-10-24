package org.openpnp.model;

import java.util.ArrayList;
import java.util.List;
import org.openpnp.spi.HeadMountable;

/** Keeps the native precision model active while incorporating its endpoint into the continuous move. */
public final class ContinuousVisionCompensation {
    private static final ThreadLocal<List<Motion>> NOMINAL = new ThreadLocal<>();
    private static final ThreadLocal<List<Motion>> PHYSICAL = new ThreadLocal<>();
    private ContinuousVisionCompensation() { }

    static void begin(List<Motion> expected) {
        NOMINAL.set(expected);
        PHYSICAL.set(new ArrayList<>());
    }

    static void end() {
        NOMINAL.remove();
        PHYSICAL.remove();
    }

    public static boolean isActive() { return NOMINAL.get() != null; }

    public static void record(HeadMountable tool, AxesLocation from, AxesLocation to,
            AxesLocation physicalFrom, AxesLocation physicalTo, double speed) {
        if (!isActive()) { return; }
        List<Motion> result = PHYSICAL.get();
        List<Motion> expected = NOMINAL.get();
        if (result.size() >= expected.size()) { throw new IllegalStateException("连续飞拍出现额外补偿移动"); }
        Motion nominal = expected.get(result.size());
        if (tool != nominal.getHeadMountable() || !from.matches(nominal.getLocation0())
                || !to.matches(nominal.getLocation1())) {
            throw new IllegalStateException("连续飞拍名义轨迹与精度补偿输入不一致");
        }
        result.add(new Motion(tool, physicalFrom, physicalTo, speed, Motion.MotionOption.UncoordinatedMotion));
    }

    static List<Motion> expected(List<Motion> nominal) {
        List<Motion> result = PHYSICAL.get();
        if (result.isEmpty()) { return nominal; }
        if (result.size() != nominal.size()) { throw new IllegalStateException("连续飞拍补偿队列不完整"); }
        return result;
    }
}
