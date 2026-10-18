package org.openpnp.corexy.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.model.Configuration;

class CoreXYUiTest {
    @TempDir
    Path temporary;

    @Test
    void animationDrawsAllGuidanceStagesAndStopsWhenRemoved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkerGuidePanel panel = new MarkerGuidePanel();
            panel.setSize(480, 290);
            BufferedImage image = new BufferedImage(480, 290, BufferedImage.TYPE_INT_RGB);
            for (int stage = 0; stage < 3; stage++) {
                panel.setGuide(stage, 5, stage);
                Graphics2D graphics = image.createGraphics();
                panel.paint(graphics);
                graphics.dispose();
                assertNotEquals(image.getRGB(0, 0), image.getRGB(200, 160));
            }
            panel.addNotify();
            assertTrue(panel.isAnimating());
            panel.removeNotify();
            assertFalse(panel.isAnimating());
        });
        MarkerBoard.write(temporary.resolve("board.svg"), 5, 10, 1);
        assertTrue(java.nio.file.Files.size(temporary.resolve("board.svg")) > 2000);
    }

    @Test
    void cameraCrosshairPointsToOrangeOriginInEveryRotatedPlatePose() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkerGuidePanel panel = new MarkerGuidePanel();
            panel.setSize(480, 290);
            int orange = new java.awt.Color(238, 152, 39).getRGB();
            int green = new java.awt.Color(85, 211, 179).getRGB();
            for (int pose = 0; pose < 4; pose++) {
                panel.setGuide(1, 5, pose);
                BufferedImage image = new BufferedImage(480, 290, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                panel.paint(graphics);
                graphics.dispose();
                int[] columns = new int[480], rows = new int[290];
                double orangeX = 0, orangeY = 0;
                int pixels = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if (image.getRGB(x, y) == orange) {
                            orangeX += x;
                            orangeY += y;
                            pixels++;
                        }
                        if (image.getRGB(x, y) == green) {
                            columns[x]++;
                            rows[y]++;
                        }
                    }
                }
                assertTrue(pixels > 10, "Origin must remain visible");
                int crosshairX = 0, crosshairY = 0;
                for (int x = 1; x < columns.length; x++) {
                    if (columns[x] > columns[crosshairX]) {
                        crosshairX = x;
                    }
                }
                for (int y = 1; y < rows.length; y++) {
                    if (rows[y] > rows[crosshairY]) {
                        crosshairY = y;
                    }
                }
                assertEquals(orangeX / pixels, crosshairX, 1.5, "Camera must point to origin in pose " + pose);
                assertEquals(orangeY / pixels, crosshairY, 1.5, "Camera must point to origin in pose " + pose);
            }
        });
    }

    @Test
    void offlineButtonCompletesWorkflowAndOnlyWritesSimulationReports() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        final CoreXYWizard[] wizard = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                wizard[0] = new CoreXYWizard(null, Configuration.get());
                java.lang.reflect.Field button = FixedMarkerDialog.class.getDeclaredField("simulation");
                button.setAccessible(true);
                ((javax.swing.JButton) button.get(wizard[0])).doClick();
            }
            catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        final boolean[] done = {false};
        for (int attempt = 0; attempt < 150 && !done[0]; attempt++) {
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    java.lang.reflect.Field running = FixedMarkerDialog.class.getDeclaredField("running");
                    running.setAccessible(true);
                    done[0] = !running.getBoolean(wizard[0]);
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        SwingUtilities.invokeAndWait(() -> wizard[0].dispose());
        assertTrue(done[0], "Swing background workflow must finish and re-enable controls");
        Path store = org.openpnp.corexy.CoreXYIntegration.store(Configuration.get()).getRoot();
        assertTrue(java.nio.file.Files.isDirectory(store.resolve("simulation")));
        assertFalse(java.nio.file.Files.exists(store.resolve("profile.json")));
        assertFalse(org.openpnp.corexy.CoreXYIntegration.store(Configuration.get()).isEnabled());
    }

    @Test
    void wizardConstructsAndRendersWithoutConnectingOrHomingMachine() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        final Throwable[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            CoreXYWizard dialog = null;
            try {
                dialog = new CoreXYWizard(null, Configuration.get());
                dialog.addNotify();
                dialog.validate();
                java.awt.Container content = dialog.getContentPane();
                BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                content.printAll(graphics);
                graphics.dispose();
                java.util.Set<Integer> colors = new java.util.HashSet<>();
                for (int y = 0; y < image.getHeight(); y += 20) {
                    for (int x = 0; x < image.getWidth(); x += 20) {
                        colors.add(image.getRGB(x, y));
                    }
                }
                assertTrue(colors.size() > 5, "UI render must contain actual controls and guidance, not a blank window.");
                String output = System.getProperty("corexy.ui.preview");
                if (output != null) {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(output));
                }
            }
            catch (Throwable exception) {
                failure[0] = exception;
            }
            finally {
                if (dialog != null) {
                    dialog.dispose();
                }
            }
        });
        assertNull(failure[0], failure[0] == null ? "" : failure[0].toString());
    }
}
