package org.openpnp.machine.reference.vision;

import java.util.ArrayList;
import java.util.List;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PnpJobPlanner.PlannedPlacement;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.pmw.tinylog.Logger;

/** Compare dual-nozzle camera visits using the native motor transforms, including shared cam Z. */
public final class BottomVisionRouteOrder {
    private BottomVisionRouteOrder() { }

    static boolean reverse(double normal, double reversed) {
        return Double.isFinite(normal) && Double.isFinite(reversed) && reversed + .005 < normal;
    }

    static double routeSeconds(Nozzle[] nozzles, Location[] centers, boolean[] flying,
            Location[] boardEnds, boolean reversed, int direction) throws Exception {
        AxesLocation position = new AxesLocation(nozzles[0].getHead().getMachine());
        double seconds = 0;
        for (int visit = 0; visit < 2; visit++) {
            int index = reversed ? 1 - visit : visit;
            Nozzle nozzle = nozzles[index];
            Location center = centers[index].convertToUnits(LengthUnit.Millimeters);
            List<Location> poses = new ArrayList<>();
            if (flying[index]) {
                poses.add(center.derive(center.getX() - direction * FlyingVisionSettings.getApproachDistanceMm(), null, null, null));
                poses.add(center.derive(center.getX() + direction * FlyingVisionSettings.getExitDistanceMm(), null, null, null));
            } else {
                poses.add(center);
            }
            // Flying exposure currently uses a full retreat; stationary matching
            // packages may use the separately certified direct handoff.
            if (visit == 1 || flying[index]
                    || nozzles[0].getPart().getPackage() != nozzles[1].getPart().getPackage()) {
                Location last = poses.get(poses.size() - 1);
                poses.add(last.derive(null, null, nozzle.getEffectiveSafeZ()
                        .convertToUnits(LengthUnit.Millimeters).getValue(), null));
            }
            for (int step = 0; step < poses.size(); step++) {
                AxesLocation next = position.put(nozzle.toRaw(nozzle.toHeadLocation(poses.get(step))));
                double speed = flying[index] && step == 1 ? FlyingVisionSettings.getSpeed()
                        : nozzle.getHead().getMachine().getSpeed();
                seconds += new Motion(nozzle, position, next, speed * nozzle.getHead().getMaxPartSpeed(),
                        Motion.MotionOption.CoordinatedMotion).getTime();
                position = next;
            }
        }
        double endSeconds = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nozzles.length; i++) {
            Location end = boardEnds[i].derive(null, null, nozzles[i].getEffectiveSafeZ()
                    .convertToUnits(LengthUnit.Millimeters).getValue(), null);
            AxesLocation next = position.put(nozzles[i].toRaw(nozzles[i].toHeadLocation(end)));
            endSeconds = Math.min(endSeconds, new Motion(nozzles[i], position, next,
                    nozzles[i].getHead().getMachine().getSpeed() * nozzles[i].getHead().getMaxPartSpeed(),
                    Motion.MotionOption.CoordinatedMotion).getTime());
        }
        return seconds + endSeconds;
    }

    public static List<PlannedPlacement> order(List<PlannedPlacement> planned) {
        if (!DynamicSafeMotionSettings.isEnabled() || planned.size() != 2
                || planned.get(0).nozzle.getHead() != planned.get(1).nozzle.getHead()) { return planned; }
        try {
            Camera camera = VisionUtils.getBottomVisionCamera();
            ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
            if (camera.getHead() != null || !vision.isEnabled() || !SafetyWorkbenchStore.get().isCertified()) {
                return planned;
            }
            Nozzle[] nozzles = new Nozzle[2];
            Location[] centers = new Location[2], boardEnds = new Location[2];
            boolean[] flying = new boolean[2];
            for (int i = 0; i < 2; i++) {
                PlannedPlacement item = planned.get(i);
                var placement = item.jobPlacement.getPlacement();
                Part part = placement.getPart();
                if (part == null || item.nozzle.getPart() != part
                        || !vision.getInheritedVisionSettings(part).isEnabled()) { return planned; }
                nozzles[i] = item.nozzle;
                centers[i] = vision.getLocation(part, item.jobPlacement.getBoardLocation(), placement, item.nozzle);
                boardEnds[i] = Utils2D.calculateBoardPlacementLocation(item.jobPlacement.getBoardLocation(), placement.getLocation());
                flying[i] = FlyingBottomVisionService.shouldAttempt(part, item.jobPlacement.getBoardLocation(), placement, item.nozzle);
            }
            int direction = FlyingVisionMotion.direction();
            double normal = routeSeconds(nozzles, centers, flying, boardEnds, false, direction);
            double swapped = routeSeconds(nozzles, centers, flying, boardEnds, true, direction);
            List<PlannedPlacement> result = planned;
            if (reverse(normal, swapped)) {
                result = new ArrayList<>(planned);
                java.util.Collections.reverse(result);
            }
            Logger.info("Bottom vision route {} -> {}: native XYZ estimate {} / {} s; direction {} unchanged.",
                    result.get(0).nozzle.getName(), result.get(1).nozzle.getName(), normal, swapped, direction);
            return result;
        } catch (Exception unavailable) {
            Logger.debug(unavailable, "Keep existing bottom vision order because native route estimation is unavailable.");
            return planned;
        }
    }
}
