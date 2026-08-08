/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.io.File;

import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/** Pure coordinate math for correcting Bottom Vision from a measured placement error. */
public final class BottomVisionTrialCorrection {
    private BottomVisionTrialCorrection() {
    }

    public static Result calculate(Location currentVisionOffset,
            Location nominalGlobalLocation, Location measuredGlobalLocation) {
        if (currentVisionOffset == null || nominalGlobalLocation == null
                || measuredGlobalLocation == null) {
            throw new IllegalArgumentException("Locations must not be null.");
        }
        Location nominal = nominalGlobalLocation.convertToUnits(LengthUnit.Millimeters);
        Location measured = measuredGlobalLocation.convertToUnits(LengthUnit.Millimeters);
        Location oldOffset = currentVisionOffset.convertToUnits(LengthUnit.Millimeters);

        // Jogging the top camera from the fixed nominal projection to the real component center
        // measures actual-minus-nominal in machine coordinates. BottomVisionSettings.visionOffset
        // is expressed in the component's local coordinates, so first undo the global placement
        // rotation. ReferenceBottomVision subtracts this setting from the measured alignment
        // offset, while ReferencePnpJobProcessor subtracts that alignment from the commanded place
        // location. Consequently the stored correction has the opposite sign.
        Location globalError = measured.subtract(nominal).derive(null, null, 0.0, 0.0);
        Location localError = globalError.rotateXy(-nominal.getRotation())
                .derive(null, null, 0.0, 0.0);
        Location newOffset = oldOffset.subtract(localError)
                .derive(null, null, 0.0, 0.0);
        return new Result(oldOffset, newOffset, globalError, localError,
                nominal.getRotation());
    }

    /** Reads the just-saved setting back from vision-settings.xml for persistence verification. */
    public static Location readSavedOffset(File visionSettingsFile, String settingsId)
            throws Exception {
        Configuration.VisionSettingsConfigurationHolder holder = Configuration.createSerializer()
                .read(Configuration.VisionSettingsConfigurationHolder.class, visionSettingsFile);
        for (AbstractVisionSettings settings : holder.visionSettings) {
            if (settings instanceof BottomVisionSettings
                    && settings.getId().equalsIgnoreCase(settingsId)) {
                return ((BottomVisionSettings) settings).getVisionOffset()
                        .convertToUnits(LengthUnit.Millimeters);
            }
        }
        throw new Exception("保存后的 vision-settings.xml 中找不到设置 " + settingsId);
    }

    public static void verifySavedOffset(File visionSettingsFile, String settingsId,
            Location expected) throws Exception {
        Location saved = readSavedOffset(visionSettingsFile, settingsId);
        Location expectedMm = expected.convertToUnits(LengthUnit.Millimeters);
        if (Math.abs(saved.getX() - expectedMm.getX()) > 1e-6
                || Math.abs(saved.getY() - expectedMm.getY()) > 1e-6) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "保存回读不一致：期望 X %.6f / Y %.6f，文件中为 X %.6f / Y %.6f mm",
                    expectedMm.getX(), expectedMm.getY(), saved.getX(), saved.getY()));
        }
    }

    public static final class Result {
        public final Location oldOffset;
        public final Location newOffset;
        public final Location globalError;
        public final Location localError;
        public final double placementRotation;

        private Result(Location oldOffset, Location newOffset, Location globalError,
                Location localError, double placementRotation) {
            this.oldOffset = oldOffset;
            this.newOffset = newOffset;
            this.globalError = globalError;
            this.localError = localError;
            this.placementRotation = placementRotation;
        }

        public double getMagnitudeMm() {
            return Math.hypot(globalError.getX(), globalError.getY());
        }

        /** Global shift the stored offset change will apply at this placement rotation. */
        public Location getAppliedGlobalShift() {
            return newOffset.subtract(oldOffset).rotateXy(placementRotation)
                    .derive(null, null, 0.0, 0.0);
        }
    }
}
