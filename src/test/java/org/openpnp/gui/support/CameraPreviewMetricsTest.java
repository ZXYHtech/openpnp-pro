package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.CameraPreviewMetrics.Snapshot;

class CameraPreviewMetricsTest {
    @Test
    void measuresCaptureDisplayJitterDropsAndDurations() {
        CameraPreviewMetrics metrics = new CameraPreviewMetrics(5_000_000_000L);
        long start = 1_000_000_000L;
        metrics.reset(start);
        BufferedImage image = image(Color.GREEN);

        metrics.recordFrame(image, start);
        metrics.recordFrame(image, start + 100_000_000L);
        metrics.recordFrame(image, start + 200_000_000L);
        metrics.recordDisplay(start);
        metrics.recordDisplay(start + 50_000_000L);
        metrics.recordDisplay(start + 100_000_000L);
        metrics.recordDroppedFrame();
        metrics.recordRawCaptureDuration(4, start);
        metrics.recordRawCaptureDuration(6, start + 100_000_000L);
        metrics.recordCaptureDuration(5, start);
        metrics.recordCaptureDuration(7, start + 100_000_000L);
        metrics.recordProcessingDuration(1, start);
        metrics.recordProcessingDuration(3, start + 100_000_000L);
        metrics.recordPreviewLoopDuration(8, start);
        metrics.recordPreviewLoopDuration(10, start + 100_000_000L);
        metrics.recordRenderDuration(2, start);
        metrics.recordRenderDuration(4, start + 100_000_000L);

        Snapshot snapshot = metrics.snapshot(start + 200_000_000L);

        assertEquals(10, snapshot.captureFps, 0.001);
        assertEquals(20, snapshot.displayFps, 0.001);
        assertEquals(100, snapshot.frameIntervalAverageMs, 0.001);
        assertEquals(0, snapshot.frameIntervalJitterMs, 0.001);
        assertEquals(100, snapshot.maximumStallMs, 0.001);
        assertEquals(5, snapshot.rawCaptureAverageMs, 0.001);
        assertEquals(6, snapshot.captureAverageMs, 0.001);
        assertEquals(5, snapshot.captureMinimumMs, 0.001);
        assertEquals(7, snapshot.captureMaximumMs, 0.001);
        assertEquals(2, snapshot.processingAverageMs, 0.001);
        assertEquals(9, snapshot.previewLoopAverageMs, 0.001);
        assertEquals(3, snapshot.renderAverageMs, 0.001);
        assertEquals(1, snapshot.droppedFrames);
        assertEquals(2, snapshot.duplicateFrames);
    }

    @Test
    void imageFingerprintDetectsContentChanges() {
        assertNotEquals(CameraPreviewMetrics.fingerprint(image(Color.BLACK)),
                CameraPreviewMetrics.fingerprint(image(Color.WHITE)));
    }

    private static BufferedImage image(Color color) {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics graphics = image.getGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }
}
