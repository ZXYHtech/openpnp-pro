package org.openpnp.machine.reference.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PnpJobPlanner.PlannedPlacement;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.pmw.tinylog.Logger;

/** Local camera entry and same-direction dual-nozzle order; all moves retain full swept certification. */
public final class FlyingVisionMotion {
    private FlyingVisionMotion() {
    }

    public static int direction() {
        return Preferences.userNodeForPackage(FlyingVisionMotion.class)
                .getInt("OpenPnPPro.flyingVision.direction", 1) == -1 ? -1 : 1;
    }

    public static void setDirection(int direction) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("飞拍方向只能为 +X 或 -X；改变后需重新评估。");
        }
        Preferences.userNodeForPackage(FlyingVisionMotion.class)
                .putInt("OpenPnPPro.flyingVision.direction", direction);
    }

    public static javax.swing.JComponent directionSelector() {
        javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        javax.swing.JComboBox<String> choice = new javax.swing.JComboBox<>(new String[] {"+X", "-X"});
        choice.setSelectedIndex(direction() == 1 ? 0 : 1);
        javax.swing.JLabel status = new javax.swing.JLabel("改变后需重新进行封装评估");
        choice.addActionListener(event -> {
            if (org.openpnp.model.Configuration.get().getMachine().isBusy() || FlyingVisionEvaluation.isArmed()) {
                int saved = direction() == 1 ? 0 : 1;
                if (choice.getSelectedIndex() != saved) {
                    choice.setSelectedIndex(saved);
                }
                status.setText("先停止评估并等待机器空闲");
                return;
            }
            setDirection(choice.getSelectedIndex() == 0 ? 1 : -1);
            FlyingVisionEvaluation.cancel();
            status.setText("已保存，两只吸嘴保持同向；请重新评估");
        });
        row.add(new javax.swing.JLabel("双头飞拍方向"));
        row.add(choice);
        row.add(status);
        return row;
    }

    static boolean forwardDelta(double delta, int direction) {
        return Double.isFinite(delta) && (direction == 1 || direction == -1) && delta * direction >= .015;
    }

    static boolean inCameraCorridor(Location current, Location target, boolean nozzleAtCamera) {
        Location from = current.convertToUnits(LengthUnit.Millimeters);
        Location to = target.convertToUnits(LengthUnit.Millimeters);
        return nozzleAtCamera && Double.isFinite(from.getX() + from.getY() + from.getZ()
                + from.getRotation() + to.getX() + to.getY() + to.getZ() + to.getRotation())
                && Math.abs(from.getX() - to.getX()) <= 40
                && Math.abs(from.getY() - to.getY()) <= 4
                && Math.abs(from.getZ() - to.getZ()) <= 15
                && Math.abs(Utils2D.angleNorm(from.getRotation() - to.getRotation(), 180)) <= .01;
    }

    /** Recheck certification and the actual start immediately before submitting the direct move. */
    static boolean submitIfCurrent(Nozzle nozzle, Location from, Location target,
            BooleanSupplier certified) throws Exception {
        Location current = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location expected = from.convertToUnits(LengthUnit.Millimeters);
        if (!certified.getAsBoolean() || current.getXyzDistanceTo(expected) > .001
                || Math.abs(Utils2D.angleNorm(current.getRotation() - expected.getRotation(), 180)) > .01) {
            return false;
        }
        nozzle.moveTo(target, nozzle.getHead().getMachine().getSpeed(), org.openpnp.model.Motion.MotionOption.CoordinatedMotion);
        return true;
    }

    public static boolean tryDirectEntry(ReferenceBottomVision vision, Part expectedPart, Nozzle nozzle,
            Camera camera, Location target) throws Exception {
        if (!DynamicSafeMotionSettings.isEnabled() || camera.getHead() != null
                || nozzle.getClass() != ReferenceNozzle.class || expectedPart == null || expectedPart.isPartHeightUnknown()
                || !nozzle.isReachable(target)) {
            return false;
        }
        Location from = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        boolean atCamera = false;
        for (Nozzle sibling : nozzle.getHead().getNozzles()) {
            Part atHeight = sibling.getPart() == null ? expectedPart : sibling.getPart();
            if (atHeight.isPartHeightUnknown()) {
                continue;
            }
            Location actual = sibling.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location center = vision.getCameraLocationAtPartHeight(atHeight, camera, sibling,
                    actual.getRotation()).convertToUnits(LengthUnit.Millimeters);
            if (Math.hypot(actual.getX() - center.getX(), actual.getY() - center.getY()) <= 2
                    && Math.abs(actual.getZ() - center.getZ()) <= .02) {
                atCamera = true;
            }
        }
        if (!inCameraCorridor(from, target, atCamera)) {
            return false;
        }
        SafetyWorkbenchModel live = SafetyWorkbenchStore.get();
        if (!live.isCertified()) {
            return false;
        }
        long revision = live.getRevision();
        Location mm = target.convertToUnits(LengthUnit.Millimeters);
        long started = System.nanoTime();
        var plan = DynamicSafeMotionPlanner.previewBottomVision(nozzle, target, nozzle.getHead().getMachine().getSpeed());
        double planningMs = (System.nanoTime() - started) / 1e6;
        if (!plan.isOptimized() || plan.getLocations().size() != 1
                || plan.getLocations().get(0).convertToUnits(LengthUnit.Millimeters).getXyzDistanceTo(mm) > .001
                || (plan.getOriginalSeconds() - plan.getCoordinatedSeconds()) * 1000 <= planningMs + 5) {
            Logger.debug("Flying camera corridor declined for {}: {}", nozzle.getName(), plan.getReason());
            return false;
        }
        boolean submitted = submitIfCurrent(nozzle, from, target,
                () -> SafetyWorkbenchStore.get() == live && live.isCertified() && live.getRevision() == revision);
        if (submitted) {
            Logger.info("Flying camera corridor {}: certified direct entry, {} mm, revision {}",
                    nozzle.getName(), from.getXyzDistanceTo(mm), revision);
        }
        return submitted;
    }

    /** Same-direction pass order requires disjoint windows in the same local camera corridor. */
    static boolean reverseOrder(double firstX, double firstY, double secondX, double secondY,
            double approach, double exit, int direction) {
        return Double.isFinite(firstX + firstY + secondX + secondY + approach + exit)
                && (direction == 1 || direction == -1) && approach >= 1 && exit >= 1 && Math.abs(firstY - secondY) <= 4
                && (firstX - secondX) * direction > approach + exit + .1;
    }

    static double routeDistance(Location start, Location first, Location second, Location[] ends,
            double approach, double exit, int direction) {
        Location entry1 = first.derive(first.getX() - direction * approach, null, 0., null);
        Location exit1 = first.derive(first.getX() + direction * exit, null, 0., null);
        Location entry2 = second.derive(second.getX() - direction * approach, null, 0., null);
        Location exit2 = second.derive(second.getX() + direction * exit, null, 0., null);
        double endDistance = Double.POSITIVE_INFINITY;
        for (Location end : ends) {
            endDistance = Math.min(endDistance, exit2.getLinearDistanceTo(end));
        }
        return start.getLinearDistanceTo(entry1) + 2 * (approach + exit)
                + exit1.getLinearDistanceTo(entry2) + endDistance;
    }

    public static List<PlannedPlacement> orderForFlying(List<PlannedPlacement> planned) {
        return BottomVisionRouteOrder.order(planned);
    }
}
