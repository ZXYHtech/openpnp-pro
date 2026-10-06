package org.openpnp.corexy.precision;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import org.openpnp.corexy.CoreXYIntegration;
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
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;

/** Runs against the installed stock GUI JAR, never Pro's classes, and only a NullDriver. */
public final class CoreXYStockSmoke {
    private static ReferenceControllerAxis x, y;
    private static FaultPlanner planner;
    private static StockParkCamera camera;

    public static void main(String[] arguments) throws Exception {
        require(!CoreXYAgent.hasNativeHooks(), "Must use stock OpenPnP, not Pro");
        require(CoreXYAgent.getStartupInstrumentation() != null, "Must launch using bundled -javaagent");
        Path directory = java.nio.file.Paths.get(arguments[0]).resolve("run-" + java.util.UUID.randomUUID());
        Files.createDirectories(directory);
        Configuration.initialize(directory.toFile());
        ReferenceMachine machine = new ReferenceMachine();
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
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        camera = new StockParkCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        head.addCamera(camera);
        try {
            Path legacy = CoreXYIntegration.legacyConfiguration(Configuration.get());
            Properties properties = CoreXYIntegration.loadProperties(legacy);
            properties.setProperty("enabled", "true");
            properties.setProperty("threshold.a.mm", "1");
            properties.setProperty("threshold.b.mm", "1");
            properties.setProperty("offset.a.positive.x.mm", ".03");
            properties.setProperty("offset.a.positive.y.mm", "-.02");
            CoreXYIntegration.storeProperties(legacy, properties);
            CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), legacy.toString());
            planner.home();
            plan(100, 100, 120, 100);
            require(motions().size() == 2, "Stock legacy advice must enqueue a correction");
            close(120.03, motions().getLast().getLocation1().getCoordinate(x));
            plan(120, 100, 121, 100);
            require(motions().size() == 1, "Stock legacy offsets must not accumulate");
            close(121.03, motions().getLast().getLocation1().getCoordinate(x));
            planner.home();
            properties.setProperty("enabled", "false");
            CoreXYIntegration.storeProperties(legacy, properties);
            CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), legacy.toString());
            PrecisionModel model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                    new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
            model.setCoefficients(new double[] {.03}, new double[] {-.02});
            PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), 0, .25, 1);
            planner.rejectCorrection = true;
            boolean rejected = false;
            try {
                plan(100, 100, 120, 100);
            }
            catch (java.lang.reflect.InvocationTargetException expected) {
                rejected = true;
            }
            require(rejected && motions().isEmpty(), "Stock queue must roll back rejected correction");
            require(PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero), "Failed correction must not commit state");
            planner.rejectCorrection = false;
            plan(100, 100, 120, 100);
            require(motions().size() == 2, "Stock precision advice must execute");
            close(120.03, motions().getLast().getLocation1().getCoordinate(x));
            verifyNozzle(head);
            motions().clear();
            planner.setGlobalOffsets(location(200, 100));
            close(200, x.getCoordinate());
            close(100, y.getCoordinate());
            CoreXYRuntime.beginHome(planner);
            planner.unhome();
            planner.home();
            plan(0, 0, 100, 100);
            require(motions().size() == 1, "Stock nested Home must bypass compensation");
            CoreXYRuntime.endHome(planner);
            plan(0, 0, 100, 100);
            require(motions().size() == 2, "Stock first move from Home must compensate");
            CoreXYRuntime.beginHome(planner);
            plan(100, 100, 110, 100);
            require(motions().size() == 1, "Stock mechanical Home stage must bypass");
            try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
                plan(110, 100, 120, 100);
                require(motions().size() == 2, "Stock visual Home stage must use the loaded profile");
                close(120.03, motions().getLast().getLocation1().getCoordinate(x));
                scope.complete();
            }
            // Park occurs before ReferenceMachine.home() returns.
            plan(120, 100, 130, 100);
            require(motions().size() == 1, "Stock Home tail must retain compensation for Park");
            close(130.03, motions().getFirst().getLocation1().getCoordinate(x));
            CoreXYRuntime.endHome(planner);
            close(.03, PrecisionRuntime.activeOffset(planner).getCoordinate(x));
            close(-.02, PrecisionRuntime.activeOffset(planner).getCoordinate(y));
            planner.unhome();
            PrecisionRuntime.deactivate();
            verifyFiveMarkerLifecycle(machine);
            int scripts = 0;
            try (org.python.util.PythonInterpreter python = new org.python.util.PythonInterpreter()) {
                String manifest = resource("/corexy/scripts-manifest.txt");
                for (String name : manifest.split("\\r?\\n")) {
                    if (name.endsWith(".py") || name.endsWith(".pytxt")) {
                        String source = resource("/corexy/scripts/" + name);
                        Field mark = org.python.core.ParserFacade.class.getDeclaredField("MARK_LIMIT");
                        mark.setAccessible(true);
                        mark.setInt(null, Math.max(1000000, source.length() + 65536));
                        python.compile(source, name);
                        scripts++;
                    }
                }
            }
            System.out.println("STOCK PASS: external legacy/precision advice, transactional rejection, logical coordinates, Home bypass, "
                    + "compensated visual Home and Park, real stock Home hooks with five-marker startup/datum/failure/retry history, "
                    + scripts + " Jython scripts compiled; only a NullDriver and synthetic measurements, no hardware connected.");
        }
        finally {
            FixedMarkerLifecycle.clear();
            CoreXYIntegration.releaseWorkflow();
            planner.unhome();
            PrecisionRuntime.deactivate();
            camera.close();
        }
    }

    private static void verifyFiveMarkerLifecycle(ReferenceMachine machine) throws Exception {
        // The preceding transform-only nozzle fixture intentionally has no Z axis; it is not a Park fixture.
        for (org.openpnp.spi.Nozzle nozzle : new ArrayList<>(machine.getDefaultHead().getNozzles())) {
            machine.getDefaultHead().removeNozzle(nozzle);
        }
        ToolPointRuntime.setModels(java.util.Collections.emptyMap());
        machine.setEnabled(true);
        PrecisionProfile profile = fixedProfile();
        PrecisionRuntime.activate(planner, profile.model, x.getId(), y.getId(), 0, .25, 1);
        FakeMeasurement measurement = new FakeMeasurement(machine, profile);
        camera.profile = profile;
        camera.measurement = measurement;
        ((ReferenceHead) machine.getDefaultHead()).setParkLocation(
                new Location(LengthUnit.Millimeters, 150, 140, 0, 0));
        machine.setParkAfterHomed(true);
        FixedMarkerLifecycle.install(Configuration.get(), profile, measurement, false);
        PrecisionRuntime.setProductionGuards(() -> FixedMarkerLifecycle.assertReady(profile), null);
        boolean pendingBlocked = false;
        try {
            FixedMarkerLifecycle.assertReady(profile);
        }
        catch (IllegalStateException expected) {
            pendingBlocked = true;
        }
        require(pendingBlocked, "Stock production must wait for the first Home verification");
        machine.home();
        require(machine.isHomed() && planner.isHomed(), "Stock first verified Home must complete");
        require(camera.parks == 1, "Stock startup must finish visual alignment before Park");
        require(measurement.prepares == 1 && measurement.machineHomedAtPrepare.equals(List.of(false)),
                "Stock setHomed Advice must run the callback before machine homed=true");
        require(measurement.fullChecks == 18 && measurement.checkedMarkers.size() == 5,
                "Stock startup must inspect all five fixed markers with independent approach paths");
        FixedMarkerLifecycle.assertReady(profile);
        List<StartupVerificationReport> reports = fixedReports();
        require(reports.size() == 1 && reports.get(0).passed && reports.get(0).samples.size() == 18,
                "Stock startup report and raw samples must be saved");
        require(reports.get(0).kind.equals("startup") && reports.get(0).profileId.equals(profile.solutionId),
                "Stock startup history must identify the saved compensation solution");
        close(.08, reports.get(0).rawHomeX);
        close(-.04, reports.get(0).rawHomeY);
        close(0, reports.get(0).afterHomeResidual);

        measurement.driftX = -.03;
        measurement.driftY = .02;
        machine.home();
        require(camera.parks == 2, "Every subsequent stock Home must align its datum before Park");
        require(measurement.prepares == 2 && measurement.fullChecks == 18,
                "Another stock Home must fix datum drift without repeating the full startup check");
        reports = fixedReports();
        require(reports.size() == 2, "Every stock Home datum result must be retained");
        StartupVerificationReport datum = reports.stream().filter(r -> r.kind.equals("home")).findFirst().orElseThrow();
        require(datum.passed && datum.samples.isEmpty() && datum.homeSamples.size() == 10,
                "Stock later Home must save only the datum acquisition");
        close(-.03, datum.rawHomeX);
        close(.02, datum.rawHomeY);

        // Reinstalling the lifecycle here represents an unverified startup in the same isolated fixture.
        FakeMeasurement failing = new FakeMeasurement(machine, profile);
        camera.measurement = failing;
        failing.badResidual = true;
        FixedMarkerLifecycle.install(Configuration.get(), profile, failing, false);
        boolean failedHome = false;
        try {
            machine.home();
        }
        catch (IllegalStateException expected) {
            failedHome = true;
        }
        require(failedHome && !machine.isHomed() && !planner.isHomed(),
                "A rejected stock startup must unhome both machine and planner");
        require(camera.parks == 2, "Rejected stock Home must not reach Park");
        require(PrecisionRuntime.isActive() && PrecisionRuntime.activeOffset(planner).matches(AxesLocation.zero),
                "A failed stock startup keeps the solution and clears unsafe runtime offset");
        reports = fixedReports();
        require(reports.size() == 3 && reports.stream().anyMatch(r -> !r.passed && r.samples.size() == 18),
                "Stock failure must save the full raw residual evidence");
        failing.badResidual = false;
        Path retried = FixedMarkerLifecycle.verifyNow(Configuration.get(), (p, message) -> {}, () -> false);
        StartupVerificationReport retry = CoreXYIntegration.store(Configuration.get()).loadVerification(retried);
        require(retry.passed && retry.kind.equals("manual") && retry.samples.size() == 18,
                "Stock manual retry must save a new successful full check");
        require(failing.prepares == 2 && failing.machineHomedAtPrepare.equals(List.of(false, false)),
                "Stock manual retry must finish its Home callback before publishing homed=true");
        require(camera.parks == 3, "Stock successful manual retry must finish visual alignment before Park");
        require(machine.isHomed() && planner.isHomed(), "Stock manual retry must restore a usable Home");
        FixedMarkerLifecycle.assertReady(profile);
        require(CoreXYIntegration.acquireWorkflow(), "Stock lifecycle must release the workflow lock");
        CoreXYIntegration.releaseWorkflow();
        FixedMarkerLifecycle.clear();
        planner.unhome();
        PrecisionRuntime.deactivate();
    }

    private static PrecisionProfile fixedProfile() {
        PrecisionProfile profile = new PrecisionProfile();
        profile.solutionId = "stock-fixed-marker-fixture";
        profile.fingerprint = "STOCK-NULL-FIXTURE";
        profile.axisX = x.getId();
        profile.axisY = y.getId();
        profile.createdAt = System.currentTimeMillis();
        profile.noise = .001;
        profile.target = .05;
        profile.previousBacklashX = profile.previousBacklashY = "None";
        profile.fixedMarkers = new FixedMarkerSetup();
        double[][] coordinates = {{100, 100}, {120, 100}, {120, 120}, {100, 120}, {110, 110}};
        for (int index = 0; index < coordinates.length; index++) {
            profile.fixedMarkers.markers.add(new FixedMarkerSetup.Marker("M" + index, "marker " + index,
                    coordinates[index][0], coordinates[index][1]));
        }
        profile.model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        profile.model.setCoefficients(new double[] {.03}, new double[] {-.02});
        List<PrecisionSample> baseline = new ArrayList<>(), corrected = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            baseline.add(fixedSample("baseline-" + index, 100, 100, .1, 0));
            corrected.add(fixedSample("corrected-" + index, 100, 100, .005, 0));
        }
        profile.baseline = PrecisionMetrics.evaluate(baseline, null);
        profile.corrected = PrecisionMetrics.evaluate(corrected, null);
        profile.validate(profile.fingerprint);
        return profile;
    }

    private static List<StartupVerificationReport> fixedReports() throws Exception {
        PrecisionStore store = CoreXYIntegration.store(Configuration.get());
        List<StartupVerificationReport> reports = new ArrayList<>();
        for (Path directory : store.listVerificationDirectories()) {
            reports.add(store.loadVerification(directory));
            require(Files.isRegularFile(directory.resolve("home.csv"))
                    && Files.isRegularFile(directory.resolve("markers.csv")), "Stock raw CSV files must be retained");
        }
        return reports;
    }

    private static PrecisionSample fixedSample(String path, double px, double py, double rx, double ry) {
        return new PrecisionSample(path, camera.getId(), px, py, 0, 0, .35, 1,
                Double.NaN, 0, 0, 0, 0, 0, 0, 0, 0, rx, ry);
    }

    private static final class FakeMeasurement implements FixedMarkerLifecycle.Measurement {
        final ReferenceMachine machine;
        final PrecisionProfile profile;
        int prepares, fullChecks, finishes;
        boolean badResidual, measuring;
        double driftX = .08, driftY = -.04;
        final List<Boolean> machineHomedAtPrepare = new ArrayList<>();
        final HashSet<String> checkedMarkers = new HashSet<>();
        BooleanSupplier cancellation = () -> false;

        FakeMeasurement(ReferenceMachine machine, PrecisionProfile profile) {
            this.machine = machine;
            this.profile = profile;
        }

        @Override public void prepare(PrecisionProfile selected) {
            require(selected == profile && PrecisionRuntime.isActive() && planner.isHomed(),
                    "Stock visual Home needs the loaded profile and completed mechanical Home");
            prepares++;
            measuring = true;
            machineHomedAtPrepare.add(machine.isHomed());
            FixedMarkerLifecycle.assertReady(profile);
        }

        @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
            require(!cancellation.getAsBoolean(), "Stock synthetic measurement must honor cancellation");
            AxesLocation planned = planner.probe(camera, path.x, path.y);
            close(path.x + .03, planned.getCoordinate(x));
            close(path.y - .02, planned.getCoordinate(y));
            if (path.id.startsWith("verify-")) {
                fullChecks++;
                checkedMarkers.add(path.markerId);
                return fixedSample(path.id, path.x, path.y, badResidual ? .2 : .004, 0);
            }
            return fixedSample(path.id, path.x, path.y, driftX, driftY);
        }

        @Override public void subtractDatum(double dx, double dy) {
            driftX -= dx;
            driftY -= dy;
        }
        @Override public void finish() { finishes++; measuring = false; }
        @Override public void setCancellationGuard(BooleanSupplier cancelled) { cancellation = cancelled; }
    }

    private static final class StockParkCamera extends SimulatedUpCamera {
        int parks;
        PrecisionProfile profile;
        FakeMeasurement measurement;

        @Override public void moveTo(Location location, double speed, Motion.MotionOption... options) throws Exception {
            Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
            CoreXYStockSmoke.close(150, millimeters.getX());
            CoreXYStockSmoke.close(140, millimeters.getY());
            require(Configuration.get().getMachine().isHomed() && measurement != null && !measurement.measuring,
                    "Stock Park must follow completed visual acquisition and corrected Home publication");
            require(measurement.finishes > 0, "Stock Park must follow visual cleanup");
            CoreXYStockSmoke.close(0, measurement.driftX);
            CoreXYStockSmoke.close(0, measurement.driftY);
            FixedMarkerLifecycle.assertReady(profile);
            AxesLocation planned = planner.probe(this, 150, 140);
            CoreXYStockSmoke.close(150.03, planned.getCoordinate(x));
            CoreXYStockSmoke.close(139.98, planned.getCoordinate(y));
            parks++;
        }
    }

    private static void verifyNozzle(ReferenceHead head) throws Exception {
        org.openpnp.machine.reference.ReferenceNozzle nozzle = new org.openpnp.machine.reference.ReferenceNozzle();
        nozzle.setAxisX(x);
        nozzle.setAxisY(y);
        nozzle.setRotationModeOffset(45.0);
        head.addNozzle(nozzle);
        org.openpnp.machine.reference.ReferenceNozzleTip tip = new org.openpnp.machine.reference.ReferenceNozzleTip();
        nozzle.setNozzleTip(tip);
        org.openpnp.machine.reference.ReferenceNozzleTipCalibration calibration = tip.getCalibration();
        calibration.setEnabled(true);
        Field lookup = calibration.getClass().getDeclaredField("runoutCompensationLookup");
        lookup.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, org.openpnp.machine.reference.ReferenceNozzleTipCalibration.RunoutCompensation> values =
                (java.util.Map<String, org.openpnp.machine.reference.ReferenceNozzleTipCalibration.RunoutCompensation>) lookup.get(calibration);
        values.put(nozzle.getId(), new org.openpnp.machine.reference.ReferenceNozzleTipCalibration.RunoutCompensation() {
            public org.openpnp.model.Location getOffset(double angle) {
                return new org.openpnp.model.Location(LengthUnit.Millimeters, .1, -.07, 0, 0);
            }
            public org.openpnp.model.Location getCameraOffset() {
                return new org.openpnp.model.Location(LengthUnit.Millimeters, .08, 0, 0, 0);
            }
            public org.openpnp.model.Location getAxisOffset() {
                return new org.openpnp.model.Location(LengthUnit.Millimeters);
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
        org.openpnp.model.Location target = new org.openpnp.model.Location(LengthUnit.Millimeters, 100, 110, .5, 135);
        org.openpnp.model.Location transformed = nozzle.toHeadLocation(target, target);
        close(99.97, transformed.getX());
        close(110.01, transformed.getY());
        org.openpnp.model.Location inverse = nozzle.toHeadMountableLocation(transformed, target);
        close(target.getX(), inverse.getX());
        close(target.getY(), inverse.getY());
        close(target.getRotation(), inverse.getRotation());
        require(ToolPointRuntime.replacesNative(nozzle), "Tool model must replace native correction only once");
        ToolPointRuntime.setModels(java.util.Collections.emptyMap());
    }

    private static String resource(String name) throws Exception {
        try (java.io.InputStream stream = CoreXYStockSmoke.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void require(boolean pass, String message) {
        if (!pass) {
            throw new AssertionError(message);
        }
    }
    private static void close(double expected, double actual) {
        require(Math.abs(expected - actual) < 1e-8, expected + " != " + actual);
    }
    private static void plan(double cx, double cy, double tx, double ty) throws Exception {
        motions().clear();
        Method method = AbstractMotionPlanner.class.getDeclaredMethod("createBacklashCompensatedMotion",
                HeadMountable.class, double.class, AxesLocation.class, AxesLocation.class, Motion.MotionOption[].class);
        method.setAccessible(true);
        method.invoke(planner, camera, .5, location(cx, cy), location(tx, ty), new Motion.MotionOption[0]);
    }
    @SuppressWarnings("unchecked")
    private static LinkedList<Motion> motions() throws Exception {
        Field field = AbstractMotionPlanner.class.getDeclaredField("motionCommands");
        field.setAccessible(true);
        return (LinkedList<Motion>) field.get(planner);
    }
    private static AxesLocation location(double px, double py) {
        return new AxesLocation(x, px).put(new AxesLocation(y, py));
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
        axis.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.None);
        return axis;
    }
    private static final class FaultPlanner extends ReferenceAdvancedMotionPlanner {
        boolean rejectCorrection;
        AxesLocation probe(HeadMountable hm, double px, double py) throws Exception {
            AxesLocation target = location(px, py);
            AxesLocation planned = createBacklashCompensatedMotion(hm, .35,
                    new AxesLocation(Configuration.get().getMachine()), target, new Motion.MotionOption[0]);
            // Synthetic fixture: inspect transformed plans without executing any move.
            motionCommands.clear();
            target.setToCoordinates();
            return planned;
        }
        @Override
        protected Motion addMotion(HeadMountable hm, double speed, AxesLocation current, AxesLocation target, int options) {
            if (rejectCorrection && speed == .25) {
                throw new IllegalStateException("Injected rejection before enqueue");
            }
            return super.addMotion(hm, speed, current, target, options);
        }
    }
}
