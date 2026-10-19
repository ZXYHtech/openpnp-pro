package org.openpnp.vision.pipeline.stages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.CvType;
import org.openpnp.util.OpenCvUtils;

class CircularSymmetryDiagnosticsTest {
    @Test void invalidRoiReportsImageAndExpectedCenterInsteadOfGenericCropping() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Mat image = Mat.zeros(240, 320, CvType.CV_8UC3);
        try {
            Exception failure = assertThrows(Exception.class, () -> DetectCircularSymmetry.findCircularSymmetry(
                    image, 9000, -4000, 15, 25, 50, 50, 50, 1, 1.2, 0.8, 2, 1,
                    DetectCircularSymmetry.SymmetryScore.OverallVarianceVsRingVarianceSum,
                    false, false, new DetectCircularSymmetry.ScoreRange()));
            assertTrue(failure.getMessage().contains("预期中心位于图像外"));
            assertTrue(failure.getMessage().contains("320×240"));
            assertTrue(failure.getMessage().contains("9000, -4000"));
        }
        finally { image.release(); }
    }
}
