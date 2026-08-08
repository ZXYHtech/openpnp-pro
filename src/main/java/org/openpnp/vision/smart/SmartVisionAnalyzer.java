/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.awt.image.BufferedImage;

/** CPU-only ROI analysis. It intentionally has no OpenCV/native dependency. */
public final class SmartVisionAnalyzer {
    private SmartVisionAnalyzer() {
    }

    public static RoiAnalysis analyze(BufferedImage image) {
        if (image == null || image.getWidth() < 2 || image.getHeight() < 2) {
            throw new IllegalArgumentException("识别区域至少需要 2×2 像素");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int step = Math.max(1, (int) Math.sqrt((width * (long) height) / 120000.0));
        long count = 0;
        double sum = 0;
        double sumSquares = 0;
        long edges = 0;
        long edgeSamples = 0;
        int[] histogram = new int[256];
        for (int y = 0; y < height; y += step) {
            int previous = -1;
            for (int x = 0; x < width; x += step) {
                int gray = gray(image.getRGB(x, y));
                histogram[gray]++;
                sum += gray;
                sumSquares += gray * (double) gray;
                count++;
                if (previous >= 0) {
                    if (Math.abs(gray - previous) >= 28) {
                        edges++;
                    }
                    edgeSamples++;
                }
                previous = gray;
                if (y >= step) {
                    int above = gray(image.getRGB(x, y - step));
                    if (Math.abs(gray - above) >= 28) {
                        edges++;
                    }
                    edgeSamples++;
                }
            }
        }
        double mean = sum / count;
        double variance = Math.max(0, sumSquares / count - mean * mean);
        int median = percentile(histogram, count, 0.5);
        long foreground = 0;
        int separation = Math.max(18, (int) Math.sqrt(variance) / 2);
        for (int value = 0; value < histogram.length; value++) {
            if (Math.abs(value - median) >= separation) {
                foreground += histogram[value];
            }
        }
        return new RoiAnalysis(width, height, mean, Math.sqrt(variance),
                edgeSamples == 0 ? 0 : edges / (double) edgeSamples,
                foreground / (double) count);
    }

    private static int gray(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 77 + green * 150 + blue * 29) >> 8;
    }

    private static int percentile(int[] histogram, long count, double percentile) {
        long threshold = Math.max(1, Math.round(count * percentile));
        long accumulated = 0;
        for (int i = 0; i < histogram.length; i++) {
            accumulated += histogram[i];
            if (accumulated >= threshold) {
                return i;
            }
        }
        return 255;
    }
}
