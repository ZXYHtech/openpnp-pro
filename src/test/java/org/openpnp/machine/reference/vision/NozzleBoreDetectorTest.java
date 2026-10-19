package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

class NozzleBoreDetectorTest {
    @BeforeAll
    static void load() throws Exception { Class.forName("org.openpnp.util.OpenCvUtils"); }

    private Mat nozzle(double x, double y) {
        Mat large = new Mat(128 * 8, 128 * 8, CvType.CV_8UC1, new Scalar(25));
        Imgproc.circle(large, new Point(x * 8, y * 8), 15 * 8, new Scalar(180), -1);
        Imgproc.circle(large, new Point(x * 8, y * 8), 9 * 8, new Scalar(8), -1);
        // A nearby reflection must not become the selected feature.
        Imgproc.ellipse(large, new Point((x + 23) * 8, (y + 14) * 8), new Size(7 * 8, 3 * 8),
                20, 0, 360, new Scalar(170), -1);
        Mat image = new Mat();
        Imgproc.resize(large, image, new Size(128, 128), 0, 0, Imgproc.INTER_AREA);
        large.release();
        return image;
    }

    @Test
    void subpixelCentersSurviveTranslationBrightnessAndBlur() throws Exception {
        for (double x : new double[] {57.25, 64.5, 70.75}) {
            for (double gain : new double[] {.6, 1, 1.4}) {
                Mat image = nozzle(x, 62.25);
                try {
                    image.convertTo(image, -1, gain);
                    Imgproc.GaussianBlur(image, image, new Size(5, 5), .7);
                    NozzleBoreDetector.Detection result = NozzleBoreDetector.detect(image, new Point(64, 64), 20, 30);
                    // INTER_AREA's pixel-center convention shifts the rendered reference by 7/16 px.
                    assertEquals(x - 7.0 / 16, result.center.x, .25);
                    assertEquals(62.25 - 7.0 / 16, result.center.y, .25);
                    assertTrue(result.edgeCoverage >= .875);
                    assertTrue(result.edgeResidual < .8);
                } finally { image.release(); }
            }
        }
    }

    @Test
    void absentPartialAmbiguousAndOutOfRangeFeaturesAreRejected() throws Exception {
        Mat blank = new Mat(128, 128, CvType.CV_8UC1, new Scalar(25));
        Mat partial = nozzle(64, 64), duplicate = nozzle(46, 64), distant = nozzle(90, 64);
        try {
            Imgproc.rectangle(partial, new Point(64, 62), new Point(88, 67), new Scalar(8), -1);
            Imgproc.circle(duplicate, new Point(82, 64), 15, new Scalar(180), -1);
            Imgproc.circle(duplicate, new Point(82, 64), 9, new Scalar(8), -1);
            for (Mat image : new Mat[] {blank, partial, duplicate}) {
                assertThrows(Exception.class, () -> NozzleBoreDetector.detect(image, new Point(64, 64), 30, 30));
            }
            assertThrows(Exception.class, () -> NozzleBoreDetector.detect(distant, new Point(64, 64), 10, 30));
        } finally {
            blank.release();
            partial.release();
            duplicate.release();
            distant.release();
        }
    }
}
