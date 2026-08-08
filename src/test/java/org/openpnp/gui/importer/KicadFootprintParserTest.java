package org.openpnp.gui.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.importer.KicadFootprintParser.Result;
import org.openpnp.model.Footprint.Pad;

class KicadFootprintParserTest {
    private static final String SOIC = "(footprint \"Package_SO:SOIC-8\"\n"
            + "  (descr \"Eight pin package\")\n"
            + "  (fp_rect (start -2 -3) (end 2 3) (layer \"F.Fab\"))\n"
            + "  (pad \"1\" smd roundrect\n"
            + "    (at -1.9 -2.5 90) (size 0.6 1.5)\n"
            + "    (layers \"F.Cu\" \"F.Paste\" \"F.Mask\")\n"
            + "    (roundrect_rratio 0.25))\n"
            + "  (pad \"2\" smd rect (at -1.9 2.5) (size 0.6 1.5)"
            + " (layers \"F.Cu\" \"F.Mask\"))\n"
            + "  (pad \"9\" thru_hole circle (at 0 0) (size 1 1)"
            + " (layers \"*.Cu\" \"*.Mask\"))\n"
            + ")";

    @Test
    void parsesMultilinePadsBodyAndWarnings() throws Exception {
        Result result = new KicadFootprintParser().parse(SOIC);

        assertEquals("Package_SO:SOIC-8", result.name);
        assertEquals(2, result.pads.size());
        assertTrue(result.hasBodyBounds);
        assertEquals(-2, result.bodyMinX, 0.0001);
        assertEquals(3, result.bodyMaxY, 0.0001);
        assertTrue(result.warnings.stream().anyMatch(value -> value.contains("通孔")));
    }

    @Test
    void convertsCoordinatesOnlyOnceAndPlacesPinOneLowerLeft() throws Exception {
        Result parsed = new KicadFootprintParser().parse(SOIC);
        KicadFootprintNormalizer.Result normalized = KicadFootprintNormalizer.normalize(parsed);
        Pad pinOne = normalized.footprint.getPads().stream()
                .filter(pad -> "1".equals(pad.getName())).findFirst().get();

        assertTrue(pinOne.getX() < 0);
        assertTrue(pinOne.getY() < 0);
        assertEquals(90, normalized.rotation, 0.0001);
        assertEquals(6, normalized.footprint.getBodyWidth(), 0.0001);
        assertEquals(4, normalized.footprint.getBodyHeight(), 0.0001);
        assertFalse(normalized.needsReview);
    }

    @Test
    void manualRotationRotatesTheWholeFootprintAsOneRigidShape() throws Exception {
        Result parsed = new KicadFootprintParser().parse(SOIC);
        KicadFootprintNormalizer.Result original = KicadFootprintNormalizer.normalize(parsed);
        KicadFootprintNormalizer.Result rotated = KicadFootprintNormalizer.normalize(parsed, 90);

        assertEquals(original.footprint.getPads().size(), rotated.footprint.getPads().size());
        for (int i = 0; i < original.footprint.getPads().size(); i++) {
            Pad before = original.footprint.getPads().get(i);
            Pad after = rotated.footprint.getPads().get(i);
            assertEquals(-before.getY(), after.getX(), 0.0001,
                    "pad center X must rotate around the footprint origin");
            assertEquals(before.getX(), after.getY(), 0.0001,
                    "pad center Y must rotate around the footprint origin");
            assertEquals((before.getRotation() + 90) % 360, after.getRotation(), 0.0001,
                    "pad axis must rotate together with its center");
            assertEquals(before.getWidth(), after.getWidth(), 0.0001);
            assertEquals(before.getHeight(), after.getHeight(), 0.0001);
        }
    }

    @Test
    void rejectsMalformedInputWithLineAndColumn() {
        IOException error = assertThrows(IOException.class,
                () -> new KicadFootprintParser().parse("(footprint \"broken\"\n (pad 1"));

        assertTrue(error.getMessage().contains("第 "));
        assertTrue(error.getMessage().contains("行"));
    }
}
