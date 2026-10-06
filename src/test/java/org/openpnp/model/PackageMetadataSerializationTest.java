package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.StringWriter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.simpleframework.xml.core.Persister;

class PackageMetadataSerializationTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void readsLegacyPackageWithoutNewMetadata() throws Exception {
        Package pkg = new Persister().read(Package.class,
                "<package id=\"SOIC-8\"><footprint units=\"Millimeters\" /></package>", false);

        assertEquals("SOIC-8", pkg.getId());
        assertNull(pkg.getCategoryId());
        assertEquals(Package.SourceType.LEGACY, pkg.getSourceType());
        assertEquals(Package.ReviewState.MISSING_FOOTPRINT, pkg.getReviewState());
    }

    @Test
    void roundTripsClassificationImportAndOrientationMetadata() throws Exception {
        Package source = new Package("QFN-32");
        source.setCategoryId("ic.qfn");
        source.setSourceType(Package.SourceType.KICAD);
        source.setSourceReference("Package_DFN_QFN.pretty/QFN-32.kicad_mod");
        source.setReviewState(Package.ReviewState.NEEDS_REVIEW);
        source.setOrientationAdjustment(90);
        source.setOrientationReason("焊盘 1 调整到左上象限");
        source.setImportBatchId("KICAD-1234");
        source.setDuplicateGroupId("D001");

        StringWriter writer = new StringWriter();
        Persister serializer = new Persister();
        serializer.write(source, writer);
        Package restored = serializer.read(Package.class, writer.toString());

        assertEquals("ic.qfn", restored.getCategoryId());
        assertEquals(Package.SourceType.KICAD, restored.getSourceType());
        assertEquals(90, restored.getOrientationAdjustment(), 0.0001);
        assertEquals("KICAD-1234", restored.getImportBatchId());
        assertEquals("D001", restored.getDuplicateGroupId());
    }
}
