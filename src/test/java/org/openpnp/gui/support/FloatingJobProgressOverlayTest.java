package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

import javax.swing.JLayeredPane;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;

class FloatingJobProgressOverlayTest {
    private static final long NOW = 2000000000000L;

    private JobRunRecord running() {
        JobRunRecord record = new JobRunRecord();
        record.id = "current-run";
        record.totalPlacements = 50;
        record.placedPlacements = 25;
        record.remainingPlacements = 25;
        record.elapsedMillis = 185000;
        record.running = true;
        record.state = "运行中";
        record.estimatedCompletionTime = NOW + 120000;
        return record;
    }

    @Test
    void showsCurrentRunElapsedAndRemainingDuration() {
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        overlay.update(running(), NOW);
        assertEquals("已用 00:03:05", overlay.getElapsedText());
        assertEquals("剩余约 00:02:00", overlay.getRemainingText());
    }

    @Test
    void pauseFreezesRemainingButRetainsTheTotalElapsedTime() {
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        JobRunRecord record = running();
        overlay.update(record, NOW);
        record.state = "已暂停";
        overlay.update(record, NOW);
        record.elapsedMillis += 30000;
        record.estimatedCompletionTime += 30000;
        overlay.update(record, NOW + 30000);
        assertEquals("剩余约 00:02:00（已暂停）", overlay.getRemainingText());
        assertEquals("已用 00:03:35", overlay.getElapsedText());
        record.state = "运行中";
        overlay.update(record, NOW + 40000);
        assertEquals("剩余约 00:01:50", overlay.getRemainingText());
    }

    @Test
    void newRunDoesNotReusePreviousPausedEstimate() {
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        JobRunRecord record = running();
        record.state = "已暂停";
        overlay.update(record, NOW);
        record.id = "new-run";
        record.estimatedCompletionTime = 0;
        overlay.update(record, NOW);
        assertEquals("剩余 估算中（已暂停）", overlay.getRemainingText());
    }

    @Test
    void completionAndInterruptedRunHaveDifferentRemainingText() {
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        JobRunRecord record = running();
        record.running = false;
        overlay.update(record, NOW);
        assertEquals("剩余 --:--:--（已停止）", overlay.getRemainingText());
        record.completed = true;
        record.placedPlacements = 50;
        record.remainingPlacements = 0;
        overlay.update(record, NOW);
        assertEquals("剩余 00:00:00", overlay.getRemainingText());
    }

    @Test
    void idleHasNoStaleTimesAndDoesNotBlockCameraMouseEvents() {
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        overlay.update(running(), NOW);
        overlay.update(null, NOW);
        assertEquals("已用 00:00:00", overlay.getElapsedText());
        assertEquals("剩余 --:--:--", overlay.getRemainingText());
        assertFalse(overlay.contains(50, 50));
    }

    @Test
    void progressSurvivesCameraPaintAndWarningsStayAboveItAtSmallSizes() {
        FloatingCameraMonitor.CameraLayerPane layers = new FloatingCameraMonitor.CameraLayerPane();
        JPanel video = new JPanel();
        video.setBackground(Color.BLUE);
        FloatingJobProgressOverlay overlay = new FloatingJobProgressOverlay();
        overlay.update(running(), NOW);
        layers.add(video, Integer.valueOf(JLayeredPane.DEFAULT_LAYER));
        layers.add(overlay, Integer.valueOf(JLayeredPane.PALETTE_LAYER));
        layers.setSize(320, 180);
        layers.doLayout();
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = image.getGraphics();
        layers.paint(graphics);
        assertEquals(Color.BLUE.getRGB(), image.getRGB(160, 40));
        assertEquals(new Color(0x45C4F7).getRGB(), image.getRGB(20, 179));
        assertEquals(new Color(0x334155).getRGB(), image.getRGB(300, 179));
        assertSame(video, layers.findComponentAt(300, 160));
        JPanel warning = new JPanel();
        warning.setBackground(Color.RED);
        layers.add(warning, Integer.valueOf(JLayeredPane.MODAL_LAYER));
        layers.doLayout();
        layers.paint(graphics);
        graphics.dispose();
        assertEquals(Color.RED.getRGB(), image.getRGB(20, 179));
    }
}
