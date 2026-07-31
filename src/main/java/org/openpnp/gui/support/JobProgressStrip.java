/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;

/** Compact live production indicators integrated with the main status progress bar. */
@SuppressWarnings("serial")
public class JobProgressStrip extends JPanel {
    private final JobTelemetryManager telemetry;
    private final JLabel elapsed = createMetric("◷", "本次任务总耗时：从任务启动到当前或结束的时间");
    private final JLabel eta = createMetric("⌛", "预计完成时间：按本次任务当前有效贴片速度动态估算");
    private final JLabel remaining = createMetric("Σ", "剩余贴片数量：当前仍未完成的有效器件数量");
    private final JLabel speed = createMetric("↗", "平均速度：本次任务每小时完成的器件数量（CPH）");

    public JobProgressStrip(JobTelemetryManager telemetry) {
        super(new FlowLayout(FlowLayout.LEFT, 3, 0));
        this.telemetry = telemetry;
        setOpaque(false);
        add(elapsed);
        add(eta);
        add(remaining);
        add(speed);
        new Timer(1000, event -> refresh()).start();
        refresh();
    }

    private JLabel createMetric(String icon, String tooltip) {
        JLabel label = new JLabel(icon + " —");
        label.setToolTipText(tooltip);
        label.setOpaque(true);
        label.setForeground(ModernTheme.isDark() ? new Color(0xE8F1FB) : new Color(0x17324D));
        label.setBackground(ModernTheme.isDark() ? new Color(0x1B2B3D) : new Color(0xEAF2FA));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10.5f));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernTheme.border()),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        label.setPreferredSize(new Dimension(70, 22));
        return label;
    }

    private void refresh() {
        JobRunRecord current = telemetry.getCurrent();
        if (current == null) {
            elapsed.setText("◷ 00:00");
            eta.setText("⌛ --:--");
            remaining.setText("Σ 0");
            speed.setText("↗ 0");
            return;
        }
        elapsed.setText("◷ " + shortDuration(current.elapsedMillis));
        long estimated = current.getEstimatedCompletionTime();
        eta.setText("⌛ " + (estimated == 0 ? "--:--"
                : new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(estimated))));
        remaining.setText("Σ " + current.remainingPlacements);
        speed.setText("↗ " + Math.round(current.averageCph));
    }

    private String shortDuration(long millis) {
        long totalMinutes = Math.max(0, millis / 60000);
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                totalMinutes / 60, totalMinutes % 60);
    }
}