package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.*;
import org.openpnp.vision.pipeline.stages.*;
import org.opencv.core.RotatedRect;

class FlyingVisionFrozenAnalysisTest {
    @BeforeAll
    static void load() throws Exception { Class.forName("org.openpnp.util.OpenCvUtils"); }

    private Camera camera() {
        return (Camera)Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[] {Camera.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getWidth") || method.getName().equals("getHeight")) {
                        return 400;
                    }
                    throw new AssertionError("Frozen analysis accessed the camera: " + method.getName());
                });
    }

    private void shot(CvPipeline pipeline) {
        pipeline.new PipelineShot() {
            CvStage.Result result;
            @Override public void apply() { throw new AssertionError("Frozen analysis moved the nozzle"); }
            @Override public void processResult(CvStage.Result value) { result = value; }
            @Override public CvStage.Result processCompositeResult() { return result; }
        };
    }

    @Test
    void usesOnlyInjectedPixelsWithoutMovingOrCapturing() throws Exception {
        BufferedImage frame = new BufferedImage(400, 400, BufferedImage.TYPE_3BYTE_BGR);
        var graphics = frame.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(230, 180, 40, 20);
        graphics.dispose();
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("capture", new ImageCapture());
            pipeline.add("gray", new ConvertColor());
            MinAreaRect detector = new MinAreaRect();
            detector.setThresholdMin(200);
            detector.setThresholdMax(255);
            pipeline.add("results", detector);
            pipeline.setProperty("camera", camera());
            shot(pipeline);
            RotatedRect rect = FlyingVisionFrozenAnalysis.analyze(pipeline, camera(), frame);
            assertEquals(249.5, rect.center.x, .1);
            assertEquals(189.5, rect.center.y, .1);
        }
    }

    @Test
    void refusesMultiShotInputsBeforeAnyHardwareAction() throws Exception {
        try (CvPipeline pipeline = new CvPipeline()) {
            pipeline.add("capture", new ImageCapture());
            shot(pipeline);
            shot(pipeline);
            assertThrows(Exception.class, () -> FlyingVisionFrozenAnalysis.analyze(pipeline, camera(),
                    new BufferedImage(400, 400, BufferedImage.TYPE_3BYTE_BGR)));
        }
    }
}
