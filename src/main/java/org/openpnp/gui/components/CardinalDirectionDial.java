package org.openpnp.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Locale;
import java.util.function.IntConsumer;

import javax.swing.JComponent;
import javax.swing.UIManager;

import org.openpnp.gui.support.ModernTheme;

/** Four-position feeder direction selector drawn as a recognizable instrument dial. */
@SuppressWarnings("serial")
public class CardinalDirectionDial extends JComponent {
    private double originalRotation;
    private int direction;
    private int hoverDirection = -1;
    private boolean overridden;
    private IntConsumer directionListener;

    public CardinalDirectionDial() {
        setPreferredSize(new Dimension(210, 210));
        setMinimumSize(new Dimension(170, 170));
        setToolTipText("按相机蓝色方向线：上 0°、左 90°、下 180°、右 270°");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                selectDirection(directionForPoint(event.getX() - getWidth() / 2.0,
                        event.getY() - getHeight() / 2.0));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hoverDirection = -1;
                repaint();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                hoverDirection = directionForPoint(event.getX() - getWidth() / 2.0,
                        event.getY() - getHeight() / 2.0);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                repaint();
            }
        });
    }

    public void setFeederRotation(double rotation) {
        originalRotation = rotation;
        direction = nearestCardinal(rotation);
        overridden = false;
        repaint();
    }

    public Double getRotationOverride() {
        return overridden ? (double) direction : null;
    }

    public void setDirectionListener(IntConsumer listener) {
        directionListener = listener;
    }

    private void selectDirection(int selected) {
        direction = selected;
        overridden = true;
        if (directionListener != null) {
            directionListener.accept(direction);
        }
        repaint();
    }

    static int directionForPoint(double x, double y) {
        // Camera view Y is downward and OpenPnP positive rotation appears counter-clockwise
        // on screen: right is therefore 270°, while left is 90°.
        double angle = Math.toDegrees(Math.atan2(-x, -y));
        if (angle < 0) {
            angle += 360;
        }
        return nearestCardinal(angle);
    }

    static int nearestCardinal(double angle) {
        int value = (int) Math.round(angle / 90.0) * 90;
        value %= 360;
        return value < 0 ? value + 360 : value;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int radius = Math.max(48, Math.min(getWidth(), getHeight()) / 2 - 24);
            Color foreground = UIManager.getColor("Label.foreground");
            if (foreground == null) {
                foreground = Color.DARK_GRAY;
            }
            Color muted = ModernTheme.mutedForeground();
            Color accent = ModernTheme.isDark() ? new Color(0x43B8D8) : ModernTheme.ACCENT;

            g2.setColor(muted);
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            for (int angle = 0; angle < 360; angle += 15) {
                double radians = Math.toRadians(angle - 90);
                int outerX = cx + (int) Math.round(radius * Math.cos(radians));
                int outerY = cy + (int) Math.round(radius * Math.sin(radians));
                int tick = angle % 90 == 0 ? 12 : 6;
                int innerX = cx + (int) Math.round((radius - tick) * Math.cos(radians));
                int innerY = cy + (int) Math.round((radius - tick) * Math.sin(radians));
                g2.drawLine(innerX, innerY, outerX, outerY);
            }

            String[] labels = {"0°", "90°", "180°", "270°"};
            int[] values = {0, 270, 180, 90};
            int[][] positions = {{cx, cy - radius + 28}, {cx + radius - 32, cy},
                    {cx, cy + radius - 20}, {cx - radius + 34, cy}};
            FontMetrics metrics = g2.getFontMetrics();
            for (int i = 0; i < values.length; i++) {
                boolean active = direction == values[i];
                g2.setColor(active ? accent : foreground);
                if (hoverDirection == values[i]) {
                    g2.fillOval(positions[i][0] - 18, positions[i][1] - 14, 36, 28);
                    g2.setColor(ModernTheme.isDark() ? Color.BLACK : Color.WHITE);
                }
                g2.drawString(labels[i], positions[i][0] - metrics.stringWidth(labels[i]) / 2,
                        positions[i][1] + metrics.getAscent() / 2 - 2);
            }

            double radians = Math.toRadians(-direction - 90);
            int arrowX = cx + (int) Math.round((radius - 38) * Math.cos(radians));
            int arrowY = cy + (int) Math.round((radius - 38) * Math.sin(radians));
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(overridden ? 5f : 3f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.drawLine(cx, cy, arrowX, arrowY);
            g2.fillOval(cx - 5, cy - 5, 10, 10);

            String center = overridden ? "已覆盖 " + direction + "°"
                    : String.format(Locale.ROOT, "保留 %.1f°", originalRotation);
            g2.setColor(foreground);
            g2.setStroke(new BasicStroke(1f));
            g2.drawString(center, cx - metrics.stringWidth(center) / 2, cy + 26);
        }
        finally {
            g2.dispose();
        }
    }
}
