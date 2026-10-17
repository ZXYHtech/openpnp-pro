package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
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
import org.openpnp.spi.Machine;

/** Exercises the actual native machine Home hooks using only synthetic measurements and a NullDriver. */
class FixedMarkerLifecycleTest {
    @TempDir Path temporary;
    ReferenceMachine machine;
    ProbePlanner planner;
    CountingNullDriver driver;
    ParkSpyCamera camera;
    ReferenceControllerAxis x, y;
    PrecisionProfile profile;
    FakeMeasurement backend;
    final List<String> homeEvents = new ArrayList<>();

    @BeforeEach
    void fixture() throws Exception {
        FixedMarkerLifecycle.clear();
        CoreXYIntegration.releaseWorkflow();
        Configuration.initialize(temporary.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        driver = new CountingNullDriver();
        machine.addDriver(driver);
        x = axis(Axis.Type.X);
        y = axis(Axis.Type.Y);
        machine.addAxis(x);
        machine.addAxis(y);
        planner = new ProbePlanner();
        machine.setMotionPlanner(planner);
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        camera = new ParkSpyCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        CoreXYRuntime.reset(planner, "fixed lifecycle fixture");
        PrecisionRuntime.deactivate();
        CoreXYAgent.installNative(CoreXYIntegration.legacyConfiguration(Configuration.get()).toString());
        machine.setEnabled(true);
        profile = profile();
        PrecisionRuntime.activate(planner, profile.model, x.getId(), y.getId(), 0, .25, 1);
        backend = new FakeMeasurement();
        FixedMarkerLifecycle.install(Configuration.get(), profile, backend, false);
        PrecisionRuntime.setProductionGuards(() -> FixedMarkerLifecycle.assertReady(profile), null);
    }

    @AfterEach
    void cleanup() throws Exception {
        FixedMarkerLifecycle.clear();
        CoreXYIntegration.releaseWorkflow();
        if (planner != null) {
            planner.unhome();
        }
        PrecisionRuntime.deactivate();
        if (machine != null) {
            machine.close();
        }
    }

    @Test
    void firstHomeUsesLoadedCompensationBeforePublishingHomedAndChecksOnlyHome() throws Exception {
        assertFalse(machine.isHomed());
        assertTrue(PrecisionRuntime.isActive());
        assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.assertReady(profile));
        machine.home();
        assertTrue(machine.isHomed());
        assertTrue(planner.isHomed());
        assertEquals(1, driver.homes);
        assertEquals(List.of(false), backend.machineHomedAtPrepare);
        assertEquals(List.of(true), backend.plannerHomedAtPrepare);
        assertTrue(backend.compensationObserved);
        assertEquals(0, backend.fullChecks);
        assertEquals(0, backend.checkedMarkers.size());
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
        StartupVerificationReport report = reports().get(0);
        assertTrue(report.passed);
        assertEquals("home", report.kind);
        assertEquals(profile.solutionId, report.profileId);
        assertEquals(0, report.samples.size());
        assertEquals(10, report.homeSamples.size());
        assertEquals(.08, report.rawHomeX, 1e-9);
        assertEquals(-.04, report.rawHomeY, 1e-9);
        assertEquals(0, report.afterHomeResidual, 1e-9);
    }

    @Test
    void laterHomesFixNewDatumDriftWithoutRepeatingTheFullSessionCheck() throws Exception {
        machine.home();
        backend.driftX = -.03;
        backend.driftY = .02;
        machine.home();
        assertEquals(2, driver.homes);
        assertEquals(0, backend.fullChecks);
        assertEquals(2, backend.prepares);
        List<StartupVerificationReport> reports = reports();
        assertEquals(2, reports.size());
        StartupVerificationReport second = reports.stream().filter(r -> r.rawHomeX < 0).findFirst().orElseThrow();
        assertTrue(second.passed);
        assertTrue(second.samples.isEmpty());
        assertEquals(10, second.homeSamples.size());
        assertEquals(-.03, second.rawHomeX, 1e-9);
        assertEquals(reports.get(0).sessionId, reports.get(1).sessionId);
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void modelReferencedHomeUsesOpposingApproachesToResolveSmallCornerResidual() throws Exception {
        profile.modelDatumMarkerId = "M3";
        backend.driftX = -.01;
        backend.driftY = -.003;
        FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        StartupVerificationReport report = reports().get(0);
        assertTrue(report.passed);
        assertEquals(1, report.homeIterations, "Coarse Home must not chase tiny reversal deadband.");
        assertEquals(18, report.homeSamples.size(), "Five coarse frames, two four-direction rounds, and five saved Home-reference frames.");
        assertEquals(0, report.afterHomeResidual, 1e-9, "Precise model datum still converges strictly.");
        assertEquals(34, report.samples.size(), "Independent acceptance criteria remain unchanged.");
    }

    @Test
    void modelDatumAllowsConvergingSmallResidualWithoutRelaxingTolerance() throws Exception {
        profile.modelDatumMarkerId = "M3";
        StartupVerificationReport report = new StartupVerificationReport();
        FixedMarkerVerifier.Backend slowResponse = new FixedMarkerVerifier.Backend() {
            double drift = .04;
            @Override public PrecisionSample observe(CalibrationPlan.Path path) {
                return sample(path.id, path.x, path.y, .02 + drift, -.01);
            }
            @Override public void subtractDatum(double dx, double dy) { drift -= dx * .5; }
        };
        ModelReferencedDatum.align(slowResponse, profile, report, (p, message) -> {}, () -> false);
        assertEquals(20, report.homeSamples.size());
        assertEquals(.0025, report.afterHomeResidual, 1e-9);
        assertTrue(report.afterHomeResidual <= profile.noise * 3);
    }

    @Test
    void failedStartupLeavesMachineUnhomedAndKeepsRawFailureEvidence() throws Exception {
        backend.driftX = 2;
        assertThrows(IllegalStateException.class, machine::home);
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        assertTrue(PrecisionRuntime.isActive(), "The calibrated solution is retained for a manual retry.");
        assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
        assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.assertReady(profile));
        StartupVerificationReport report = reports().get(0);
        assertFalse(report.passed);
        assertEquals(0, report.samples.size());
        assertEquals("fixed-lifecycle-solution", report.profileId);
        assertEquals(1, backend.finishes);
        assertWorkflowReleased();
    }

    @Test
    void manualRetryHomesAndChecksAgainWithoutRecursingOrKeepingTheGlobalLock() throws Exception {
        backend.badResidual = true;
        assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false));
        backend.badResidual = false;
        Path saved = FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        assertTrue(Files.isRegularFile(saved.resolve("report.json")));
        assertEquals(2, driver.homes);
        assertEquals(2, backend.prepares, "Manual Home reuses the visual callback inside its caller-owned transaction.");
        assertEquals(List.of(false, false), backend.machineHomedAtPrepare);
        assertEquals(36, backend.fullChecks);
        assertTrue(machine.isHomed());
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
        assertTrue(CoreXYIntegration.store(Configuration.get()).loadVerification(saved).passed);
        assertEquals("manual", CoreXYIntegration.store(Configuration.get()).loadVerification(saved).kind);
        assertWorkflowReleased();
    }

    @Test
    void cancellationBeforeManualHomeSendsNoHomeOrMeasurementCommands() throws Exception {
        assertThrows(CancellationException.class,
                () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> true));
        assertEquals(0, driver.homes);
        assertEquals(0, backend.observations);
        assertEquals(0, backend.prepares);
        assertTrue(reports().isEmpty());
        assertFalse(machine.isHomed());
        assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.assertReady(profile));
        assertWorkflowReleased();
    }

    @Test
    void reportPersistenceFailureBlocksHomeCompletionAndProduction() throws Exception {
        Path root = CoreXYIntegration.store(Configuration.get()).getRoot();
        Files.createDirectories(root);
        Files.writeString(root.resolve("verifications"), "a file blocks the history directory");
        assertThrows(Exception.class, machine::home);
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        assertEquals(0, backend.fullChecks);
        assertEquals(1, backend.finishes);
        assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.assertReady(profile));
        assertTrue(CoreXYIntegration.getStartupStatus().contains("无法保存校验数据"));
        assertWorkflowReleased();
    }

    @Test
    void freshlyCalibratedSessionOnlyRechecksTheHomeDatum() throws Exception {
        FixedMarkerLifecycle.install(Configuration.get(), profile, backend, true);
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
        machine.home();
        assertEquals(0, backend.fullChecks);
        assertEquals(10, backend.observations);
        StartupVerificationReport report = reports().get(0);
        assertTrue(report.passed);
        assertEquals("home", report.kind);
        assertTrue(report.samples.isEmpty());
    }

    @Test
    void busyCalibrationTransactionPreservesPendingStartupCheckForTheNextHome() throws Exception {
        assertTrue(CoreXYIntegration.acquireWorkflow());
        try {
            machine.home();
            assertTrue(machine.isHomed());
            assertEquals(0, backend.prepares);
            assertTrue(reports().isEmpty());
            assertThrows(IllegalStateException.class, () -> FixedMarkerLifecycle.assertReady(profile));
        }
        finally {
            CoreXYIntegration.releaseWorkflow();
        }
        machine.home();
        assertEquals(1, backend.prepares);
        assertEquals(0, backend.fullChecks);
        assertEquals("home", reports().get(0).kind);
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void homeDrainsPendingMovesBeforeDriverHomeAndWaitsForParkBeforeReturning() throws Exception {
        enablePark();
        homeEvents.clear();
        machine.home();
        assertEquals("wait", homeEvents.get(0), "Pending moves must drain before compensation reset and G28.");
        assertTrue(homeEvents.indexOf("driverHome") > 0);
        assertTrue(homeEvents.lastIndexOf("wait") > homeEvents.indexOf("park"), "Home must not leave Park queued.");
    }

    @Test
    void modelReferencedHomeMeasuresRawThenChecksThirtyFourCompensatedPaths() throws Exception {
        profile.modelDatumMarkerId = "M3";
        FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        assertTrue(machine.isHomed());
        assertFalse(ModelReferencedDatum.isRawStage());
        assertEquals(34, backend.fullChecks);
        StartupVerificationReport result = reports().get(0);
        assertTrue(result.passed);
        assertEquals(19, result.homeSamples.size());
        assertEquals(34, result.samples.size());
        assertTrue(backend.rawObserved);
        assertTrue(backend.compensationObserved);
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void cancelledRawDatumRestoresCompensationButDoesNotPublishHome() throws Exception {
        profile.modelDatumMarkerId = "M3";
        assertThrows(CancellationException.class, () -> FixedMarkerLifecycle.verifyNow(Configuration.get(),
                (p, message) -> {}, () -> backend.observations >= 11));
        assertFalse(machine.isHomed());
        assertTrue(PrecisionRuntime.isActive());
        assertFalse(ModelReferencedDatum.isRawStage());
        assertEquals(0, backend.fullChecks);
        assertFalse(reports().get(0).passed);
        assertWorkflowReleased();
    }

    @Test
    void removedCoverageMarkersAndOldStartupFlagNeverTriggerWorkspaceMeasurements() throws Exception {
        profile.fixedMarkers.verifyOnStartup = true;
        backend.badResidual = true;
        machine.home();
        assertEquals(0, backend.fullChecks);
        assertTrue(machine.isHomed());
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void oldModelTransfersHomeReferenceOnceWithoutFullVerificationThenOnlyHomeAfterRestart() throws Exception {
        profile.modelDatumMarkerId = "M3";
        machine.home();
        assertEquals(0, backend.fullChecks, "Legacy transfer must not force a five-point verification.");
        assertTrue(backend.rawObserved, "Preserve the old model datum before recording its Home signature.");
        assertNotNull(profile.homeReference);
        profile = CoreXYIntegration.store(Configuration.get()).loadProfile(profile.fingerprint);
        assertNotNull(profile.homeReference, "The signature must survive restart.");
        planner.unhome();
        PrecisionRuntime.activate(planner, profile.model, x.getId(), y.getId(), 0, .25, 1);
        FixedMarkerLifecycle.install(Configuration.get(), profile, backend, false);
        int fullChecks = backend.fullChecks;
        backend.badResidual = true;
        backend.driftX = .03;
        backend.driftY = -.02;
        machine.home();
        assertEquals(fullChecks, backend.fullChecks);
        assertTrue(machine.isHomed());
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void failedOptionalVerificationStaysInHistoryButDoesNotForceAnotherFullCheck() throws Exception {
        backend.badResidual = true;
        assertThrows(IllegalStateException.class,
                () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false));
        int fullChecks = backend.fullChecks;
        FixedMarkerLifecycle.install(Configuration.get(), profile, backend, false);
        machine.home();
        assertTrue(machine.isHomed());
        assertEquals(fullChecks, backend.fullChecks);
        assertFalse(reports().get(0).passed, "Failed historical evidence is preserved.");
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
        backend.badResidual = false;
        FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        assertTrue(machine.isHomed());
    }

    @Test
    void oldStartupFailureDoesNotForceFullValidationAfterUpgrade() throws Exception {
        backend.badResidual = true;
        assertThrows(IllegalStateException.class,
                () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false));
        StartupVerificationReport legacy = reports().get(0);
        legacy.kind = "startup";
        CoreXYIntegration.store(Configuration.get()).saveVerification(legacy);
        int fullChecks = backend.fullChecks;
        FixedMarkerLifecycle.install(Configuration.get(), profile, backend, false);
        machine.home();
        assertTrue(machine.isHomed());
        assertEquals(fullChecks, backend.fullChecks);
        assertFalse(reports().get(0).passed, "Failed historical evidence is preserved.");
        assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
    }

    @Test
    void ordinaryHomeIsPreservedWhenNoFixedMarkerLifecycleIsInstalled() throws Exception {
        FixedMarkerLifecycle.clear();
        machine.home();
        assertTrue(machine.isHomed());
        assertTrue(planner.isHomed());
        assertEquals(0, backend.prepares);
        assertTrue(reports().isEmpty());
    }

    @Test
    void preparationFailureAlsoClosesMeasurementAndRecordsTheFailedAttempt() throws Exception {
        backend.failPreparation = true;
        assertThrows(IllegalStateException.class, machine::home);
        assertFalse(machine.isHomed());
        assertEquals(0, backend.observations);
        assertEquals(1, backend.finishes);
        StartupVerificationReport report = reports().get(0);
        assertFalse(report.passed);
        assertTrue(report.homeSamples.isEmpty());
        assertTrue(report.outcome.contains("fixture preparation failure"));
        assertWorkflowReleased();
    }

    @Test
    void startupLaterHomeAndManualCheckAllFinishVisualAlignmentBeforePark() throws Exception {
        enablePark();
        machine.home();
        assertEquals(1, camera.parks);
        assertEquals(0, backend.fullChecks);
        backend.driftX = -.03;
        backend.driftY = .02;
        machine.home();
        assertEquals(2, camera.parks);
        assertEquals(0, backend.fullChecks);
        Path manual = FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        assertEquals(3, camera.parks);
        assertEquals(18, backend.fullChecks);
        assertEquals(List.of(false, false, false), backend.machineHomedAtPrepare);
        assertEquals(List.of(1, 2, 3), camera.completedChecksAtPark);
        assertEquals("manual", CoreXYIntegration.store(Configuration.get()).loadVerification(manual).kind);
        assertWorkflowReleased();
    }

    @Test
    void rejectedManualCheckNeverParksOrPublishesAUsableHome() throws Exception {
        enablePark();
        machine.home();
        assertEquals(1, camera.parks);
        backend.badResidual = true;
        assertThrows(IllegalStateException.class,
                () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false));
        assertEquals(1, camera.parks, "A rejected manual Home cannot reach Park.");
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        assertEquals(List.of(false, false), backend.machineHomedAtPrepare);
        assertTrue(reports().stream().anyMatch(r -> r.kind.equals("manual") && !r.passed));
        assertWorkflowReleased();
    }

    @Test
    void cancellationDuringHomeDatumAcquisitionKeepsHomedFalseAndNeverParks() throws Exception {
        enablePark();
        assertThrows(CancellationException.class, () -> FixedMarkerLifecycle.verifyNow(Configuration.get(),
                (p, message) -> {}, () -> backend.observations >= 2));
        assertEquals(1, driver.homes, "Mechanical Home completed before cancellation in its visual phase.");
        assertEquals(0, camera.parks);
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        assertEquals(List.of(false), backend.machineHomedAtPrepare);
        StartupVerificationReport partial = reports().get(0);
        assertTrue(partial.cancelled);
        assertFalse(partial.passed);
        assertEquals(2, partial.homeSamples.size());
        assertTrue(partial.samples.isEmpty());
        assertWorkflowReleased();
    }

    @Test
    void cancellationDuringTheHomeFivePointCheckSavesPartialEvidenceWithoutPark() throws Exception {
        enablePark();
        assertThrows(CancellationException.class, () -> FixedMarkerLifecycle.verifyNow(Configuration.get(),
                (p, message) -> {}, () -> backend.fullChecks >= 3));
        assertEquals(0, camera.parks);
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        StartupVerificationReport partial = reports().get(0);
        assertTrue(partial.cancelled);
        assertFalse(partial.passed);
        assertEquals(10, partial.homeSamples.size());
        assertEquals(3, partial.samples.size());
        assertWorkflowReleased();
    }

    @Test
    void manualHomeCannotReuseAnEarlierPassedReportWhenItsCallbackIsMissing() throws Exception {
        machine.home();
        assertEquals(1, backend.prepares);
        driver.afterHome = FixedMarkerLifecycle::clear;
        assertThrows(IllegalStateException.class,
                () -> FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false));
        assertEquals(1, backend.prepares, "The second Home did not run a marker check.");
        assertEquals(1, reports().size(), "An earlier successful report cannot stand in for a new manual check.");
        assertFalse(machine.isHomed());
        assertFalse(planner.isHomed());
        assertWorkflowReleased();
    }

    private void enablePark() throws Exception {
        ((ReferenceHead) machine.getDefaultHead()).setParkLocation(
                new Location(LengthUnit.Millimeters, 150, 140, 0, 0));
        machine.setParkAfterHomed(true);
    }

    private void assertWorkflowReleased() {
        assertTrue(CoreXYIntegration.acquireWorkflow(), "The workflow lock must be released after every outcome.");
        CoreXYIntegration.releaseWorkflow();
    }

    private List<StartupVerificationReport> reports() throws Exception {
        PrecisionStore store = CoreXYIntegration.store(Configuration.get());
        List<StartupVerificationReport> reports = new ArrayList<>();
        for (Path directory : store.listVerificationDirectories()) {
            reports.add(store.loadVerification(directory));
            assertTrue(Files.isRegularFile(directory.resolve("markers.csv")));
            assertTrue(Files.isRegularFile(directory.resolve("home.csv")));
        }
        return reports;
    }

    private ReferenceControllerAxis axis(Axis.Type type) {
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

    private PrecisionProfile profile() {
        PrecisionProfile result = new PrecisionProfile();
        result.solutionId = "fixed-lifecycle-solution";
        result.fingerprint = "FIXED-LIFECYCLE-NULL-FIXTURE";
        result.axisX = x.getId();
        result.axisY = y.getId();
        result.createdAt = System.currentTimeMillis();
        result.noise = .001;
        result.target = .05;
        result.previousBacklashX = result.previousBacklashY = "None";
        result.fixedMarkers = new FixedMarkerSetup();
        double[][] points = {{100, 100}, {120, 100}, {120, 120}, {100, 120}, {110, 110}};
        for (int index = 0; index < points.length; index++) {
            result.fixedMarkers.markers.add(new FixedMarkerSetup.Marker("M" + index,
                    "marker " + index, points[index][0], points[index][1]));
        }
        result.model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        result.model.setCoefficients(new double[] {.02}, new double[] {-.01});
        List<PrecisionSample> baseline = new ArrayList<>(), corrected = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            baseline.add(sample("baseline-" + index, 100, 100, .1, 0));
            corrected.add(sample("corrected-" + index, 100, 100, .005, 0));
        }
        result.baseline = PrecisionMetrics.evaluate(baseline, null);
        result.corrected = PrecisionMetrics.evaluate(corrected, null);
        result.validate(result.fingerprint);
        return result;
    }

    private PrecisionSample sample(String path, double px, double py, double residualX, double residualY) {
        return new PrecisionSample(path, camera.getId(), px, py, 0, 0, .35, 1,
                Double.NaN, 0, 0, 0, 0, 0, 0, 0, 0, residualX, residualY);
    }

    private final class FakeMeasurement implements FixedMarkerLifecycle.Measurement {
        int prepares, finishes, observations, fullChecks;
        boolean badResidual, failPreparation, compensationObserved, measuring, rawObserved;
        double driftX = .08, driftY = -.04;
        final List<Boolean> machineHomedAtPrepare = new ArrayList<>();
        final List<Boolean> plannerHomedAtPrepare = new ArrayList<>();
        final HashSet<String> checkedMarkers = new HashSet<>();
        BooleanSupplier cancellation = () -> false;

        @Override public void prepare(PrecisionProfile selected) {
            prepares++;
            measuring = true;
            assertSame(profile, selected);
            machineHomedAtPrepare.add(machine.isHomed());
            plannerHomedAtPrepare.add(planner.isHomed());
            assertTrue(PrecisionRuntime.isActive());
            assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile), "Only the measurement thread can pass the pending guard.");
            if (failPreparation) {
                throw new IllegalStateException("fixture preparation failure");
            }
        }

        @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
            assertFalse(cancellation.getAsBoolean());
            observations++;
            AxesLocation planned = planner.probe(camera, path.x, path.y);
            if (ModelReferencedDatum.isRawStage()) {
                rawObserved = true;
                assertEquals(path.x, planned.getCoordinate(x), 1e-9);
                assertEquals(path.y, planned.getCoordinate(y), 1e-9);
                assertTrue(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero));
                return sample(path.id, path.x, path.y,
                        driftX + (path.id.startsWith("model-datum-") ? .02 : 0),
                        driftY + (path.id.startsWith("model-datum-") ? -.01 : 0));
            }
            assertEquals(path.x + .02, planned.getCoordinate(x), 1e-9);
            assertEquals(path.y - .01, planned.getCoordinate(y), 1e-9);
            compensationObserved = true;
            if (path.id.startsWith("verify-") || path.id.startsWith("coupled-check-")) {
                fullChecks++;
                checkedMarkers.add(path.markerId);
                return sample(path.id, path.x, path.y, badResidual ? .2 : .004, 0);
            }
            return sample(path.id, path.x, path.y, driftX, driftY);
        }

        @Override public void subtractDatum(double dx, double dy) {
            driftX -= dx;
            driftY -= dy;
        }

        @Override public void finish() {
            finishes++;
            measuring = false;
        }

        @Override public void setCancellationGuard(BooleanSupplier cancelled) {
            cancellation = cancelled;
        }
    }

    private final class ParkSpyCamera extends SimulatedUpCamera {
        int parks;
        final List<Integer> completedChecksAtPark = new ArrayList<>();

        @Override public void moveTo(Location location, double speed, Motion.MotionOption... options) throws Exception {
            homeEvents.add("park");
            Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
            assertEquals(150, millimeters.getX(), 1e-9);
            assertEquals(140, millimeters.getY(), 1e-9);
            assertTrue(machine.isHomed(), "Park follows publication of a successfully corrected Home.");
            assertFalse(backend.measuring, "Visual acquisition and cleanup must finish before Park.");
            assertTrue(backend.finishes > parks);
            assertEquals(0, backend.driftX, 1e-9);
            assertEquals(0, backend.driftY, 1e-9);
            assertDoesNotThrow(() -> FixedMarkerLifecycle.assertReady(profile));
            assertTrue(reports().stream().allMatch(r -> r.passed));
            AxesLocation planned = planner.probe(this, 150, 140);
            assertEquals(150.02, planned.getCoordinate(x), 1e-9, "Home tail Park keeps the loaded compensation.");
            assertEquals(139.99, planned.getCoordinate(y), 1e-9);
            parks++;
            completedChecksAtPark.add(backend.finishes);
        }
    }

    private final class ProbePlanner extends ReferenceAdvancedMotionPlanner {
        @Override public void waitForCompletion(HeadMountable hm, org.openpnp.spi.MotionPlanner.CompletionType type) throws Exception {
            homeEvents.add("wait");
            super.waitForCompletion(hm, type);
        }
        AxesLocation probe(HeadMountable hm, double px, double py) throws Exception {
            AxesLocation target = new AxesLocation(x, px).put(new AxesLocation(y, py));
            AxesLocation planned = createBacklashCompensatedMotion(hm, .35,
                    new AxesLocation(machine), target, new Motion.MotionOption[0]);
            // This fixture observes only planned commands and never executes them.
            motionCommands.clear();
            target.setToCoordinates();
            return planned;
        }
    }

    private final class CountingNullDriver extends NullDriver {
        int homes;
        Runnable afterHome = () -> {};
        @Override public void home(Machine machine) throws Exception {
            homeEvents.add("driverHome");
            homes++;
            super.home(machine);
            afterHome.run();
        }
    }
}
