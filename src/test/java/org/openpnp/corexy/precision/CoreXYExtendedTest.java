package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYExtendedTest {
    @TempDir
    Path temporary;

    @Test
    void ellipseLocalizationHandlesPolarityAndRejectsAmbiguityAndWrongDiameter() {
        for (boolean dark : new boolean[] {true, false}) {
            BufferedImage image = markerImage(100.3, 80.7, dark);
            MarkerDetector.Detection detection = new MarkerDetector().detect(image, 100, 80, 40, 50);
            assertEquals(100.3, detection.x, .7);
            assertEquals(80.7, detection.y, .7);
            assertThrows(IllegalStateException.class, () -> new MarkerDetector().detect(image, 100, 80, 15, 50));
        }
        BufferedImage image = markerImage(80, 80, true);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillOval(100, 60, 40, 40);
        graphics.dispose();
        assertThrows(IllegalStateException.class, () -> new MarkerDetector().detect(image, 100, 80, 40, 60));
    }

    @Test
    void firmwareShapingRequiresCapabilitiesAndRollsBackFailedCalibration() throws Exception {
        String settings = "shaper_type_x:mzv shaper_freq_x:42.5 damping_ratio_x:0.1 "
                + "shaper_type_y:ei shaper_freq_y:51 damping_ratio_y:0.12";
        String command = ControllerShaping.parseSettings(settings);
        assertNotNull(command);
        ControllerShaping.validateCommand(command);
        assertThrows(IllegalArgumentException.class, () -> ControllerShaping.validateCommand(command + "\nSAVE_CONFIG"));
        assertNull(ControllerShaping.parseSettings("shaper_freq_x=NaN"));
        List<String> calls = new ArrayList<>();
        assertThrows(UnsupportedOperationException.class, () -> new ControllerShaping().calibrate((c, t) -> "HELP without sensor"));
        assertThrows(IllegalStateException.class, () -> new ControllerShaping().calibrate((c, t) -> {
            calls.add(c);
            if (c.equals("HELP")) {
                return "SET_INPUT_SHAPER SHAPER_CALIBRATE";
            }
            if (c.equals("SHAPER_CALIBRATE")) {
                throw new IllegalStateException("accelerometer missing");
            }
            return settings;
        }));
        assertEquals(command, calls.get(calls.size() - 1));
        assertFalse(calls.contains("SAVE_CONFIG"));
        ControllerShaping.Session result = new ControllerShaping().calibrate((c, t) ->
                c.equals("HELP") ? "SET_INPUT_SHAPER SHAPER_CALIBRATE" : settings);
        assertEquals(command, result.calibrated);
    }

    @Test
    void fourTranslatedRotatedViewsSeparateArtifactFromMachineError() {
        int size = 5;
        List<PlateSelfCalibration.Observation> data = new ArrayList<>();
        double[][] artifact = new double[25][2];
        for (int marker = 1; marker < 25; marker++) {
            artifact[marker][0] = .005 * Math.sin(marker);
            artifact[marker][1] = .006 * Math.cos(marker);
        }
        artifact[4] = new double[] {0, 0};
        for (int view = 0; view < 4; view++) {
            double radians = Math.toRadians(view * 90), c = Math.cos(radians), s = Math.sin(radians);
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    int marker = row * size + column;
                    double x = 100 + view * 6.3 + column * 10 * c - row * 10 * s;
                    double y = 100 + view * 3.7 + column * 10 * s + row * 10 * c;
                    double dx = .015 + .0001 * (x - 100) + .000002 * (x - 100) * (y - 100);
                    double dy = -.012 + .00013 * (y - 100) - .000001 * (x - 100) * (y - 100);
                    data.add(new PlateSelfCalibration.Observation(view, marker, x, y, view * 90,
                            dx + c * artifact[marker][0] - s * artifact[marker][1],
                            dy + s * artifact[marker][0] + c * artifact[marker][1]));
                }
            }
        }
        PlateSelfCalibration.Result result = new PlateSelfCalibration().solve(data, size, true, .1);
        assertEquals(result.parameters, result.rank);
        assertTrue(result.residualRms < 1e-8);
        for (int marker = 0; marker < 25; marker++) {
            assertArrayEquals(artifact[marker], result.markerOffsets.get(Integer.toString(marker)), 1e-7);
        }
        assertThrows(IllegalArgumentException.class, () -> new PlateSelfCalibration().solve(data, size, false, .1));
        List<PlateSelfCalibration.Observation> sameViews = new ArrayList<>();
        for (int view = 0; view < 4; view++) {
            for (int marker = 0; marker < 25; marker++) {
                sameViews.add(new PlateSelfCalibration.Observation(view, marker, 100 + marker % 5 * 10,
                        100 + marker / 5 * 10, 0, .01, -.01));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PlateSelfCalibration().solve(sameViews, size, true, .1));
    }

    @Test
    void heightAndAngularToolModelBeatsExistingRunoutAndRejectsWrongSign() throws Exception {
        ToolBackend backend = new ToolBackend();
        ToolPointModel model = new ToolPointOptimizer().calibrate(backend, "nozzle", "tip", 0, 1, .05, .2);
        assertNotNull(model);
        assertTrue(model.afterP95 < model.beforeP95 * .2);
        model.validate();
        double[] query = model.predict(.3, 72);
        assertArrayEquals(backend.plant(.3, 72), query, 1e-8);
        assertArrayEquals(model.predict(.5, 20), model.predict(200, 20), 1e-9);
        backend = new ToolBackend();
        backend.wrongSign = true;
        assertNull(new ToolPointOptimizer().calibrate(backend, "nozzle", "tip", 0, 1, .05, .2));
        assertNull(backend.model);
    }

    @Test
    void profileIntegrityAndMachineFingerprintPreventUnsafeReload() throws Exception {
        CalibrationRequest request = new CalibrationRequest();
        request.spatial = false;
        CalibrationController.Report report = new CalibrationController().run(new DigitalTwinMachine(43), request, (p, m) -> { });
        PrecisionProfile profile = new PrecisionProfile();
        profile.fingerprint = "real-fixture-fingerprint";
        profile.axisX = "X";
        profile.axisY = "Y";
        profile.createdAt = 1;
        profile.model = report.chosen;
        profile.noise = report.noise;
        profile.target = request.target;
        profile.baseline = report.baselineFinal;
        profile.corrected = report.correctedFinal;
        profile.previousBacklashX = "None";
        profile.previousBacklashY = "None";
        profile.temperatureActuator = "sensor";
        PrecisionStore store = new PrecisionStore(temporary);
        store.saveProfile(profile, true);
        assertNotNull(store.loadProfile(profile.fingerprint));
        assertTrue(store.isEnabled());
        PrecisionStore.Snapshot previous = store.snapshot();
        profile.createdAt = 2;
        store.saveProfile(profile, false);
        assertFalse(store.isEnabled());
        assertTrue(Files.isRegularFile(temporary.resolve("profile-previous.sha256")));
        store.restore(previous);
        assertTrue(store.isEnabled());
        assertEquals(1, store.loadProfile(profile.fingerprint).createdAt);
        assertThrows(IllegalArgumentException.class, () -> store.loadProfile("different-machine"));
        Files.write(temporary.resolve("profile.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> store.loadProfile(profile.fingerprint));
    }

    @Test
    void manualPlateRotationErrorIsSeparatedInsteadOfBecomingMarkerCorrection() {
        int size = 5;
        double[] errors = {0, .2, -.15, .12};
        List<PlateSelfCalibration.Observation> data = new ArrayList<>();
        double[][] artifact = new double[25][2];
        for (int marker = 1; marker < 25; marker++) {
            if (marker != 4) {
                artifact[marker][0] = .008 * Math.sin(marker);
                artifact[marker][1] = .006 * Math.cos(marker);
            }
        }
        for (int view = 0; view < 4; view++) {
            double angle = Math.toRadians(view * 90), actual = angle + Math.toRadians(errors[view]);
            double c = Math.cos(angle), s = Math.sin(angle), ac = Math.cos(actual), as = Math.sin(actual);
            for (int marker = 0; marker < 25; marker++) {
                double u = marker % size * 10, v = marker / size * 10;
                double x = 100 + view * 6.3 + u * c - v * s;
                double y = 100 + view * 3.7 + u * s + v * c;
                double dx = .015 + .0001 * (x - 100) + .000002 * (x - 100) * (y - 100);
                double dy = -.012 + .00013 * (y - 100) - .000001 * (x - 100) * (y - 100);
                data.add(new PlateSelfCalibration.Observation(view, marker, x, y, view * 90,
                        dx + (u + artifact[marker][0]) * ac - (v + artifact[marker][1]) * as - u * c + v * s,
                        dy + (u + artifact[marker][0]) * as + (v + artifact[marker][1]) * ac - u * s - v * c));
            }
        }
        PlateSelfCalibration.Result result = new PlateSelfCalibration().solve(data, size, true, .1);
        assertTrue(result.residualRms < .0001);
        for (int marker = 0; marker < 25; marker++) {
            assertArrayEquals(artifact[marker], result.markerOffsets.get(Integer.toString(marker)), .0004);
        }
        assertArrayEquals(errors, result.viewAngleCorrections, .001);
    }

    @Test
    void spatialStateMapChangesWithBothPositionAndDirection() {
        PrecisionModel model = new PrecisionModel(java.util.EnumSet.of(PrecisionModel.Layer.COMMON_BIAS,
                PrecisionModel.Layer.CONTINUOUS_REVERSAL, PrecisionModel.Layer.STATE_SPATIAL),
                new double[] {0, 100, 0, 100, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        double[] coefficients = new double[9];
        coefficients[1] = .02;
        coefficients[7] = .03;
        model.setCoefficients(coefficients, new double[9]);
        PrecisionSample origin = new PrecisionSample("a", "camera", 0, 0, 0, 0, .5, 1000,
                24, 0, 48, 48, 48, 48, 0, 1, 1, 0, 0);
        PrecisionSample corner = new PrecisionSample("b", "camera", 100, 100, 0, 0, .5, 1000,
                24, 0, 48, 48, 48, 48, 0, 1, 1, 0, 0);
        assertEquals(.02, model.predict(origin, 1, 1)[0], 1e-9);
        assertEquals(.05, model.predict(corner, 1, 1)[0], 1e-9);
        assertEquals(-.05, model.predict(corner, -1, 1)[0], 1e-9);
    }

    @Test
    void homeSeedIsEstimatedFromSeparateHomeMeasurements() {
        PrecisionModel model = new PrecisionModel(java.util.EnumSet.of(PrecisionModel.Layer.COMMON_BIAS,
                PrecisionModel.Layer.CONTINUOUS_REVERSAL), new double[] {0, 300, 0, 300, .5, 1000, 24, 0},
                96, 96, 2, 2, .2);
        model.setCoefficients(new double[] {.01, .03, .015}, new double[] {-.005, -.012, .02});
        java.util.List<HomeStateFitter.Observation> observations = new java.util.ArrayList<>();
        for (double[] delta : new double[][] {{2, 1}, {3, -2}, {-2, 3}}) {
            ReversalState a = new ReversalState(), b = new ReversalState();
            a.initialize(-.5);
            b.initialize(.75);
            a.advance(delta[0] + delta[1], 96);
            b.advance(delta[0] - delta[1], 96);
            PrecisionSample sample = new PrecisionSample("seed", "camera", delta[0], delta[1], 0, 0,
                    .5, 1000, 24, 0, delta[0] + delta[1], delta[0] - delta[1],
                    Math.abs(delta[0] + delta[1]), Math.abs(delta[0] - delta[1]), 0, 1, 1, 0, 0);
            double[] measured = model.predict(sample, a.getValue(), b.getValue());
            observations.add(new HomeStateFitter.Observation(new PrecisionSample(sample.path, sample.tool,
                    sample.x, sample.y, sample.z, sample.angle, sample.speed, sample.acceleration, sample.temperature,
                    sample.elapsed, sample.deltaA, sample.deltaB, sample.travelA, sample.travelB, sample.settleMillis,
                    sample.stateA, sample.stateB, measured[0], measured[1]), java.util.Collections.emptyList(), delta[0], delta[1]));
        }
        new HomeStateFitter().fit(model, observations);
        assertEquals(-.5, model.getHomeA(), 1e-9);
        assertEquals(.75, model.getHomeB(), 1e-9);
    }

    private BufferedImage markerImage(double x, double y, boolean dark) {
        BufferedImage image = new BufferedImage(220, 180, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(dark ? Color.WHITE : Color.BLACK);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(dark ? Color.BLACK : Color.WHITE);
        graphics.fill(new java.awt.geom.Ellipse2D.Double(x - 20, y - 20, 40, 40));
        graphics.dispose();
        return image;
    }

    private static final class ToolBackend implements ToolPointOptimizer.Backend {
        ToolPointModel model;
        boolean wrongSign;
        double[] plant(double z, double angle) {
            double c = Math.cos(Math.toRadians(angle)), s = Math.sin(Math.toRadians(angle));
            return new double[] {.012 + .017 * c + .023 * z + .009 * z * s,
                    -.01 + .014 * s - .019 * z + .006 * z * c};
        }
        @Override
        public ToolPointModel.Observation observeRaw(double z, double angle) {
            double[] value = plant(z, angle);
            return new ToolPointModel.Observation(z, angle, value[0], value[1]);
        }
        @Override
        public ToolPointModel.Observation observe(double z, double angle) {
            double[] value = plant(z, angle);
            double[] correction = model == null ? new double[] {.017 * Math.cos(Math.toRadians(angle)),
                    .014 * Math.sin(Math.toRadians(angle))} : model.predict(z, angle);
            return new ToolPointModel.Observation(z, angle,
                    value[0] - correction[0] * (wrongSign && model != null ? -1 : 1),
                    value[1] - correction[1] * (wrongSign && model != null ? -1 : 1));
        }
        @Override
        public void apply(ToolPointModel candidate) {
            model = candidate;
        }
    }
}
