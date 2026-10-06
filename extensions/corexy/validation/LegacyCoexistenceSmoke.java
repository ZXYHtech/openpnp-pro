package org.openpnp.corexy.precision;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Properties;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.LegacyCompensationInterlock;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;
import org.python.util.PythonInterpreter;

/** Original supplied source/Jython versus the current kernel, using no hardware or self-attach. */
public final class LegacyCoexistenceSmoke {
    public static void main(String[] arguments) throws Exception {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(180000); Runtime.getRuntime().halt(2); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        Path fixture = java.nio.file.Paths.get(arguments[0]).resolve("run-" + java.util.UUID.randomUUID());
        Path originalJar = java.nio.file.Paths.get(arguments[1]).toAbsolutePath();
        Path originalStartup = java.nio.file.Paths.get(arguments[2]).toAbsolutePath();
        Configuration.initialize(fixture.toFile());
        Configuration configuration = Configuration.get();
        ReferenceMachine machine = new ReferenceMachine();
        configuration.setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);
        driver.setEnabled(true);
        ReferenceControllerAxis x = axis(Axis.Type.X, driver), y = axis(Axis.Type.Y, driver);
        machine.addAxis(x);
        machine.addAxis(y);
        ReferenceAdvancedMotionPlanner planner = new ReferenceAdvancedMotionPlanner();
        machine.setMotionPlanner(planner);
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        SimulatedUpCamera camera = new SimulatedUpCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        Path formal = CoreXYIntegration.legacyConfiguration(configuration);
        Properties original = CoreXYIntegration.loadProperties(formal);
        original.setProperty("enabled", "true");
        original.setProperty("dry-run", "true");
        original.setProperty("offset.a.positive.x.mm", ".04");
        CoreXYIntegration.storeProperties(formal, original);
        CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), formal.toString());
        planner.home();
        Path copiedJar = CoreXYIntegration.runtimeDirectory(configuration).resolve("lib/openpnp-corexy-agent.jar");
        Files.createDirectories(copiedJar.getParent());
        Files.copy(originalJar, copiedJar);
        // Reinstalled legacy configuration must not abort Startup.00 and skip Precision Startup.01.
        CoreXYIntegration.store(configuration).setEnabled(true);
        try (PythonInterpreter python = new PythonInterpreter()) {
            python.execfile(originalStartup.toString());
        }
        require(!CoreXYRuntime.isLegacyEnabled() && !CoreXYRuntime.isLegacyDryRun(), "Saved ownership disables legacy before Precision Startup");
        require("false".equals(CoreXYIntegration.loadProperties(formal).getProperty("enabled")), "Startup persists disable");
        CoreXYIntegration.store(configuration).setEnabled(false);
        LegacyCompensationInterlock.release(configuration);
        CoreXYIntegration.storeProperties(formal, original);
        CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), formal.toString());
        LegacyCompensationInterlock.Session session = LegacyCompensationInterlock.beginCalibration(configuration);
        System.out.println("COEXISTENCE: legacy suspended and backed up");
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {.03}, new double[] {-.02});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
        session.commit();
        System.out.println("COEXISTENCE: experimental ownership committed");
        try {
            plan(planner, camera, x, y, 100, 120);
            require(Math.abs(motions(planner).getLast().getLocation1().getCoordinate(x) - 120.03) < 1e-9,
                    "Exactly one experimental correction must apply");
            AxesLocation before = PrecisionRuntime.activeOffset(planner);
            try (PythonInterpreter python = new PythonInterpreter()) {
                System.out.println("COEXISTENCE: executing original Startup script");
                python.execfile(originalStartup.toString());
            }
            require(before.matches(PrecisionRuntime.activeOffset(planner)), "Original Startup must preserve experimental offset");
            try (OriginalLoader loader = new OriginalLoader(originalJar.toUri().toURL())) {
                Class<?> bootstrap = Class.forName("org.openpnp.corexy.agent.CoreXYScriptBootstrap", true, loader);
                require(bootstrap.getClassLoader() == loader, "Must execute original Bootstrap bytecode");
                // Register existing launcher instrumentation for the original Bootstrap's ByteBuddy lookup.
                // This fixture does not call an external JVM attach process.
                net.bytebuddy.agent.Installer.premain("", CoreXYAgent.getStartupInstrumentation());
                bootstrap.getMethod("install", String.class, String.class)
                        .invoke(null, copiedJar.toString(), formal.toString());
                bootstrap.getMethod("reload").invoke(null);
                require(before.matches(PrecisionRuntime.activeOffset(planner)), "Original install/reload must not reset advanced state");
                Path oldTrial = fixture.resolve("original-enabled-trial.properties");
                CoreXYIntegration.storeProperties(oldTrial, original);
                boolean blocked = false;
                try {
                    bootstrap.getMethod("loadImmediate", String.class).invoke(null, oldTrial.toString());
                }
                catch (InvocationTargetException expected) {
                    blocked = expected.getCause() instanceof IllegalStateException;
                }
                require(blocked, "Original transient enable must be rejected");
                require(before.matches(PrecisionRuntime.activeOffset(planner)), "Rejected original reload must preserve offset");
            }
            plan(planner, camera, x, y, 120, 121);
            require(Math.abs(motions(planner).getLast().getLocation1().getCoordinate(x) - 121.03) < 1e-9,
                    "Original script must not double-compensate or clear the retained offset");
            require(!CoreXYRuntime.isLegacyEnabled() && !CoreXYRuntime.isLegacyDryRun(), "Legacy must remain suspended");
            require("false".equals(CoreXYIntegration.loadProperties(formal).getProperty("enabled")), "Persisted legacy disabled");
            require(Files.isRegularFile(session.getBackupDirectory().resolve("formal.properties")), "Original backup retained");
            System.out.println("COEXISTENCE PASS: " + (CoreXYAgent.hasNativeHooks() ? "Pro native" : "stock external Agent")
                    + "; original supplied Startup and original Bootstrap install/reload/transient bridge; one correction, preserved offset, durable disable and backup; no hardware, no self-attach.");
        }
        finally {
            planner.unhome();
            PrecisionRuntime.deactivate();
            session.rollback();
            LegacyCompensationInterlock.release(configuration);
            machine.close();
            watchdog.interrupt();
        }
        System.exit(0);
    }

    private static ReferenceControllerAxis axis(Axis.Type type, NullDriver driver) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        axis.setLetter(type.name());
        axis.setSoftLimitLow(new Length(0, LengthUnit.Millimeters));
        axis.setSoftLimitHigh(new Length(300, LengthUnit.Millimeters));
        axis.setSoftLimitLowEnabled(true);
        axis.setSoftLimitHighEnabled(true);
        return axis;
    }

    private static void plan(AbstractMotionPlanner planner, HeadMountable camera,
            ReferenceControllerAxis x, ReferenceControllerAxis y, double start, double end) throws Exception {
        motions(planner).clear();
        Method method = AbstractMotionPlanner.class.getDeclaredMethod("createBacklashCompensatedMotion",
                HeadMountable.class, double.class, AxesLocation.class, AxesLocation.class, Motion.MotionOption[].class);
        method.setAccessible(true);
        method.invoke(planner, camera, .5, new AxesLocation(x, start).put(new AxesLocation(y, 100)),
                new AxesLocation(x, end).put(new AxesLocation(y, 100)), new Motion.MotionOption[0]);
    }

    @SuppressWarnings("unchecked") private static LinkedList<Motion> motions(AbstractMotionPlanner planner) throws Exception {
        Field field = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
        field.setAccessible(true);
        return (LinkedList<Motion>) field.get(planner);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static final class OriginalLoader extends URLClassLoader {
        OriginalLoader(URL url) { super(new URL[] {url}, LegacyCoexistenceSmoke.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.openpnp.corexy.")) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try { type = findClass(name); }
                    catch (ClassNotFoundException missing) { return super.loadClass(name, resolve); }
                }
                if (resolve) { resolveClass(type); }
                return type;
            }
            return super.loadClass(name, resolve);
        }
    }
}
