package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.stages.MaskRectangle;
import org.openpnp.vision.pipeline.stages.MatchTemplate;
import org.openpnp.vision.pipeline.stages.Threshold;

public class SmartVisionSchemeStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void savesAndRestoresCompleteSchemeHistory() throws Exception {
        SmartVisionScheme scheme = new SmartVisionScheme();
        scheme.setName("原始方案");
        scheme.setDescription("识别 0603 电阻");
        scheme.setActive(true);
        scheme.setPipelineXml("<cv-pipeline/>");
        SmartVisionSchemeStore store = new SmartVisionSchemeStore(
                temporaryDirectory.toFile(), "bottom-vision");
        store.save(Arrays.asList(scheme));

        List<SmartVisionScheme> restored = store.load();
        assertEquals(1, restored.size());
        assertEquals("原始方案", restored.get(0).getName());
        assertTrue(restored.get(0).isActive());
        assertEquals("<cv-pipeline/>", restored.get(0).getPipelineXml());
    }

    @Test
    public void tunerPreservesMasksAndRaisesTemplateConfidence() throws Exception {
        CvPipeline original = new CvPipeline();
        Threshold binary = new Threshold();
        MaskRectangle mask = new MaskRectangle();
        mask.setWidth(123);
        mask.setHeight(87);
        MatchTemplate match = new MatchTemplate();
        match.setTemplateStageName("template");
        original.add("binary", binary);
        original.add("mask", mask);
        original.add("match", match);

        CvPipeline tuned = SmartVisionPipelineTuner.tune(original, null, 0.92);
        assertEquals(123, ((MaskRectangle) tuned.getStage("mask")).getWidth());
        assertEquals(87, ((MaskRectangle) tuned.getStage("mask")).getHeight());
        assertEquals(0.92, ((MatchTemplate) tuned.getStage("match")).getCorr(), 0.0001);
        assertEquals(3, tuned.getStages().size());
    }

    @Test
    public void tunerCanSnapshotCameraScaledPipelineOffline() throws Exception {
        String resource = "/org/openpnp/machine/reference/vision/"
                + "ReferenceFiducialLocator-DefaultPipeline.xml";
        CvPipeline offline;
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            offline = new CvPipeline(IOUtils.toString(input, StandardCharsets.UTF_8));
        }
        assertNull(offline.getProperty("camera"));

        CvPipeline tunedOffline = SmartVisionPipelineTuner.tune(offline, null, 0.90);
        String offlineXml = tunedOffline.toXmlStringPreservingRuntimeStageValues();
        assertTrue(offlineXml.contains("MillimetersToPixels"));

        Camera camera = mock(Camera.class);
        when(camera.getUnitsPerPixelAtZ()).thenReturn(
                new Location(LengthUnit.Millimeters, 0.02, 0.02, 0, 0));
        offline.setProperty("camera", camera);
        CvPipeline tunedOnline = SmartVisionPipelineTuner.tune(offline, null, 0.90);
        assertSame(camera, tunedOnline.getProperty("camera"));
        assertTrue(tunedOnline.toXmlString().contains("MillimetersToPixels"));
    }

    @Test
    public void createsVisibleBackupBeforeExclusiveChannelActivation() throws Exception {
        SmartVisionScheme original = new SmartVisionScheme();
        original.setName("原始基准方案");
        original.setPipelineXml("<cv-pipeline/> ");
        original.setActive(true);
        SmartVisionScheme replacement = new SmartVisionScheme();
        replacement.setName("智能方案");
        replacement.setPipelineXml("<cv-pipeline/> ");
        List<SmartVisionScheme> schemes = new java.util.ArrayList<>(
                Arrays.asList(original, replacement));

        SmartVisionScheme backup = SmartVisionSchemeTransfer.createAutomaticBackup(
                original.getPipelineXml(), "顶部视觉");
        schemes.add(backup);
        SmartVisionSchemeTransfer.activateExclusive(schemes, replacement);

        assertTrue(backup.getName().startsWith("基准视觉自动备份"));
        assertTrue(backup.getDescription().contains("顶部视觉"));
        assertEquals(original.getPipelineXml(), backup.getPipelineXml());
        assertTrue(replacement.isActive());
        assertFalse(original.isActive());
        assertFalse(backup.isActive());
    }
}
