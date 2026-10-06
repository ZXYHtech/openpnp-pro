package org.openpnp.packageimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.importer.KicadFootprintParser;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;
import org.openpnp.model.Package;

class PackageImportCoreTest {
    @BeforeEach
    void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void normalizesLibraryPathExtensionAndKnownVariantSuffix() {
        assertEquals("SOIC8HANDSOLDER",
                PackageNameNormalizer.strict("Package_SO:SOIC-8_HandSolder.kicad_mod"));
        assertEquals("SOIC8",
                PackageNameNormalizer.loose("Package_SO:SOIC-8_HandSolder.kicad_mod"));
        assertEquals("QFN-32", PackageNameNormalizer.baseName("C:\\KiCad\\QFN-32.kicad_mod"));
    }

    @Test
    void fingerprintIsStableAtOneMicronButDetectsRealGeometryChange() {
        Footprint first = footprint(1.0000, 2.0);
        Footprint quantizedSame = footprint(1.0004, 2.0);
        Footprint changed = footprint(1.002, 2.0);

        assertEquals(PackageGeometryFingerprint.compute(first),
                PackageGeometryFingerprint.compute(quantizedSame));
        assertNotEquals(PackageGeometryFingerprint.compute(first),
                PackageGeometryFingerprint.compute(changed));
    }

    @Test
    void matcherUsesAliasAndRefusesGeometryDifference() {
        Package existing = new Package("SOIC-8");
        existing.setAliases("SO8; Package_SO:SOIC-8");
        existing.setFootprint(footprint(1, 2));

        PackageMatcher.Result same = PackageMatcher.match("SO8", footprint(1, 2),
                Collections.singletonList(existing));
        PackageMatcher.Result different = PackageMatcher.match("SO8", footprint(2, 2),
                Collections.singletonList(existing));

        assertSame(existing, same.match);
        assertTrue(same.canAutoBind());
        assertEquals(PackageMatcher.Kind.CONFLICT, different.kind);
        assertFalse(different.difference.isEmpty());
    }

    @Test
    void classifierExplainsSuggestionAndPreservesVerifiedPackage() {
        Package pkg = new Package("Package_SO:SOIC-8");
        pkg.setFootprint(footprint(1, 2));
        PackageClassifier.Result result = PackageClassifier.classify(pkg);

        assertEquals("ic.so", result.categoryId);
        assertTrue(result.confidence > .9);
        assertFalse(result.reason.isEmpty());

        pkg.setCategoryId("custom");
        pkg.setReviewState(Package.ReviewState.VERIFIED);
        PackageClassifier.applySuggestion(pkg, true);
        assertEquals("custom", pkg.getCategoryId());

        Package oldAutomatic = new Package("R0603");
        oldAutomatic.setCategoryId("passive.capacitor");
        oldAutomatic.setReviewState(Package.ReviewState.AUTO_CLASSIFIED);
        PackageClassifier.applySuggestion(oldAutomatic, true);
        assertEquals("passive.resistor", oldAutomatic.getCategoryId());

        Package manual = new Package("R0603");
        manual.setCategoryId("custom");
        manual.setClassificationReason("用户手工分类");
        PackageClassifier.applySuggestion(manual, true);
        assertEquals("custom", manual.getCategoryId());
    }

    @Test
    void classifierRecognizesCompactNamesUsedByInstalledOpenPnpLibrary() {
        assertCategory("passive.resistor", "R0603");
        assertCategory("passive.resistor", "R0402-8P");
        assertCategory("passive.resistor", "TTC0603");
        assertCategory("passive.capacitor", "C0603-lite");
        assertCategory("passive.capacitor", "C3528");
        assertCategory("passive.inductor", "L0603-rd");
        assertCategory("discrete.diode-led", "LED0603-RD_RED");
        assertCategory("discrete.diode-led", "WS2812B");
        assertCategory("connector", "TYPEC-16P");
        assertCategory("connector", "KF301-5.0-3P");
        assertCategory("electromechanical.switch", "SW-SMD_4P-L5.2-W5.2");
        assertCategory("passive.polarized-capacitor", "CASE-B_3528");
        assertCategory("crystal", "R-CSTSE800G52");
        assertCategory("mechanical.test-point", "测试点0.8mm");
    }

    @Test
    void classifierUsesKicadPrettyLibraryWhenFootprintNameIsVendorSpecific() {
        Package pkg = new Package("Vendor-ABC123");
        pkg.setSourceReference("Resistor_SMD.pretty/Vendor-ABC123.kicad_mod");

        PackageClassifier.Result result = PackageClassifier.classify(pkg);

        assertEquals("passive.resistor", result.categoryId);
        assertTrue(result.confidence >= .99);
        assertTrue(result.reason.contains("RESISTOR_SMD"));
    }

    @Test
    void auditCreatesDuplicateGroupsWithoutOverwritingVerifiedState() {
        Package first = new Package("A");
        first.setFootprint(footprint(1, 2));
        Package second = new Package("B");
        second.setFootprint(footprint(1, 2));
        Package verified = new Package("C");
        verified.setFootprint(footprint(1, 2));
        verified.setReviewState(Package.ReviewState.VERIFIED);
        Package missing = new Package("EMPTY");

        PackageLibraryAuditService.Result result = PackageLibraryAuditService.audit(
                Arrays.asList(first, second, verified, missing),
                Arrays.asList(first, second, verified, missing));

        assertEquals(1, result.duplicateGroups);
        assertEquals(3, result.duplicatePackages);
        assertEquals(first.getDuplicateGroupId(), second.getDuplicateGroupId());
        assertEquals(Package.ReviewState.DUPLICATE_CANDIDATE, first.getReviewState());
        assertEquals(Package.ReviewState.VERIFIED, verified.getReviewState());
        assertEquals(Package.ReviewState.MISSING_FOOTPRINT, missing.getReviewState());
        assertNull(missing.getDuplicateGroupId());
    }

    @Test
    void bomResolutionReusesAliasAndOnlyCreatesUnmatchedPlaceholder() {
        Configuration configuration = Configuration.get();
        Package existing = new Package("SOIC-8");
        existing.setAliases("Package_SO:SOIC-8");
        existing.setFootprint(footprint(1, 2));
        configuration.addPackage(existing);

        Package reused = PackageResolutionService.resolveOrCreatePlaceholder(configuration,
                "Package_SO:SOIC-8", Package.SourceType.BOM, "board.csv", "BATCH-1");
        Package created = PackageResolutionService.resolveOrCreatePlaceholder(configuration,
                "Unknown_LGA-9", Package.SourceType.BOM, "board.csv", "BATCH-1");

        assertSame(existing, reused);
        assertNotEquals(existing, created);
        assertEquals(Package.SourceType.BOM, created.getSourceType());
        assertEquals(Package.ReviewState.MISSING_FOOTPRINT, created.getReviewState());
        assertEquals("BATCH-1", created.getImportBatchId());
    }

    @Test
    void importPlanDoesNotWriteUntilApply() throws Exception {
        Configuration configuration = Configuration.get();
        KicadFootprintParser.Result parsed = new KicadFootprintParser().parse(
                "(footprint \"QFN-4\""
                + " (pad \"1\" smd rect (at -1 -1) (size 1 1) (layers \"F.Cu\"))"
                + " (pad \"2\" smd rect (at 1 -1) (size 1 1) (layers \"F.Cu\"))"
                + " (pad \"3\" smd rect (at 1 1) (size 1 1) (layers \"F.Cu\"))"
                + " (pad \"4\" smd rect (at -1 1) (size 1 1) (layers \"F.Cu\")))");
        PackageImportPlan plan = new PackageImportPlan();

        PackageImportPlan.Item item = plan.add(parsed, configuration);
        assertEquals(PackageImportPlan.Decision.ADD_NEW, item.decision);
        assertNull(configuration.getPackage("QFN-4"));

        plan.apply(configuration);
        Package imported = configuration.getPackage("QFN-4");
        assertEquals(Package.SourceType.KICAD, imported.getSourceType());
        assertEquals(4, imported.getFootprint().getPads().size());
    }

    private static Footprint footprint(double x, double width) {
        Footprint footprint = new Footprint();
        footprint.setBodyWidth(4);
        footprint.setBodyHeight(5);
        Pad pad = new Pad();
        pad.setName("1");
        pad.setX(x);
        pad.setY(1);
        pad.setWidth(width);
        pad.setHeight(1);
        footprint.addPad(pad);
        return footprint;
    }

    private static void assertCategory(String expected, String packageId) {
        Package pkg = new Package(packageId);
        PackageClassifier.Result result = PackageClassifier.classify(pkg);
        assertEquals(expected, result.categoryId,
                () -> packageId + " 分类原因：" + result.reason);
        assertTrue(result.confidence >= .8);
    }
}
