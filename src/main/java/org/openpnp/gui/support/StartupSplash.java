package org.openpnp.gui.support;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.lang.reflect.InvocationTargetException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * Lightweight startup window that can repaint progress while the main frame is being assembled on
 * the event dispatch thread.
 */
public final class StartupSplash {
    private static JWindow window;
    private static JProgressBar progressBar;
    private static JLabel statusLabel;
    private static JLabel percentLabel;

    private StartupSplash() {
    }

    public static void showSplash() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        runOnUiAndWait(() -> {
            if (window != null) {
                return;
            }
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setContentPane(createContent());
            window.pack();
            window.setLocationRelativeTo(null);
            window.setVisible(true);
        });
    }

    public static void update(int percent, String status) {
        runOnUiAndWait(() -> {
            if (window == null) {
                return;
            }
            int boundedPercent = Math.max(0, Math.min(100, percent));
            progressBar.setValue(boundedPercent);
            statusLabel.setText(status);
            percentLabel.setText(boundedPercent + "%");
            window.getRootPane().paintImmediately(
                    0, 0, window.getRootPane().getWidth(), window.getRootPane().getHeight());
        });
    }

    public static void close() {
        runOnUiAndWait(() -> {
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                window = null;
            }
        });
    }

    private static JPanel createContent() {
        JPanel panel = new GradientPanel();
        panel.setLayout(new BorderLayout());
        panel.setPreferredSize(new Dimension(620, 330));
        panel.setBorder(BorderFactory.createLineBorder(new Color(63, 72, 77), 1));

        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        titlePanel.setBorder(BorderFactory.createEmptyBorder(58, 58, 20, 58));
        JLabel eyebrow = new JLabel("PRECISION PLACEMENT WORKSPACE");
        eyebrow.setForeground(new Color(142, 158, 166));
        eyebrow.setFont(eyebrow.getFont().deriveFont(Font.BOLD, 11f));
        JLabel title = new JLabel("OPENPNP Pro");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 35f));
        JLabel logo = new JLabel(ProBranding.icon(82));
        logo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 26));
        JLabel subtitle = new JLabel("Machine intelligence, ready for production");
        subtitle.setForeground(new Color(174, 182, 186));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        titlePanel.add(eyebrow);
        titlePanel.add(Box.createVerticalStrut(10));
        titlePanel.add(title);
        titlePanel.add(Box.createVerticalStrut(8));
        titlePanel.add(subtitle);
        JPanel brandPanel = new JPanel(new BorderLayout());
        brandPanel.setOpaque(false);
        brandPanel.add(logo, BorderLayout.WEST);
        brandPanel.add(titlePanel, BorderLayout.CENTER);
        brandPanel.setBorder(BorderFactory.createEmptyBorder(0, 34, 0, 0));
        panel.add(brandPanel, BorderLayout.CENTER);

        JPanel progressPanel = new JPanel(new BorderLayout(12, 8));
        progressPanel.setOpaque(false);
        progressPanel.setBorder(BorderFactory.createEmptyBorder(15, 58, 45, 58));
        statusLabel = new JLabel("Preparing startup...");
        statusLabel.setForeground(new Color(199, 205, 208));
        percentLabel = new JLabel("0%");
        percentLabel.setForeground(new Color(142, 158, 166));
        percentLabel.setFont(percentLabel.getFont().deriveFont(Font.BOLD));
        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(500, 6));
        progressBar.setBorderPainted(false);
        progressBar.setForeground(new Color(113, 138, 149));
        progressBar.setBackground(new Color(47, 53, 57));
        progressPanel.add(statusLabel, BorderLayout.WEST);
        progressPanel.add(percentLabel, BorderLayout.EAST);
        progressPanel.add(progressBar, BorderLayout.SOUTH);
        panel.add(progressPanel, BorderLayout.SOUTH);
        return panel;
    }

    private static void runOnUiAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static class GradientPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D graphics2d = (Graphics2D) graphics.create();
            graphics2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics2d.setPaint(new GradientPaint(0, 0, new Color(25, 29, 31),
                    getWidth(), getHeight(), new Color(37, 42, 45)));
            graphics2d.fillRect(0, 0, getWidth(), getHeight());
            graphics2d.setColor(new Color(116, 132, 140, 22));
            graphics2d.fillOval(getWidth() - 210, -90, 310, 310);
            graphics2d.setColor(new Color(92, 110, 118, 18));
            graphics2d.fillOval(-120, getHeight() - 160, 280, 280);
            graphics2d.dispose();
        }
    }
}
