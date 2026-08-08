/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.util.List;

/** CPU-only aggregation for disk-backed historical vision samples. */
public final class VisionDatasetAnalyzer {
    private VisionDatasetAnalyzer() {
    }

    public static VisionDatasetAnalysis analyze(List<OfflineVisionSample> samples) {
        int count = 0;
        double width = 0, height = 0, brightness = 0, contrast = 0, edges = 0, foreground = 0;
        double minBrightness = Double.POSITIVE_INFINITY, maxBrightness = Double.NEGATIVE_INFINITY;
        double minContrast = Double.POSITIVE_INFINITY, maxContrast = Double.NEGATIVE_INFINITY;
        for (OfflineVisionSample sample : samples) {
            if (sample == null || !sample.isIncluded()) {
                continue;
            }
            RoiAnalysis item = sample.getAnalysis();
            count++;
            width += item.getWidth();
            height += item.getHeight();
            brightness += item.getBrightness();
            contrast += item.getContrast();
            edges += item.getEdgeDensity();
            foreground += item.getForegroundRatio();
            minBrightness = Math.min(minBrightness, item.getBrightness());
            maxBrightness = Math.max(maxBrightness, item.getBrightness());
            minContrast = Math.min(minContrast, item.getContrast());
            maxContrast = Math.max(maxContrast, item.getContrast());
        }
        if (count == 0) {
            throw new IllegalArgumentException("请至少勾选一张有效历史图片");
        }
        RoiAnalysis representative = new RoiAnalysis((int) Math.round(width / count),
                (int) Math.round(height / count), brightness / count, contrast / count,
                edges / count, foreground / count);
        return new VisionDatasetAnalysis(count, representative, minBrightness, maxBrightness,
                minContrast, maxContrast);
    }
}
