package org.openpnp.stencil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Collections;
import javax.swing.SwingUtilities;

class StencilPreviewCameraTest {

    @TempDir
    Path dir;

    private StencilPreview preview() throws Exception {
        Path mesh = dir.resolve("mesh.json");
        StencilFiles.writeText(mesh, "{\"triangles\":[[-50,-30,0,50,-30,0,50,30,0],[-50,-30,0,50,30,0,-50,30,0]]}");
        StencilPreview p = new StencilPreview();
        p.setSize(640, 420);
        p.model(StencilParameters.factory(), Collections.singletonList(StencilMesh.read(mesh)), false);
        p.threeD(true);
        p.references(false);
        return p;
    }

    private Object field(StencilPreview p, String name) throws Exception {
        Field f = StencilPreview.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(p);
    }

    private StencilCamera camera(StencilPreview p) throws Exception {
        Method m = StencilPreview.class.getDeclaredMethod("camera");
        m.setAccessible(true);
        return (StencilCamera) m.invoke(p);
    }

    private void drag(StencilPreview p, int dx, int dy) {
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1));
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_DRAGGED, 1, MouseEvent.BUTTON1_DOWN_MASK, 100 + dx, 100 + dy, 0, false, MouseEvent.NOBUTTON));
        p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_RELEASED, 2, 0, 100 + dx, 100 + dy, 1, false, MouseEvent.BUTTON1));
    }

    private void paint(StencilPreview p) {
        BufferedImage image = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        p.paint(g);
        g.dispose();
        int ink = 0;
        for (int y = 40; y < image.getHeight() - 35; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != p.getBackground().getRGB()) {
                    ink++;
                }
            }
        }
        assertTrue(ink > 100, "Model must remain visible after orbit");
    }

    @Test
    void draggingDoesNotRefitOrDistortTheCameraAndDoesNotInvertAtThePole() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                StencilPreview p = preview();
                StencilCamera original = camera(p);
                double[] target = ((double[]) field(p, "cameraTarget")).clone();
                for (int i = 0; i < 12; i++) {
                    drag(p, 60, 2);
                    paint(p);
                    assertEquals(original.distance, camera(p).distance, 1e-12);
                    assertEquals(original.focal, camera(p).focal, 1e-12);
                    assertArrayEquals(target, (double[]) field(p, "cameraTarget"), 0);
                }
                drag(p, 0, 1000);
                assertEquals(90.0, (Double) field(p, "pitch"));
                paint(p);
                drag(p, 0, -1000);
                assertEquals(-89.5, (Double) field(p, "pitch"));
                paint(p);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void actualMouseClickRecoversEngravingCoordinatesInPerspective() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                StencilPreview p = preview();
                StencilParameters params = StencilParameters.factory();
                for (String part : new String[] { "pcb_base", "stencil_upper" }) {
                    p.parameters(params.with("emboss_part", part));
                    StencilPlan plan = new StencilPlan(params);
                    double z = part.equals("pcb_base") ? plan.pcbTop : plan.pcbTop + params.n("stencil_gap") + params.n("stencil_thickness") + 2.8;
                    double worldY = part.equals("pcb_base") ? 8 : plan.jawFront + 8;
                    double[] point = camera(p).project(new double[] { 12, worldY, z });
                    double[] expected = camera(p).pickTop(Math.round(point[0]), Math.round(point[1]), z);
                    double[] actual = { Double.NaN, Double.NaN };
                    p.placement(true, (x, y) -> {
                        actual[0] = x;
                        actual[1] = y;
                    });
                    p.dispatchEvent(new MouseEvent(p, MouseEvent.MOUSE_CLICKED, 0, 0, (int) Math.round(point[0]), (int) Math.round(point[1]), 1, false, MouseEvent.BUTTON1));
                    assertEquals(Math.rint(expected[0] * 100) / 100, actual[0]);
                    double expectedY = expected[1] - (part.equals("pcb_base") ? 0 : plan.jawFront);
                    assertEquals(Math.rint(expectedY * 100) / 100, actual[1]);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
