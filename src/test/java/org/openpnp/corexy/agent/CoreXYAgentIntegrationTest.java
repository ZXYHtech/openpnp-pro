package org.openpnp.corexy.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.Main;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;

import net.bytebuddy.agent.ByteBuddyAgent;

class CoreXYAgentIntegrationTest {
    private static ReferenceAdvancedMotionPlanner planner;
    private static ReferenceControllerAxis x;
    private static ReferenceControllerAxis y;
    private static Path enabledConfig;
    private static Path disabledConfig;

    @BeforeAll
    static void installAgent() throws Exception {
        Configuration.initialize();
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);

        x = axis(Axis.Type.X, driver);
        y = axis(Axis.Type.Y, driver);
        machine.addAxis(x);
        machine.addAxis(y);
        planner = new ReferenceAdvancedMotionPlanner();
        machine.setMotionPlanner(planner);

        enabledConfig = writeConfig(true, false);
        disabledConfig = writeConfig(false, false);
        if (CoreXYAgent.hasNativeHooks()) {
            CoreXYAgent.installNative(enabledConfig.toString());
        }
        else {
            Instrumentation instrumentation = ByteBuddyAgent.install();
            CoreXYAgent.install(instrumentation, enabledConfig.toString());
        }
    }

    @BeforeEach
    void resetRuntime() throws Exception {
        CoreXYRuntime.reset(planner, "integration test setup");
        CoreXYRuntime.configure(enabledConfig, Main.getBuildString());
    }

    @AfterAll
    static void disableRuntime() throws Exception {
        CoreXYRuntime.reset(planner, "integration test cleanup");
        CoreXYRuntime.configure(disabledConfig, Main.getBuildString());
    }

    @Test
    void instrumentsStockPlannerAndAddsOnlyStateTransitionMove() throws Exception {
        AxesLocation origin = location(0, 0);
        AxesLocation xPositive = location(20, 0);
        invokePlan(origin, xPositive);

        LinkedList<Motion> motions = motions();
        assertEquals(2, motions.size());
        assertEquals(20.0, motions.get(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(0.0, motions.get(0).getLocation1().getCoordinate(y), 1e-9);
        assertEquals(20.04, motions.get(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, motions.get(1).getLocation1().getCoordinate(y), 1e-9);

        invokePlan(xPositive, location(21, 0));
        motions = motions();
        assertEquals(1, motions.size());
        assertEquals(20.04, motions.get(0).getLocation0().getCoordinate(x), 1e-9);
        assertEquals(21.04, motions.get(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, motions.get(0).getLocation1().getCoordinate(y), 1e-9);
    }

    @Test
    void immediateSwitchCanDisableAnActiveOffsetButNormalReloadStillRejectsIt()
            throws Exception {
        invokePlan(location(0, 0), location(20, 0));
        assertTrue(CoreXYRuntime.status().contains("A-state=+"));
        assertTrue(CoreXYRuntime.status().contains("B-state=+"));

        assertThrows(IllegalStateException.class,
                () -> CoreXYRuntime.configure(disabledConfig, Main.getBuildString()));

        CoreXYRuntime.configureImmediate(disabledConfig, Main.getBuildString());
        invokePlan(location(20, 0), location(21, 0));
        String status = CoreXYRuntime.status();
        assertTrue(status.contains("enabled=false"));
        assertTrue(status.contains("A-state=0"));
        assertTrue(status.contains("B-state=0"));
        LinkedList<Motion> motions = motions();
        assertEquals(1, motions.size());
        assertEquals(21.0, motions.get(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(0.0, motions.get(0).getLocation1().getCoordinate(y), 1e-9);
    }

    @Test
    void bypassesCompensationThroughoutHomeAndResetsForNextMove() throws Exception {
        AxesLocation origin = location(0, 0);

        // ReferenceMachine.home() wraps AbstractMotionPlanner.home(), so the
        // outer bypass must remain active after the inner scope has exited.
        CoreXYRuntime.beginHome(planner);
        CoreXYRuntime.beginHome(planner);
        invokePlan(origin, location(100, 0));

        LinkedList<Motion> motions = motions();
        assertEquals(1, motions.size());
        assertEquals(100.0, motions.get(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(0.0, motions.get(0).getLocation1().getCoordinate(y), 1e-9);

        CoreXYRuntime.endHome(planner);
        invokePlan(origin, location(80, 0));
        motions = motions();
        assertEquals(1, motions.size());
        assertEquals(80.0, motions.get(0).getLocation1().getCoordinate(x), 1e-9);

        CoreXYRuntime.endHome(planner);
        invokePlan(origin, location(20, 0));

        motions = motions();
        assertEquals(2, motions.size());
        assertEquals(20.04, motions.get(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, motions.get(1).getLocation1().getCoordinate(y), 1e-9);
    }

    @Test
    void compatibleApiPolicyAcceptsAChangedBuildString() throws Exception {
        CoreXYRuntime.configure(enabledConfig, "different-compatible-build");

        String status = CoreXYRuntime.status();
        assertTrue(status.contains("openpnp-version=different-compatible-build"));
        assertTrue(status.contains("version-policy=compatible-api"));
        assertTrue(status.contains("configured=true"));
    }

    @Test
    void strictPolicyRejectsAChangedBuildString() throws Exception {
        Path strictConfig = writeConfig(true, false, "strict");

        assertThrows(IllegalStateException.class,
                () -> CoreXYRuntime.configure(strictConfig, "different-build"));
    }

    @Test
    void appliesDistanceSpeedSurfaceAndPreservesItForShortMoves() throws Exception {
        Path surfaceConfig = writeSurfaceConfig(true, false);
        CoreXYRuntime.configure(surfaceConfig, Main.getBuildString());

        AxesLocation origin = location(0, 0);
        AxesLocation xPositive = location(20, 0);
        invokePlan(origin, xPositive);

        LinkedList<Motion> motions = motions();
        assertEquals(2, motions.size());
        assertEquals(20.03, motions.get(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.025, motions.get(1).getLocation1().getCoordinate(y), 1e-9);
        assertTrue(CoreXYRuntime.status().contains(
                "mode=distance-speed-state-offset"));

        invokePlan(xPositive, location(21, 0));
        motions = motions();
        assertEquals(1, motions.size());
        assertEquals(20.03, motions.get(0).getLocation0().getCoordinate(x), 1e-9);
        assertEquals(21.03, motions.get(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.025, motions.get(0).getLocation1().getCoordinate(y), 1e-9);
    }

    @Test
    void interpolatesBByTargetPositionAndPreservesItsQueryOnShortMoves()
            throws Exception {
        Path spatialConfig = writeSpatialConfig(true, false);
        CoreXYRuntime.configure(spatialConfig, Main.getBuildString());

        AxesLocation origin = location(0, 0);
        invokePlan(origin, location(20, 0));

        LinkedList<Motion> motions = motions();
        assertEquals(2, motions.size());
        assertEquals(20.038, motions.get(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.015, motions.get(1).getLocation1().getCoordinate(y), 1e-9);
        assertTrue(CoreXYRuntime.status().contains(
                "mode=distance-speed-position-state-offset"));
        assertTrue(CoreXYRuntime.status().contains("spatial-B-weight=0.200000"));

        invokePlan(location(20, 0), location(21, 0));
        motions = motions();
        assertEquals(2, motions.size());
        assertEquals(21.0384, motions.get(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.0145, motions.get(1).getLocation1().getCoordinate(y), 1e-9);
    }

    @Test
    void limitsSpatialCombinedOffsetAtRuntime() throws Exception {
        Path spatialConfig = writeSpatialConfig(true, false);
        String text = Files.readString(spatialConfig, StandardCharsets.ISO_8859_1)
                .replace("safety.max-offset.mm=1\n",
                        "safety.max-offset.mm=0.065\n");
        Files.writeString(spatialConfig, text, StandardCharsets.ISO_8859_1);
        CoreXYRuntime.configure(spatialConfig, Main.getBuildString());

        invokePlan(location(0, 0), location(100, 0));

        LinkedList<Motion> motions = motions();
        assertEquals(2, motions.size());
        double offsetX = motions.get(1).getLocation1().getCoordinate(x) - 100.0;
        double offsetY = motions.get(1).getLocation1().getCoordinate(y);
        assertEquals(0.065, Math.hypot(offsetX, offsetY), 1e-9);
    }

    private static void invokePlan(AxesLocation current, AxesLocation target) throws Exception {
        motions().clear();
        Method method = AbstractMotionPlanner.class.getDeclaredMethod(
                "createBacklashCompensatedMotion",
                org.openpnp.spi.HeadMountable.class,
                double.class,
                AxesLocation.class,
                AxesLocation.class,
                Motion.MotionOption[].class);
        method.setAccessible(true);
        method.invoke(planner, null, 1.0, current, target, new Motion.MotionOption[0]);
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<Motion> motions() throws Exception {
        Field field = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
        field.setAccessible(true);
        return (LinkedList<Motion>) field.get(planner);
    }

    private static ReferenceControllerAxis axis(Axis.Type type, NullDriver driver) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        return axis;
    }

    private static AxesLocation location(double xValue, double yValue) {
        return new AxesLocation(x, xValue).put(new AxesLocation(y, yValue));
    }

    private static Path writeConfig(boolean enabled, boolean dryRun) throws Exception {
        return writeConfig(enabled, dryRun, "compatible-api");
    }

    private static Path writeConfig(boolean enabled, boolean dryRun,
            String versionPolicy) throws Exception {
        Path file = Files.createTempFile("openpnp-corexy-agent-test-", ".properties");
        String text = "schema.version=1\n"
                + "mode=state-offset\n"
                + "enabled=" + enabled + "\n"
                + "dry-run=" + dryRun + "\n"
                + "fail-closed=true\n"
                + "require-homed=false\n"
                + "openpnp.version-policy=" + versionPolicy + "\n"
                + "openpnp.implementation-version=" + Main.getBuildString() + "\n"
                + "axis.x.id=" + x.getId() + "\n"
                + "axis.y.id=" + y.getId() + "\n"
                + "threshold.a.mm=15\n"
                + "threshold.b.mm=15\n"
                + "offset.a.positive.x.mm=0.01\n"
                + "offset.a.positive.y.mm=0.02\n"
                + "offset.a.negative.x.mm=0\n"
                + "offset.a.negative.y.mm=0\n"
                + "offset.b.positive.x.mm=0.03\n"
                + "offset.b.positive.y.mm=-0.04\n"
                + "offset.b.negative.x.mm=0\n"
                + "offset.b.negative.y.mm=0\n"
                + "speed-factor=0.2\n"
                + "safety.soft-limit-margin.mm=1\n"
                + "safety.max-offset.mm=1\n";
        Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
        file.toFile().deleteOnExit();
        return file;
    }

    private static Path writeSurfaceConfig(boolean enabled, boolean dryRun)
            throws Exception {
        Path file = Files.createTempFile(
                "openpnp-corexy-agent-surface-test-", ".properties");
        StringBuilder text = new StringBuilder();
        text.append("schema.version=3\n");
        text.append("mode=distance-speed-state-offset\n");
        text.append("enabled=").append(enabled).append("\n");
        text.append("dry-run=").append(dryRun).append("\n");
        text.append("fail-closed=true\n");
        text.append("require-homed=false\n");
        text.append("openpnp.version-policy=compatible-api\n");
        text.append("openpnp.implementation-version=")
                .append(Main.getBuildString()).append("\n");
        text.append("axis.x.id=").append(x.getId()).append("\n");
        text.append("axis.y.id=").append(y.getId()).append("\n");
        text.append("threshold.a.mm=5\n");
        text.append("threshold.b.mm=5\n");
        text.append("model.kind=GAUSSIAN_KERNEL_LOG_DISTANCE_V1\n");
        text.append("model.gain=0.25\n");
        appendSurface(text, "a", "A", 0.04, 0.02);
        appendSurface(text, "b", "B", 0.08, -0.12);
        text.append("speed-factor=0.2\n");
        text.append("safety.soft-limit-margin.mm=1\n");
        text.append("safety.max-offset.mm=1\n");
        Files.write(file, text.toString().getBytes(StandardCharsets.ISO_8859_1));
        file.toFile().deleteOnExit();
        return file;
    }

    private static Path writeSpatialConfig(boolean enabled, boolean dryRun)
            throws Exception {
        Path file = Files.createTempFile(
                "openpnp-corexy-agent-spatial-test-", ".properties");
        StringBuilder text = new StringBuilder();
        text.append("schema.version=4\n");
        text.append("mode=distance-speed-position-state-offset\n");
        text.append("enabled=").append(enabled).append("\n");
        text.append("dry-run=").append(dryRun).append("\n");
        text.append("fail-closed=true\n");
        text.append("require-homed=false\n");
        text.append("openpnp.version-policy=compatible-api\n");
        text.append("openpnp.implementation-version=")
                .append(Main.getBuildString()).append("\n");
        text.append("axis.x.id=").append(x.getId()).append("\n");
        text.append("axis.y.id=").append(y.getId()).append("\n");
        text.append("threshold.a.mm=5\n");
        text.append("threshold.b.mm=5\n");
        text.append("model.kind=GAUSSIAN_KERNEL_LOG_DISTANCE_V1\n");
        text.append("model.gain=0.25\n");
        appendSurface(text, "a", "A", 0.04, 0.02);
        appendSurface(text, "b", "B", 0.08, -0.12);
        text.append("model.home-b.gain=1.0\n");
        appendSurface(text, "home-b", "B", 0.06, 0.02);
        text.append("spatial.kind=CLAMPED_ANCHOR_PROJECTION_V1\n");
        text.append("spatial.anchor.primary.x.mm=0\n");
        text.append("spatial.anchor.primary.y.mm=0\n");
        text.append("spatial.anchor.home.x.mm=100\n");
        text.append("spatial.anchor.home.y.mm=0\n");
        text.append("speed-factor=0.2\n");
        text.append("safety.soft-limit-margin.mm=1\n");
        text.append("safety.max-offset.mm=1\n");
        Files.write(file, text.toString().getBytes(StandardCharsets.ISO_8859_1));
        file.toFile().deleteOnExit();
        return file;
    }

    private static void appendSurface(StringBuilder text, String key,
            String direction, double correctionX, double correctionY) {
        String prefix = "model." + key;
        text.append(prefix).append(".direction=").append(direction).append("\n");
        text.append(prefix).append(".speed-bandwidth=0.18\n");
        text.append(prefix).append(".log-distance-bandwidth=0.09\n");
        text.append(prefix).append(".minimum-speed=0.2\n");
        text.append(prefix).append(".maximum-speed=1.0\n");
        text.append(prefix).append(".minimum-distance.mm=5\n");
        text.append(prefix).append(".maximum-distance.mm=300\n");
        text.append(prefix).append(".point.count=6\n");
        double[] speeds = {0.2, 0.2, 0.6, 0.6, 1.0, 1.0};
        double[] distances = {5, 300, 5, 300, 5, 300};
        for (int index = 0; index < speeds.length; index++) {
            String point = prefix + ".point." + (index + 1);
            text.append(point).append(".speed-factor=")
                    .append(speeds[index]).append("\n");
            text.append(point).append(".distance.mm=")
                    .append(distances[index]).append("\n");
            text.append(point).append(".correction.x.mm=")
                    .append(correctionX).append("\n");
            text.append(point).append(".correction.y.mm=")
                    .append(correctionY).append("\n");
            text.append(point).append(".scatter.mm=0.005\n");
            text.append(point).append(".detection-floor.mm=0.007\n");
        }
    }
}
