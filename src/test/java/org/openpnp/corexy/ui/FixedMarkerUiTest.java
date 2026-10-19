package org.openpnp.corexy.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.precision.FixedMarkerSetup;
import org.openpnp.model.Configuration;

class FixedMarkerUiTest {
    @TempDir
    Path temporary;

    @Test void recordingBecomesAvailableAfterHomingWithoutReopeningTheDialog() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        org.openpnp.corexy.precision.PrecisionRuntime.deactivate();
        org.openpnp.machine.reference.ReferenceMachine machine = new org.openpnp.machine.reference.ReferenceMachine();
        Configuration.get().setMachine(machine);
        FixedMarkerDialog[] dialog = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dialog[0] = new FixedMarkerDialog(null, Configuration.get());
                    assertFalse(((JButton) field(dialog[0], "record")).isEnabled());
                }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            machine.setEnabled(true);
            machine.setHomed(true); // State notification only; no Home operation or motion is run.
            SwingUtilities.invokeAndWait(() -> {
                try { assertTrue(((JButton) field(dialog[0], "record")).isEnabled()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
        }
        finally {
            SwingUtilities.invokeAndWait(() -> { if (dialog[0] != null) { dialog[0].dispose(); } });
            machine.close();
        }
    }

    @Test
    void animationShowsExactlyFiveReferencesWithHomeAndAlignsEverySelectedPoint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FixedMarkerGuidePanel panel = new FixedMarkerGuidePanel();
            panel.setSize(430, 280);
            int[] x = {390, 40, 40, 390, 215}, y = {62, 62, 222, 222, 142};
            int green = new Color(85, 211, 179).getRGB();
            int orange = new Color(238, 152, 39).getRGB();
            BufferedImage image = render(panel);
            assertEquals(orange, image.getRGB(x[0], y[0]), "First fixed reference must be the Home marker.");
            for (int marker = 0; marker < 5; marker++) {
                panel.setGuide(1, marker);
                image = render(panel);
                assertEquals(green, image.getRGB(x[marker] + 20, y[marker]),
                        "Crosshair must identify recorded marker " + marker);
                for (int other = 0; other < 5; other++) {
                    if (other != marker) {
                        assertNotEquals(green, image.getRGB(x[other] + 20, y[other]));
                    }
                }
            }
            for (int stage = 2; stage < 4; stage++) {
                panel.setGuide(stage, 0);
                image = render(panel);
                assertEquals(green, image.getRGB(x[0] + 20, y[0]), "Automatic steps must begin at Home.");
            }
            panel.addNotify();
            assertTrue(panel.isAnimating());
            panel.removeNotify();
            assertFalse(panel.isAnimating());
            assertThrows(IllegalArgumentException.class, () -> panel.setGuide(0, 5));
        });
    }

    @Test
    void reopensFivePointSetupPreservesHomeIdentityAndSavingDoesNotEnableCompensation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        FixedMarkerSetup saved = setup();
        saved.homeIndex = 3;
        saved.maximumHomeOffset = .7;
        saved.homeSearchRadius = 1.5;
        saved.maximumOffset = .3;
        saved.settleMillis = 450;
        saved.verifyOnStartup = false;
        org.openpnp.machine.reference.ReferenceMachine machine = new org.openpnp.machine.reference.ReferenceMachine();
        Configuration.get().setMachine(machine);
        org.openpnp.machine.reference.ReferenceHead head = new org.openpnp.machine.reference.ReferenceHead();
        machine.addHead(head);
        head.setVisualHomingMethod(org.openpnp.spi.base.AbstractHead.VisualHomingMethod.ResetToFiducialLocation);
        head.setHomingFiducialLocation(new org.openpnp.model.Location(org.openpnp.model.LengthUnit.Millimeters,
                saved.homeMarker().x, saved.homeMarker().y, 0, 0));
        CoreXYIntegration.store(Configuration.get()).saveFixedMarkers(saved);
        final FixedMarkerDialog[] dialog = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dialog[0] = new FixedMarkerDialog(null, Configuration.get());
                    JTable table = (JTable) field(dialog[0], "table");
                    assertEquals(5, table.getRowCount());
                    assertFalse(table.isCellEditable(0, 0));
                    assertFalse(table.isCellEditable(0, 2));
                    assertTrue(table.isCellEditable(1, 2));
                    assertTrue(((JButton) field(dialog[0], "start")).isEnabled());
                    FixedMarkerSetup actual = read(dialog[0]);
                    assertEquals("ref-3", actual.homeMarker().id);
                    assertEquals(saved.homeMarker().x, actual.homeMarker().x);
                    assertEquals(saved.homeMarker().y, actual.homeMarker().y);
                    assertEquals(.7, actual.maximumHomeOffset);
                    assertEquals(1.5, actual.homeSearchRadius);
                    assertEquals(.3, actual.maximumOffset);
                    assertEquals(450, actual.settleMillis);
                    assertFalse(actual.verifyOnStartup);
                    ((JButton) field(dialog[0], "save")).doClick();
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            FixedMarkerSetup reloaded = CoreXYIntegration.store(Configuration.get()).loadFixedMarkers();
            assertEquals("ref-3", reloaded.homeMarker().id);
            assertFalse(CoreXYIntegration.store(Configuration.get()).isEnabled());
            assertFalse(Files.exists(CoreXYIntegration.store(Configuration.get()).getRoot().resolve("profile.json")));
        }
        finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog[0] != null) {
                    dialog[0].dispose();
                }
            });
        }
    }

    @Test
    void initialDialogRequiresAllFiveCoordinatesAndRendersWithoutConnectingMachine() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        SwingUtilities.invokeAndWait(() -> {
            FixedMarkerDialog dialog = null;
            try {
                dialog = new FixedMarkerDialog(null, Configuration.get());
                assertFalse(((JButton) field(dialog, "start")).isEnabled(), "All five coordinates are needed before calibration.");
                FixedMarkerDialog initial = dialog;
                InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> read(initial));
                assertTrue(failure.getCause().getMessage().contains("Home"));
                dialog.addNotify();
                dialog.validate();
                JTable table = (JTable) field(dialog, "table");
                javax.swing.JViewport viewport = (javax.swing.JViewport) SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, table);
                assertTrue(viewport.getViewRect().contains(table.getCellRect(4, 3, false)),
                        "The fifth fixed marker must be fully visible at the initial window size.");
                java.awt.Container content = dialog.getContentPane();
                BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                content.printAll(graphics);
                graphics.dispose();
                assertTrue(image.getWidth() > 900);
                String preview = System.getProperty("corexy.fixed.preview");
                if (preview != null) {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(preview));
                }
            }
            catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            finally {
                if (dialog != null) {
                    dialog.dispose();
                }
            }
        });
    }

    @Test
    void offlineButtonUsesFiveIndependentSimulationMarkersAndLeavesProductionSettingsUntouched() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        FixedMarkerSetup saved = setup();
        CoreXYIntegration.store(Configuration.get()).saveFixedMarkers(saved);
        final FixedMarkerDialog[] dialog = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dialog[0] = new FixedMarkerDialog(null, Configuration.get());
                    ((JButton) field(dialog[0], "simulation")).doClick();
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            final boolean[] complete = {false};
            for (int retry = 0; retry < 900 && !complete[0]; retry++) {
                Thread.sleep(100);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        complete[0] = !(Boolean) field(dialog[0], "running");
                    }
                    catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
            assertTrue(complete[0], "The five-point simulation must finish and release the workflow lock.");
            Path root = CoreXYIntegration.store(Configuration.get()).getRoot();
            assertTrue(Files.isDirectory(root.resolve("simulation")));
            assertFalse(Files.exists(root.resolve("profile.json")));
            assertFalse(CoreXYIntegration.store(Configuration.get()).isEnabled());
            assertEquals("ref-0", CoreXYIntegration.store(Configuration.get()).loadFixedMarkers().homeMarker().id,
                    "An offline demonstration must not replace the saved physical marker configuration.");
            try (java.util.stream.Stream<Path> sessions = Files.list(root.resolve("simulation"))) {
                Path report = sessions.findFirst().orElseThrow(() -> new AssertionError("Missing simulation report"));
                String json = new String(Files.readAllBytes(report.resolve("report.json")), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(json.contains("\"fixedMarkers\""));
                assertTrue(json.contains("SIMULATION-ONLY"));
            }
            assertTrue(CoreXYIntegration.acquireWorkflow(), "Completed UI operation must release the shared workflow.");
            CoreXYIntegration.releaseWorkflow();
        }
        finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog[0] != null) {
                    dialog[0].dispose();
                }
            });
        }
    }

    @Test
    void cancelledOfflineRunSavesPartialHistoryAndAllowsAnotherRun() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Configuration.initialize(temporary.toFile());
        final FixedMarkerDialog[] dialog = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dialog[0] = new FixedMarkerDialog(null, Configuration.get());
                    ((JButton) field(dialog[0], "simulation")).doClick(0);
                    ((JButton) field(dialog[0], "stop")).doClick(0);
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            awaitIdle(dialog[0]);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertTrue(((JButton) field(dialog[0], "simulation")).isEnabled());
                    assertFalse(((JButton) field(dialog[0], "stop")).isEnabled());
                    ((JButton) field(dialog[0], "simulation")).doClick(0);
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            awaitIdle(dialog[0]);
            Path root = CoreXYIntegration.store(Configuration.get()).getRoot();
            try (java.util.stream.Stream<Path> sessions = Files.list(root.resolve("simulation"))) {
                assertEquals(2, sessions.count(), "A cancelled acquisition and its replacement must each have durable history.");
            }
            assertFalse(CoreXYIntegration.store(Configuration.get()).isEnabled());
            assertTrue(CoreXYIntegration.acquireWorkflow(), "Cancellation must not leave the shared operation lock held.");
            CoreXYIntegration.releaseWorkflow();
        }
        finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog[0] != null) {
                    dialog[0].dispose();
                }
            });
        }
    }

    private static void awaitIdle(FixedMarkerDialog dialog) throws Exception {
        final boolean[] complete = {false};
        for (int retry = 0; retry < 900 && !complete[0]; retry++) {
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    complete[0] = !(Boolean) field(dialog, "running");
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        assertTrue(complete[0], "Five-point UI workflow did not finish.");
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static FixedMarkerSetup read(FixedMarkerDialog dialog) throws Exception {
        java.lang.reflect.Method method = FixedMarkerDialog.class.getDeclaredMethod("readSettings");
        method.setAccessible(true);
        return (FixedMarkerSetup) method.invoke(dialog);
    }

    private static FixedMarkerSetup setup() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        double[][] points = {{100, 100}, {140, 100}, {140, 140}, {100, 140}, {120, 120}};
        for (int i = 0; i < points.length; i++) {
            setup.markers.add(new FixedMarkerSetup.Marker("ref-" + i, "固定点 " + i, points[i][0], points[i][1]));
        }
        return setup;
    }

    @Test
    void recordedLayoutPlacesHomeInTheRightUpperCornerAndKeepsPhysicalAspectRatio() throws Exception {
        FixedMarkerGuidePanel panel = new FixedMarkerGuidePanel();
        panel.setMarkerPositions(new double[][] {{434.94, 335.92}, {18.12, 292.49},
                {43.90, 150.63}, {401.41, 86.33}, {234.18, 148.60}});
        int[][] pixels = panel.markerPixels(40, 390, 62, 222);
        for (int i = 1; i < 5; i++) {
            assertTrue(pixels[0][0] > pixels[0][i]);
            assertTrue(pixels[1][0] < pixels[1][i]);
        }
        double scaleX = (pixels[0][0] - pixels[0][1]) / (434.94 - 18.12);
        double scaleY = (pixels[1][3] - pixels[1][0]) / (335.92 - 86.33);
        assertEquals(scaleX, scaleY, .005);
        panel.setSize(430, 280);
        String preview = System.getProperty("corexy.fixed.layout.preview");
        if (preview != null) {
            javax.imageio.ImageIO.write(render(panel), "png", new java.io.File(preview));
        }
    }

    private static BufferedImage render(FixedMarkerGuidePanel panel) {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        panel.paint(graphics);
        graphics.dispose();
        return image;
    }
}
