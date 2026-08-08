/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

public class CameraViewPrecisionLocatorTest {
    @Test
    public void relocatesTheSelectedPointAfterTheSceneMoves() {
        BufferedImage before = frameWithTarget(320, 240, 82, 67);
        CameraViewPrecisionLocator.Target target =
                CameraViewPrecisionLocator.capture(before, 82, 67);

        BufferedImage after = frameWithTarget(320, 240, 173, 128);
        CameraViewPrecisionLocator.Result result =
                CameraViewPrecisionLocator.locate(after, target);

        assertNotNull(result);
        assertTrue(result.confidence >= CameraViewPrecisionLocator.MIN_CONFIDENCE);
        assertEquals(173, result.sourceX, 0.5);
        assertEquals(128, result.sourceY, 0.5);
    }

    @Test
    public void clipsTheTemplateAtTheImageEdgeWithoutMovingItsAnchor() {
        BufferedImage before = frameWithTarget(160, 120, 8, 9);
        CameraViewPrecisionLocator.Target target =
                CameraViewPrecisionLocator.capture(before, 8, 9);

        assertNotNull(target);
        assertEquals(8, target.anchorX, 0.01);
        assertEquals(9, target.anchorY, 0.01);
    }

    @Test
    public void refusesATexturelessSelection() {
        BufferedImage flat = new BufferedImage(160, 120, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(new Color(80, 80, 80));
            graphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        }
        finally {
            graphics.dispose();
        }
        CameraViewPrecisionLocator.Target target =
                CameraViewPrecisionLocator.capture(flat, 80, 60);

        assertNotNull(target);
        assertNull(CameraViewPrecisionLocator.locate(flat, target));
    }

    @Test
    public void localSearchIgnoresAnIdenticalDistantTarget() {
        BufferedImage before = frameWithTarget(320, 240, 70, 70);
        CameraViewPrecisionLocator.Target target =
                CameraViewPrecisionLocator.capture(before, 70, 70);
        BufferedImage after = frameWithTarget(320, 240, 160, 120);
        Graphics2D graphics = after.createGraphics();
        try {
            drawTarget(graphics, 35, 35);
        }
        finally {
            graphics.dispose();
        }

        CameraViewPrecisionLocator.Result result = CameraViewPrecisionLocator.locateNear(
                after, target, 160, 120, 72, 72);

        assertNotNull(result);
        assertEquals(160, result.sourceX, 0.5);
        assertEquals(120, result.sourceY, 0.5);
    }

    private static BufferedImage frameWithTarget(int width, int height, int x, int y) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(44, 57, 66));
            graphics.fillRect(0, 0, width, height);
            drawTarget(graphics, x, y);
        }
        finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawTarget(Graphics2D graphics, int x, int y) {
        graphics.setColor(new Color(196, 205, 211));
        graphics.fillRoundRect(x - 14, y - 10, 29, 21, 6, 6);
        graphics.setColor(new Color(17, 22, 27));
        graphics.fillRect(x - 8, y - 5, 17, 11);
        graphics.setColor(new Color(245, 180, 28));
        graphics.fillRect(x - 11, y - 7, 4, 15);
        graphics.setColor(new Color(70, 210, 150));
        graphics.fillOval(x + 2, y - 3, 6, 6);
    }
}
