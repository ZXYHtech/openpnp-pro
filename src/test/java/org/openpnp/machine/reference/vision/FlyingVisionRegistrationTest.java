package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;

class FlyingVisionRegistrationTest {
    @BeforeAll
    static void load() throws Exception { Class.forName("org.openpnp.util.OpenCvUtils"); }

    @Test
    void registersFixtureWithoutFollowingTheComponent() {
        BufferedImage reference = new BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = reference.createGraphics();
        Random random = new Random(57);
        for (int i = 0; i < 4000; i++) {
            int gray = 40 + random.nextInt(140);
            g.setColor(new Color(gray, gray, gray));
            g.fillOval(random.nextInt(512), random.nextInt(512), 5 + random.nextInt(15), 5 + random.nextInt(15));
        }
        g.dispose();
        BufferedImage changed = new BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D q = changed.createGraphics();
        q.drawImage(reference, 0, 0, null);
        q.setColor(Color.WHITE);
        q.fillRect(230, 243, 52, 26);
        q.dispose();
        BufferedImage moved = new BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D m = moved.createGraphics();
        m.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        m.translate(14.25, -8.5);
        m.drawImage(changed, 0, 0, null);
        m.dispose();
        double[] result = FlyingVisionRegistration.translation(reference, moved);
        assertEquals(14.25, result[0], .4);
        assertEquals(-8.5, result[1], .4);
        assertTrue(result[2] > .98);
    }

    private PartAlignmentOffset offset(double x, double y, double angle) {
        return new PartAlignmentOffset(new Location(LengthUnit.Millimeters, x, y, 0, angle), true);
    }

    @Test
    void restoresPartPlaneWithDifferentFixtureMagnification() throws Exception {
        BufferedImage reference = new BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = reference.createGraphics();
        Random random = new Random(93);
        for (int i = 0; i < 4500; i++) {
            int gray = 40 + random.nextInt(140);
            g.setColor(new Color(gray, gray, gray));
            g.fillOval(random.nextInt(512), random.nextInt(512), 8, 8);
        }
        g.dispose();
        BufferedImage current = new BufferedImage(512, 512, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D draw = current.createGraphics();
        draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        draw.drawImage(reference, 10, -4, null);
        double ratio = .039 / .028899449247859334;
        draw.translate(10 * ratio, -4 * ratio);
        draw.setColor(Color.WHITE);
        draw.fillRect(230, 243, 52, 26);
        draw.dispose();
        FlyingVisionRegistration.Model model = new FlyingVisionRegistration.Model();
        model.reference = reference;
        model.xx = .039;
        model.yy = -.039;
        var camera = (org.openpnp.spi.Camera)java.lang.reflect.Proxy.newProxyInstance(
                org.openpnp.spi.Camera.class.getClassLoader(), new Class<?>[] {org.openpnp.spi.Camera.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUnitsPerPixelAtZ")) {
                        return new Location(LengthUnit.Millimeters, .028899449247859334, .028899449247859334, 0, 0);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BufferedImage restored = FlyingVisionRegistration.register(model, current, camera).image;
        double x = 0, y = 0, count = 0;
        for (int row = 210; row < 300; row++) {
            for (int col = 210; col < 300; col++) {
                if ((restored.getRGB(col, row) & 255) > 240) {
                    x += col;
                    y += row;
                    count++;
                }
            }
        }
        assertTrue(count > 800);
        assertEquals(255.5, x / count, .5);
        assertEquals(255.5, y / count, .5);
    }

    @Test
    void consensusRejectsIsolatedWrongFeaturesAndInsufficientEvidence() throws Exception {
        List<PartAlignmentOffset> values = Arrays.asList(offset(.1, -.2, 2), offset(.11, -.19, 2.1),
                offset(.09, -.21, 1.9), offset(.8, .7, 20));
        PartAlignmentOffset result = FlyingVisionRegistration.consensus(values);
        assertEquals(.1, result.getLocation().getX(), 1e-12);
        assertEquals(-.2, result.getLocation().getY(), 1e-12);
        assertEquals(2, result.getLocation().getRotation(), 1e-12);
        assertThrows(Exception.class, () -> FlyingVisionRegistration.consensus(values.subList(0, 2)));
        assertThrows(Exception.class, () -> FlyingVisionRegistration.consensus(Arrays.asList(
                offset(0, 0, 0), offset(.1, 0, 0), offset(.2, 0, 0))));
        assertThrows(Exception.class, () -> FlyingVisionRegistration.consensus(Arrays.asList(
                offset(0, 0, 0), offset(0, 0, 3), offset(0, 0, 6))));
    }
}
