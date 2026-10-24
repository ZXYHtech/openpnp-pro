/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

/**
 * Reversible projection helpers for the safety workbench. The mirrored front
 * view makes increasing machine X move left on screen, matching an operator
 * looking at the machine from the front.
 */
public final class SafetyWorkbenchViewTransform {
    private SafetyWorkbenchViewTransform() {
    }

    public static double clampPitch(double pitch) {
        return Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch));
    }

    public static boolean canMapGround(double pitch) {
        return Double.isFinite(pitch) && Math.abs(Math.sin(pitch)) >= Math.sin(Math.toRadians(3));
    }

    public static double vertical(double depth, double height, double pitch) {
        return depth * Math.sin(pitch) - height * Math.cos(pitch);
    }

    public static double horizontal(double dx, double dy, double yaw,
            boolean machineFrontView) {
        double rotated = Math.cos(yaw) * dx - Math.sin(yaw) * dy;
        return machineFrontView ? -rotated : rotated;
    }

    public static double depth(double dx, double dy, double yaw) {
        return Math.sin(yaw) * dx + Math.cos(yaw) * dy;
    }

    public static double[] worldDelta(double horizontal, double depth, double yaw,
            boolean machineFrontView) {
        double rotatedHorizontal = machineFrontView ? -horizontal : horizontal;
        double dx = Math.cos(yaw) * rotatedHorizontal + Math.sin(yaw) * depth;
        double dy = -Math.sin(yaw) * rotatedHorizontal + Math.cos(yaw) * depth;
        return new double[] {dx, dy};
    }
}
