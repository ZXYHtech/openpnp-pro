package org.openpnp.gui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import javax.swing.*;
import org.openpnp.stencil.*;

class StencilFixtureDialogTest {

    @TempDir
    Path dir;

    private Component named(Component c, String name) {
        if (name.equals(c.getName())) {
            return c;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                Component found = named(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void chineseNativeFieldsFactoryValuesAndLiveMarginAreUsable() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Throwable[] failure = { null };
        SwingUtilities.invokeAndWait(() -> {
            StencilFixtureDialog d = null;
            try {
                d = new StencilFixtureDialog(null, dir);
                d.setVisible(true);
                d.validate();
                assertTrue(d.getTitle().contains("钢网夹具"));
                assertTrue(d.getTitle().contains("YAA2000-GWJJ"));
                assertFalse(d.getTitle().contains("Java"));
                JPanel actions = (JPanel) named(d, "stencil-parameter-actions");
                assertEquals("保存为启动参数", ((JButton) actions.getComponent(0)).getText());
                assertEquals("导入参数", ((JButton) actions.getComponent(1)).getText());
                assertEquals("YAA2000-GWJJ", ((JTextField) named(d, "stencil-model")).getText());
                JComponent length = (JComponent) named(d, "stencil-pcb_length");
                JComponent width = (JComponent) named(d, "stencil-pcb_width");
                Point a = SwingUtilities.convertPoint(length, 0, 0, d);
                Point b = SwingUtilities.convertPoint(width, 0, 0, d);
                assertEquals(a.y, b.y, "PCB length and width must share a row");
                assertTrue(b.x > a.x + length.getWidth(), "Two parameter fields must appear side by side");
                assertTrue(named(d, "stencil-generate") instanceof JButton);
                assertEquals("4", ((JComboBox<?>) named(d, "stencil-screw_count")).getSelectedItem());
                assertEquals(146.9, ((Number) ((JSpinner) named(d, "stencil-pcb_length")).getValue()).doubleValue());
                ((JSpinner) named(d, "stencil-top_distance")).setValue(15.0);
                assertEquals(23.9, ((Number) ((JSpinner) named(d, "stencil-stencil_margin")).getValue()).doubleValue(), 1e-9);
                ((JSpinner) named(d, "stencil-pcb_depth_compensation")).setValue(.03);
                assertFalse(named(d, "stencil-generate").isEnabled());
                ((JSpinner) named(d, "stencil-pcb_depth_compensation")).setValue(.05);
                assertTrue(named(d, "stencil-generate").isEnabled());
                ((JButton) actions.getComponent(0)).doClick();
                assertEquals(15, new StencilStore(dir.resolve("stencil-fixtures")).startup().n("top_distance"));
                assertFalse(Files.exists(dir.resolve("machine.xml")));
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                if (d != null) {
                    d.dispose();
                }
            }
        });
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    void previewAndPaperHaveNoMachineDependency() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StencilPreview p = new StencilPreview();
            p.setSize(640, 420);
            p.parameters(StencilParameters.factory());
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(640, 420, java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            p.paint(g);
            g.dispose();
            assertNotEquals(image.getRGB(0, 0), image.getRGB(320, 210));
            p.stop();
        });
    }
}
