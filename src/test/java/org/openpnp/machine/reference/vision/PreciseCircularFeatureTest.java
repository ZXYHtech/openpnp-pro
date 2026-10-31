package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.vision.PreciseCircularFeatureDetector;
import org.openpnp.vision.PreciseCircularFeatureDetector.Feature;

class PreciseCircularFeatureTest {
    private static final double DIAMETER = 2.1 / .030408;
    @BeforeAll static void loadNative() { nu.pattern.OpenCV.loadLocally(); }

    private Mat recorded(String name) throws Exception {
        BufferedImage image = ImageIO.read(getClass().getResource("/vision/top-fiducial/" + name + ".png"));
        return OpenCvUtils.toMat(image);
    }

    private Feature detect(Mat image) {
        var found = PreciseCircularFeatureDetector.detect(image, 128, 128, DIAMETER, 69, 1);
        assertEquals(1, found.size());
        return found.get(0);
    }

    @Test void texturedHoleUsesItsFullBoundaryInsteadOfInternalStripes() throws Exception {
        Mat image = recorded("textured-hole");
        try {
            Feature f = detect(image);
            assertEquals(127.7, f.x, .4);
            assertEquals(125.3, f.y, .4);
            assertTrue(f.rms < .5);
            assertTrue(f.coverage >= .9);
        }
        finally { image.release(); }
    }

    @Test void subpixelTranslationsAreRecoveredFromTheSamePhysicalHole() throws Exception {
        Mat image = recorded("textured-hole"), moved = new Mat(), transform = Mat.eye(2, 3, CvType.CV_64F);
        try {
            Feature baseline = detect(image);
            for (double[] delta : new double[][] { { .35, -.65 }, { -5.5, 4.25 }, { 6, -3 }, { -.8, .7 } }) {
                transform.put(0, 2, delta[0]);
                transform.put(1, 2, delta[1]);
                Imgproc.warpAffine(image, moved, transform, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_REFLECT);
                Feature f = detect(moved);
                assertEquals(delta[0], f.x - baseline.x, .15);
                assertEquals(delta[1], f.y - baseline.y, .15);
            }
        }
        finally {
            image.release();
            moved.release();
            transform.release();
        }
    }

    @Test void brightnessChangesDoNotPullTheCenterTowardTheInterior() throws Exception {
        Mat image = recorded("textured-hole"), changed = new Mat();
        try {
            Feature baseline = detect(image);
            for (double scale : new double[] { .55, .8, 1.15 }) {
                image.convertTo(changed, -1, scale, 8);
                Feature f = detect(changed);
                assertEquals(baseline.x, f.x, .2);
                assertEquals(baseline.y, f.y, .2);
            }
        }
        finally {
            image.release();
            changed.release();
        }
    }

    @Test void ordinaryRecordedHolesRemainRecognisable() throws Exception {
        for (String name : new String[] { "ordinary-hole", "second-hole" }) {
            Mat image = recorded(name);
            try { assertTrue(Math.hypot(detect(image).x - 128, detect(image).y - 128) < 2); }
            finally { image.release(); }
        }
    }

    @Test void rectangularPadsAreNotAcceptedAsCircularFiducials() {
        Mat image = new Mat(256, 256, CvType.CV_8UC3, new Scalar(25, 25, 25));
        try {
            Imgproc.rectangle(image, new Point(95, 95), new Point(165, 165), new Scalar(255, 255, 255), -1);
            assertTrue(PreciseCircularFeatureDetector.detect(image, 128, 128, 70, 69, 1).isEmpty());
        }
        finally { image.release(); }
    }

    @Test void clippedHoleAndWrongDiameterCannotPass() throws Exception {
        Mat image = recorded("textured-hole");
        Mat clipped = image.submat(0, 256, 120, 256);
        try {
            assertTrue(PreciseCircularFeatureDetector.detect(clipped, 8, 128, DIAMETER, 30, 1).isEmpty());
            assertTrue(PreciseCircularFeatureDetector.detect(image, 128, 128, 24, 30, 1).isEmpty());
        }
        finally {
            clipped.release();
            image.release();
        }
    }

    @Test void equalNearbyCircularTargetsAreRejectedAsAmbiguous() {
        Mat image = new Mat(250, 350, CvType.CV_8UC3, new Scalar(20, 20, 20));
        try {
            Imgproc.circle(image, new Point(105, 125), 30, new Scalar(255, 255, 255), 5);
            Imgproc.circle(image, new Point(245, 125), 30, new Scalar(255, 255, 255), 5);
            assertThrows(IllegalStateException.class, () ->
                    PreciseCircularFeatureDetector.detect(image, 175, 125, 60, 100, 1));
        }
        finally { image.release(); }
    }

    @Test void rectangularSearchWindowIsStillEnforced() throws Exception {
        Mat image = recorded("textured-hole");
        try {
            assertTrue(PreciseCircularFeatureDetector.detect(image, 100, 128, DIAMETER, 69, 1, 20, 138).isEmpty());
        }
        finally { image.release(); }
    }

    @Test void settingIsStoredAsAnOrdinaryAssignmentWithoutChangingThePipeline() throws Exception {
        var settings = new FiducialVisionSettings("test-precise-hole");
        var assignments = new HashMap<String, Object>();
        assignments.put("maxDistance", new Length(2.11, LengthUnit.Millimeters));
        settings.setPipelineParameterAssignments(assignments);
        String pipeline = settings.getPipeline().toXmlString();
        settings.setPreciseCircularFeatureEnabled(true);
        assertTrue(settings.isPreciseCircularFeatureEnabled());
        assertEquals(pipeline, settings.getPipeline().toXmlString());
        var out = new java.io.StringWriter();
        new org.simpleframework.xml.core.Persister().write(settings, out);
        assertTrue(out.toString().contains("java.lang.Boolean"));
        assertFalse(out.toString().contains("PreciseCircularFeatureDetector"));
        var restored = new org.simpleframework.xml.core.Persister().read(FiducialVisionSettings.class, out.toString());
        assertTrue(restored.isPreciseCircularFeatureEnabled());
        restored.setPreciseCircularFeatureEnabled(false);
        assertFalse(restored.isPreciseCircularFeatureEnabled());
        assertTrue(restored.getPipelineParameterAssignments().containsKey("maxDistance"));
    }
}
