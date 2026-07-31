package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class FlyingVisionFrameEvaluatorTest {
    @Test
    void sharpEdgesScoreHigherThanAFlatFrame() {
        BufferedImage flat = new BufferedImage(120, 80, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D flatGraphics = flat.createGraphics();
        flatGraphics.setColor(Color.GRAY);
        flatGraphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        flatGraphics.dispose();

        BufferedImage checkerboard =
                new BufferedImage(120, 80, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = checkerboard.createGraphics();
        for (int y = 0; y < checkerboard.getHeight(); y += 8) {
            for (int x = 0; x < checkerboard.getWidth(); x += 8) {
                graphics.setColor(((x + y) / 8) % 2 == 0 ? Color.BLACK : Color.WHITE);
                graphics.fillRect(x, y, 8, 8);
            }
        }
        graphics.dispose();

        assertEquals(0, FlyingVisionFrameEvaluator.sharpness(null));
        assertTrue(FlyingVisionFrameEvaluator.sharpness(flat) < 1);
        assertTrue(FlyingVisionFrameEvaluator.sharpness(checkerboard)
                > FlyingVisionFrameEvaluator.sharpness(flat) + 10);
    }
}
