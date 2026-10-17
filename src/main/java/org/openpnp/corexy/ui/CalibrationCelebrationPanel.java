package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.openpnp.corexy.precision.PrecisionProfile;

/** A finite, silent celebration with measured statistics and explicit live enable state. */
public final class CalibrationCelebrationPanel extends JPanel {
    private final JLabel title = label("等待测量结果", 40, Color.WHITE);
    private final JLabel status = label("完成校准或验证后显示实测结果", 18, new Color(115, 255, 195));
    private final JPanel metrics = new JPanel(new GridLayout(1, 3, 18, 0));
    private final JLabel details = label("", 14, Color.WHITE);
    private int tick;
    private final javax.swing.Timer animation = new javax.swing.Timer(40, event -> {
        tick++;
        repaint();
        if (tick > 180) { ((javax.swing.Timer) event.getSource()).stop(); }
    });

    public CalibrationCelebrationPanel() {
        super(new BorderLayout(18, 18));
        setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        setPreferredSize(new java.awt.Dimension(860, 490));
        JPanel heading = new JPanel();
        heading.setLayout(new javax.swing.BoxLayout(heading, javax.swing.BoxLayout.Y_AXIS));
        heading.setOpaque(false);
        heading.add(label("C O R E X Y   /   P R E C I S I O N", 17, new Color(115, 255, 195)));
        heading.add(javax.swing.Box.createVerticalStrut(12));
        heading.add(title);
        heading.add(javax.swing.Box.createVerticalStrut(12));
        heading.add(status);
        add(heading, BorderLayout.NORTH);
        metrics.setOpaque(false);
        add(metrics, BorderLayout.CENTER);
        add(details, BorderLayout.SOUTH);
        CalibrationResultPresentation.waiting(this);
    }

    public void showProfile(PrecisionProfile profile, boolean enabled, boolean celebrate) {
        boolean good = CalibrationResultPresentation.profile(this, profile, enabled);
        animation.stop();
        if (celebrate && good) {
            tick = 0;
            animation.restart();
        }
    }
    private static JPanel card(String value, String name) {
        JPanel panel = new JPanel(new GridLayout(0, 1));
        panel.setBackground(new Color(25, 43, 68));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(80, 155, 180)),
                BorderFactory.createEmptyBorder(16, 8, 16, 8)));
        panel.add(label(value, 36, new Color(255, 221, 108)));
        panel.add(label(name, 18, Color.WHITE));
        return panel;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font("Microsoft YaHei", Font.BOLD, size));
        label.setForeground(color);
        label.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        return label;
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setPaint(new GradientPaint(0, 0, new Color(10, 24, 52), getWidth(), getHeight(), new Color(34, 13, 66)));
        g.fillRect(0, 0, getWidth(), getHeight());
        if (animation.isRunning() && Boolean.TRUE.equals(getClientProperty("result.success"))) {
            Color[] colors = {new Color(255, 213, 75), new Color(82, 255, 185), new Color(234, 106, 255)};
            for (int i = 0; i < 95; i++) {
                g.setColor(colors[i % colors.length]);
                int x = Math.floorMod(i * 137 + tick * (i % 5 - 2), Math.max(1, getWidth()));
                int y = Math.floorMod(i * 83 + tick * (3 + i % 4), Math.max(1, getHeight()));
                g.fillRect(x, y, 5 + i % 7, 4);
            }
        }
        g.dispose();
    }

    @Override public void removeNotify() {
        animation.stop();
        super.removeNotify();
    }
}
