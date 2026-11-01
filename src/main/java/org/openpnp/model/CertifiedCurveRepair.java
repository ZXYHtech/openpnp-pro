package org.openpnp.model;

import java.util.*;
import org.pmw.tinylog.Logger;

/** Repair an unsolved/overshooting blend inside the same already-proven free volume, before dispatch. */
public final class CertifiedCurveRepair {
    private static final Map<Motion, CertifiedTravelProfile.Bounds> BOUNDS = Collections.synchronizedMap(new WeakHashMap<>());
    private CertifiedCurveRepair() { }
    public static void register(Motion motion, CertifiedTravelProfile.Bounds bounds) { BOUNDS.put(motion, bounds); }

    public static boolean rebuild(List<Motion> path) throws Exception {
        if (path.stream().noneMatch(BOUNDS::containsKey)) { return false; }
        List<Motion> rebuilt = new ArrayList<>();
        for (Motion old : path) {
            var bounds = BOUNDS.get(old);
            int options = old.getOptions();
            if (bounds != null) {
                options &= ~(Motion.MotionOption.UncoordinatedMotion.flag() | Motion.MotionOption.SynchronizeStraighten.flag()
                        | Motion.MotionOption.SynchronizeEarlyBird.flag() | Motion.MotionOption.SynchronizeLastMinute.flag());
                options |= Motion.MotionOption.CoordinatedMotion.flag();
            }
            Motion next = new Motion(old.getHeadMountable(), old.getLocation0(), old.getLocation1(), old.getNominalSpeed(), options);
            if (bounds != null) { bounds.constrain(next); }
            rebuilt.add(next);
        }
        new AbstractMotionPath() {
            @Override public int size() { return rebuilt.size(); }
            @Override public MotionProfile[] get(int index) { return rebuilt.get(index).getAxesProfiles(); }
        }.solve();
        for (int i = 0; i < rebuilt.size(); i++) {
            var bounds = BOUNDS.get(path.get(i));
            if (bounds != null) { bounds.validate(rebuilt.get(i)); }
        }
        path.clear();
        path.addAll(rebuilt);
        Logger.info("Certified XYZ blend re-solved inside its original free volume before dispatch; waypoints and clearance unchanged");
        return true;
    }
}
