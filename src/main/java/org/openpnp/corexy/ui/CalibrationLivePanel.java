package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.openpnp.CameraListener;
import org.openpnp.spi.Camera;

/** Read-only live preview: shares the camera stream and cannot jog the machine. */
public final class CalibrationLivePanel extends JPanel implements CameraListener {
    private volatile BufferedImage frame;
    private volatile long receivedAt;
    private Camera camera;
    private final JLabel step = new JLabel("等待操作 · 相机画面仅供查看");
    private final JTextArea log = new JTextArea(7, 50);
    private final JPanel view = new JPanel() {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(new Color(10, 16, 27));
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage image = frame;
            if (image != null) {
                double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
                int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.drawImage(image, x, y, w, h, null);
                g.setColor(new Color(65, 255, 180));
                g.drawLine(getWidth() / 2 - 18, getHeight() / 2, getWidth() / 2 + 18, getHeight() / 2);
                g.drawLine(getWidth() / 2, getHeight() / 2 - 18, getWidth() / 2, getHeight() / 2 + 18);
            }
            g.setColor(Color.WHITE);
            g.drawString(image == null ? "等待顶部相机画面…"
                    : System.currentTimeMillis() - receivedAt > 3000 ? "画面暂未更新 · 请查看当前步骤" : "LIVE  顶部相机 · 只读画面", 16, 25);
            g.dispose();
        }
    };
    private final javax.swing.Timer refresh = new javax.swing.Timer(500, event -> view.repaint());

    public CalibrationLivePanel() {
        super(new BorderLayout(8, 10));
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        step.setFont(step.getFont().deriveFont(java.awt.Font.BOLD, 16));
        add(step, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        add(new JScrollPane(log), BorderLayout.SOUTH);
    }

    public void connect(Camera selected) {
        if (camera == selected) { return; }
        disconnect();
        camera = selected;
        org.openpnp.util.CameraPreviewDemand.acquire(camera);
        camera.startContinuousCapture(this);
        refresh.start();
    }

    public void disconnect() {
        refresh.stop();
        if (camera != null) {
            camera.stopContinuousCapture(this);
            org.openpnp.util.CameraPreviewDemand.release(camera);
        }
        camera = null;
        frame = null;
    }

    public void update(String message) {
        step.setText(message);
        log.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message + "\n");
        if (log.getDocument().getLength() > 30000) { log.replaceRange("", 0, 10000); }
        log.setCaretPosition(log.getDocument().getLength());
    }

    @Override public void frameReceived(BufferedImage image) {
        frame = image;
        receivedAt = System.currentTimeMillis();
        view.repaint();
    }
}
