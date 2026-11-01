package org.openpnp.machine.reference.vision;

import java.util.*;
import org.openpnp.machine.reference.safety.*;
import org.openpnp.model.*;
import org.openpnp.spi.*;
import org.openpnp.spi.Locatable.LocationOption;

/** One ordering call shares pure coordinate conversions and identical native segment solves. */
public final class DualNozzleCycleCosts implements AutoCloseable {
    private static final ThreadLocal<DualNozzleCycleCosts> ACTIVE = new ThreadLocal<>();
    private final Map<List<Object>, Double> times = new HashMap<>();
    private final Map<List<Object>, List<Step>> visits = new HashMap<>();
    private final Map<List<Object>, AxesLocation> coordinates = new HashMap<>();
    private int solved, hits;
    public static final class Step {
        public final Location location;
        public final double speed;
        Step(Location location, double speed) {
            this.location = location;
            this.speed = speed;
        }
    }
    public DualNozzleCycleCosts() { ACTIVE.set(this); }
    @Override public void close() { ACTIVE.remove(); }
    public int getSolved() { return solved; }
    public int getHits() { return hits; }

    public static AxesLocation raw(Nozzle nozzle, Location target) throws Exception {
        var cache = ACTIVE.get();
        if (cache == null) { return nozzle.toRaw(target, LocationOption.Quiet); }
        List<Object> key = Arrays.asList(nozzle, target);
        AxesLocation raw = cache.coordinates.get(key);
        if (raw == null) {
            raw = nozzle.toRaw(target, LocationOption.Quiet);
            cache.coordinates.put(key, raw);
        }
        return raw;
    }

    public static double seconds(Nozzle nozzle, AxesLocation from, AxesLocation to, double speed) {
        var cache = ACTIVE.get();
        if (cache == null) { return new Motion(nozzle, from, to, speed, Motion.MotionOption.CoordinatedMotion).getTime(); }
        List<Object> key = new ArrayList<>();
        key.add(nozzle);
        key.add(speed);
        for (var axis : to.getControllerAxes()) {
            double delta = to.getCoordinate(axis) - from.getCoordinate(axis);
            if (delta == 0) { continue; }
            key.add(axis);
            key.add(delta);
        }
        Double time = cache.times.get(key);
        if (time != null) {
            cache.hits++;
            return time;
        }
        time = new Motion(nozzle, from, to, speed, Motion.MotionOption.CoordinatedMotion).getTime();
        cache.times.put(key, time);
        cache.solved++;
        return time;
    }

    public static List<Step> steps(Nozzle nozzle, Part part, Location target, int stage,
            boolean flying, double speed, SafetyWorkbenchModel model, double plane) throws Exception {
        var cache = ACTIVE.get();
        List<Object> key = Arrays.asList(nozzle, part, target, stage, flying, speed);
        if (cache != null && cache.visits.containsKey(key)) { return cache.visits.get(key); }
        List<Step> result = new ArrayList<>();
        Location tool = nozzle.toHeadMountableLocation(target, LocationOption.Quiet);
        var profile = model.findToolProfile(nozzle.getHead().getId(), nozzle.getId());
        double clearance = profile == null ? 2 : profile.clearance;
        double partHeight = nozzle.getSafePartHeight(part).convertToUnits(LengthUnit.Millimeters).getValue();
        double safe = nozzle.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        double local = Double.isFinite(plane) ? Math.min(safe, plane + clearance + partHeight + .5) : safe;
        if (stage == 1 && flying) {
            int direction = FlyingVisionMotion.direction();
            result.add(new Step(target.derive(target.getX() - direction * FlyingVisionSettings.getApproachDistanceMm(), null, null, null), speed));
            result.add(new Step(target.derive(target.getX() + direction * FlyingVisionSettings.getExitDistanceMm(), null, null, null), Math.min(speed, FlyingVisionSettings.getSpeed())));
            result.add(new Step(nozzle.toHeadLocation(tool.derive(tool.getX() + direction * FlyingVisionSettings.getExitDistanceMm(), null, safe, null), LocationOption.Quiet), speed));
        } else {
            if (stage != 1) {
                result.add(new Step(nozzle.toHeadLocation(tool.derive(null, null, Math.max(local, tool.getZ() + 1), null), LocationOption.Quiet), speed));
            }
            result.add(new Step(target, speed));
            result.add(new Step(nozzle.toHeadLocation(tool.derive(null, null, stage == 1 ? safe : Math.max(tool.getZ(), local), null), LocationOption.Quiet), speed));
        }
        if (cache != null) { cache.visits.put(key, result); }
        return result;
    }
}
