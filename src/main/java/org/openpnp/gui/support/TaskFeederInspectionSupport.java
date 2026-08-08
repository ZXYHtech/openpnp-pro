package org.openpnp.gui.support;

import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.model.Location;

/** Geometry and rollback-safe update support for task feeder inspection. */
public final class TaskFeederInspectionSupport {
    private TaskFeederInspectionSupport() {
    }

    public static Result applyXyCorrection(ReferenceFeeder feeder, Location measuredPick,
            Double rotationOverride) throws Exception {
        if (feeder == null || measuredPick == null) {
            throw new IllegalArgumentException("Feeder and measured pick location are required.");
        }
        Location originalBase = feeder.getLocation();
        Location originalReference = null;
        Location originalLast = null;
        if (feeder instanceof ReferenceStripFeeder) {
            originalReference = ((ReferenceStripFeeder) feeder).getReferenceHoleLocation();
            originalLast = ((ReferenceStripFeeder) feeder).getLastHoleLocation();
        }
        Location rotatedBase = originalBase.derive(null, null, originalBase.getZ(),
                rotationOverride == null ? originalBase.getRotation() : rotationOverride);
        try {
            // Some feeder implementations derive the active pocket from their base rotation.
            // Apply the candidate rotation first, then measure the derived pick error.
            feeder.setLocation(rotatedBase);
            Location predictedPick = feeder.getPickLocation();
            if (predictedPick == null) {
                throw new Exception("Feeder returned no pick location.");
            }
            Location predicted = predictedPick.convertToUnits(rotatedBase.getUnits());
            Location measured = measuredPick.convertToUnits(rotatedBase.getUnits());
            double correctionX = measured.getX() - predicted.getX();
            double correctionY = measured.getY() - predicted.getY();
            Location correctedBase;
            Location correctedReference = null;
            Location correctedLast = null;
            if (feeder instanceof ReferenceStripFeeder) {
                // ReferenceStripFeeder derives XY from its sprocket-hole line. Moving only the
                // generic feeder location would change the reported angle but not the pick XY.
                ReferenceStripFeeder stripFeeder = (ReferenceStripFeeder) feeder;
                correctedBase = rotatedBase;
                correctedReference = translateXy(originalReference, correctionX, correctionY,
                        rotatedBase.getUnits());
                correctedLast = translateXy(originalLast, correctionX, correctionY,
                        rotatedBase.getUnits());
                stripFeeder.setReferenceHoleLocation(correctedReference);
                stripFeeder.setLastHoleLocation(correctedLast);
            }
            else {
                correctedBase = calculateCorrectedBase(rotatedBase, predictedPick, measuredPick);
                feeder.setLocation(correctedBase);
            }
            Location correctedPick = feeder.getPickLocation();
            return new Result(originalBase, correctedBase, predictedPick, correctedPick,
                    correctionX, correctionY, originalReference, originalLast,
                    correctedReference, correctedLast);
        }
        catch (Exception exception) {
            feeder.setLocation(originalBase);
            if (feeder instanceof ReferenceStripFeeder) {
                ((ReferenceStripFeeder) feeder).setReferenceHoleLocation(originalReference);
                ((ReferenceStripFeeder) feeder).setLastHoleLocation(originalLast);
            }
            throw exception;
        }
    }

    static Location calculateCorrectedBase(Location rotatedBase, Location predictedPick,
            Location measuredPick) {
        Location predicted = predictedPick.convertToUnits(rotatedBase.getUnits());
        Location measured = measuredPick.convertToUnits(rotatedBase.getUnits());
        return rotatedBase.derive(rotatedBase.getX() + measured.getX() - predicted.getX(),
                rotatedBase.getY() + measured.getY() - predicted.getY(), rotatedBase.getZ(),
                rotatedBase.getRotation());
    }

    static Location translateXy(Location original, double correctionX, double correctionY,
            org.openpnp.model.LengthUnit correctionUnits) {
        Location converted = original.convertToUnits(correctionUnits);
        Location corrected = converted.derive(converted.getX() + correctionX,
                converted.getY() + correctionY, converted.getZ(), converted.getRotation());
        return corrected.convertToUnits(original.getUnits());
    }

    public static final class Result {
        public final Location originalBase;
        public final Location correctedBase;
        public final Location predictedPick;
        public final Location correctedPick;
        public final double correctionX;
        public final double correctionY;
        public final Location originalReference;
        public final Location originalLast;
        public final Location correctedReference;
        public final Location correctedLast;

        private Result(Location originalBase, Location correctedBase, Location predictedPick,
                Location correctedPick, double correctionX, double correctionY,
                Location originalReference, Location originalLast, Location correctedReference,
                Location correctedLast) {
            this.originalBase = originalBase;
            this.correctedBase = correctedBase;
            this.predictedPick = predictedPick;
            this.correctedPick = correctedPick;
            this.correctionX = correctionX;
            this.correctionY = correctionY;
            this.originalReference = originalReference;
            this.originalLast = originalLast;
            this.correctedReference = correctedReference;
            this.correctedLast = correctedLast;
        }

        public boolean usesSprocketHoleGeometry() {
            return originalReference != null && originalLast != null;
        }

        public void rollback(ReferenceFeeder feeder) {
            feeder.setLocation(originalBase);
            if (usesSprocketHoleGeometry() && feeder instanceof ReferenceStripFeeder) {
                ((ReferenceStripFeeder) feeder).setReferenceHoleLocation(originalReference);
                ((ReferenceStripFeeder) feeder).setLastHoleLocation(originalLast);
            }
        }
    }
}
