package org.openpnp.corexy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYSurfaceModelTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void predictsNoiseWeightedCorrectionAtConfiguredGain() {
        Properties properties = surface("a", "A", 0.08, -0.12);

        CoreXYSurfaceModel model = CoreXYSurfaceModel.load(
                properties, "a", 0.25);
        CoreXYSurfaceModel.Vector predicted = model.predict(0.6, 35);

        assertEquals(0.02, predicted.x, 1e-9);
        assertEquals(-0.03, predicted.y, 1e-9);
        assertEquals(0.25 * Math.hypot(0.08, -0.12),
                model.maximumPointMagnitude(), 1e-9);
    }

    @Test
    void rejectsPointsOutsideDeclaredRange() {
        Properties properties = surface("b", "B", 0.1, -0.1);
        properties.setProperty("model.b.point.1.distance.mm", "301");

        assertThrows(IllegalArgumentException.class,
                () -> CoreXYSurfaceModel.load(properties, "b", 0.25));
    }

    @Test
    void combinedBoundPreservesCancellationBetweenMotorVectors() {
        CoreXYSurfaceModel a = CoreXYSurfaceModel.load(
                surface("a", "A", 0.02, 0.06), "a", 0.30);
        CoreXYSurfaceModel b = CoreXYSurfaceModel.load(
                surface("b", "B", 0.13, -0.175), "b", 0.30);

        assertEquals(0.30 * Math.hypot(0.13, -0.175),
                a.maximumCombinedPointMagnitude(b), 1e-9);
    }

    @Test
    void combinedBoundDetectsAlignedMotorVectors() {
        CoreXYSurfaceModel a = CoreXYSurfaceModel.load(
                surface("a", "A", 0.02, -0.06), "a", 0.30);
        CoreXYSurfaceModel b = CoreXYSurfaceModel.load(
                surface("b", "B", 0.13, -0.175), "b", 0.30);

        assertEquals(0.30 * Math.hypot(0.15, -0.235),
                a.maximumCombinedPointMagnitude(b), 1e-9);
    }

    @Test
    void surfaceConfigAcceptsCancellationButRejectsUnsafeAlignment()
            throws Exception {
        Properties safe = surfaceConfig(0.02, 0.06, 0.13, -0.175);
        Path safeFile = write(safe, "safe.properties");
        assertDoesNotThrow(() -> CoreXYConfig.load(safeFile));

        Properties unsafe = surfaceConfig(0.02, -0.06, 0.13, -0.175);
        Path unsafeFile = write(unsafe, "unsafe.properties");
        assertThrows(IllegalArgumentException.class,
                () -> CoreXYConfig.load(unsafeFile));
    }

    @Test
    void spatialSurfaceInterpolatesOnlyBetweenAnchors() {
        Properties properties = surface("b", "B", 0.02, -0.03);
        properties.putAll(surface("home-b", "B", 0.06, 0.04));
        properties.setProperty("spatial.kind",
                CoreXYSpatialSurfaceModel.KIND);
        properties.setProperty("spatial.anchor.primary.x.mm", "100");
        properties.setProperty("spatial.anchor.primary.y.mm", "50");
        properties.setProperty("spatial.anchor.home.x.mm", "300");
        properties.setProperty("spatial.anchor.home.y.mm", "150");
        properties.setProperty("model.home-b.gain", "1.0");

        CoreXYSurfaceModel primary = CoreXYSurfaceModel.load(
                properties, "b", 1.0);
        CoreXYSpatialSurfaceModel spatial =
                CoreXYSpatialSurfaceModel.load(properties, primary);

        assertVector(spatial.predict(0.6, 35, 100, 50), 0.02, -0.03);
        assertVector(spatial.predict(0.6, 35, 200, 100), 0.04, 0.005);
        assertVector(spatial.predict(0.6, 35, 400, 200), 0.06, 0.04);
        assertVector(spatial.predict(0.6, 35, 0, 0), 0.02, -0.03);
        assertEquals(0.5, spatial.weight(200, 100), 1e-9);
    }

    @Test
    void spatialSurfaceProtectsPrimaryCalibrationEnvelope() {
        Properties properties = surface("b", "B", 0.02, -0.03);
        properties.putAll(surface("home-b", "B", 0.06, 0.04));
        properties.setProperty("spatial.kind",
                CoreXYSpatialSurfaceModel.KIND);
        properties.setProperty("spatial.anchor.primary.x.mm", "0");
        properties.setProperty("spatial.anchor.primary.y.mm", "0");
        properties.setProperty("spatial.anchor.home.x.mm", "200");
        properties.setProperty("spatial.anchor.home.y.mm", "0");
        properties.setProperty("spatial.primary-protection-radius.mm", "100");
        properties.setProperty("model.home-b.gain", "1.0");

        CoreXYSurfaceModel primary = CoreXYSurfaceModel.load(
                properties, "b", 1.0);
        CoreXYSpatialSurfaceModel spatial =
                CoreXYSpatialSurfaceModel.load(properties, primary);

        assertEquals(0.0, spatial.weight(100, 0), 1e-9);
        assertEquals(0.5, spatial.weight(150, 0), 1e-9);
        assertEquals(1.0, spatial.weight(200, 0), 1e-9);
    }

    private static void assertVector(CoreXYSurfaceModel.Vector vector,
            double x, double y) {
        assertEquals(x, vector.x, 1e-9);
        assertEquals(y, vector.y, 1e-9);
    }

    private Path write(Properties properties, String name) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "test");
        }
        return path;
    }

    private static Properties surfaceConfig(double ax, double ay,
            double bx, double by) {
        Properties properties = surface("a", "A", ax, ay);
        properties.putAll(surface("b", "B", bx, by));
        properties.setProperty("schema.version", "3");
        properties.setProperty("mode", "distance-speed-state-offset");
        properties.setProperty("enabled", "true");
        properties.setProperty("dry-run", "false");
        properties.setProperty("fail-closed", "true");
        properties.setProperty("require-homed", "true");
        properties.setProperty("openpnp.version-policy", "compatible-api");
        properties.setProperty("axis.x.id", "x");
        properties.setProperty("axis.y.id", "y");
        properties.setProperty("threshold.a.mm", "5");
        properties.setProperty("threshold.b.mm", "5");
        properties.setProperty("model.kind", "GAUSSIAN_KERNEL_LOG_DISTANCE_V1");
        properties.setProperty("model.gain", "0.30");
        properties.setProperty("speed-factor", "1.0");
        properties.setProperty("safety.soft-limit-margin.mm", "1.0");
        properties.setProperty("safety.max-offset.mm", "0.075");
        return properties;
    }

    private static Properties surface(String key, String direction,
            double x, double y) {
        Properties properties = new Properties();
        String prefix = "model." + key;
        properties.setProperty(prefix + ".direction", direction);
        properties.setProperty(prefix + ".speed-bandwidth", "0.18");
        properties.setProperty(prefix + ".log-distance-bandwidth", "0.09");
        properties.setProperty(prefix + ".minimum-speed", "0.2");
        properties.setProperty(prefix + ".maximum-speed", "1.0");
        properties.setProperty(prefix + ".minimum-distance.mm", "5");
        properties.setProperty(prefix + ".maximum-distance.mm", "300");
        properties.setProperty(prefix + ".point.count", "6");
        double[] speeds = {0.2, 0.2, 0.6, 0.6, 1.0, 1.0};
        double[] distances = {5, 300, 5, 300, 5, 300};
        for (int index = 0; index < speeds.length; index++) {
            String point = prefix + ".point." + (index + 1);
            properties.setProperty(point + ".speed-factor",
                    Double.toString(speeds[index]));
            properties.setProperty(point + ".distance.mm",
                    Double.toString(distances[index]));
            properties.setProperty(point + ".correction.x.mm", Double.toString(x));
            properties.setProperty(point + ".correction.y.mm", Double.toString(y));
            properties.setProperty(point + ".scatter.mm", "0.005");
            properties.setProperty(point + ".detection-floor.mm", "0.007");
        }
        return properties;
    }
}
