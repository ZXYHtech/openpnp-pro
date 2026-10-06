package org.openpnp.packageimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.importer.KicadFootprintParser;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Optional read-only smoke tests against locally installed OpenPnP and KiCad data. */
class InstalledPackageDataSmokeTest {
    @BeforeEach
    void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void previewsOneRealFootprintFromEveryInstalledKicadLibrary() throws Exception {
        String configured = System.getProperty("kicad.footprints");
        Assumptions.assumeTrue(configured != null && Files.isDirectory(Paths.get(configured)),
                "set -Dkicad.footprints to an installed KiCad footprints directory");
        Path root = Paths.get(configured);
        List<Path> candidates = new ArrayList<>();
        if (Boolean.getBoolean("kicad.fullScan")) {
            try (Stream<Path> files = Files.walk(root)) {
                candidates.addAll(files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".kicad_mod"))
                        .sorted().collect(Collectors.toList()));
            }
        }
        else {
        try (Stream<Path> directories = Files.list(root)) {
            List<Path> prettyDirectories = directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(".pretty"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path directory : prettyDirectories) {
                try (Stream<Path> files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".kicad_mod"))
                            .min(Comparator.naturalOrder()).ifPresent(candidates::add);
                }
            }
        }
        try (Stream<Path> resistorFiles = Files.walk(root.resolve("Resistor_SMD.pretty"), 1)) {
            resistorFiles.filter(path -> path.getFileName().toString()
                    .startsWith("R_0603_1608Metric"))
                    .findFirst().ifPresent(candidates::add);
        }
        }

        int parsed = 0;
        int classified = 0;
        int failures = 0;
        boolean verifiedR0603 = false;
        KicadFootprintParser parser = new KicadFootprintParser();
        for (Path path : candidates) {
            try {
                PackageImportPlan plan = new PackageImportPlan();
                PackageImportPlan.Item item = plan.add(parser.parse(path.toFile()),
                        Configuration.get());
                parsed++;
                if (item.classification.categoryId != null) {
                    classified++;
                }
                if (!verifiedR0603 && path.getFileName().toString()
                        .startsWith("R_0603_1608Metric")) {
                    assertEquals("passive.resistor", item.classification.categoryId);
                    plan.apply(Configuration.get());
                    Package imported = Configuration.get().getPackage(
                            PackageNameNormalizer.baseName(item.parsed.name));
                    assertEquals("passive.resistor", imported.getCategoryId());
                    verifiedR0603 = true;
                }
            }
            catch (Exception exception) {
                failures++;
            }
        }
        double coverage = parsed == 0 ? 0 : classified / (double) parsed;
        System.out.printf("KiCad preview: candidates=%d parsed=%d failures=%d classified=%d coverage=%.1f%%%n",
                candidates.size(), parsed, failures, classified, coverage * 100);
        assertTrue(candidates.size() >= 100, "应找到 KiCad 官方封装库");
        assertTrue(parsed >= candidates.size() * .9, "真实 KiCad 封装解析成功率应不低于 90%");
        assertTrue(coverage >= .8, "按名称和 .pretty 库名自动分类覆盖率应不低于 80%");
        assertTrue(verifiedR0603, "必须实际预演官方 R_0603 封装");
    }

    @Test
    void classifiesMostNamesFromInstalledOpenPnpPackageFile() throws Exception {
        String configured = System.getProperty("openpnp.packages");
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Paths.get(configured)),
                "set -Dopenpnp.packages to the installed packages.xml");
        File packagesFile = Paths.get(configured).toFile();
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(packagesFile).getElementsByTagName("package");
        int classified = 0;
        boolean verifiedR0603 = false;
        List<String> unclassified = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String id = element.getAttribute("id");
            PackageClassifier.Result result = PackageClassifier.classify(new Package(id));
            if (result.categoryId != null) {
                classified++;
            }
            else {
                unclassified.add(id);
            }
            if ("R0603".equalsIgnoreCase(id)) {
                assertEquals("passive.resistor", result.categoryId);
                assertTrue(result.confidence >= .95);
                verifiedR0603 = true;
            }
        }
        double coverage = nodes.getLength() == 0 ? 0 : classified / (double) nodes.getLength();
        System.out.printf("OpenPnP names: total=%d classified=%d coverage=%.1f%%%n",
                nodes.getLength(), classified, coverage * 100);
        System.out.println("OpenPnP unclassified names: " + String.join(", ", unclassified));
        assertTrue(nodes.getLength() >= 50, "应读取实际 OpenPnP 封装列表");
        assertTrue(coverage >= .65, "只使用现有封装名时自动分类覆盖率应不低于 65%");
        assertTrue(verifiedR0603, "实际封装库中应包含并正确识别 R0603");
    }
}
