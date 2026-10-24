package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;

class CoreXYRuntimeIntegrationTest {
    @Test
    void continuousPassKeepsPrecisionOffsetWithoutAnExtraCorrectionMove() throws Exception {
        activate(.03, -.02);
        Motion nominal = new Motion(camera, location(100, 100), location(110, 100), .5,
                Motion.MotionOption.UncoordinatedMotion);
        try (var scope = new org.openpnp.model.ContinuousVisionProfile(
                java.util.Collections.singletonList(nominal), 12, path -> { })) {
            plan(100, 100, 110, 100);
            assertEquals(1, motions().size());
            assertEquals(110.03, motions().getFirst().getLocation1().getCoordinate(x), 1e-9);
            assertEquals(99.98, motions().getFirst().getLocation1().getCoordinate(y), 1e-9);
            assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        }
        assertFalse(org.openpnp.model.ContinuousVisionCompensation.isActive());
        plan(110, 100, 120, 100);
        assertEquals(120.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
    }

    @TempDir
    Path temporary;
    ReferenceMachine machine;
    FaultPlanner planner;
    ReferenceControllerAxis x, y;
    ReferenceHead head;
    SimulatedUpCamera camera;

    @BeforeEach
    void fixture() throws Exception {
        Configuration.initialize(temporary.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);
        driver.setEnabled(true);
        x = axis(Axis.Type.X, driver);
        y = axis(Axis.Type.Y, driver);
        machine.addAxis(x);
        machine.addAxis(y);
        planner = new FaultPlanner();
        machine.setMotionPlanner(planner);
        head = new ReferenceHead();
        machine.addHead(head);
        camera = new SimulatedUpCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        CoreXYRuntime.reset(planner, "fixture");
        PrecisionRuntime.deactivate();
        ToolPointRuntime.setModels(java.util.Collections.emptyMap());
        CoreXYAgent.installNative(CoreXYIntegration.legacyConfiguration(Configuration.get()).toString());
        planner.home();
    }

    @AfterEach
    void cleanup() throws Exception {
        planner.unhome();
        PrecisionRuntime.deactivate();
        ToolPointRuntime.setModels(java.util.Collections.emptyMap());
        camera.close();
    }

    @Test
    void rawDatumMovesPreservePartialBeltPreloadForTheFirstCompensatedMove() throws Exception {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS,
                PrecisionModel.Layer.CONTINUOUS_REVERSAL),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 34.5, 2, 2, .2);
        model.setCoefficients(new double[] {0, 0, .1}, new double[] {0, 0, 0});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
        Field flag = ModelReferencedDatum.class.getDeclaredField("RAW");
        flag.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Boolean> raw = (ThreadLocal<Boolean>) flag.get(null);
        raw.set(true);
        try {
            plan(100, 100, 76, 124); // B -48: fully preload negative.
            plan(76, 124, 85, 115); // B +18: retain partial reversal.
            assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        }
        finally { raw.remove(); }
        plan(85, 115, 90, 110); // B +10 must continue from the raw +18, not mechanical Home.
        assertEquals(.1 * (-1 + 2 * 28 / 34.5), PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
    }

    @Test
    void aFailedProfileCommitCannotLeaveCalibrationHistoryMarkedEnabled() throws Exception {
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(Configuration.get());
        CalibrationController.Report report = new CalibrationController.Report();
        report.request = new CalibrationRequest();
        report.fingerprint = backend.fingerprint();
        report.enabled = true;
        report.outcome = "Measured improvement accepted";
        report.chosen = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        report.chosen.setCoefficients(new double[] {.02}, new double[] {-.01});
        // A backend that never prepared has no rollback settings and cannot publish a production profile.
        assertThrows(Exception.class, () -> CoreXYIntegration.persistResult(Configuration.get(), backend, report));
        assertFalse(report.enabled);
        PrecisionStore store = CoreXYIntegration.store(Configuration.get());
        assertFalse(store.isEnabled());
        try (java.nio.file.DirectoryStream<Path> sessions = java.nio.file.Files.newDirectoryStream(store.getRoot().resolve("sessions"))) {
            Path saved = sessions.iterator().next();
            com.google.gson.JsonObject summary = new com.google.gson.JsonParser().parse(
                    java.nio.file.Files.readString(saved.resolve("report.json"))).getAsJsonObject();
            assertFalse(summary.get("enabled").getAsBoolean());
            assertTrue(summary.get("outcome").getAsString().contains("保存或启用失败"));
            assertTrue(java.nio.file.Files.isRegularFile(saved.resolve("training.csv")));
            assertTrue(java.nio.file.Files.isRegularFile(saved.resolve("final.csv")));
        }
    }

    @Test
    void backendRollbackRestoresFormalBytesAxesAndDeletesTransientConfig() throws Exception {
        Path legacy = CoreXYIntegration.legacyConfiguration(Configuration.get());
        byte[] original = java.nio.file.Files.readAllBytes(legacy);
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(Configuration.get());
        rollbackFixture(backend, original);
        Path transientFile = temporary.resolve("transient.properties");
        java.nio.file.Files.write(transientFile, original);
        set(backend, "disabledConfig", transientFile);
        x.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.DirectionalCompensation);
        java.nio.file.Files.write(legacy, "damaged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        backend.finish(false, null, 0);
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(legacy));
        assertEquals(ReferenceControllerAxis.BacklashCompensationMethod.None, x.getBacklashCompensationMethod());
        assertFalse(java.nio.file.Files.exists(transientFile));
        assertFalse(PrecisionRuntime.isActive());
    }

    @Test
    void damagedToolBackupDoesNotPreventOtherToolsCameraAndFormalConfigFromRestoring() throws Exception {
        Path legacy = CoreXYIntegration.legacyConfiguration(Configuration.get());
        byte[] original = java.nio.file.Files.readAllBytes(legacy);
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(Configuration.get());
        rollbackFixture(backend, original);
        ReferenceNozzleTip damaged = new ReferenceNozzleTip(), healthy = new ReferenceNozzleTip();
        healthy.getCalibration().setEnabled(true);
        java.io.StringWriter serialized = new java.io.StringWriter();
        Configuration.createSerializer().write(healthy.getCalibration(), serialized);
        healthy.getCalibration().setEnabled(false);
        Map<ReferenceNozzleTip, String> backups = new java.util.LinkedHashMap<>();
        backups.put(damaged, "<invalid");
        backups.put(healthy, serialized.toString());
        set(backend, "toolBackups", backups);
        SimulatedUpCamera bottom = new SimulatedUpCamera();
        Location originalOffset = new Location(LengthUnit.Millimeters, .01, -.02, 0, 0);
        set(backend, "bottom", bottom);
        set(backend, "originalBottomOffsets", originalOffset);
        set(backend, "originalBottomRotation", 3.5);
        java.nio.file.Files.write(legacy, "damaged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> backend.finish(false, null, 0));
        assertTrue(healthy.getCalibration().isEnabled());
        assertEquals(originalOffset, bottom.getHeadOffsets());
        assertEquals(3.5, bottom.getRotation(), 1e-9);
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(legacy));
        assertFalse(PrecisionRuntime.isActive());
        bottom.close();
    }

    @Test
    void profileWriteFailureRollsBackRuntimeAndPreviousEnabledState() throws Exception {
        Path legacy = CoreXYIntegration.legacyConfiguration(Configuration.get());
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(Configuration.get());
        rollbackFixture(backend, java.nio.file.Files.readAllBytes(legacy));
        CalibrationRequest request = new CalibrationRequest();
        request.spatial = false;
        CalibrationController.Report report = new CalibrationController().run(new DigitalTwinMachine(43), request, (p, m) -> { });
        report.simulation = false;
        report.enabled = true;
        set(backend, "request", request);
        PrecisionStore store = CoreXYIntegration.store(Configuration.get());
        store.setEnabled(false);
        java.nio.file.Files.createDirectory(store.getRoot().resolve("profile.json"));
        assertThrows(Exception.class, () -> CoreXYIntegration.persistResult(Configuration.get(), backend, report));
        assertFalse(report.enabled);
        assertFalse(PrecisionRuntime.isActive());
        assertFalse(store.isEnabled());
    }

    private void rollbackFixture(OpenPnpCalibrationMachine backend, byte[] original) throws Exception {
        set(backend, "started", true);
        set(backend, "originalLegacyBytes", original);
        set(backend, "originalBacklashX", ReferenceControllerAxis.BacklashCompensationMethod.None);
        set(backend, "originalBacklashY", ReferenceControllerAxis.BacklashCompensationMethod.None);
        set(backend, "originalAccelerationX", x.getAccelerationPerSecond2());
        set(backend, "originalAccelerationY", y.getAccelerationPerSecond2());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void outerHomeSurvivesUnhomeAndFirstMoveCanStartAtSoftLimit() throws Exception {
        activate(.03, 0);
        CoreXYRuntime.beginHome(planner);
        planner.unhome();
        planner.home();
        plan(0, 0, 100, 100);
        assertEquals(1, motions().size());
        assertEquals(1, PrecisionRuntime.homeTrace(planner).size());
        CoreXYRuntime.endHome(planner);
        plan(0, 0, 100, 100);
        assertEquals(2, motions().size());
        assertEquals(100.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
    }

    @Test
    void productionStandstillNeverSleepsForOrConsumesTheOneSecondMeasurementDelay() throws Exception {
        activateProduction(.03, -.02);
        PrecisionRuntime.activate(planner, PrecisionRuntime.getModel(), x.getId(), y.getId(), 1000, .25);
        plan(100, 100, 120, 100);
        assertTimeout(java.time.Duration.ofMillis(700), () -> {
            for (int wait = 0; wait < 5; wait++) {
                // Each driver completion refreshes physical coordinates before bookkeeping.
                location(120.03, 99.98).setToDriverCoordinates(x.getDriver());
                CoreXYRuntime.correctReportedCoordinates(planner);
                assertEquals(120, x.getCoordinate(), 1e-9);
                assertEquals(100, y.getCoordinate(), 1e-9);
            }
        });
        assertEquals(1000, PrecisionRuntime.getSettlingMillis(planner),
                "An explicit camera observation still receives its calibrated wait.");
        assertEquals(0, PrecisionRuntime.getSettlingMillis(planner),
                "Stationary frames do not wait another second.");
        assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
    }

    @Test
    void repeatedNativeAndAgentCompletionCannotSubtractASubResolutionOffsetAgain() throws Exception {
        x.setResolution(.0125);
        y.setResolution(.0125);
        activateProduction(.004, -.003);
        plan(100, 100, 120, 100);
        motions().clear(); // Driver is already at the endpoint; only completion callbacks follow.
        location(120.004, 99.997).setToDriverCoordinates(x.getDriver());
        location(120, 100).setToCoordinates();
        for (int repeat = 0; repeat < 2000; repeat++) {
            planner.waitForCompletion(camera, org.openpnp.spi.MotionPlanner.CompletionType.WaitForStillstand);
            assertEquals(120, x.getCoordinate(), 1e-9, "Native repeat " + repeat);
            assertEquals(100, y.getCoordinate(), 1e-9, "Native repeat " + repeat);
            CoreXYRuntime.correctReportedCoordinates(planner); // Standalone Agent path is idempotent too.
            assertEquals(120, x.getCoordinate(), 1e-9);
            assertEquals(100, y.getCoordinate(), 1e-9);
        }
    }

    @Test
    void fiveThousandMovesAndRepeatedWaitsKeepQuantizationBoundedWithoutDrift() throws Exception {
        x.setResolution(.0125);
        y.setResolution(.0125);
        activateProduction(.033, -.024);
        double cx = 100, cy = 100;
        for (int step = 0; step < 5000; step++) {
            double tx = 50 + (step * 31 % 190), ty = 50 + (step * 17 % 190);
            plan(cx, cy, tx, ty);
            AxesLocation end = motions().getLast().getLocation1();
            // Match this machine's G-code XY formatting: 0.01 mm command increments.
            double px = Math.rint(end.getCoordinate(x) * 100) / 100;
            double py = Math.rint(end.getCoordinate(y) * 100) / 100;
            location(px, py).setToDriverCoordinates(x.getDriver());
            for (int repeat = 0; repeat < 4; repeat++) {
                CoreXYRuntime.correctReportedCoordinates(planner);
                assertEquals(tx, x.getCoordinate(), .005000001);
                assertEquals(ty, y.getCoordinate(), .005000001);
            }
            cx = x.getCoordinate();
            cy = y.getCoordinate();
        }
    }

    @Test
    void conditioningWaypointsShareOneFinalMeasurementWaitAndNextMoveRearmsIt() throws Exception {
        activateProduction(.03, -.02);
        PrecisionRuntime.activate(planner, PrecisionRuntime.getModel(), x.getId(), y.getId(), 1000, .25);
        for (int step = 0; step < 3; step++) {
            plan(100 + step * 20, 100, 120 + step * 20, 100);
            location(120.03 + step * 20, 99.98).setToDriverCoordinates(x.getDriver());
            CoreXYRuntime.correctReportedCoordinates(planner);
        }
        assertEquals(1000, PrecisionRuntime.getSettlingMillis(planner));
        assertEquals(0, PrecisionRuntime.getSettlingMillis(planner));
        plan(160, 100, 170, 100);
        assertEquals(1000, PrecisionRuntime.getSettlingMillis(planner));
        planner.unhome();
        assertEquals(0, PrecisionRuntime.getSettlingMillis(planner));
    }

    @Test
    void pureZRetainsPreviousXYSpeedAndSettlingIsConsumedOnlyOnce() throws Exception {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS,
                PrecisionModel.Layer.FIXED_STATE, PrecisionModel.Layer.SPEED_ACCELERATION),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {0, .02, 0, .01, 0, 0, 0}, new double[7]);
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 150, .25, 1);
        plan(100, 100, 120, 100);
        assertEquals(.02, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        assertEquals(150, PrecisionRuntime.getSettlingMillis(planner));
        assertEquals(0, PrecisionRuntime.getSettlingMillis(planner));
        PrecisionRuntime.Plan pureZ = PrecisionRuntime.beforePlan(planner, camera, .1,
                location(120, 100), location(120, 100));
        PrecisionRuntime.afterPlan(planner, camera, .1, new Motion.MotionOption[0], pureZ.target, pureZ);
        assertEquals(.02, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        assertEquals(0, PrecisionRuntime.getSettlingMillis(planner));
    }

    @Test
    void stoppingDamagedProfileWorksWithoutAnyMachineMotion() throws Exception {
        activate(.03, 0);
        plan(100, 100, 120, 100);
        PrecisionStore store = CoreXYIntegration.store(Configuration.get());
        store.setEnabled(true);
        java.nio.file.Files.write(store.getRoot().resolve("profile.json"), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(store.getRoot().resolve("profile.sha256"), "wrong".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CoreXYIntegration.disable(Configuration.get());
        assertFalse(PrecisionRuntime.isActive());
        assertFalse(store.isEnabled());
        assertFalse(planner.isHomed());
        assertFalse(CoreXYRuntime.isLegacyEnabled());
    }

    @Test
    void productionAllowsBothEdgeNozzlesAfterTheirHeadOffsetsAreAppliedExactlyOnce() throws Exception {
        activateProduction(-.12, .02);
        ReferenceNozzle left = nozzle("N1", -10.856018607776528, -41.49334392872871);
        ReferenceNozzle right = nozzle("N2", 10.993631164249075, -41.83277061752746);
        AxesLocation leftTarget = left.toRaw(left.toHeadLocation(new Location(LengthUnit.Millimeters,
                -10.227, 244.751, 0, 0)));
        assertEquals(.629018607776528, leftTarget.getCoordinate(x), 1e-9);
        plan(left, location(100, 100), leftTarget);
        assertEquals(.509018607776528, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-.12, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9,
                "Edge correction must not be clipped or disabled.");
        // The right tool tip is outside the axis range, but its controller coordinate is inside it.
        AxesLocation rightTarget = right.toRaw(right.toHeadLocation(new Location(LengthUnit.Millimeters,
                299.6 + right.getHeadOffsets().getX(), 200, 0, 0)));
        assertEquals(299.6, rightTarget.getCoordinate(x), 1e-9);
        plan(right, leftTarget, rightTarget);
        assertEquals(299.48, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        // Pure non-XY motion at an edge must retain its correction without re-entering a hidden margin.
        PrecisionRuntime.Plan unchanged = PrecisionRuntime.beforePlan(planner, right, .5, rightTarget, rightTarget);
        assertEquals(-.12, unchanged.proposedOffset.getCoordinate(x), 1e-9);
    }

    @Test
    void productionRejectsTrueLowAndHighCrossingsWithToolAxisAndAmountBeforeEnqueue() throws Exception {
        ReferenceNozzle nozzle = nozzle("N2", 11, -42);
        for (double sign : new double[] {-1, 1}) {
            activateProduction(sign * .12, 0);
            double endpoint = sign < 0 ? .05 : 299.95;
            java.lang.reflect.InvocationTargetException rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> plan(nozzle, location(100, 100), location(endpoint, 100)));
            assertTrue(rejected.getCause().getMessage().contains("N2"));
            assertTrue(rejected.getCause().getMessage().contains("补偿终点"));
            assertTrue(rejected.getCause().getMessage().contains("0.07000 mm"));
            assertEquals(0, motions().size());
            assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        }
    }

    @Test
    void productionUsesNativeAxisResolutionAtTheBoundary() throws Exception {
        x.setResolution(.0125);
        activateProduction(-.001, 0);
        assertTrue(planner.isValidLocation(camera, location(-.001, 100)));
        plan(100, 100, 0, 100);
        assertTrue(x.coordinatesMatch(new Length(-.001, LengthUnit.Millimeters),
                motions().getLast().getLocation1().getLengthCoordinate(x)),
                "Native planning may omit a sub-resolution correction, but must not falsely reject the boundary.");
        assertEquals(-.001, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        assertFalse(planner.isValidLocation(camera, location(-.02, 100)));
    }

    @Test
    void previousOffsetSegmentCannotCrossLimitEvenWhenFinalCorrectionWouldBeInside() throws Exception {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.FIXED_STATE),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        // Directional layer has one feature per motor, reversing from positive to negative flips X correction.
        model.setCoefficients(new double[] {.1, 0}, new double[] {0, 0});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25);
        plan(200, 100, 250, 100);
        assertEquals(.1, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        // A target near the upper limit while moving in negative A makes the final correction inward.
        java.lang.reflect.InvocationTargetException rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> plan(250, 200, 299.95, 100));
        assertTrue(rejected.getCause().getMessage().contains("主运动终点"));
        assertEquals(0, motions().size());
        assertEquals(.1, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
    }

    private ReferenceNozzle nozzle(String name, double offsetX, double offsetY) throws Exception {
        ReferenceNozzle nozzle = new ReferenceNozzle();
        nozzle.setName(name);
        nozzle.setAxisX(x);
        nozzle.setAxisY(y);
        nozzle.setHeadOffsets(new Location(LengthUnit.Millimeters, offsetX, offsetY, 0, 0));
        head.addNozzle(nozzle);
        return nozzle;
    }

    private void activateProduction(double dx, double dy) {
        activate(dx, dy);
        PrecisionRuntime.activate(planner, PrecisionRuntime.getModel(), x.getId(), y.getId(), 0, .25);
    }

    @Test
    void nativePlannerKeepsLogicalCoordinatesAndDoesNotAccumulateOffsets() throws Exception {
        activate(.03, -.02);
        plan(100, 100, 120, 100);
        assertEquals(2, motions().size());
        assertEquals(120.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        assertEquals(99.98, motions().getLast().getLocation1().getCoordinate(y), 1e-9);
        plan(120, 100, 121, 100);
        assertEquals(1, motions().size());
        assertEquals(120.03, motions().getFirst().getLocation0().getCoordinate(x), 1e-9);
        assertEquals(121.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        assertThrows(IllegalStateException.class, PrecisionRuntime::deactivate);
        for (int i = 0; i < 3; i++) {
            location(121.03, 99.98).setToDriverCoordinates(x.getDriver());
            CoreXYRuntime.correctReportedCoordinates(planner);
            assertEquals(121, x.getCoordinate(), 1e-9);
            assertEquals(100, y.getCoordinate(), 1e-9);
        }
        assertEquals(130.03, CoreXYRuntime.adjustGlobalOffsets(planner, location(130, 100)).getCoordinate(x), 1e-9);
    }

    @Test
    void homingBypassesEveryNestedScopeAndUnhomeClearsTheOffset() throws Exception {
        activate(.03, 0);
        CoreXYRuntime.beginHome(planner);
        CoreXYRuntime.beginHome(planner);
        plan(100, 100, 120, 100);
        assertEquals(1, motions().size());
        CoreXYRuntime.endHome(planner);
        plan(100, 100, 120, 100);
        assertEquals(1, motions().size());
        CoreXYRuntime.endHome(planner);
        plan(100, 100, 120, 100);
        assertEquals(2, motions().size());
        planner.unhome();
        assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        plan(100, 100, 120, 100);
        assertEquals(1, motions().size());
    }

    @Test
    void visualHomeAppliesLoadedProfileBeforeCompletionAndPreservesOffsetThroughPark() throws Exception {
        activate(.03, -.02);
        CoreXYRuntime.beginHome(planner);
        planner.unhome();
        planner.home();
        plan(0, 0, 100, 100);
        assertEquals(1, motions().size(), "Mechanical Home stays uncompensated.");
        assertEquals(1, PrecisionRuntime.homeTrace(planner).size());

        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            plan(100, 100, 120, 100);
            assertEquals(2, motions().size());
            assertEquals(120.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
            assertEquals(99.98, motions().getLast().getLocation1().getCoordinate(y), 1e-9);
            scope.complete();
        }
        // ReferenceMachine can Park after setHomed(true), before its outer Home exits.
        plan(120, 100, 130, 100);
        assertEquals(1, motions().size());
        assertEquals(130.03, motions().getFirst().getLocation1().getCoordinate(x), 1e-9);
        assertEquals(1, PrecisionRuntime.homeTrace(planner).size(), "Visual and Park moves are not mechanical Home trace.");
        CoreXYRuntime.endHome(planner);
        assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        assertEquals(-.02, PrecisionRuntime.activeOffset(planner).getCoordinate(y), 1e-9);
        assertEquals(130.03, CoreXYRuntime.adjustGlobalOffsets(planner, location(130, 100)).getCoordinate(x), 1e-9);
        plan(130, 100, 140, 100);
        assertEquals(1, motions().size(), "No second correction step after a completed visual Home.");
        assertEquals(140.03, motions().getFirst().getLocation1().getCoordinate(x), 1e-9);
    }

    @Test
    void failedOrCancelledVisualHomeClearsOffsetAndLeavesPlannerUnhomed() throws Exception {
        activate(.03, 0);
        CoreXYRuntime.beginHome(planner);
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            plan(100, 100, 120, 100);
            assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
            // No complete(): cancellation or an exception during marker acquisition.
        }
        assertFalse(planner.isHomed());
        assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        CoreXYRuntime.endHome(planner);
        plan(100, 100, 120, 100);
        assertEquals(1, motions().size());
    }

    @Test
    void visualHomeRequiresFinalMechanicalPhaseAndNextHomeClearsItsState() throws Exception {
        activate(.03, 0);
        assertThrows(IllegalStateException.class, () -> PrecisionRuntime.compensatedHoming(planner));
        CoreXYRuntime.beginHome(planner);
        CoreXYRuntime.beginHome(planner);
        assertThrows(IllegalStateException.class, () -> PrecisionRuntime.compensatedHoming(planner));
        CoreXYRuntime.endHome(planner);
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            assertThrows(IllegalStateException.class, () -> PrecisionRuntime.compensatedHoming(planner));
            assertThrows(IllegalStateException.class, () -> CoreXYRuntime.beginHome(planner));
            plan(100, 100, 120, 100);
            scope.complete();
        }
        CoreXYRuntime.endHome(planner);
        assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        CoreXYRuntime.beginHome(planner);
        assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        plan(100, 100, 120, 100);
        assertEquals(1, motions().size());
        CoreXYRuntime.endHome(planner);
    }

    @Test
    void visualHomeOriginAllowsInwardCorrectionButProductionKeepsMargin() throws Exception {
        activate(.03, .02);
        CoreXYRuntime.beginHome(planner);
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            plan(100, 100, 0, 0);
            assertEquals(2, motions().size());
            assertEquals(.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
            assertEquals(.02, motions().getLast().getLocation1().getCoordinate(y), 1e-9);
            scope.complete();
        }
        CoreXYRuntime.endHome(planner);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(0, 0, 0, 0));
        assertEquals(0, motions().size(), "The production margin is restored after visual Home.");
    }

    @Test
    void visualHomeOriginStillRejectsCorrectionOutsideActualAxisLimits() throws Exception {
        activate(-.03, .02);
        CoreXYRuntime.beginHome(planner);
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(100, 100, 0, 0));
            assertEquals(0, motions().size());
            assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        }
        CoreXYRuntime.endHome(planner);
        assertFalse(planner.isHomed());
    }

    @Test
    void candidateReferenceAlignmentRetainsOffsetAndRestoresProductionMargin() throws Exception {
        activate(.03, .02);
        plan(100, 100, 120, 100);
        assertEquals(2, motions().size());
        try (PrecisionRuntime.ReferenceAlignmentScope scope = PrecisionRuntime.referenceAlignment(planner)) {
            assertThrows(IllegalStateException.class, () -> PrecisionRuntime.referenceAlignment(planner));
            plan(120, 100, 130, 100);
            assertEquals(1, motions().size(), "Entering reference alignment must retain the existing offset.");
            plan(130, 100, 0, 0);
            assertEquals(1, motions().size());
            assertEquals(.03, motions().getLast().getLocation1().getCoordinate(x), 1e-9);
        }
        assertTrue(planner.isHomed());
        assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(0, 0, 0, 0));
        CoreXYRuntime.beginHome(planner);
        assertThrows(IllegalStateException.class, () -> PrecisionRuntime.referenceAlignment(planner));
        CoreXYRuntime.endHome(planner);
        planner.unhome();
        assertThrows(IllegalStateException.class, () -> PrecisionRuntime.referenceAlignment(planner));
    }

    @Test
    void completedVisualHomeKeepsPartialBeltPreloadForTheNextShortReversal() throws Exception {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.CONTINUOUS_REVERSAL),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {.02, .03}, new double[] {0, 0});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
        CoreXYRuntime.beginHome(planner);
        plan(100, 100, 120, 100);
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            plan(120, 100, 119, 100);
            assertEquals(.02 * (5.0 / 6) + .03 * (2.0 / 3),
                    PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
            scope.complete();
        }
        CoreXYRuntime.endHome(planner);
        plan(119, 100, 118, 100);
        assertEquals(.02 * (2.0 / 3) + .03 * (1.0 / 3),
                PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9,
                "Home completion must keep the visual stage's partial A/B preload instead of replaying it again.");
    }

    @Test
    void rejectedMotionRollsBackQueueAndDoesNotCommitReversalOrOffset() throws Exception {
        activate(.03, 0);
        planner.rejectCorrection = true;
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(100, 100, 120, 100));
        assertEquals(0, motions().size());
        assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        planner.rejectCorrection = false;
        plan(100, 100, 120, 100);
        assertEquals(2, motions().size());
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(120, 100, 299, 100));
        assertEquals(0, motions().size());
        assertEquals(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
    }

    @Test
    void nativeBacklashCannotBeStackedAndChangedProductionConfigBlocksMotion() throws Exception {
        x.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.DirectionalCompensation);
        assertThrows(IllegalStateException.class, () -> activate(.03, 0));
        x.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.None);
        activate(.03, 0);
        PrecisionRuntime.setProductionGuards(() -> { throw new IllegalStateException("changed calibration"); }, null);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(100, 100, 120, 100));
        assertEquals(0, motions().size());
    }

    @Test
    void temperatureModelsReadActualTelemetryAndFailOnMissingOrInvalidData() throws Exception {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS,
                PrecisionModel.Layer.THERMAL_DRIFT), new double[] {0, 300, 0, 300, .5, 1000, 24, 0},
                12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {.01, .002}, new double[] {0, 0});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
        PrecisionRuntime.setTemperatureSource(() -> Double.NaN);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> plan(100, 100, 120, 100));
        PrecisionRuntime.setTemperatureSource(() -> 26);
        plan(100, 100, 120, 100);
        assertEquals(.014, PrecisionRuntime.activeOffset(planner).getCoordinate(x), 1e-9);
    }

    @Test
    void nozzleTransformsApplyFullToolModelOnceAndInvertItExactly() throws Exception {
        ReferenceNozzle nozzle = new ReferenceNozzle();
        nozzle.setAxisX(x);
        nozzle.setAxisY(y);
        head.addNozzle(nozzle);
        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        nozzle.setNozzleTip(tip);
        ReferenceNozzleTipCalibration calibration = tip.getCalibration();
        calibration.setEnabled(true);
        Field lookup = ReferenceNozzleTipCalibration.class.getDeclaredField("runoutCompensationLookup");
        lookup.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ReferenceNozzleTipCalibration.RunoutCompensation> values =
                (Map<String, ReferenceNozzleTipCalibration.RunoutCompensation>) lookup.get(calibration);
        values.put(nozzle.getId(), new ReferenceNozzleTipCalibration.RunoutCompensation() {
            public Location getOffset(double angle) {
                return new Location(LengthUnit.Millimeters, .1, -.07, 0, 0);
            }
            public Location getCameraOffset() {
                return new Location(LengthUnit.Millimeters, .08, 0, 0, 0);
            }
            public Location getAxisOffset() {
                return new Location(LengthUnit.Millimeters);
            }
        });
        ToolPointModel model = new ToolPointModel();
        model.nozzleId = nozzle.getId();
        model.tipId = tip.getId();
        model.minZ = -1;
        model.maxZ = 1;
        model.maximumOffset = .2;
        model.beforeP95 = .1;
        model.afterP95 = .01;
        model.x = new double[] {.02, .01, 0, .02, 0, 0};
        model.y = new double[] {-.01, 0, .01, -.02, 0, 0};
        model.replacesNative = true;
        ToolPointRuntime.setModels(java.util.Collections.singletonMap(nozzle.getId(), model));
        Location target = new Location(LengthUnit.Millimeters, 100, 110, .5, 90);
        Location transformed = nozzle.toHeadLocation(target, target);
        assertEquals(99.97, transformed.getX(), 1e-9);
        assertEquals(110.01, transformed.getY(), 1e-9);
        Location inverse = nozzle.toHeadMountableLocation(transformed, target);
        assertEquals(target.getX(), inverse.getX(), 1e-9);
        assertEquals(target.getY(), inverse.getY(), 1e-9);
        nozzle.setNozzleTip(new ReferenceNozzleTip());
        assertEquals(0, ToolPointRuntime.correction(nozzle, target).getX(), 1e-9);
    }

    private void activate(double dx, double dy) {
        PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        model.setCoefficients(new double[] {dx}, new double[] {dy});
        PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
    }
    private void plan(double cx, double cy, double tx, double ty) throws Exception {
        plan(camera, location(cx, cy), location(tx, ty));
    }
    private void plan(HeadMountable tool, AxesLocation current, AxesLocation target) throws Exception {
        motions().clear();
        Method method = AbstractMotionPlanner.class.getDeclaredMethod("createBacklashCompensatedMotion",
                HeadMountable.class, double.class, AxesLocation.class, AxesLocation.class, Motion.MotionOption[].class);
        method.setAccessible(true);
        method.invoke(planner, tool, .5, current, target, new Motion.MotionOption[0]);
    }
    @SuppressWarnings("unchecked")
    private LinkedList<Motion> motions() throws Exception {
        Field field = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
        field.setAccessible(true);
        return (LinkedList<Motion>) field.get(planner);
    }
    private AxesLocation location(double px, double py) {
        return new AxesLocation(x, px).put(new AxesLocation(y, py));
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
        axis.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.None);
        return axis;
    }
    private static final class FaultPlanner extends ReferenceAdvancedMotionPlanner {
        boolean rejectCorrection;
        @Override
        protected Motion addMotion(HeadMountable hm, double speed, AxesLocation current, AxesLocation target, int options) {
            if (rejectCorrection && speed == .25) {
                throw new IllegalStateException("Injected failure before enqueue");
            }
            return super.addMotion(hm, speed, current, target, options);
        }
    }
}
