package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AbstractMotionPath;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.CertifiedTravelProfile;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.model.MotionProfile;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.Locatable.LocationOption;
import org.openpnp.spi.Nozzle;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;

/** Let the native jerk solver create the Z arch inside a proven free volume; no polygonal pseudo-curve. */
final class BoundedClearanceTravel {
    static final class Candidate {
        final List<Location> locations;
        final int freeIndex;
        final CertifiedTravelProfile.Bounds bounds;
        final double seconds;
        Candidate(List<Location> locations, int index, CertifiedTravelProfile.Bounds bounds, double seconds) {
            this.locations = locations;
            freeIndex = index;
            this.bounds = bounds;
            this.seconds = seconds;
        }
    }
    private BoundedClearanceTravel() { }

    private static Location pose(Nozzle nozzle, AxesLocation raw) throws Exception {
        return nozzle.toHeadMountableLocation(nozzle.toTransformed(raw, LocationOption.Quiet), LocationOption.Quiet)
                .convertToUnits(LengthUnit.Millimeters);
    }
    private static AxesLocation raw(Nozzle nozzle, Location target) throws Exception {
        return nozzle.toRaw(nozzle.toHeadLocation(target, LocationOption.Quiet), LocationOption.Quiet);
    }
    private static Waypoint point(Location location) { return new Waypoint(location.getX(), location.getY(), location.getZ()); }

    /** Corner bounds are valid for affine axes and the native strictly monotonic cam transforms only. */
    private static boolean boundedTransform(Axis axis, java.util.Set<Axis> visited) {
        if (axis == null) { return true; }
        if (!visited.add(axis)) { return false; }
        try {
            if (axis.getClass() == ReferenceControllerAxis.class
                    || axis.getClass() == org.openpnp.machine.reference.axis.ReferenceVirtualAxis.class) { return true; }
            if (axis.getClass() == org.openpnp.machine.reference.axis.ReferenceLinearTransformAxis.class) {
                var affine = (org.openpnp.machine.reference.axis.ReferenceLinearTransformAxis) axis;
                return boundedTransform(affine.getInputAxisX(), visited) && boundedTransform(affine.getInputAxisY(), visited)
                        && boundedTransform(affine.getInputAxisZ(), visited) && boundedTransform(affine.getInputAxisRotation(), visited);
            }
            if (axis.getClass() == org.openpnp.machine.reference.axis.ReferenceCamCounterClockwiseAxis.class
                    || axis.getClass() == org.openpnp.machine.reference.axis.ReferenceCamClockwiseAxis.class) {
                return boundedTransform(((org.openpnp.spi.base.AbstractSingleTransformedAxis) axis).getInputAxis(), visited);
            }
            return false;
        }
        finally { visited.remove(axis); }
    }

    private static boolean globallyClear(Nozzle selected, AxesLocation axes, List<DynamicTool> tools,
            double floor) throws Exception {
        for (DynamicTool tool : tools) {
            var head = selected.getHead().getMachine().getHead(tool.headId);
            var nozzle = head.getNozzle(tool.toolId);
            double z = pose(nozzle, axes).getZ();
            if (!Double.isFinite(z) || tool.minimumZAt(z) < floor + .5) { return false; }
        }
        return true;
    }

    private static double boundary(Nozzle nozzle, AxesLocation base, ControllerAxis z,
            double clear, double end, List<DynamicTool> tools, double floor, long deadline) throws Exception {
        if (globallyClear(nozzle, base.put(new AxesLocation(z, end)), tools, floor)) { return end; }
        double blocked = end;
        for (int i = 0; i < 20; i++) {
            if (System.nanoTime() > deadline) { return Double.NaN; }
            double middle = (clear + blocked) / 2;
            if (globallyClear(nozzle, base.put(new AxesLocation(z, middle)), tools, floor)) { clear = middle; }
            else { blocked = middle; }
        }
        return clear;
    }

    static Candidate plan(ReferenceNozzle nozzle, Location start, Location target, double speed,
            SafetyWorkbenchModel model, List<DynamicTool> tools, CoordinatedMotionValidator.FeederContact contact,
            long deadline, Runnable guard) throws Exception {
        if (!(nozzle.getHead().getMachine().getMotionPlanner() instanceof ReferenceAdvancedMotionPlanner)) { return null; }
        var planner = (ReferenceAdvancedMotionPlanner) nozzle.getHead().getMachine().getMotionPlanner();
        if (!planner.isAllowContinuousMotion()) { return null; }
        for (DynamicTool tool : tools) {
            Nozzle actual = nozzle.getHead().getMachine().getHead(tool.headId).getNozzle(tool.toolId);
            for (Axis axis : new Axis[] { actual.getAxisX(), actual.getAxisY(), actual.getAxisZ(), actual.getAxisRotation() }) {
                if (!boundedTransform(axis, new java.util.HashSet<>())) { return null; }
            }
        }
        List<ControllerAxis> zAxes = new ArrayList<>(nozzle.getMappedAxes(nozzle.getHead().getMachine())
                .byType(Axis.Type.Z).getControllerAxes());
        if (zAxes.size() != 1 || !(zAxes.get(0) instanceof ReferenceControllerAxis)) { return null; }
        ControllerAxis z = zAxes.get(0);
        double floor = FastTravelClearance.planeZ(model);
        if (!Double.isFinite(floor)) { return null; }
        AxesLocation initial = new AxesLocation(nozzle.getHead().getMachine());
        AxesLocation goal = planner.normalizeForPreview(nozzle, raw(nozzle, target));
        if (goal == null) { return null; }
        goal = initial.put(planner.getPendingSubordinateAxes().byType(Axis.Type.Rotation)).put(goal);
        double safeZ = nozzle.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        AxesLocation safe = initial.put(raw(nozzle, target.derive(null, null, safeZ, null)));
        double center = safe.getCoordinate(z);
        if (!globallyClear(nozzle, safe, tools, floor)) { return null; }
        double from = initial.getCoordinate(z), to = goal.getCoordinate(z);
        double low = boundary(nozzle, safe, z, center, Math.min(center, Math.min(from, to)), tools, floor, deadline);
        double high = boundary(nozzle, safe, z, center, Math.max(center, Math.max(from, to)), tools, floor, deadline);
        if (!Double.isFinite(low + high) || high - low < .001) { return null; }
        double margin = Math.max(((ReferenceControllerAxis) z).getResolution() * 2, (high - low) * .02);
        if (low + margin >= high - margin) { return null; }
        double entryZ = Math.max(low + margin, Math.min(high - margin, from));
        double exitZ = Math.max(low + margin, Math.min(high - margin, to));
        AxesLocation entryAxes = initial.put(goal.byType(Axis.Type.Rotation)).put(new AxesLocation(z, entryZ));
        Location entry = pose(nozzle, entryAxes).derive(start.getX(), start.getY(), null, null);
        entryAxes = initial.put(raw(nozzle, entry));
        AxesLocation exitAxes = goal.put(new AxesLocation(z, exitZ));
        Location exit = pose(nozzle, exitAxes).derive(target.getX(), target.getY(), null, null);
        exitAxes = goal.put(raw(nozzle, exit));
        AxesLocation minimum = initial, maximum = initial;
        List<ControllerAxis> varying = new ArrayList<>();
        for (ControllerAxis axis : initial.getControllerAxes()) {
            double a = entryAxes.getCoordinate(axis), b = exitAxes.getCoordinate(axis);
            double lo = Math.min(a, b), hi = Math.max(a, b);
            if (axis == z) {
                lo = low;
                hi = high;
            }
            else if (axis.getType() == Axis.Type.X || axis.getType() == Axis.Type.Y) {
                // Also cover the existing CoreXY precision bridge without disabling it.
                lo -= 1;
                hi += 1;
            }
            else if (axis.getType() == Axis.Type.Rotation && axis instanceof ReferenceControllerAxis) {
                var reference = (ReferenceControllerAxis) axis;
                lo = reference.isSoftLimitLowEnabled() ? reference.getSoftLimitLow().getValue() : lo - 360;
                hi = reference.isSoftLimitHighEnabled() ? reference.getSoftLimitHigh().getValue() : hi + 360;
            }
            if (axis instanceof ReferenceControllerAxis && axis.getType() != Axis.Type.Rotation) {
                var reference = (ReferenceControllerAxis) axis;
                if (reference.isSoftLimitLowEnabled()) { lo = Math.max(lo, reference.getSoftLimitLow().convertToUnits(AxesLocation.getUnits()).getValue()); }
                if (reference.isSoftLimitHighEnabled()) { hi = Math.min(hi, reference.getSoftLimitHigh().convertToUnits(AxesLocation.getUnits()).getValue()); }
            }
            if (lo > a || hi < a || lo > b || hi < b) { return null; }
            minimum = minimum.put(new AxesLocation(axis, lo));
            maximum = maximum.put(new AxesLocation(axis, hi));
            if (axis.getType() != Axis.Type.Rotation && hi - lo > 1e-8) { varying.add(axis); }
        }
        if (varying.size() > 3) { return null; }
        List<Waypoint> bottoms = new ArrayList<>(), tops = new ArrayList<>();
        for (DynamicTool tool : tools) {
            Nozzle actual = nozzle.getHead().getMachine().getHead(tool.headId).getNozzle(tool.toolId);
            double runout = actual instanceof ReferenceNozzle && ((ReferenceNozzle) actual).getCalibrationNozzleTip() != null
                    ? ((ReferenceNozzle) actual).getCalibrationNozzleTip().getCalibration().getMaximumOffsetMagnitude((ReferenceNozzle) actual) : 0;
            if (!Double.isFinite(runout)) { return null; }
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int corner = 0; corner < (1 << varying.size()); corner++) {
                AxesLocation axes = entryAxes;
                for (int i = 0; i < varying.size(); i++) {
                    ControllerAxis axis = varying.get(i);
                    axes = axes.put(new AxesLocation(axis, ((corner >> i) & 1) == 0
                            ? minimum.getCoordinate(axis) : maximum.getCoordinate(axis)));
                }
                Location p = pose(actual, axes);
                minX = Math.min(minX, p.getX() - 2 * runout);
                minY = Math.min(minY, p.getY() - 2 * runout);
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX() + 2 * runout);
                maxY = Math.max(maxY, p.getY() + 2 * runout);
                maxZ = Math.max(maxZ, p.getZ());
            }
            bottoms.add(new Waypoint(minX, minY, minZ));
            tops.add(new Waypoint(maxX, maxY, maxZ));
        }
        if (!CoordinatedMotionValidator.validateToolInterval(model, tools, bottoms, tops).isSafe()) { return null; }
        List<Location> locations = new ArrayList<>();
        if (!raw(nozzle, start).matches(raw(nozzle, entry))) { locations.add(entry); }
        int freeIndex = locations.size();
        if (entryAxes.matches(exitAxes)) { return null; }
        locations.add(exit);
        if (!raw(nozzle, exit).matches(raw(nozzle, target))) { locations.add(target); }
        List<Waypoint> path = new ArrayList<>();
        path.add(point(start));
        for (Location location : locations) { path.add(point(location)); }
        if (!CoordinatedMotionValidator.validate(model, path, tools, point(start), deadline, contact).isSafe()) { return null; }
        var bounds = new CertifiedTravelProfile.Bounds(minimum, maximum, guard);
        List<Motion> motions = new ArrayList<>();
        AxesLocation previous = initial;
        for (int i = 0; i < locations.size(); i++) {
            // Queueing normalizes equivalent C turns on every waypoint. The
            // preview must do the same: a fictitious 360->0 turn changes the
            // native splice timing and can hide an unsolvable bounded curve.
            AxesLocation normalized = planner.normalizeForPreview(nozzle, raw(nozzle, locations.get(i)));
            if (normalized == null) { return null; }
            AxesLocation next = previous.put(normalized);
            if (i == 0) { next = next.put(goal.byType(Axis.Type.Rotation)); }
            Motion motion = new Motion(nozzle, previous, next, speed * nozzle.getHead().getMaxPartSpeed(),
                    i == freeIndex ? Motion.MotionOption.UncoordinatedMotion : Motion.MotionOption.CoordinatedMotion);
            if (i == freeIndex) { bounds.constrain(motion); }
            motions.add(motion);
            previous = next;
        }
        new AbstractMotionPath() {
            @Override public int size() { return motions.size(); }
            @Override public MotionProfile[] get(int index) { return motions.get(index).getAxesProfiles(); }
        }.solve();
        bounds.validate(motions.get(freeIndex));
        double seconds = 0;
        for (Motion motion : motions) { seconds += motion.getTime(); }
        if (!Double.isFinite(seconds) || System.nanoTime() > deadline) { return null; }
        return new Candidate(locations, freeIndex, bounds, seconds);
    }
}
