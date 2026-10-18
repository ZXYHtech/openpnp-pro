package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.corexy.CoreXYAdvancedMotionPlanner;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.LegacyCompensationInterlock;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.*;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;

class LegacyCompensationInterlockTest {
    @TempDir Path temporary;
    Configuration configuration;
    ReferenceMachine machine;
    ReferenceAdvancedMotionPlanner planner;
    ReferenceControllerAxis x, y;
    SimulatedUpCamera camera;
    Path formal;
    LegacyCompensationInterlock.Session session;

    @BeforeEach void fixture() throws Exception {
        Configuration.initialize(temporary.toFile());
        configuration = Configuration.get();
        machine = new ReferenceMachine();
        configuration.setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);
        driver.setEnabled(true);
        x = axis(Axis.Type.X, driver);
        y = axis(Axis.Type.Y, driver);
        machine.addAxis(x);
        machine.addAxis(y);
        planner = new ReferenceAdvancedMotionPlanner();
        machine.setMotionPlanner(planner);
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        camera = new SimulatedUpCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        PrecisionRuntime.deactivate();
        FixedMarkerLifecycle.clear();
        formal = CoreXYIntegration.legacyConfiguration(configuration);
        CoreXYAgent.installNative(formal.toString());
        planner.home();
    }

    @AfterEach void cleanup() throws Exception {
        planner.unhome();
        PrecisionRuntime.deactivate();
        FixedMarkerLifecycle.clear();
        CoreXYIntegration.store(configuration).setEnabled(false);
        if (session != null) { session.rollback(); }
        LegacyCompensationInterlock.release(configuration);
        planner.unhome();
        CoreXYAgent.suspendLegacy(formal.toString());
        camera.close();
    }

    @Test void calibrationDisablesAgentAndDryRunAndBacksUpExactParameters() throws Exception {
        byte[] before = enableLegacy(true);
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        assertDisabled();
        Properties disabled = CoreXYIntegration.loadProperties(formal);
        assertEquals(".04", disabled.getProperty("offset.a.positive.x.mm"));
        assertEquals("retain-me", disabled.getProperty("custom.setting"));
        assertArrayEquals(before, Files.readAllBytes(session.getBackupDirectory().resolve("formal.properties")));
        assertTrue(Files.isRegularFile(session.getBackupDirectory().resolve("planner.xml")));
        Path enabled = temporary.resolve("old-transient.properties");
        Files.write(enabled, before);
        assertThrows(Exception.class, () -> CoreXYAgent.loadImmediate(enabled.toString()));
        assertDisabled();
        session.rollback();
        session = null;
        assertArrayEquals(before, Files.readAllBytes(formal));
        assertTrue(CoreXYRuntime.isLegacyDryRun());
    }

    @Test void calibrationSuspendsAndRestoresTheOriginalPlanner() throws Exception {
        CoreXYAdvancedMotionPlanner old = new CoreXYAdvancedMotionPlanner();
        machine.setMotionPlanner(old);
        planner = old;
        old.setCoreXYCompensationXAxisId(x.getId());
        old.setCoreXYCompensationYAxisId(y.getId());
        old.setCoreXYCompensationEnabled(true);
        old.home();
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        assertFalse(old.isCoreXYCompensationEnabled());
        assertThrows(IllegalStateException.class, () -> old.setCoreXYCompensationEnabled(true));
        session.rollback();
        session = null;
        assertTrue(old.isCoreXYCompensationEnabled());
    }

    @Test void rollbackRestoresAnActiveTransientProfileEvenWhenItsFileDisappears() throws Exception {
        Properties transientProperties = CoreXYIntegration.loadProperties(formal);
        transientProperties.setProperty("enabled", "true");
        transientProperties.setProperty("threshold.a.mm", "1");
        transientProperties.setProperty("threshold.b.mm", "1");
        transientProperties.setProperty("offset.a.positive.x.mm", ".07");
        Path transientPath = temporary.resolve("old-trial.properties");
        CoreXYIntegration.storeProperties(transientPath, transientProperties);
        CoreXYAgent.loadImmediate(transientPath.toString());
        byte[] original = Files.readAllBytes(formal);
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        Files.delete(transientPath);
        session.rollback();
        session = null;
        assertArrayEquals(original, Files.readAllBytes(formal));
        assertTrue(CoreXYRuntime.isLegacyEnabled());
        plan(100, 120);
        assertEquals(120.07, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
    }

    @Test void startupAutomaticallySuspendsReinstalledLegacySettingsAndLoadsTheSavedProfile() throws Exception {
        enableLegacy(false);
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        PrecisionProfile profile = profile(backend);
        CoreXYIntegration.store(configuration).saveProfile(profile, true);
        // The original Startup script installs the Agent before Precision Startup runs.
        CoreXYAgent.installNative(formal.toString());
        assertDisabled();
        CoreXYIntegration.initialize(configuration);
        assertTrue(PrecisionRuntime.isActive(), CoreXYIntegration.getStartupStatus());
        assertEquals(java.util.Collections.singletonList(PrecisionModel.Layer.COMMON_BIAS),
                PrecisionRuntime.getModel().getLayers());
        assertDisabled();
        assertTrue(CoreXYIntegration.getStartupStatus().contains("原插件补偿已自动停用"));
        // Simulate a later software launch while the old plugin's settings have been reinstalled.
        planner.unhome();
        PrecisionRuntime.deactivate();
        Properties overwritten = CoreXYIntegration.loadProperties(formal);
        overwritten.setProperty("enabled", "true");
        overwritten.setProperty("dry-run", "true");
        CoreXYIntegration.storeProperties(formal, overwritten);
        CoreXYIntegration.initialize(configuration);
        assertTrue(PrecisionRuntime.isActive(), CoreXYIntegration.getStartupStatus());
        assertDisabled();
    }

    @Test void rejectedOldReloadsPreserveTheExperimentalOffsetAndOnlyOneCorrectionIsApplied() throws Exception {
        byte[] old = enableLegacy(false);
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        CoreXYIntegration.activateProfile(configuration, profile(backend), backend);
        planner.home();
        plan(100, 120);
        assertEquals(2, motions().size());
        assertEquals(120.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        AxesLocation offset = PrecisionRuntime.activeOffset(planner);
        Path enabled = temporary.resolve("original-enabled.properties");
        Files.write(enabled, old);
        assertThrows(Exception.class, () -> CoreXYAgent.loadImmediate(enabled.toString()));
        assertThrows(Exception.class, () -> CoreXYAgent.install(null, enabled.toString()));
        assertTrue(offset.matches(PrecisionRuntime.activeOffset(planner)));
        assertTrue(PrecisionRuntime.isActive());
        // Re-executing the original Startup with the now-disabled config is harmless.
        CoreXYAgent.reload();
        assertTrue(offset.matches(PrecisionRuntime.activeOffset(planner)));
        assertTrue(CoreXYAgent.getStatus().contains("legacy-suspended=true"));
        plan(120, 121);
        assertEquals(121.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
    }

    @Test void enablingASavedProfileAlsoDisablesTheOldPlannerAndPreventsItsReenable() throws Exception {
        CoreXYAdvancedMotionPlanner old = new CoreXYAdvancedMotionPlanner();
        machine.setMotionPlanner(old);
        planner = old;
        old.setCoreXYCompensationXAxisId(x.getId());
        old.setCoreXYCompensationYAxisId(y.getId());
        old.setCoreXYCompensationEnabled(true);
        old.home();
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        CoreXYIntegration.activateProfile(configuration, profile(backend), backend);
        assertFalse(old.isCoreXYCompensationEnabled());
        assertThrows(Exception.class, () -> old.setCoreXYCompensationEnabled(true));
        plan(100, 120);
        assertEquals(2, motions().size());
        assertEquals(120.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
    }

    @Test void aBrokenSavedProfileCannotFallBackToTheOldPlugin() throws Exception {
        byte[] old = enableLegacy(false);
        CoreXYIntegration.store(configuration).setEnabled(true);
        CoreXYIntegration.initialize(configuration);
        assertFalse(PrecisionRuntime.isActive());
        assertFalse(planner.isHomed());
        Path enabled = temporary.resolve("old-enabled.properties");
        Files.write(enabled, old);
        assertThrows(Exception.class, () -> CoreXYAgent.loadImmediate(enabled.toString()));
        planner.home();
        assertThrows(Exception.class, () -> plan(100, 120));
    }

    @Test void failedLoadAllowsScopedInspectionAndRecalibrationButNeverLegacyOrProductionFallback() throws Exception {
        CoreXYIntegration.store(configuration).setEnabled(true);
        CoreXYIntegration.initialize(configuration);
        planner.home();
        assertThrows(Exception.class, () -> plan(100, 120));
        try (LegacyCompensationInterlock.RecoveryScope scope = LegacyCompensationInterlock.recoveryView(configuration)) {
            plan(100, 120);
            assertFalse(PrecisionRuntime.isActive());
            assertDisabled();
            assertThrows(Exception.class, () -> LegacyCompensationInterlock.assertLegacyLoadAllowed(true));
        }
        assertThrows(Exception.class, () -> plan(100, 120));
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        plan(100, 120);
        assertDisabled();
        session.rollback();
        session = null;
        assertThrows(Exception.class, () -> plan(100, 120));
        CoreXYIntegration.disable(configuration);
        assertFalse(CoreXYIntegration.store(configuration).isEnabled());
        assertFalse(LegacyCompensationInterlock.isExclusive());
        planner.home();
        plan(100, 120);
    }

    @Test void positionReportsDoNotChangeConfigurationIdentityButRealSettingsDo() throws Exception {
        org.openpnp.machine.reference.driver.GcodeDriver driver = new org.openpnp.machine.reference.driver.GcodeDriver();
        machine.addDriver(driver);
        x.setDriver(driver);
        y.setDriver(driver);
        driver.setReportedAxes("ok C: X:214.7200 Y:144.4100 Z:0 A:360 B:0");
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        PrecisionProfile saved = profile(backend);
        saved.fingerprintVersion = 2;
        String before = backend.fingerprint();
        driver.setReportedAxes("ok C: X:225.2400 Y:103.2500 Z:0 A:360 B:360");
        assertEquals(before, backend.fingerprint());
        backend.validateProfile(saved);
        x.setAccelerationPerSecond2(new Length(4321, LengthUnit.Millimeters));
        assertThrows(IllegalArgumentException.class, () -> backend.validateProfile(saved));
    }

    @Test void exactLegacyIdentityMigratesOnceAndSubsequentPositionReportsStayCompatible() throws Exception {
        org.openpnp.machine.reference.driver.GcodeDriver driver = new org.openpnp.machine.reference.driver.GcodeDriver();
        machine.addDriver(driver);
        x.setDriver(driver);
        y.setDriver(driver);
        driver.setReportedAxes("X:100 Y:150");
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        PrecisionProfile saved = profile(backend);
        Method legacyHash = OpenPnpCalibrationMachine.class.getDeclaredMethod("fingerprint", boolean.class);
        legacyHash.setAccessible(true);
        saved.fingerprint = (String) legacyHash.invoke(backend, true);
        PrecisionStore store = CoreXYIntegration.store(configuration);
        store.saveProfile(saved, false);
        PrecisionProfile migrated = store.loadProfile(backend);
        assertEquals(2, migrated.fingerprintVersion);
        assertEquals(backend.fingerprint(), migrated.fingerprint);
        driver.setReportedAxes("X:240 Y:21");
        assertNotNull(store.loadProfile(backend));
        assertFalse(store.isEnabled());
        assertTrue(Files.isRegularFile(store.getRoot().resolve("profile-previous.json")));
    }

    @Test void failedProfileLoadDoesNotBlockTheFirstCalibrationPositioningMove() throws Exception {
        OpenPnpCalibrationMachine backend = failedLoadBackend();
        CalibrationRequest request = recoveryMarkers().toRequest();
        Exception failure = assertThrows(Exception.class,
                () -> backend.prepare(request, new CalibrationPlan(request)));
        assertEquals("TEST_POSITIONING_REACHED", failure.getMessage());
        Field lease = OpenPnpCalibrationMachine.class.getDeclaredField("legacySession");
        lease.setAccessible(true);
        session = (LegacyCompensationInterlock.Session) lease.get(backend);
        assertNotNull(session, "Calibration owns recovery before any positioning move.");
        assertDisabled();
        session.rollback();
        session = null;
        assertThrows(Exception.class, LegacyCompensationInterlock::assertProductionAllowed);
    }

    @Test void failedProfileLoadStillAllowsMarkerInspectionAndClosesTheRecoveryScopeOnError() throws Exception {
        OpenPnpCalibrationMachine backend = failedLoadBackend();
        Exception failure = assertThrows(Exception.class, () -> backend.viewFixedMarker(recoveryMarkers(), 1));
        assertEquals("TEST_POSITIONING_REACHED", failure.getMessage());
        assertThrows(Exception.class, LegacyCompensationInterlock::assertProductionAllowed);
        assertDisabled();
    }

    private OpenPnpCalibrationMachine failedLoadBackend() throws Exception {
        CoreXYIntegration.store(configuration).setEnabled(true);
        CoreXYIntegration.initialize(configuration);
        machine.setEnabled(true); // Only the fixture's NullDriver exists; never connect real hardware.
        planner.home();
        machine.setHomed(true);
        org.openpnp.spi.Head head = camera.getHead();
        head.removeCamera(camera);
        camera.close();
        camera = new RecoveryCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        // The positioning spy throws before a driver is called. This type passes the real-backend preflight.
        org.openpnp.machine.reference.driver.GcodeDriver fake = new org.openpnp.machine.reference.driver.GcodeDriver();
        x.setDriver(fake);
        y.setDriver(fake);
        return new OpenPnpCalibrationMachine(configuration);
    }

    private FixedMarkerSetup recoveryMarkers() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        int id = 0;
        for (double[] p : new double[][] {{100, 100}, {80, 80}, {200, 80}, {80, 200}, {200, 200}}) {
            setup.markers.add(new FixedMarkerSetup.Marker("marker-" + id++, "point", p[0], p[1]));
        }
        return setup;
    }

    private static final class RecoveryCamera extends SimulatedUpCamera {
        @Override public synchronized int getWidth() { return 640; }
        @Override public synchronized int getHeight() { return 480; }
        @Override public void moveTo(org.openpnp.model.Location target, double speed, Motion.MotionOption... options) {
            LegacyCompensationInterlock.assertProductionAllowed();
            throw new IllegalStateException("TEST_POSITIONING_REACHED");
        }
    }

    @Test void aFailedBackupLeavesTheFormalConfigUntouchedAndNoCalibrationLease() throws Exception {
        byte[] old = enableLegacy(false);
        Files.write(CoreXYIntegration.runtimeDirectory(configuration).resolve("backups"), new byte[] {1});
        assertThrows(Exception.class, () -> LegacyCompensationInterlock.beginCalibration(configuration));
        assertArrayEquals(old, Files.readAllBytes(formal));
        assertFalse(LegacyCompensationInterlock.isExclusive());
        assertTrue(CoreXYRuntime.isLegacyEnabled());
    }

    @Test void aSuccessfulCalibrationKeepsLegacyDisabledAfterCommitAndUntilExplicitDisable() throws Exception {
        byte[] old = enableLegacy(false);
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        PrecisionRuntime.activate(planner, model(), x.getId(), y.getId(), 0, .25, 1);
        session.commit();
        assertDisabled();
        Path enabled = temporary.resolve("old-enabled.properties");
        Files.write(enabled, old);
        assertThrows(Exception.class, () -> CoreXYAgent.loadImmediate(enabled.toString()));
        CoreXYIntegration.disable(configuration);
        assertFalse(LegacyCompensationInterlock.isExclusive());
        assertDisabled();
        CoreXYAgent.loadImmediate(enabled.toString());
        assertTrue(CoreXYRuntime.isLegacyEnabled());
    }

    private byte[] enableLegacy(boolean dryRun) throws Exception {
        Properties properties = CoreXYIntegration.loadProperties(formal);
        properties.setProperty("enabled", "true");
        properties.setProperty("dry-run", Boolean.toString(dryRun));
        properties.setProperty("threshold.a.mm", "1");
        properties.setProperty("threshold.b.mm", "1");
        properties.setProperty("offset.a.positive.x.mm", ".04");
        properties.setProperty("custom.setting", "retain-me");
        CoreXYIntegration.storeProperties(formal, properties);
        CoreXYAgent.installNative(formal.toString());
        return Files.readAllBytes(formal);
    }

    @Test void persistenceFailureAfterCommitCanStillRestoreThePreviousLegacyScheme() throws Exception {
        byte[] original = enableLegacy(false);
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        set(backend, "started", true);
        set(backend, "originalLegacyBytes", original);
        set(backend, "originalBacklashX", ReferenceControllerAxis.BacklashCompensationMethod.None);
        set(backend, "originalBacklashY", ReferenceControllerAxis.BacklashCompensationMethod.None);
        set(backend, "originalAccelerationX", x.getAccelerationPerSecond2());
        set(backend, "originalAccelerationY", y.getAccelerationPerSecond2());
        set(backend, "legacySession", session);
        PrecisionRuntime.activate(planner, model(), x.getId(), y.getId(), 0, .25, 1);
        session.commit();
        backend.finish(false, null, 0);
        session = null;
        assertFalse(PrecisionRuntime.isActive());
        assertFalse(LegacyCompensationInterlock.isExclusive());
        assertArrayEquals(original, Files.readAllBytes(formal));
        assertTrue(CoreXYRuntime.isLegacyEnabled());
        assertFalse(backend.preparationNotes().isEmpty());
    }

    @Test void failedLegacyRestorationStopsProductionAndKeepsItsBackup() throws Exception {
        byte[] original = enableLegacy(false);
        session = LegacyCompensationInterlock.beginCalibration(configuration);
        Path backup = session.getBackupDirectory();
        Files.delete(formal);
        Files.createDirectory(formal);
        assertThrows(Exception.class, session::rollback);
        assertFalse(planner.isHomed());
        assertFalse(machine.isHomed());
        assertTrue(LegacyCompensationInterlock.isExclusive());
        assertArrayEquals(original, Files.readAllBytes(backup.resolve("formal.properties")));
        planner.home();
        assertThrows(Exception.class, () -> plan(100, 120));
        // Repair the injected filesystem fault before the fixture tears down.
        Files.delete(formal);
        Files.write(formal, original);
        session.rollback();
        session = null;
        assertTrue(CoreXYRuntime.isLegacyEnabled());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void assertDisabled() throws Exception {
        Properties properties = CoreXYIntegration.loadProperties(formal);
        assertEquals("false", properties.getProperty("enabled"));
        assertEquals("false", properties.getProperty("dry-run"));
        assertFalse(CoreXYRuntime.isLegacyEnabled());
        assertFalse(CoreXYRuntime.isLegacyDryRun());
    }

    private PrecisionProfile profile(OpenPnpCalibrationMachine backend) throws Exception {
        PrecisionProfile profile = new PrecisionProfile();
        profile.createdAt = System.currentTimeMillis();
        profile.fingerprint = backend.fingerprint();
        profile.axisX = x.getId();
        profile.axisY = y.getId();
        profile.target = .01;
        profile.noise = .0001;
        profile.previousBacklashX = profile.previousBacklashY = "None";
        profile.model = model();
        profile.baseline = metrics(.05);
        profile.corrected = metrics(.005);
        profile.validate(profile.fingerprint);
        return profile;
    }

    private PrecisionModel model() {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {.03}, new double[] {-.02});
        return model;
    }

    private PrecisionMetrics metrics(double error) {
        PrecisionSample sample = new PrecisionSample("fixture", "camera", 100, 100, 0, 0,
                .5, 1, Double.NaN, 0, 0, 0, 12, 6, 0, 1, 1, error, 0);
        return PrecisionMetrics.evaluate(java.util.Collections.nCopies(20, sample), null);
    }

    private ReferenceControllerAxis axis(Axis.Type type, NullDriver driver) {
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

    private void plan(double start, double end) throws Exception {
        motions().clear();
        Method method = AbstractMotionPlanner.class.getDeclaredMethod("createBacklashCompensatedMotion",
                HeadMountable.class, double.class, AxesLocation.class, AxesLocation.class, Motion.MotionOption[].class);
        method.setAccessible(true);
        method.invoke(planner, camera, .5, new AxesLocation(x, start).put(new AxesLocation(y, 100)),
                new AxesLocation(x, end).put(new AxesLocation(y, 100)), new Motion.MotionOption[0]);
    }

    @SuppressWarnings("unchecked") private LinkedList<Motion> motions() throws Exception {
        Field field = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
        field.setAccessible(true);
        return (LinkedList<Motion>) field.get(planner);
    }
}
