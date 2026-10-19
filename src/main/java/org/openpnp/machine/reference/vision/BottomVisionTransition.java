package org.openpnp.machine.reference.vision;

import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Nozzle;

/** Keep the first imaging pose only when the complete next move has a certified direct envelope. */
public final class BottomVisionTransition {
    private BottomVisionTransition() { }

    public static boolean canKeepHeight(ReferenceBottomVision alignment, Nozzle current, Nozzle next,
            Part part, BoardLocation board, Placement placement) {
        if (!DynamicSafeMotionSettings.isEnabled() || current == next
                || current.getClass() != ReferenceNozzle.class || next.getClass() != ReferenceNozzle.class
                || current.getHead() != next.getHead() || current.getPart() == null
                || next.getPart() != part || part.isPartHeightUnknown()
                || part.getPackage() != current.getPart().getPackage()
                || FlyingBottomVisionService.shouldAttempt(part, board, placement, next)
                || !Configuration.get().getScripting().hasNoScript("Vision.PartAlignment.Before")
                || !Configuration.get().getScripting().hasNoScript("Vision.PartAlignment.After")) {
            return false;
        }
        try {
            SafetyWorkbenchModel model = SafetyWorkbenchStore.get();
            if (!model.isCertified()
                    || !model.findToolProfile(current.getHead().getId(), current.getId()).zIsNozzleTip
                    || !model.findToolProfile(next.getHead().getId(), next.getId()).zIsNozzleTip) {
                return false;
            }
            long revision = model.getRevision();
            Location destination = alignment.getLocation(part, board, placement, next);
            DynamicSafeMotionPlanner.Plan plan = DynamicSafeMotionPlanner.previewBottomVision(next,
                    destination, next.getHead().getMachine().getSpeed());
            return model.isCertified() && model.getRevision() == revision && plan.isOptimized()
                    && plan.getLocations().size() == 1
                    && plan.getLocations().get(0).convertToUnits(LengthUnit.Millimeters)
                            .getXyzDistanceTo(destination.convertToUnits(LengthUnit.Millimeters)) < .001;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
