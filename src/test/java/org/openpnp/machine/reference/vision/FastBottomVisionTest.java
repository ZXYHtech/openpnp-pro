package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.opencv.core.RotatedRect;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.vision.FluentCv.ColorCode;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.stages.*;

class FastBottomVisionTest {
    private Camera camera(int width, int height) {
        return (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[]{Camera.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getWidth": return width;
                        case "getHeight": return height;
                        case "getUnitsPerPixelAtZ": return new Location(LengthUnit.Millimeters, .03, .03, 0, 0);
                        case "getLocation": return Location.origin;
                        default: throw new AssertionError("Unexpected camera access: " + m.getName());
                    }
                });
    }

    private CvPipeline pipeline(BufferedImage image, int diameter) {
        CvPipeline pipeline = new CvPipeline();
        pipeline.setProperty("camera", camera(image.getWidth(), image.getHeight()));
        pipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY, image);
        pipeline.add("image", new ImageCapture());
        MaskCircle mask = new MaskCircle();
        mask.setDiameter(diameter);
        mask.setPropertyName("");
        pipeline.add("mask", mask);
        BlurGaussian blur = new BlurGaussian();
        blur.setKernelSize(9);
        pipeline.add("blur", blur);
        ConvertColor gray = new ConvertColor();
        gray.setConversion(ColorCode.Bgr2Gray);
        pipeline.add("gray", gray);
        Threshold threshold = new Threshold();
        threshold.setThreshold(100);
        pipeline.add("threshold", threshold);
        MinAreaRect rectangle = new MinAreaRect();
        rectangle.setThresholdMin(100);
        rectangle.setThresholdMax(255);
        pipeline.add("results", rectangle);
        ImageRecall recall = new ImageRecall();
        recall.setImageStageName("image");
        pipeline.add("recall", recall);
        return pipeline;
    }

    @Test void croppedDetectionMatchesFullFrameAcrossOffsetsAnglesAndOddDimensions() throws Exception {
        for (int width : new int[]{1280,1281}) {
            for (double degrees : new double[]{0,17,44,-28}) {
            BufferedImage image = new BufferedImage(width, 550, BufferedImage.TYPE_3BYTE_BGR);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.translate(width / 2.0 + 31, 550 / 2.0 - 19);
            g.rotate(Math.toRadians(degrees));
            g.fillRect(-35, -15, 70, 30);
            g.dispose();
            try (CvPipeline full = pipeline(image, 260); CvPipeline fast = pipeline(image, 260)) {
                full.process();
                RotatedRect expected = (RotatedRect)full.getResult("results").model;
                try (FastBottomVision.CaptureScope scope = FastBottomVision.prepareCapture(fast, (Camera)fast.getProperty("camera"))) {
                    assertNotNull(fast.getProperty(FastBottomVision.REGION));
                    assertTrue(FastBottomVision.settlingImage(image).getWidth() < width);
                }
                assertSame(image, FastBottomVision.settlingImage(image));
                FastBottomVision.process(fast);
                RotatedRect actual = (RotatedRect)fast.getResult("results").model;
                assertEquals(expected.center.x, actual.center.x, .0002);
                assertEquals(expected.center.y, actual.center.y, .0002);
                assertEquals(expected.angle, actual.angle, .0002);
                assertEquals(expected.size.width, actual.size.width, .0002);
                assertEquals(expected.size.height, actual.size.height, .0002);
                assertNotNull(fast.getResult("image").image);
                assertNull(fast.getResult("blur").image);
                assertEquals(((FastBottomVision.Region)fast.getProperty(FastBottomVision.REGION)).bounds.width,
                        fast.getWorkingImage().cols()); // ImageRecall still has its required source.
                assertEquals(width, full.getWorkingImage().cols());
            }
            }
        }
    }

    @Test void automaticThresholdAndInvertedMaskKeepOriginalFullFrameSemantics() throws Exception {
        BufferedImage image = new BufferedImage(1280,550,BufferedImage.TYPE_3BYTE_BGR);
        try (CvPipeline pipeline = pipeline(image,260)) {
            ((Threshold)pipeline.getStage("threshold")).setAuto(true);
            assertNull(FastBottomVision.findRegion(pipeline,(Camera)pipeline.getProperty("camera")));
            ((Threshold)pipeline.getStage("threshold")).setAuto(false);
            ((MaskCircle)pipeline.getStage("mask")).setDiameter(-260);
            assertNull(FastBottomVision.findRegion(pipeline,(Camera)pipeline.getProperty("camera")));
        }
    }

    @Test void parameterAssignmentsDetermineActualMaskBeforeCropping() throws Exception {
        BufferedImage image = new BufferedImage(1280,550,BufferedImage.TYPE_3BYTE_BGR);
        try (CvPipeline pipeline = pipeline(image,260)) {
            ParameterNumeric parameter = new ParameterNumeric();
            parameter.setName("maskSize");
            parameter.setParameterLabel("Mask");
            parameter.setStageName("mask");
            parameter.setPropertyName("diameter");
            pipeline.insert("maskSize",parameter,0);
            pipeline.setProperty("maskSize",400);
            FastBottomVision.Region region = FastBottomVision.findRegion(pipeline,(Camera)pipeline.getProperty("camera"));
            assertNotNull(region);
            assertTrue(region.bounds.width > 400);
            pipeline.setProperty("maskSize",900);
            assertNull(FastBottomVision.findRegion(pipeline,(Camera)pipeline.getProperty("camera")));
        }
    }

    @Test void mismatchedCapturedGeometryIsRejectedAndScopeRestoredOnFailure() throws Exception {
        BufferedImage image = new BufferedImage(1280,550,BufferedImage.TYPE_3BYTE_BGR);
        try (CvPipeline pipeline = pipeline(image,260)) {
            assertThrows(IllegalStateException.class,()->{
                try (FastBottomVision.CaptureScope scope = FastBottomVision.prepareCapture(pipeline,(Camera)pipeline.getProperty("camera"))) {
                    throw new IllegalStateException("Capture failure");
                }
            });
            assertSame(image,FastBottomVision.settlingImage(image));
            pipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY,new BufferedImage(640,480,BufferedImage.TYPE_3BYTE_BGR));
            assertThrows(Exception.class,()->FastBottomVision.process(pipeline));
        }
    }
}
