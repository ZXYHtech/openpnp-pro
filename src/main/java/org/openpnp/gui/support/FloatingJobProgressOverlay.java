package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

import javax.swing.JComponent;
import javax.swing.UIManager;

import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;

/** Camera overlay with a thin progress line and readable, mouse-transparent timing. */
@SuppressWarnings("serial")
final class FloatingJobProgressOverlay extends JComponent {
    private double progress;
    private long remainingMillis = -1;
    private String runKey;
    private boolean paused;
    private boolean completed;
    private String elapsedText = "已用 00:00:00";
    private String remainingText = "剩余 --:--:--";

    FloatingJobProgressOverlay() {
        setOpaque(false);
        setFocusable(false);
        setFont(UIManager.getFont("Label.font").deriveFont(12f));
    }

    void update(JobRunRecord record, long now) {
        String nextKey = record == null ? null : record.id + ":" + record.startTime;
        if (!Objects.equals(runKey, nextKey)) {
            remainingMillis = -1;
            paused = false;
            runKey = nextKey;
        }
        progress = record == null || record.totalPlacements <= 0 ? 0
                : Math.max(0, Math.min(1, record.placedPlacements / (double) record.totalPlacements));
        completed = record != null && record.completed;
        boolean nextPaused = record != null && record.running && "已暂停".equals(record.state);
        elapsedText = "已用 " + JobTelemetryManager.formatDuration(record == null ? 0 : record.elapsedMillis);
        if (completed) {
            remainingMillis = 0;
            remainingText = "剩余 00:00:00";
        }
        else if (record == null || !record.running) {
            remainingMillis = -1;
            remainingText = "剩余 --:--:--" + (record == null ? "" : "（已停止）");
        }
        else {
            if (!paused || !nextPaused) {
                long estimate = record.getEstimatedCompletionTime();
                remainingMillis = estimate > 0 ? Math.max(0, estimate - now) : -1;
            }
            remainingText = (remainingMillis < 0 ? "剩余 估算中" : "剩余约 "
                    + JobTelemetryManager.formatDuration(remainingMillis)) + (nextPaused ? "（已暂停）" : "");
        }
        paused = nextPaused;
        repaint();
    }

    String getElapsedText() {
        return elapsedText;
    }

    String getRemainingText() {
        return remainingText;
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            FontMetrics metrics = g.getFontMetrics(getFont());
            String combined = elapsedText + "    " + remainingText;
            boolean wrap = metrics.stringWidth(combined) + 24 > width;
            int textWidth = wrap ? Math.max(metrics.stringWidth(elapsedText), metrics.stringWidth(remainingText))
                    : metrics.stringWidth(combined);
            int rowHeight = metrics.getHeight();
            int boxHeight = rowHeight * (wrap ? 2 : 1) + 8;
            int boxWidth = Math.min(width, textWidth + 16);
            int left = Math.max(0, width - boxWidth - 5);
            int top = Math.max(0, height - 7 - boxHeight);
            g.setColor(new Color(0, 0, 0, 185));
            g.fillRoundRect(left, top, boxWidth, boxHeight, 6, 6);
            g.setFont(getFont());
            g.setColor(Color.WHITE);
            int baseline = top + 4 + metrics.getAscent();
            if (wrap) {
                g.drawString(elapsedText, Math.max(4, width - metrics.stringWidth(elapsedText) - 12), baseline);
                g.drawString(remainingText, Math.max(4, width - metrics.stringWidth(remainingText) - 12), baseline + rowHeight);
            }
            else {
                g.drawString(combined, Math.max(4, width - textWidth - 12), baseline);
            }
            g.setColor(new Color(0x334155));
            g.fillRect(0, Math.max(0, height - 3), width, 3);
            g.setColor(completed ? new Color(0x42D392) : paused ? new Color(0xFFC857) : new Color(0x45C4F7));
            g.fillRect(0, Math.max(0, height - 3), (int) Math.round(width * progress), 3);
        }
        finally {
            g.dispose();
        }
    }
}
