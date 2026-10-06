package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.openpnp.spi.Camera;

class FloatingCameraMonitorCommandTest {
    @Test
    void exposesComprehensiveCommandsIncludingSafeNozzlePause() {
        assertTrue(FloatingCameraMonitor.Command.values().length >= 50);
        assertNotNull(FloatingCameraMonitor.Command.valueOf("PAUSE_NOZZLES_EMPTY"));
        assertTrue(FloatingCameraMonitor.Command.PAUSE_NOZZLES_EMPTY.toString()
                .contains("吸嘴贴完暂停"));
        assertTrue(FloatingCameraMonitor.standardOptionValues(JOptionPane.YES_NO_OPTION)
                .contains(JOptionPane.YES_OPTION));
        assertTrue(FloatingCameraMonitor.standardOptionValues(JOptionPane.YES_NO_OPTION)
                .contains(JOptionPane.NO_OPTION));
    }

    @Test
    void selectsTopBottomOrActiveCameraExplicitly() {
        Camera top = camera("top", Camera.Looking.Down);
        Camera bottom = camera("bottom", Camera.Looking.Up);

        assertSame(bottom, FloatingCameraMonitor.selectCameraForMode(
                FloatingCameraMonitor.CameraSourceMode.FOLLOW_ACTIVE, bottom,
                Arrays.asList(top, bottom)));
        assertSame(top, FloatingCameraMonitor.selectCameraForMode(
                FloatingCameraMonitor.CameraSourceMode.TOP_CAMERA, bottom,
                Arrays.asList(top, bottom)));
        assertSame(bottom, FloatingCameraMonitor.selectCameraForMode(
                FloatingCameraMonitor.CameraSourceMode.BOTTOM_CAMERA, top,
                Arrays.asList(top, bottom)));
    }

    @Test
    void cameraLayerDisablesOptimizedPaintingSoWarningsStayAboveFrames() {
        FloatingCameraMonitor.CameraLayerPane layers =
                new FloatingCameraMonitor.CameraLayerPane();
        JPanel video = solidPanel(Color.BLUE);
        JPanel warning = solidPanel(Color.RED);
        layers.add(video, Integer.valueOf(JLayeredPane.DEFAULT_LAYER));
        layers.add(warning, Integer.valueOf(JLayeredPane.MODAL_LAYER));
        layers.setSize(80, 60);
        layers.doLayout();

        BufferedImage rendered = new BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = rendered.getGraphics();
        layers.paint(graphics);
        graphics.dispose();

        assertFalse(layers.isOptimizedDrawingEnabled());
        assertEquals(Color.RED.getRGB(), rendered.getRGB(40, 30));
    }

    private JPanel solidPanel(Color color) {
        return new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                graphics.setColor(color);
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        };
    }

    private Camera camera(String name, Camera.Looking looking) {
        return (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(),
                new Class<?>[] { Camera.class }, (proxy, method, args) -> {
                    if ("getLooking".equals(method.getName())) {
                        return looking;
                    }
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
