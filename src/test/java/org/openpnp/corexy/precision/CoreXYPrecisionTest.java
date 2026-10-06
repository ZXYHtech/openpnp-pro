package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYPrecisionTest {
    @TempDir
    Path temporary;

    @Test
    void shortSegmentsAccumulateAndZeroMotionDoesNotEraseHistory() {
        ReversalState continuous = new ReversalState(), split = new ReversalState();
        continuous.advance(-20, 12);
        split.advance(-20, 12);
        continuous.advance(9, 12);
        for (int i = 0; i < 9; i++) {
            split.advance(1, 12);
        }
        assertEquals(continuous.getValue(), split.getValue(), 1e-12);
        assertEquals(9, split.getTravel(), 1e-12);
        split.advance(0, 12);
        assertEquals(9, split.getTravel(), 1e-12);
        assertEquals(.5, split.getValue(), 1e-12);
    }

    @Test
    void completeIndependentPlantPassesAndNeverEnablesSimulationOnProduction() throws Exception {
        CalibrationRequest request = new CalibrationRequest();
        request.optimizeShaping = true;
        request.calibrateTools = true;
        DigitalTwinMachine machine = new DigitalTwinMachine(42);
        CalibrationController.Report report = new CalibrationController().run(machine, request, (p, m) -> { });
        assertNotNull(report.chosen, report.outcome);
        assertTrue(report.correctedFinal.p95 < report.baselineFinal.p95 * .5);
        assertTrue(report.targetReached);
        assertFalse(report.enabled);
        assertTrue(machine.finished);
        assertTrue(report.chosen.getLayers().contains(PrecisionModel.Layer.CONTINUOUS_REVERSAL));
        assertTrue(report.chosen.getLayers().contains(PrecisionModel.Layer.JOINT_STATE));
        assertTrue(report.chosen.getLayers().contains(PrecisionModel.Layer.THERMAL_DRIFT));
        Path directory = new PrecisionStore(temporary).saveReport(report);
        String json = new String(Files.readAllBytes(directory.resolve("report.json")), StandardCharsets.UTF_8);
        assertFalse(json.contains("NaN"));
        assertTrue(Files.size(directory.resolve("training.csv")) > 10000);
        assertFalse(new PrecisionStore(temporary).isEnabled());
    }

    @Test
    void finalPhysicalCheckRejectsWrongSignEvenWhenHeldOutFitLooksGood() throws Exception {
        CalibrationRequest request = new CalibrationRequest();
        request.spatial = false;
        DigitalTwinMachine machine = new DigitalTwinMachine(41);
        machine.corruptAppliedCorrection = true;
        CalibrationController.Report report = new CalibrationController().run(machine, request, (p, m) -> { });
        assertNotNull(report.evaluation.selected);
        assertNull(report.chosen);
        assertFalse(machine.kept);
        assertTrue(machine.finished);
    }

    @Test
    void highNoiseAndCancellationRestoreOriginalState() throws Exception {
        DigitalTwinMachine noisy = new DigitalTwinMachine(1);
        noisy.imageNoise = .04;
        CalibrationController.Report noiseReport = new CalibrationController().run(noisy,
                new CalibrationRequest(), (p, m) -> { });
        assertNull(noiseReport.evaluation);
        assertTrue(noisy.finished);
        assertFalse(noisy.kept);
        DigitalTwinMachine machine = new DigitalTwinMachine(2);
        CalibrationController controller = new CalibrationController();
        assertThrows(CancellationException.class, () -> controller.run(machine, new CalibrationRequest(), (p, m) -> {
            if (p > 7) {
                controller.cancel();
            }
        }));
        assertTrue(machine.finished);
        assertFalse(machine.kept);
    }

    @Test
    void allPathsAreCheckedBeforeAcquisitionAndHoldoutsAreDisjoint() {
        CalibrationRequest request = new CalibrationRequest();
        CalibrationPlan plan = new CalibrationPlan(request);
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (CalibrationPlan.Path path : plan.training) {
            assertTrue(identities.add(path.id));
        }
        for (CalibrationPlan.Path path : plan.validation) {
            assertTrue(identities.add(path.id));
        }
        for (CalibrationPlan.Path path : plan.finalCheck) {
            assertTrue(identities.add(path.id));
        }
        request.originX = 1;
        assertThrows(IllegalArgumentException.class, () -> new DigitalTwinMachine(1)
                .prepare(request, new CalibrationPlan(request)));
    }

    @Test
    void noSystematicErrorIsNotTurnedIntoNoiseCompensation() throws Exception {
        DigitalTwinMachine machine = new DigitalTwinMachine(7);
        machine.zeroSystematicError = true;
        CalibrationRequest request = new CalibrationRequest();
        request.spatial = false;
        CalibrationController.Report report = new CalibrationController().run(machine, request, (p, m) -> { });
        assertNull(report.chosen);
        assertFalse(machine.kept);
    }

    @Test
    void fitterRejectsPathLeakageAndNonfiniteData() {
        List<PrecisionSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(sample("same-path", i));
        }
        assertThrows(IllegalArgumentException.class, () -> new PrecisionFitter()
                .evaluate(samples, samples, .001, .05, .2, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> sample("bad", Double.NaN));
    }

    @Test
    void settlingUsesMeasuredEndpointsAndRejectsUnstableReferences() throws Exception {
        int delay = new SettlingOptimizer().select(ms -> new double[] {.04 * Math.exp(-ms / 80.0), 0}, .001, .05);
        assertEquals(150, delay);
        final int[] counter = {0};
        assertThrows(IllegalStateException.class, () -> new SettlingOptimizer().select(ms ->
                new double[] {counter[0]++ % 2 == 0 ? 1 : -1, 0}, .001, .05));
    }

    private PrecisionSample sample(String path, double x) {
        return new PrecisionSample(path, "camera", x, 1, 0, 0, .5, 1000, Double.NaN,
                0, 1, 1, 1, 1, 300, 1, 1, .01, .02);
    }
}
