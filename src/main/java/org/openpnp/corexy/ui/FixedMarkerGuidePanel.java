package org.openpnp.corexy.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Offline, DPI-independent guidance for Home and four removable calibration references. */
public final class FixedMarkerGuidePanel extends JPanel {
    private final Timer timer;
    private int frame, stage, marker;
    private double[][] positions;

    public void setMarkerPositions(double[][] coordinates) {
        positions = null;
        if (coordinates != null && coordinates.length == 5) {
            double[][] copy = new double[5][2];
            boolean valid = true;
            for (int i = 0; i < 5; i++) {
                if (coordinates[i] == null || coordinates[i].length < 2
                        || !Double.isFinite(coordinates[i][0]) || !Double.isFinite(coordinates[i][1])) {
                    valid = false;
                    break;
                }
                copy[i] = coordinates[i].clone();
            }
            if (valid) {
                positions = copy;
            }
        }
        repaint();
    }

    // X increases rightward and Y upward, as in the workbench top view.
    int[][] markerPixels(int left, int right, int top, int bottom) {
        int[] xs = {right, left, left, right, (left + right) / 2};
        int[] ys = {top, top, bottom, bottom, (top + bottom) / 2};
        if (positions != null) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (double[] point : positions) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]);
                maxY = Math.max(maxY, point[1]);
            }
            if (maxX > minX && maxY > minY) {
                double scale = Math.min((right - left) / (maxX - minX), (bottom - top) / (maxY - minY));
                double centerX = (minX + maxX) / 2, centerY = (minY + maxY) / 2;
                for (int i = 0; i < 5; i++) {
                    xs[i] = (left + right) / 2 + (int) Math.round((positions[i][0] - centerX) * scale);
                    ys[i] = (top + bottom) / 2 - (int) Math.round((positions[i][1] - centerY) * scale);
                }
            }
        }
        return new int[][] {xs, ys};
    }

    public FixedMarkerGuidePanel() {
        setPreferredSize(new Dimension(430, 280));
        setMinimumSize(new Dimension(290, 220));
        timer = new Timer(40, event -> {
            frame++;
            repaint();
        });
    }

    /** 0: placement; 1: recording; 2: calibration; 3: Home only; 4: manual full verification. */
    public void setGuide(int stage, int marker) {
        if (stage < 0 || stage > 4 || marker < 0 || marker > 4) {
            throw new IllegalArgumentException("引导阶段或 marker 编号无效。");
        }
        this.stage = stage;
        this.marker = marker;
        frame = 0;
        repaint();
    }

    public boolean isAnimating() {
        return timer.isRunning();
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

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth(), height = getHeight();
            g.setColor(new Color(24, 31, 45));
            g.fillRoundRect(0, 0, width, height, 22, 22);
            int left = 40, right = width - 40, top = 62, bottom = height - 58;
            g.setColor(new Color(227, 233, 244));
            g.fillRoundRect(left - 14, top - 14, right - left + 28, bottom - top + 28, 12, 12);
            int[][] pixels = markerPixels(left, right, top, bottom);
            int[] xs = pixels[0], ys = pixels[1];
            int active = stage == 3 ? 0 : stage >= 2 ? frame / 35 % 5 : marker;
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            for (int i = 0; i < 5; i++) {
                g.setColor(i == 0 ? new Color(238, 152, 39) : new Color(37, 46, 65));
                g.fillOval(xs[i] - 6, ys[i] - 6, 12, 12);
                g.drawString(i == 0 ? "1 · Home" : Integer.toString(i + 1), xs[i] - (i == 0 ? 10 : 4),
                        ys[i] + (ys[i] < (top + bottom) / 2 ? 22 : -12));
            }
            if (stage == 0) {
                double phase = frame % 75 / 75.0;
                int x = xs[marker] + 48 + (int) (24 * (1 - phase));
                int y = ys[marker] - (int) (24 * (1 - phase));
                g.setColor(new Color(122, 184, 255));
                g.fillRoundRect(x + 10, y - 35, 15, 40, 8, 8);
                g.fillRoundRect(x, y - 5, 32, 22, 10, 10);
            }
            else {
                int radius = 16 + (int) (3 * Math.sin(frame * .09));
                g.setStroke(new BasicStroke(2));
                g.setColor(new Color(85, 211, 179));
                int x = xs[active], y = ys[active];
                g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
                g.drawLine(x - 25, y, x + 25, y);
                g.drawLine(x, y - 25, x, y + 25);
                if (stage >= 2 && active > 0) {
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {5, 5}, 0));
                    g.drawLine(xs[active - 1], ys[active - 1], x, y);
                }
            }
            g.setColor(new Color(225, 234, 249));
            g.setFont(getFont().deriveFont(Font.BOLD, width < 390 ? 12f : 14f));
            String title = stage == 0 ? "① 固定 Home 基准与 4 个覆盖点" : stage == 1
                    ? "② 对齐第 " + (marker + 1) + " 点，记录相机位置" : stage == 2
                    ? "③ 自动校准、独立评估、启用通过方案" : stage == 3
                    ? "普通复位：仅检查 Home 视觉基准" : "手动完整验证：Home 与四个覆盖点";
            g.drawString(title, 16, 27);
            g.setFont(getFont().deriveFont(12f));
            g.drawString(stage == 3 ? "补偿参数先加载；视觉归一属于 Home 完成前步骤" : (positions == null ? "示意布局；Home 位置以实际录入坐标为准" : "按录入坐标显示：X 向右，Y 向上；等比例布局"), 16, height - 19);
        }
        finally {
            g.dispose();
        }
    }
}
