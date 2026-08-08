package org.openpnp.gui.support;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.Utils2D;

/** Geometry and safety checks for task feeder sprocket-hole auto alignment. */
public final class TaskFeederAutoAlignSupport {
    public static final double MAX_HOLE_SHIFT_MM = 1.35;
    public static final double MAX_ANGLE_CHANGE_DEGREES = 20.0;
    public static final double MAX_PITCH_RESIDUAL_MM = 0.75;

    public static final class Result {
        public final Location originalReference;
        public final Location originalLast;
        public final Location correctedReference;
        public final Location correctedLast;
        public final double referenceShiftMm;
        public final double lastShiftMm;
        public final double angleChangeDegrees;
        public final long holeIntervals;
        public final double confidence;

        private Result(Location originalReference, Location originalLast,
                Location correctedReference, Location correctedLast, double referenceShiftMm,
                double lastShiftMm, double angleChangeDegrees, long holeIntervals,
                double confidence) {
            this.originalReference = originalReference;
            this.originalLast = originalLast;
            this.correctedReference = correctedReference;
            this.correctedLast = correctedLast;
            this.referenceShiftMm = referenceShiftMm;
            this.lastShiftMm = lastShiftMm;
            this.angleChangeDegrees = angleChangeDegrees;
            this.holeIntervals = holeIntervals;
            this.confidence = confidence;
        }
    }

    private TaskFeederAutoAlignSupport() {
    }

    public static Result calculate(Location originalReference, Location originalLast,
            Location detectedReference, Location detectedLast, double holePitchMm)
            throws Exception {
        if (originalReference == null || originalLast == null
                || detectedReference == null || detectedLast == null) {
            throw new Exception("定位孔坐标不完整，无法自动矫正。");
        }
        if (!Double.isFinite(holePitchMm) || holePitchMm <= 0) {
            throw new Exception("定位孔节距必须大于 0 mm。");
        }

        Location oldReferenceMm = originalReference.convertToUnits(LengthUnit.Millimeters);
        Location oldLastMm = originalLast.convertToUnits(LengthUnit.Millimeters);
        Location foundReferenceMm = detectedReference.convertToUnits(LengthUnit.Millimeters);
        Location foundLastMm = detectedLast.convertToUnits(LengthUnit.Millimeters);

        double oldSpan = oldReferenceMm.getLinearDistanceTo(oldLastMm);
        double foundSpan = foundReferenceMm.getLinearDistanceTo(foundLastMm);
        if (!Double.isFinite(oldSpan) || oldSpan < holePitchMm * 0.8) {
            throw new Exception("首、末定位孔距离过短，请先手动定义至少两个定位孔。");
        }
        long holeIntervals = Math.round(foundSpan / holePitchMm);
        if (holeIntervals < 1) {
            throw new Exception("识别到的两个定位孔不是有效的编带孔序列。");
        }
        double pitchResidual = Math.abs(foundSpan - holeIntervals * holePitchMm);
        if (pitchResidual > MAX_PITCH_RESIDUAL_MM) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "两孔距离 %.3f mm 不符合 %.3f mm 孔距（余差 %.3f mm）。",
                    foundSpan, holePitchMm, pitchResidual));
        }

        double referenceShift = oldReferenceMm.getLinearDistanceTo(foundReferenceMm);
        double lastShift = oldLastMm.getLinearDistanceTo(foundLastMm);
        if (referenceShift > MAX_HOLE_SHIFT_MM || lastShift > MAX_HOLE_SHIFT_MM) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "定位孔偏移超出自动矫正安全范围：首孔 %.3f mm，末孔 %.3f mm（上限 %.1f mm）。",
                    referenceShift, lastShift, MAX_HOLE_SHIFT_MM));
        }

        double oldAngle = Math.toDegrees(Math.atan2(oldLastMm.getY() - oldReferenceMm.getY(),
                oldLastMm.getX() - oldReferenceMm.getX()));
        double foundAngle = Math.toDegrees(Math.atan2(foundLastMm.getY() - foundReferenceMm.getY(),
                foundLastMm.getX() - foundReferenceMm.getX()));
        double angleChange = Utils2D.angleNorm(foundAngle - oldAngle, 180);
        if (Math.abs(angleChange) > MAX_ANGLE_CHANGE_DEGREES) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "编带方向变化 %.2f° 超出安全范围 %.1f°，请检查是否识别到相邻飞达。",
                    angleChange, MAX_ANGLE_CHANGE_DEGREES));
        }

        Location correctedReferenceMm = foundReferenceMm.derive(null, null,
                oldReferenceMm.getZ(), oldReferenceMm.getRotation());
        Location correctedLastMm = foundLastMm.derive(null, null,
                oldLastMm.getZ(), oldLastMm.getRotation());
        double shiftQuality = Math.max(0, 1.0 - Math.max(referenceShift, lastShift)
                / MAX_HOLE_SHIFT_MM);
        double pitchQuality = Math.max(0, 1.0 - pitchResidual / MAX_PITCH_RESIDUAL_MM);
        double angleQuality = Math.max(0, 1.0 - Math.abs(angleChange)
                / MAX_ANGLE_CHANGE_DEGREES);
        double confidence = 0.55 + 0.20 * shiftQuality + 0.15 * pitchQuality
                + 0.10 * angleQuality;
        return new Result(originalReference, originalLast,
                correctedReferenceMm.convertToUnits(originalReference.getUnits()),
                correctedLastMm.convertToUnits(originalLast.getUnits()), referenceShift, lastShift,
                angleChange, holeIntervals, confidence);
    }
}
