package org.openpnp.corexy.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYCalibrationAnalysisTest {
    private static final double FLOOR = 0.007;

    @Test
    void selectsNoCompensationForFloorLimitedMeasurements() {
        List<CoreXYCalibrationAnalysis.Observation> values = new ArrayList<>();
        for (String direction : new String[] {"A", "B"}) {
            values.add(observation(direction, 0.2, 10, 0.005, 0, 0.003));
            values.add(observation(direction, 0.2, 100, 0.006, 0, 0.003));
            values.add(observation(direction, 1.0, 10, 0.004, 0, 0.003));
            values.add(observation(direction, 1.0, 100, 0.005, 0, 0.003));
        }

        CoreXYCalibrationAnalysis.Analysis analysis =
                CoreXYCalibrationAnalysis.analyze(values, 0.01);

        assertEquals(CoreXYCalibrationAnalysis.ModelKind.NONE,
                analysis.getRecommendedModel());
        assertTrue(analysis.getRefinementPoints().isEmpty());
        assertFalse(analysis.isStepModelRejected());
    }

    @Test
    void selectsTwoDimensionalLookupAndRefinesMeasuredTransitions() {
        List<CoreXYCalibrationAnalysis.Observation> values = new ArrayList<>();
        for (double speed : new double[] {0.2, 1.0}) {
            values.add(observation("A", speed, 10, 0.005, 0, 0.003));
            values.add(observation("A", speed, 100, 0.006, 0, 0.003));
        }
        values.add(observation("B", 0.2, 10, 0.01, -0.01, 0.004));
        values.add(observation("B", 0.2, 100, 0.14, -0.16, 0.005));
        values.add(observation("B", 1.0, 10, 0.08, -0.09, 0.004));
        values.add(observation("B", 1.0, 100, 0.04, -0.05, 0.005));

        CoreXYCalibrationAnalysis.Analysis analysis =
                CoreXYCalibrationAnalysis.analyze(values, 0.06);

        assertEquals(CoreXYCalibrationAnalysis.ModelKind.DISTANCE_SPEED_LOOKUP,
                analysis.getRecommendedModel());
        assertTrue(analysis.isStepModelRejected());
        assertTrue(analysis.getRefinementPoints().stream().anyMatch(
                point -> "distance-midpoint".equals(point.getKind())));
        assertTrue(analysis.getRefinementPoints().stream().anyMatch(
                point -> "speed-midpoint".equals(point.getKind())));
    }

    @Test
    void requestsRepeatWhenScatterDominatesVisionFloor() {
        List<CoreXYCalibrationAnalysis.Observation> values = new ArrayList<>();
        for (String direction : new String[] {"A", "B"}) {
            values.add(observation(direction, 0.5, 20, 0.08, -0.08, 0.025));
            values.add(observation(direction, 0.5, 80, 0.08, -0.08, 0.005));
        }

        CoreXYCalibrationAnalysis.Analysis analysis =
                CoreXYCalibrationAnalysis.analyze(values, 0.02);

        assertTrue(analysis.getRefinementPoints().stream().anyMatch(
                point -> "repeat".equals(point.getKind())
                        && Math.abs(point.getDistanceMm() - 20) < 1e-9));
    }

    @Test
    void mergesPilotAndRefinementWithoutReplacingRepeatedCells(
            @TempDir Path directory) throws Exception {
        Path pilot = directory.resolve("pilot.properties");
        Properties pilotProperties = new Properties();
        pilotProperties.setProperty("state", "completed");
        pilotProperties.setProperty("speed.count", "1");
        pilotProperties.setProperty("distance.count", "2");
        addStage(pilotProperties, "speed.1", "factor", 0.5,
                new double[] {10, 100},
                new double[][] {{0.01, 0}, {0.01, 0}},
                new double[][] {{0.02, -0.02}, {0.14, -0.16}});
        store(pilot, pilotProperties);

        Path refinement = directory.resolve("refinement.properties");
        Properties refinementProperties = new Properties();
        refinementProperties.setProperty("state", "completed");
        refinementProperties.setProperty("stage.count", "1");
        addStage(refinementProperties, "stage.1", "speed-factor", 0.5,
                new double[] {100},
                new double[][] {{0.01, 0}},
                new double[][] {{0.10, -0.12}});
        store(refinement, refinementProperties);

        CoreXYCalibrationAnalysis.Analysis analysis =
                CoreXYCalibrationAnalysis.analyzeFiles(
                        pilot.toString(), refinement.toString());

        assertEquals(CoreXYCalibrationAnalysis.ModelKind.DISTANCE_LOOKUP,
                analysis.getRecommendedModel());
        assertEquals(Math.hypot(0.12, -0.14),
                analysis.getBSummary().getMaximumMagnitudeMm(), 1e-9);
        assertTrue(analysis.getBSummary().getMaximumScatterMm() >=
                Math.hypot(0.02, -0.02));
    }

    private static CoreXYCalibrationAnalysis.Observation observation(
            String direction, double speed, double distance,
            double x, double y, double scatter) {
        return new CoreXYCalibrationAnalysis.Observation(
                direction, speed, distance, x, y, scatter, FLOOR);
    }

    private static void addStage(Properties properties, String prefix,
            String speedKey, double speed, double[] distances,
            double[][] a, double[][] b) {
        properties.setProperty(prefix + "." + speedKey, Double.toString(speed));
        properties.setProperty(prefix + ".vision-detection-floor.mm", "0.007");
        for (String direction : new String[] {"a", "b"}) {
            double[][] values = "a".equals(direction) ? a : b;
            properties.setProperty(prefix + "." + direction
                    + ".distance.count", Integer.toString(distances.length));
            properties.setProperty(prefix + "." + direction
                    + ".step-fit.step-modeled-worst.mm", "0.05");
            for (int index = 0; index < distances.length; index++) {
                String cell = prefix + "." + direction + ".distance."
                        + (index + 1);
                properties.setProperty(cell + ".virtual-distance.mm",
                        Double.toString(distances[index]));
                properties.setProperty(cell + ".correction.x.mm",
                        Double.toString(values[index][0]));
                properties.setProperty(cell + ".correction.y.mm",
                        Double.toString(values[index][1]));
                properties.setProperty(cell + ".scatter.mm", "0.005");
            }
        }
    }

    private static void store(Path path, Properties properties) throws Exception {
        try (OutputStream stream = Files.newOutputStream(path)) {
            properties.store(stream, "test");
        }
    }
}
