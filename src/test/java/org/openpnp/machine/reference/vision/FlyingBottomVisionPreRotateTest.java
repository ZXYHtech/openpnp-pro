package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.vision.ReferenceBottomVision.PreRotateUsage;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;

class FlyingBottomVisionPreRotateTest {
    @Test
    void frameBufferIsBoundedForHdAndFourKImages() {
        long heap768MiB = 768L * 1024 * 1024;
        long heap1536MiB = 1536L * 1024 * 1024;

        assertEquals(16, FlyingBottomVisionService.frameBufferCapacity(
                1920, 1080, 8, heap1536MiB));
        int fourKCapacity = FlyingBottomVisionService.frameBufferCapacity(
                3840, 2160, 8, heap768MiB);
        assertTrue(fourKCapacity >= 3);
        assertTrue(fourKCapacity <= 4);
    }

    @Test
    void longCaptureIsTemporallyDecimatedWithoutGrowingTheBuffer() {
        FlyingBottomVisionService.BoundedFrameCollector collector =
                new FlyingBottomVisionService.BoundedFrameCollector(4, 768L * 1024 * 1024);
        BufferedImage frame = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
        frame.setRGB(0, 0, Color.WHITE.getRGB());
        for (int index = 0; index < 1000; index++) {
            collector.offer(frame, index);
            assertTrue(collector.size() <= 8);
        }

        List<FlyingBottomVisionService.FrameSample> retained = collector.snapshot();
        assertTrue(retained.size() >= 4);
        assertEquals(0, retained.get(0).getNanoTime());
        assertTrue(retained.get(retained.size() - 1).getNanoTime() > 800);
    }

    @Test
    void honorsInheritedPreRotatePolicy() {
        assertTrue(FlyingBottomVisionService.usesPreRotate(PreRotateUsage.AlwaysOn, false));
        assertFalse(FlyingBottomVisionService.usesPreRotate(PreRotateUsage.AlwaysOff, true));
        assertTrue(FlyingBottomVisionService.usesPreRotate(PreRotateUsage.Default, true));
        assertFalse(FlyingBottomVisionService.usesPreRotate(PreRotateUsage.Default, false));
    }

    @Test
    void postRotateAlwaysUsesZeroDegrees() {
        Placement placement = placement(73.0);
        assertEquals(0.0, FlyingBottomVisionService.calculateWantedAngle(
                false, null, placement, -42.0), 0.000001);
    }

    @Test
    void productionPreRotateUsesPlacementAngle() {
        Placement placement = placement(123.0);
        assertEquals(123.0, FlyingBottomVisionService.calculateWantedAngle(
                true, null, placement, -42.0), 0.000001);
    }

    @Test
    void manualFlightTestKeepsCurrentNozzleAngleAndNormalizesIt() {
        assertEquals(170.0, FlyingBottomVisionService.calculateWantedAngle(
                true, null, null, -190.0), 0.000001);
        assertEquals(0.0, FlyingBottomVisionService.calculateWantedAngle(
                true, null, null, Double.NaN), 0.000001);
    }

    @Test
    void preRotateCompensatesOffCenterPartAroundNozzleAxis() {
        Location raw = new Location(LengthUnit.Millimeters, 1, 0, 0, 0);
        Location zeroVisionOffset = new Location(LengthUnit.Millimeters);

        Location preRotated = ReferenceBottomVision.normalizeExperimentalOffsets(
                raw, 90.0, zeroVisionOffset, 90.0, true);
        Location postRotated = ReferenceBottomVision.normalizeExperimentalOffsets(
                raw, 90.0, zeroVisionOffset, 0.0, false);

        assertEquals(0.0, preRotated.getX(), 0.000001);
        assertEquals(-1.0, preRotated.getY(), 0.000001);
        assertEquals(90.0, preRotated.getRotation(), 0.000001);
        assertEquals(1.0, postRotated.getX(), 0.000001);
        assertEquals(0.0, postRotated.getY(), 0.000001);
        assertEquals(90.0, postRotated.getRotation(), 0.000001);
    }

    @Test
    void motionCorrectionUsesThePreRotateResidualCoordinateFrame() {
        PartAlignmentOffset observed = new PartAlignmentOffset(
                new Location(LengthUnit.Millimeters, 0, -1, 0, 90), true);

        PartAlignmentOffset corrected = FlyingBottomVisionService.correctForMotion(
                observed, 1.0);

        assertEquals(0.0, corrected.getLocation().getX(), 0.000001);
        assertEquals(0.0, corrected.getLocation().getY(), 0.000001);
        assertEquals(90.0, corrected.getLocation().getRotation(), 0.000001);
        assertTrue(corrected.getPreRotated());
    }

    private static Placement placement(double rotation) {
        Placement placement = new Placement("R1");
        placement.setLocation(new Location(LengthUnit.Millimeters, 10, 20, 0, rotation));
        return placement;
    }
}
