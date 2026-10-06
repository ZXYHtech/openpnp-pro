package org.openpnp.corexy.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;

import javax.swing.JPanel;
import javax.swing.Timer;

/** Code-rendered animation remains sharp at any DPI and does not require a network connection. */
public final class MarkerGuidePanel extends JPanel {
    private final Timer timer;
    private int frame, stage, gridSize = 5, pose;

    public MarkerGuidePanel() {
        setPreferredSize(new Dimension(430, 270));
        setMinimumSize(new Dimension(280, 180));
        timer = new Timer(40, event -> {
            frame++;
            repaint();
        });
    }

    public void setGuide(int stage, int size, int pose) {
        this.stage = stage;
        gridSize = size;
        this.pose = pose;
        frame = 0;
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    public boolean isAnimating() {
        return timer.isRunning();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth(), height = getHeight();
            g.setColor(new Color(24, 31, 45));
            g.fillRoundRect(0, 0, width, height, 22, 22);
            double phase = (frame % 100) / 100.0;
            int side = Math.max(70, Math.min(width - 100, height - 150));
            int cx = width / 2, cy = height / 2 + 10;
            AffineTransform saved = g.getTransform();
            double shift = stage == 0 ? Math.max(0, 1 - phase * 3) * 30 : 0;
            g.translate(cx + shift, cy - shift);
            g.rotate(Math.toRadians(pose * 90));
            g.setColor(new Color(227, 233, 244));
            g.fillRoundRect(-side / 2, -side / 2, side, side, 12, 12);
            int count = gridSize == 1 ? 1 : gridSize;
            int spacing = count == 1 ? 0 : (side - 44) / (count - 1);
            for (int row = 0; row < count; row++) {
                for (int column = 0; column < count; column++) {
                    int x = count == 1 ? 0 : -side / 2 + 22 + column * spacing;
                    int y = count == 1 ? 0 : -side / 2 + 22 + row * spacing;
                    g.setColor(row == 0 && column == 0 ? new Color(238, 152, 39) : new Color(37, 46, 65));
                    g.fillOval(x - 5, y - 5, 10, 10);
                    if (row == 0 && column == 0) {
                        g.setColor(new Color(25, 31, 45));
                        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                        g.drawString("0", x + 8, y - 6);
                    }
                }
            }
            g.setTransform(saved);
            g.setStroke(new BasicStroke(2));
            if (stage == 0) {
                int handX = (int) (cx + side / 2 + shift);
                int handY = (int) (cy - side / 2 - shift);
                g.setColor(new Color(122, 184, 255));
                g.fillRoundRect(handX - 8, handY - 28, 16, 45, 8, 8);
                g.fillRoundRect(handX - 25, handY, 35, 25, 12, 12);
                g.drawLine(handX - 45, handY + 35, handX - 15, handY + 10);
            }
            else {
                int tx = cx - (count == 1 ? 0 : side / 2 - 22);
                int ty = cy - (count == 1 ? 0 : side / 2 - 22);
                if (stage == 1) {
                    double angle = Math.toRadians(pose * 90);
                    int localX = tx - cx, localY = ty - cy;
                    tx = cx + (int) Math.round(localX * Math.cos(angle) - localY * Math.sin(angle));
                    ty = cy + (int) Math.round(localX * Math.sin(angle) + localY * Math.cos(angle));
                }
                if (stage == 2) {
                    tx += (int) ((side - 44) * phase);
                    ty += (int) ((side - 44) * (Math.sin(phase * Math.PI * 2) + 1) / 2);
                }
                int radius = 17 + (int) (4 * Math.sin(phase * Math.PI * 2));
                g.setColor(new Color(85, 211, 179));
                g.drawOval(tx - radius, ty - radius, radius * 2, radius * 2);
                g.drawLine(tx - 30, ty, tx + 30, ty);
                g.drawLine(tx, ty - 30, tx, ty + 30);
                g.drawLine(tx, ty - 55, tx, ty - 32);
            }
            g.setColor(new Color(225, 234, 249));
            g.setFont(getFont().deriveFont(Font.BOLD, width < 430 ? 12f : 14f));
            String title = stage == 0 ? "① 固定标定板，避免翘曲和滑动" : stage == 1
                    ? "② 把顶部相机移到 0 号 marker 上方" : "③ 自动采集、比较、验证与回滚";
            g.drawString(title, 16, 26);
            g.setFont(getFont().deriveFont(12f));
            g.drawString(pose == 0 ? "橙色点是基准；方向与机器 X/Y 一致" : "当前姿态：旋转 " + pose * 90 + "° 并平移", 16, height - 15);
        }
        finally {
            g.dispose();
        }
    }
}
