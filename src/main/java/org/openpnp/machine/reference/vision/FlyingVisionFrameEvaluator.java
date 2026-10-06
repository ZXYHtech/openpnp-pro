/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;

/**
 * Lightweight focus metric used before the much more expensive vision
 * pipeline. It intentionally has no OpenCV dependency so it can be tested
 * independently.
 */
public final class FlyingVisionFrameEvaluator {
    private FlyingVisionFrameEvaluator() {
    }

    public static double sharpness(BufferedImage image) {
        if (image == null || image.getWidth() < 5 || image.getHeight() < 5) {
            return 0;
        }
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 240);
        double total = 0;
        long count = 0;
        for (int y = step; y < image.getHeight() - step; y += step) {
            for (int x = step; x < image.getWidth() - step; x += step) {
                int center = gray(image.getRGB(x, y));
                int laplacian = gray(image.getRGB(x - step, y))
                        + gray(image.getRGB(x + step, y))
                        + gray(image.getRGB(x, y - step))
                        + gray(image.getRGB(x, y + step))
                        - center * 4;
                total += Math.abs(laplacian);
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    static int gray(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 77 + green * 150 + blue * 29) >> 8;
    }
}
