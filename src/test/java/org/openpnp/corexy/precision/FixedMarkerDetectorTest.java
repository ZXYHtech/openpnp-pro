package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FixedMarkerDetectorTest {
    @Test
    void recordedFailureWithBrightNeighboringPadIsDetectedWithoutRelaxingDiameter() throws Exception {
        BufferedImage image = ImageIO.read(getClass().getResource("/corexy/marker-with-bright-pad.png"));
        MarkerDetector.Detection d = new MarkerDetector().detect(image, 638.901030, 348.554572,
                33.810058, 67.620116);
        assertEquals(637.5, d.x, 1);
        assertEquals(346.5, d.y, 1);
        assertTrue(d.diameter >= 33.810058 * .7 && d.diameter <= 33.810058 * 1.3);
        assertThrows(IllegalStateException.class, () -> new MarkerDetector().detect(image,
                638.901030, 348.554572, 15, 67.620116));
    }

    @Test
    void fullCircleAtSearchEdgeIsKeptButImageEdgeClippingIsRejected() {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 320, 240);
        g.setColor(Color.BLACK);
        g.fillOval(204, 104, 32, 32);
        g.dispose();
        MarkerDetector.Detection d = new MarkerDetector().detect(image, 160, 120, 32, 62);
        assertEquals(219.5, d.x, 1);
        assertEquals(119.5, d.y, 1);
        BufferedImage clipped = image.getSubimage(210, 0, 110, 240);
        assertThrows(IllegalStateException.class, () -> new MarkerDetector().detect(clipped, 10, 120, 32, 62));
    }
}