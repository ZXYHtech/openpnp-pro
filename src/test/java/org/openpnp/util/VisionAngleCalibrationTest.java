package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

class VisionAngleCalibrationTest {

    private Camera camera(double x, double y) {
        Camera camera = mock(Camera.class);
        when(camera.getUnitsPerPixelAtZ()).thenReturn(new Location(LengthUnit.Millimeters, x, y, 0, 0));
        return camera;
    }

    @Test
    void squarePixelsKeepEveryExistingAngleIncludingFullTurns() {
        Camera camera = camera(.01, .01);
        for (double angle = -1080; angle <= 1080; angle += 1.25) {
            assertEquals(-angle, VisionUtils.getPixelAngle(camera, angle), 0);
            assertEquals(-angle, VisionUtils.getCameraAngle(camera, angle), 0);
        }
    }

    @Test
    void angleMatchesTheSameCalibratedVectorAsPixelOffsets() throws Exception {
        Camera camera = camera(.01, .0102);
        for (double degrees = -170; degrees <= 170; degrees += 5) {
            double radians = Math.toRadians(degrees);
            Location vector = VisionUtils.getPixelOffsets(camera, 100 * Math.cos(radians), 100 * Math.sin(radians));
            double measured = Math.toDegrees(Math.atan2(vector.getY(), vector.getX()));
            assertEquals(measured, VisionUtils.getPixelAngle(camera, degrees), 1e-12);
        }
        assertTrue(Math.abs(VisionUtils.getPixelAngle(camera, 45) + 45) > .5, "A two percent calibration mismatch must not be ignored");
        verify(camera, never()).capture();
    }

    @Test
    void cameraDirectionAndPhysicalDirectionRoundtripAcrossQuadrantsAndTurns() {
        for (double ratio : new double[] { .5, .98, 1.02, 2 }) {
            Camera camera = camera(.01, .01 * ratio);
            for (double world = -1080; world <= 1080; world += .75) {
                double pixel = VisionUtils.getCameraAngle(camera, world);
                assertEquals(world, VisionUtils.getPixelAngle(camera, pixel), 1e-10);
                assertTrue(Math.abs(pixel + world) < 20);
            }
        }
    }

    @Test
    void invalidOrMissingCalibrationPreservesThePreviousBehavior() {
        for (double[] scale : new double[][] { { 0, 1 }, { -1, 1 }, { 1, Double.NaN }, { Double.POSITIVE_INFINITY, 1 } }) {
            Camera camera = camera(scale[0], scale[1]);
            assertEquals(-37, VisionUtils.getPixelAngle(camera, 37));
            assertEquals(-37, VisionUtils.getCameraAngle(camera, 37));
        }
        assertEquals(-37, VisionUtils.getPixelAngle(null, 37));
        Camera camera = mock(Camera.class);
        assertEquals(-37, VisionUtils.getPixelAngle(camera, 37));
        when(camera.getUnitsPerPixel()).thenReturn(new Location(LengthUnit.Millimeters, .01, .02, 0, 0));
        assertEquals(-Math.toDegrees(Math.atan(2)), VisionUtils.getPixelAngle(camera, 45), 1e-12);
        assertTrue(Double.isNaN(VisionUtils.getPixelAngle(camera, Double.NaN)));
        assertEquals(Double.NEGATIVE_INFINITY, VisionUtils.getPixelAngle(camera, Double.POSITIVE_INFINITY));
    }

    @Test
    void compositeResultEncodingMustUseTheInverseDirection() {
        Camera camera = camera(.01, .015);
        double physical = 27.3;
        double encodedPixelAngle = VisionUtils.getCameraAngle(camera, physical);
        assertEquals(physical, VisionUtils.getPixelAngle(camera, encodedPixelAngle), 1e-12);
        assertTrue(Math.abs(physical - VisionUtils.getPixelAngle(camera, VisionUtils.getPixelAngle(camera, physical))) > 5, "Applying the forward transform twice would bias composite vision");
    }
}
