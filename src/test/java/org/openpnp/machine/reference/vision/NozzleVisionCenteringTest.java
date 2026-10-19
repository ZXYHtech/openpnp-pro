package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class NozzleVisionCenteringTest {
    private static class Calibration extends org.openpnp.machine.reference.ReferenceNozzleTipCalibration {
        Location base = new Location(LengthUnit.Millimeters, .1, -.2, 0, 0);
        @Override public Location getCalibratedCameraOffset(org.openpnp.machine.reference.ReferenceNozzle n, org.openpnp.spi.Camera c) { return base; }
    }
    private static class Tip extends org.openpnp.machine.reference.ReferenceNozzleTip {
        final Calibration calibration = new Calibration();
        @Override public Calibration getCalibration() { return calibration; }
    }
    private static class Nozzle extends org.openpnp.machine.reference.ReferenceNozzle {
        final Tip tip = new Tip();
        @Override public Tip getNozzleTip() { return tip; }
        @Override public Tip getCalibrationNozzleTip() { return tip; }
    }

    @Test
    public void balancedRotationSeparatesStaticBiasFromEccentricity() throws Exception {
        List<Location> observations = new ArrayList<>();
        for (double angle : new double[] {-135, -45, 45, 135}) {
            double radians = Math.toRadians(angle);
            observations.add(new Location(LengthUnit.Millimeters,
                    .18 + .07 * Math.cos(radians), -.12 + .07 * Math.sin(radians), 0, 0));
        }
        Location center = NozzleVisionCentering.mean(observations);
        assertEquals(.18, center.getX(), 1e-12);
        assertEquals(-.12, center.getY(), 1e-12);
        NozzleVisionCentering.accept(center, new Location(LengthUnit.Millimeters, .005, -.008, 0, 0), .01);
    }

    @Test
    public void worseningNoisyAndNonfiniteResultsFail() throws Exception {
        Location before = new Location(LengthUnit.Millimeters, .1, 0, 0, 0);
        for (double[] result : new double[][] {{.2, .01}, {.07, .01}, {.01, .04}}) {
            try {
                NozzleVisionCentering.accept(before, new Location(LengthUnit.Millimeters, result[0], 0, 0, 0), result[1]);
                fail("Rejected quality condition accepted");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("复核未通过"));
            }
        }
        try {
            NozzleVisionCentering.mean(Arrays.asList(new Location(LengthUnit.Millimeters, Double.NaN, 0, 0, 0)));
            fail("Nonfinite sample accepted");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("无效"));
        }
    }

    @Test
    public void imagingTranslationPreservesPlacementReferenceAtEveryAngle() {
        Location shift = new Location(LengthUnit.Millimeters, -.1436, .1617, 0, 0);
        Location reference = new Location(LengthUnit.Millimeters, .04, -.02, 0, 0);
        for (double angle : new double[] {-90, 0, 45, 180}) {
            for (double residual : new double[] {-7, 0, 12}) {
                Location originalRaw = new Location(LengthUnit.Millimeters, .23, -.14, 0, 0);
                // Moving the imaging pose by -shift changes the detected raw position by -shift.
                Location centeredRaw = originalRaw.subtract(shift);
                for (boolean preRotated : new boolean[] {false, true}) {
                    Location expected = ReferenceBottomVision.normalizeExperimentalOffsets(originalRaw, residual, reference, angle, preRotated);
                    Location actual = ReferenceBottomVision.normalizeExperimentalOffsets(centeredRaw.add(shift), residual, reference, angle, preRotated);
                    assertEquals(expected.getX(), actual.getX(), 1e-12);
                    assertEquals(expected.getY(), actual.getY(), 1e-12);
                    assertEquals(expected.getRotation(), actual.getRotation(), 1e-12);
                }
            }
        }
    }

    @Test
    public void nativeRecalibrationRebasesImagingShiftAndControlsPersist() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("nozzle-center-");
        org.openpnp.model.Configuration.initialize(folder.toFile());
        var machine = new org.openpnp.machine.reference.ReferenceMachine();
        org.openpnp.model.Configuration.get().setMachine(machine);
        var head = new org.openpnp.machine.reference.ReferenceHead();
        machine.addHead(head);
        Nozzle nozzle = new Nozzle();
        head.addNozzle(nozzle);
        var camera = new org.openpnp.machine.reference.camera.ImageCamera();
        camera.setLooking(org.openpnp.spi.Camera.Looking.Up);
        machine.addCamera(camera);
        var signature = NozzleVisionCentering.class.getDeclaredMethod("signature",
                org.openpnp.machine.reference.ReferenceNozzle.class, org.openpnp.spi.Camera.class);
        signature.setAccessible(true);
        NozzleVisionCentering.Entry entry = new NozzleVisionCentering.Entry();
        entry.signature = (String) signature.invoke(null, nozzle, camera);
        entry.x = .3;
        entry.y = -.15;
        NozzleVisionCentering.State state = new NozzleVisionCentering.State();
        state.entries.put(camera.getId() + "/" + head.getId() + "/" + nozzle.getId() + "/" + nozzle.tip.getId(), entry);
        java.nio.file.Files.writeString(folder.resolve("pro-nozzle-vision-centering.json"), new com.google.gson.Gson().toJson(state));
        assertEquals(.2, NozzleVisionCentering.offset(nozzle, camera).getX(), 1e-12);
        nozzle.tip.calibration.base = new Location(LengthUnit.Millimeters, .16, -.24, 0, 0);
        Location shifted = NozzleVisionCentering.offset(nozzle, camera);
        assertEquals(.14, shifted.getX(), 1e-12);
        assertEquals(.09, shifted.getY(), 1e-12);
        assertEquals(.3, shifted.add(nozzle.tip.calibration.base).getX(), 1e-12);
        NozzleVisionCentering.setEnabled(nozzle, camera, false);
        assertEquals(0, NozzleVisionCentering.norm(NozzleVisionCentering.offset(nozzle, camera)), 0);
        NozzleVisionCentering.setEnabled(nozzle, camera, true);
        assertEquals(.14, NozzleVisionCentering.offset(nozzle, camera).getX(), 1e-12);
        NozzleVisionCentering.undo(nozzle, camera);
        assertEquals(0, NozzleVisionCentering.norm(NozzleVisionCentering.offset(nozzle, camera)), 0);
        assertTrue(java.nio.file.Files.readString(folder.resolve("pro-nozzle-vision-centering.json")).contains("\"entries\": {}"));
    }
}
