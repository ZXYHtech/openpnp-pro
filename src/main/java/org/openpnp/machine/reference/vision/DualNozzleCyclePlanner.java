package org.openpnp.machine.reference.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.FastTravelClearance;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PnpJobPlanner.PlannedPlacement;
import org.openpnp.spi.Locatable.LocationOption;
import org.pmw.tinylog.Logger;

/** Exhaustive ordering of the remaining pick/vision/place visits in one dual-nozzle cycle. */
public final class DualNozzleCyclePlanner {
    private DualNozzleCyclePlanner() { }

    public static final class Score {
        public final int mask;
        public final double seconds, distance;
        public final int reversals;
        public Score(int mask, double seconds, double distance, int reversals) {
            this.mask = mask;
            this.seconds = seconds;
            this.distance = distance;
            this.reversals = reversals;
        }
    }

    public static Score best(List<Score> candidates) {
        double fastest = candidates.stream().filter(c -> Double.isFinite(c.seconds) && Double.isFinite(c.distance))
                .mapToDouble(c -> c.seconds).min().orElse(Double.NaN);
        Score best = null;
        for (Score candidate : candidates) {
            if (!Double.isFinite(candidate.seconds) || !Double.isFinite(candidate.distance)
                    || candidate.seconds > fastest + .005) { continue; }
            if (best == null || candidate.reversals < best.reversals
                    || candidate.reversals == best.reversals && (candidate.distance < best.distance - .01
                            || Math.abs(candidate.distance - best.distance) <= .01 && candidate.seconds < best.seconds)) {
                best = candidate;
            }
        }
        return best;
    }

    private static final class Route {
        AxesLocation position;
        double seconds, distance;
        int reversals;
        final java.util.Map<org.openpnp.spi.ControllerAxis, Double> directions = new java.util.HashMap<>();
        Route(AxesLocation position) { this.position = position; }

        void visit(Nozzle nozzle, Location headTarget, double speed) throws Exception {
            AxesLocation next = position.put(DualNozzleCycleCosts.raw(nozzle, headTarget));
            // Equivalent full turns must not manufacture a slow 360-degree route.
            for (var axis : next.getControllerAxes()) {
                if (axis.getType() == Axis.Type.Rotation
                        && axis instanceof org.openpnp.machine.reference.axis.ReferenceControllerAxis
                        && ((org.openpnp.machine.reference.axis.ReferenceControllerAxis) axis).isWrapAroundRotation()) {
                    double delta = org.openpnp.util.Utils2D.angleNorm(next.getCoordinate(axis) - position.getCoordinate(axis), 180);
                    next = next.put(new AxesLocation(axis, position.getCoordinate(axis) + delta));
                }
            }
            double dx = 0, dy = 0;
            for (var axis : next.getControllerAxes()) {
                double delta = next.getCoordinate(axis) - position.getCoordinate(axis);
                if (axis.getType() == Axis.Type.X) { dx = delta; }
                if (axis.getType() == Axis.Type.Y) { dy = delta; }
                if (Math.abs(delta) > .001 && axis.getType() != Axis.Type.Rotation) {
                    Double previous = directions.put(axis, Math.signum(delta));
                    if (previous != null && previous * delta < 0) { reversals++; }
                }
            }
            distance += Math.hypot(dx, dy);
            seconds += DualNozzleCycleCosts.seconds(nozzle, position, next, speed);
            position = next;
        }
    }

    public static Score evaluate(List<PlannedPlacement> items, Location[][] targets, boolean[] flying,
            int phase, int mask, AxesLocation start) throws Exception {
        Route route = new Route(start);
        double partSpeed = 1;
        for (var item : items) { partSpeed = Math.min(partSpeed, item.jobPlacement.getPlacement().getPart().getSpeed()); }
        double speed = items.get(0).nozzle.getHead().getMachine().getSpeed() * partSpeed;
        var model = SafetyWorkbenchStore.get();
        double plane = FastTravelClearance.planeZ(model);
        for (int stage = phase; stage < 3; stage++) {
            for (int visit = 0; visit < 2; visit++) {
                int index = (mask & (1 << stage)) == 0 ? visit : 1 - visit;
                var item = items.get(index);
                Nozzle nozzle = item.nozzle;
                Location target = targets[stage][index];
                if (target == null) { continue; }
                for (var step : DualNozzleCycleCosts.steps(nozzle, item.jobPlacement.getPlacement().getPart(),
                        target, stage, flying[index], speed, model, plane)) {
                    route.visit(nozzle, step.location, step.speed);
                }
            }
        }
        return new Score(mask, route.seconds, route.distance, route.reversals);
    }

    public static List<PlannedPlacement> order(List<PlannedPlacement> items, int phase,
            Function<PlannedPlacement, Location> pick, Function<PlannedPlacement, Location> vision,
            Function<PlannedPlacement, Location> place) {
        if (!DynamicSafeMotionSettings.isEnabled() || items.size() != 2
                || items.get(0).nozzle.getHead() != items.get(1).nozzle.getHead()
                || !SafetyWorkbenchStore.get().isCertified()) { return null; }
        try (var costs = new DualNozzleCycleCosts()) {
            long started = System.nanoTime();
            Location[][] targets = new Location[3][2];
            boolean[] flying = new boolean[2];
            for (int i = 0; i < 2; i++) {
                var item = items.get(i);
                targets[0][i] = pick.apply(item);
                targets[1][i] = vision.apply(item);
                targets[2][i] = place.apply(item);
                if (targets[0][i] == null || targets[2][i] == null) { return null; }
                flying[i] = targets[1][i] != null && FlyingBottomVisionService.shouldAttempt(
                        item.jobPlacement.getPlacement().getPart(), item.jobPlacement.getBoardLocation(),
                        item.jobPlacement.getPlacement(), item.nozzle);
            }
            AxesLocation start = new AxesLocation(items.get(0).nozzle.getHead().getMachine());
            List<Score> candidates = new ArrayList<>();
            for (int mask = 0; mask < 8; mask += 1 << phase) {
                candidates.add(evaluate(items, targets, flying, phase, mask, start));
            }
            Score selected = best(candidates);
            if (selected == null) { return null; }
            List<PlannedPlacement> ordered = new ArrayList<>(items);
            if ((selected.mask & (1 << phase)) != 0) { java.util.Collections.reverse(ordered); }
            Logger.info("Dual cycle phase={} candidates={} order={} -> {}; total native estimate={} s, XY={} mm, reversals={}, remaining mask={}",
                    phase, candidates.size(), ordered.get(0).nozzle.getName(), ordered.get(1).nozzle.getName(),
                    selected.seconds, selected.distance, selected.reversals, selected.mask);
            Logger.info("Dual cycle actual planning={} ms; native solves={}, reused={}",
                    (System.nanoTime() - started) / 1e6, costs.getSolved(), costs.getHits());
            return ordered;
        }
        catch (Exception unavailable) {
            Logger.debug(unavailable, "Keep the existing route because full-cycle estimates are unavailable");
            return null;
        }
    }
}
