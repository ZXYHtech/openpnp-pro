package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;

import org.junit.jupiter.api.Test;

public class CameraViewCoordinateTransformTest {
    @Test
    public void centerHasNoOnePixelBias() {
        Point2D.Double offset = CameraViewCoordinateTransform.centerOffset(
                500, 500, 0, 219, 1000, 562, 0.02, 0.02);
        assertEquals(0.0, offset.x, 0.000001);
        assertEquals(0.0, offset.y, 0.000001);
    }

    @Test
    public void physicalOffsetIsIndependentOfDisplayedResolution() {
        Point2D.Double large = CameraViewCoordinateTransform.centerOffset(
                740, 270, 0, 0, 960, 540, 0.02, 0.02);
        Point2D.Double small = CameraViewCoordinateTransform.centerOffset(
                370, 135, 0, 0, 480, 270, 0.04, 0.04);
        assertEquals(large.x, small.x, 0.000001);
        assertEquals(large.y, small.y, 0.000001);
        assertEquals(5.2, large.x, 0.000001);
    }

    @Test
    public void zoomAndCenteredImageOriginRemainAccurate() {
        Point2D.Double offset = CameraViewCoordinateTransform.centerOffset(
                750, 500, -500, -62.5, 2000, 1125, 0.01, 0.01);
        assertEquals(2.5, offset.x, 0.000001);
        assertEquals(0.0, offset.y, 0.000001);
    }

    @Test
    public void highZoomMakesSmallScreenMotionPhysicallySmaller() {
        Point2D.Double normal = CameraViewCoordinateTransform.centerOffset(
                510, 500, 0, 0, 1000, 1000, 0.02, 0.02);
        Point2D.Double zoomed = CameraViewCoordinateTransform.centerOffset(
                510, 500, -3500, -3500, 8000, 8000, 0.0025, 0.0025);

        assertEquals(0.2, normal.x, 0.000001);
        assertEquals(0.025, zoomed.x, 0.000001);
        assertTrue(zoomed.x < normal.x);
    }

    @Test
    public void highZoomRestrictsPrecisionSearchAndCorrection() {
        int normalRadius = CameraViewCoordinateTransform.precisionSearchRadius(1280, 1);
        int zoomedRadius = CameraViewCoordinateTransform.precisionSearchRadius(1280, 16);
        double normalLimit = CameraViewCoordinateTransform.precisionCorrectionLimit(
                5.0, 0.01, 1, 2.0);
        double zoomedLimit = CameraViewCoordinateTransform.precisionCorrectionLimit(
                0.1, 0.01, 16, 2.0);

        assertTrue(zoomedRadius < normalRadius);
        assertTrue(zoomedLimit <= 0.25);
        assertTrue(zoomedLimit < normalLimit);
    }

    @Test
    public void blackBarsAreNotPartOfTheImage() {
        assertFalse(CameraViewCoordinateTransform.contains(500, 100,
                0, 219, 1000, 562));
        assertTrue(CameraViewCoordinateTransform.contains(500, 500,
                0, 219, 1000, 562));
    }

    @Test
    public void outsideImageContinuesLinearExtrapolationForFastMoves() {
        Point2D.Double edge = CameraViewCoordinateTransform.centerOffset(
                1000, 500, 0, 219, 1000, 562, 0.02, 0.02);
        Point2D.Double outside = CameraViewCoordinateTransform.centerOffset(
                1250, 500, 0, 219, 1000, 562, 0.02, 0.02);

        assertEquals(10.0, edge.x, 0.000001);
        assertEquals(15.0, outside.x, 0.000001);
        assertEquals(edge.y, outside.y, 0.000001);
    }
}
