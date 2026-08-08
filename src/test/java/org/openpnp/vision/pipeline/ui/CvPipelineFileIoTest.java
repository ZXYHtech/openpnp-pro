package org.openpnp.vision.pipeline.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.stages.Threshold;

class CvPipelineFileIoTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsAndImportsTheSameXmlFormatAsClipboard() throws Exception {
        CvPipeline source = new CvPipeline();
        Threshold threshold = new Threshold();
        threshold.setName("中文阈值阶段");
        source.add(threshold);

        File selected = temporaryDirectory.resolve("bottom-vision-pipeline").toFile();
        CvPipelineFileIo.write(source, selected);
        File xmlFile = temporaryDirectory.resolve("bottom-vision-pipeline.xml").toFile();

        assertTrue(xmlFile.isFile());
        CvPipeline imported = CvPipelineFileIo.read(xmlFile);
        assertEquals(source.toXmlString(), imported.toXmlString());
    }

    @Test
    void failedImportDoesNotMutateTheCurrentPipeline() throws Exception {
        CvPipeline target = new CvPipeline();
        Threshold threshold = new Threshold();
        threshold.setName("keep-me");
        target.add(threshold);
        String before = target.toXmlString();
        File invalid = temporaryDirectory.resolve("invalid.xml").toFile();
        Files.write(invalid.toPath(), "not a pipeline".getBytes(StandardCharsets.UTF_8));

        assertThrows(Exception.class, () -> CvPipelineFileIo.replace(target, invalid));
        assertEquals(before, target.toXmlString());
    }

    @Test
    void previewCloneCanReceivePreparedRuntimePropertiesWithoutChangingXml() throws Exception {
        CvPipeline prepared = new CvPipeline();
        prepared.setProperty("camera", "configured-camera");
        CvPipeline preview = prepared.clone();

        prepared.copyPropertiesTo(preview);

        assertEquals("configured-camera", preview.getProperty("camera"));
        assertEquals(prepared.toXmlString(), preview.toXmlString());
    }

    @Test
    void writesSmartSchemeAsNativePipelineXml() throws Exception {
        CvPipeline source = new CvPipeline();
        Threshold threshold = new Threshold();
        threshold.setName("智能方案阈值");
        source.add(threshold);
        String xml = source.toXmlStringPreservingRuntimeStageValues();

        File selected = temporaryDirectory.resolve("smart-scheme").toFile();
        CvPipelineFileIo.writeXml(xml, selected);
        File exported = temporaryDirectory.resolve("smart-scheme.xml").toFile();

        assertTrue(exported.isFile());
        try (CvPipeline imported = CvPipelineFileIo.read(exported)) {
            assertEquals("智能方案阈值", imported.getStages().get(0).getName());
        }
    }

    @Test
    void rejectsBrokenSmartSchemeBeforeCreatingExportFile() {
        File exported = temporaryDirectory.resolve("broken.xml").toFile();

        assertThrows(Exception.class, () -> CvPipelineFileIo.writeXml("not xml", exported));
        assertFalse(exported.exists());
    }
}
