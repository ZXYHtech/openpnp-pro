/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

/** Lightweight, immutable measurements taken from a selected camera region. */
public final class RoiAnalysis {
    private final int width;
    private final int height;
    private final double brightness;
    private final double contrast;
    private final double edgeDensity;
    private final double foregroundRatio;

    public RoiAnalysis(int width, int height, double brightness, double contrast,
            double edgeDensity, double foregroundRatio) {
        this.width = width;
        this.height = height;
        this.brightness = brightness;
        this.contrast = contrast;
        this.edgeDensity = edgeDensity;
        this.foregroundRatio = foregroundRatio;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getBrightness() { return brightness; }
    public double getContrast() { return contrast; }
    public double getEdgeDensity() { return edgeDensity; }
    public double getForegroundRatio() { return foregroundRatio; }
    public double getAspectRatio() { return height == 0 ? 0 : (double) width / height; }

    public boolean isTooDark() { return brightness < 55; }
    public boolean isTooBright() { return brightness > 205; }
    public boolean isLowContrast() { return contrast < 24; }

    public String toChineseSummary() {
        return String.format("ROI %d×%d · 亮度 %.1f/255 · 对比度 %.1f · 边缘 %.1f%% · 前景 %.1f%%",
                width, height, brightness, contrast, edgeDensity * 100, foregroundRatio * 100);
    }
}
