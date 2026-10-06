/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components;

import java.awt.geom.Point2D;

/** Pure, double-precision mapping between a camera view and its centered image. */
public final class CameraViewCoordinateTransform {
    private CameraViewCoordinateTransform() {
    }

    public static Point2D.Double centerOffset(double viewX, double viewY,
            double imageX, double imageY, double scaledWidth, double scaledHeight,
            double unitsPerViewPixelX, double unitsPerViewPixelY) {
        requirePositive(scaledWidth, "画面宽度");
        requirePositive(scaledHeight, "画面高度");
        requirePositive(unitsPerViewPixelX, "X 像素比例");
        requirePositive(unitsPerViewPixelY, "Y 像素比例");
        double offsetX = viewX - imageX - scaledWidth / 2.0;
        double offsetY = scaledHeight / 2.0 - (viewY - imageY);
        return new Point2D.Double(offsetX * unitsPerViewPixelX,
                offsetY * unitsPerViewPixelY);
    }

    public static boolean contains(double viewX, double viewY, double imageX, double imageY,
            double scaledWidth, double scaledHeight) {
        return scaledWidth > 0 && scaledHeight > 0
                && viewX >= imageX && viewX <= imageX + scaledWidth
                && viewY >= imageY && viewY <= imageY + scaledHeight;
    }

    public static Point2D.Double viewToSource(double viewX, double viewY,
            double imageX, double imageY, double scaleRatioX, double scaleRatioY) {
        requirePositive(scaleRatioX, "X 缩放比例");
        requirePositive(scaleRatioY, "Y 缩放比例");
        return new Point2D.Double((viewX - imageX) * scaleRatioX,
                (viewY - imageY) * scaleRatioY);
    }

    /**
     * Restricts post-move template matching as the operator zooms in. At high zoom the command
     * is intentionally precise, so searching the full frame risks selecting a repeated feeder
     * pocket or component elsewhere in the image.
     */
    public static int precisionSearchRadius(int sourceExtent, double zoom) {
        requirePositive(sourceExtent, "原始画面尺寸");
        requirePositive(zoom, "画面缩放倍数");
        double radius = sourceExtent / Math.max(3.0, Math.sqrt(zoom) * 2.0);
        return (int) Math.min(sourceExtent / 2.0, Math.max(72, Math.round(radius)));
    }

    /** Dynamic safety cap for a second, vision-based correction after screen jogging. */
    public static double precisionCorrectionLimit(double commandedDistanceMm,
            double onePixelMm, double zoom, double absoluteMaximumMm) {
        requireNonNegative(commandedDistanceMm, "本次移动距离");
        requirePositive(onePixelMm, "毫米/像素");
        requirePositive(zoom, "画面缩放倍数");
        requirePositive(absoluteMaximumMm, "绝对微调上限");
        double minimumUseful = Math.max(onePixelMm * 4.0, 0.06);
        double relativeLimit = minimumUseful + commandedDistanceMm * 0.35;
        double zoomLimit = zoom >= 8.0 ? 0.25 : zoom >= 4.0 ? 0.40 : absoluteMaximumMm;
        return Math.min(absoluteMaximumMm,
                Math.max(minimumUseful, Math.min(relativeLimit, zoomLimit)));
    }

    private static void requirePositive(double value, String label) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(label + "必须是有效正数。");
        }
    }

    private static void requireNonNegative(double value, String label) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(label + "必须是有效非负数。");
        }
    }
}
