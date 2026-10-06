package org.openpnp.corexy.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYCalibrationSurfaceTest {
    @Test
    void fitsIrregularGridAndRanksFullGainForStableSurface(
            @TempDir Path directory) throws Exception {
        Path pilot = directory.resolve("pilot.properties");
        Properties pilotProperties = baseProperties("completed");
        pilotProperties.setProperty("speed.count", "2");
        pilotProperties.setProperty("distance.count", "3");
        addStage(pilotProperties, "speed.1", "factor", 0.25,
                new double[] {5, 50, 150});
        addStage(pilotProperties, "speed.2", "factor", 1.0,
                new double[] {5, 50, 150});
        store(pilot, pilotProperties);

        Path refinement = directory.resolve("refinement.properties");
        Properties refinementProperties = baseProperties("completed");
        refinementProperties.setProperty("stage.count", "1");
        addStage(refinementProperties, "stage.1", "speed-factor", 0.60,
                new double[] {20, 100});
        store(refinement, refinementProperties);

        CoreXYCalibrationSurface.FitResult result =
                CoreXYCalibrationSurface.fitFiles(
                        pilot.toString(), refinement.toString());

        assertTrue(result.getRecommendedCandidateIndex() >= 0);
        assertEquals(20, result.getCandidates().size());
        assertEquals(0.05, result.getCandidates().get(0).getGain(), 1e-9);
        assertEquals(1.0, result.getCandidates().get(19).getGain(), 1e-9);
        CoreXYCalibrationSurface.Candidate recommended = result.getCandidates()
                .get(result.getRecommendedCandidateIndex());
        assertEquals(1.0, recommended.getGain(), 1e-9);
        assertTrue(recommended.getMetrics().getRmsMm()
                < result.getBaselineMetrics().getRmsMm());
        assertEquals(8, result.getAModel().getObservations().size());
        assertEquals(8, result.getBModel().getObservations().size());

        List<CoreXYCalibrationAnalysis.Observation> heldOut = new ArrayList<>();
        heldOut.add(observation("A", 0.45, 30));
        heldOut.add(observation("B", 0.45, 30));
        heldOut.add(observation("A", 0.80, 90));
        heldOut.add(observation("B", 0.80, 90));
        CoreXYCalibrationSurface.ValidationResult validation =
                CoreXYCalibrationSurface.validate(result, heldOut, 1.0);
        assertTrue(validation.isPassed());
        assertTrue(validation.getCandidateMetrics().getRmsMm()
                < validation.getBaselineMetrics().getRmsMm());
    }

    private static Properties baseProperties(String state) {
        Properties properties = new Properties();
        properties.setProperty("state", state);
        return properties;
    }

    private static void addStage(Properties properties, String prefix,
            String speedKey, double speed, double[] distances) {
        properties.setProperty(prefix + "." + speedKey, Double.toString(speed));
        properties.setProperty(prefix + ".vision-detection-floor.mm", "0.007");
        for (String direction : new String[] {"a", "b"}) {
            properties.setProperty(prefix + "." + direction
                    + ".distance.count", Integer.toString(distances.length));
            properties.setProperty(prefix + "." + direction
                    + ".step-fit.step-modeled-worst.mm", "0.05");
            for (int index = 0; index < distances.length; index++) {
                String cell = prefix + "." + direction + ".distance."
                        + (index + 1);
                double x = "a".equals(direction) ? 0.020
                        : 0.030 + 0.0005 * distances[index]
                                + 0.040 * (1.0 - speed);
                double y = "a".equals(direction) ? 0.010 : -1.2 * x;
                properties.setProperty(cell + ".virtual-distance.mm",
                        Double.toString(distances[index]));
                properties.setProperty(cell + ".correction.x.mm",
                        Double.toString(x));
                properties.setProperty(cell + ".correction.y.mm",
                        Double.toString(y));
                properties.setProperty(cell + ".scatter.mm", "0.004");
            }
        }
    }

    private static void store(Path path, Properties properties) throws Exception {
        try (OutputStream stream = Files.newOutputStream(path)) {
            properties.store(stream, "test");
        }
    }

    private static CoreXYCalibrationAnalysis.Observation observation(
            String direction, double speed, double distance) {
        double x = "A".equals(direction) ? 0.020
                : 0.030 + 0.0005 * distance + 0.040 * (1.0 - speed);
        double y = "A".equals(direction) ? 0.010 : -1.2 * x;
        return new CoreXYCalibrationAnalysis.Observation(
                direction, speed, distance, x, y, 0.004, 0.007);
    }
}
