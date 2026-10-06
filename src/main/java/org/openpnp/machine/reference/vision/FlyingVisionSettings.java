/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.vision;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.Preferences;

import org.openpnp.gui.ExperimentalMotionCenterDialog;
import org.openpnp.model.Part;

/**
 * Persisted, deliberately conservative settings for experimental flying bottom
 * vision. The production path additionally requires experimental mode and an
 * explicit part whitelist.
 */
public final class FlyingVisionSettings {
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(FlyingVisionSettings.class);
    private static final String PREFIX = "OpenPnPPro.flyingVision.";

    private FlyingVisionSettings() {
    }

    public static boolean isEnabled() {
        return PREFS.getBoolean(PREFIX + "enabled", false);
    }

    public static void setEnabled(boolean value) {
        PREFS.putBoolean(PREFIX + "enabled", value);
    }

    public static boolean isProductionEnabled() {
        return PREFS.getBoolean(PREFIX + "productionEnabled", false);
    }

    public static void setProductionEnabled(boolean value) {
        PREFS.putBoolean(PREFIX + "productionEnabled", value);
    }

    public static double getSpeed() {
        return clamp(PREFS.getDouble(PREFIX + "speed", 0.10), 0.02, 0.35);
    }

    public static void setSpeed(double value) {
        PREFS.putDouble(PREFIX + "speed", clamp(value, 0.02, 0.35));
    }

    public static double getApproachDistanceMm() {
        return clamp(PREFS.getDouble(PREFIX + "approachMm", 12.0), 3.0, 40.0);
    }

    public static void setApproachDistanceMm(double value) {
        PREFS.putDouble(PREFIX + "approachMm", clamp(value, 3.0, 40.0));
    }

    public static double getExitDistanceMm() {
        return clamp(PREFS.getDouble(PREFIX + "exitMm", 12.0), 3.0, 40.0);
    }

    public static void setExitDistanceMm(double value) {
        PREFS.putDouble(PREFIX + "exitMm", clamp(value, 3.0, 40.0));
    }

    public static int getFrameCount() {
        return Math.max(3, Math.min(30, PREFS.getInt(PREFIX + "frames", 8)));
    }

    public static void setFrameCount(int value) {
        PREFS.putInt(PREFIX + "frames", Math.max(3, Math.min(30, value)));
    }

    public static int getCaptureTimeoutMs() {
        return Math.max(500, Math.min(5000, PREFS.getInt(PREFIX + "timeoutMs", 2200)));
    }

    public static void setCaptureTimeoutMs(int value) {
        PREFS.putInt(PREFIX + "timeoutMs", Math.max(500, Math.min(5000, value)));
    }

    public static double getMinimumSharpness() {
        return Math.max(0, PREFS.getDouble(PREFIX + "minimumSharpness", 7.0));
    }

    public static void setMinimumSharpness(double value) {
        PREFS.putDouble(PREFIX + "minimumSharpness", Math.max(0, value));
    }

    public static double getMaximumOffsetMm() {
        return clamp(PREFS.getDouble(PREFIX + "maximumOffsetMm", 1.0), 0.05, 5.0);
    }

    public static void setMaximumOffsetMm(double value) {
        PREFS.putDouble(PREFIX + "maximumOffsetMm", clamp(value, 0.05, 5.0));
    }

    public static double getMaximumAngleDeg() {
        return clamp(PREFS.getDouble(PREFIX + "maximumAngleDeg", 10.0), 0.5, 45.0);
    }

    public static void setMaximumAngleDeg(double value) {
        PREFS.putDouble(PREFIX + "maximumAngleDeg", clamp(value, 0.5, 45.0));
    }

    public static boolean isFallbackEnabled() {
        return PREFS.getBoolean(PREFIX + "fallback", true);
    }

    public static void setFallbackEnabled(boolean value) {
        PREFS.putBoolean(PREFIX + "fallback", value);
    }

    public static boolean isSaveDebugImages() {
        return PREFS.getBoolean(PREFIX + "saveDebugImages", true);
    }

    public static void setSaveDebugImages(boolean value) {
        PREFS.putBoolean(PREFIX + "saveDebugImages", value);
    }

    public static boolean isTimestampCompensationEnabled() {
        return PREFS.getBoolean(PREFIX + "timestampCompensation", false);
    }

    public static void setTimestampCompensationEnabled(boolean value) {
        PREFS.putBoolean(PREFIX + "timestampCompensation", value);
    }

    public static double getCameraLatencyMs() {
        return clamp(PREFS.getDouble(PREFIX + "cameraLatencyMs", 0.0), -500, 500);
    }

    public static void setCameraLatencyMs(double value) {
        PREFS.putDouble(PREFIX + "cameraLatencyMs", clamp(value, -500, 500));
    }

    public static Set<String> getEnabledPartIds() {
        String csv = PREFS.get(PREFIX + "partIds", "").trim();
        if (csv.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(csv.split("[,;\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(values::add);
        return Collections.unmodifiableSet(values);
    }

    public static void setEnabledPartIds(String value) {
        PREFS.put(PREFIX + "partIds", value == null ? "" : value.trim());
    }

    public static String getEnabledPartIdsText() {
        return String.join(", ", getEnabledPartIds());
    }

    public static boolean shouldUseFor(Part part) {
        return part != null
                && ExperimentalMotionCenterDialog.isExperimentalModeEnabled()
                && isEnabled()
                && isProductionEnabled()
                && getEnabledPartIds().contains(part.getId());
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
