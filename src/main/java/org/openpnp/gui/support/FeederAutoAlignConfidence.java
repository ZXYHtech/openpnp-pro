/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

/** Resolves a global confidence and an optional absolute per-feeder override. */
public final class FeederAutoAlignConfidence {
    public static final double MIN_PERCENT = 50.0;
    public static final double MAX_PERCENT = 99.5;

    private FeederAutoAlignConfidence() {}

    public static double boundPercent(double percent) {
        if (!Double.isFinite(percent)) {
            throw new IllegalArgumentException("纠偏置信度必须是有效数字。");
        }
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    /**
     * A feeder override is the final confidence threshold, not a second percentage
     * that needs to be added or multiplied. A null override follows the global value.
     */
    public static double resolveOverridePercent(double globalPercent, Double feederOverride) {
        return boundPercent(feederOverride == null ? globalPercent : feederOverride);
    }

    public static double resolveOverrideRatio(double globalPercent, Double feederOverride) {
        return resolveOverridePercent(globalPercent, feederOverride) / 100.0;
    }

    /** Kept for reading beta36 percentage-point settings during migration. */
    public static double resolvePercent(double globalPercent, double feederAdjustment) {
        if (!Double.isFinite(globalPercent) || !Double.isFinite(feederAdjustment)) {
            throw new IllegalArgumentException("纠偏置信度必须是有效数字。");
        }
        return boundPercent(globalPercent + feederAdjustment);
    }

    public static double resolveRatio(double globalPercent, double feederAdjustment) {
        return resolvePercent(globalPercent, feederAdjustment) / 100.0;
    }
}
