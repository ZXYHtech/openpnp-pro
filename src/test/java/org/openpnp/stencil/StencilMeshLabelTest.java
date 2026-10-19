package org.openpnp.stencil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import java.awt.image.BufferedImage;

class StencilMeshLabelTest {

    @TempDir
    Path dir;

    @Test
    void compatible3mfHasSharedVerticesAndDefaultNamespaces() throws Exception {
        Path mesh = dir.resolve("mesh.json");
        StencilFiles.writeText(mesh, "{\"triangles\":[[0,0,0,1,0,0,0,1,0],[1,0,0,1,1,0,0,1,0]]}");
        Path file = dir.resolve("out.3mf");
        StencilMesh.read(mesh).write3mf(file);
        try (ZipFile z = new ZipFile(file.toFile())) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            org.w3c.dom.Document d = f.newDocumentBuilder().parse(z.getInputStream(z.getEntry("3D/3dmodel.model")));
            assertEquals("millimeter", d.getDocumentElement().getAttribute("unit"));
            assertEquals(4, d.getElementsByTagNameNS("http://schemas.microsoft.com/3dmanufacturing/core/2015/02", "vertex").getLength());
            assertEquals(2, d.getElementsByTagNameNS("http://schemas.microsoft.com/3dmanufacturing/core/2015/02", "triangle").getLength());
            assertNotNull(z.getEntry("_rels/.rels"));
        }
    }

    @Test
    void labelUses203DpiAndContainsInk() {
        BufferedImage image = StencilLabel.image(StencilParameters.factory(), 50, 30);
        assertEquals(Math.round(50 * 203 / 25.4), image.getWidth());
        assertEquals(Math.round(30 * 203 / 25.4), image.getHeight());
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) == 0) {
                    ink++;
                }
            }
        }
        assertTrue(ink > 100);
        assertThrows(IllegalArgumentException.class, () -> StencilLabel.image(StencilParameters.factory(), Double.NaN, 30));
    }
}
