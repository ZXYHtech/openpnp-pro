package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.RotatedRect;
import org.opencv.core.Point;
import org.opencv.core.Size;

class FlyingPartEdgeAngleTest {
    @BeforeAll
    static void load() throws Exception { Class.forName("org.openpnp.util.OpenCvUtils"); }

    @Test
    void longEdgesRecoverAngleDespiteOnePixelHullTiltAndCornerGlare() throws Exception {
        for (double actual : new double[] {0, 6, -5}) {
            BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(128, 128);
            g.rotate(Math.toRadians(actual));
            g.setColor(new Color(90, 90, 90));
            g.fillRect(-30, -14, 60, 28);
            g.setColor(Color.WHITE);
            g.fillRect(-30, -14, 12, 28);
            g.fillRect(18, -14, 12, 28);
            g.fillOval(28, -16, 4, 4);
            g.dispose();
            RotatedRect coarse = new RotatedRect(new Point(127.5, 127.5), new Size(60, 28), actual + 1.8);
            assertEquals(actual, FlyingPartEdgeAngle.refine(image, coarse), .4);
        }
    }
}
