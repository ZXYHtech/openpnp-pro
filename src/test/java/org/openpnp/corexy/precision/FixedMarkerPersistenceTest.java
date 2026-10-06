package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixedMarkerPersistenceTest {
    @TempDir
    Path temporary;

    @Test
    void fiveMarkerPlanNeverRequestsADetectionAtAnUnregisteredGridPoint() {
        FixedMarkerSetup setup = setup();
        setup.markers.get(0).x = 2;
        setup.markers.get(0).y = 2;
        CalibrationRequest request = setup.toRequest();
        CalibrationPlan plan = new CalibrationPlan(request);
        List<CalibrationPlan.Path> all = new ArrayList<>(plan.training);
        all.addAll(plan.validation);
        all.addAll(plan.finalCheck);
        Set<String> identities = new HashSet<>();
        Set<String> trainedDirections = new HashSet<>();
        int homePaths = 0;
        for (CalibrationPlan.Path path : all) {
            assertTrue(identities.add(path.id), "Training and holdout identities must be disjoint.");
            FixedMarkerSetup.Marker marker = setup.marker(path.markerId);
            assertEquals(marker.x, path.x);
            assertEquals(marker.y, path.y);
            if (path.markerId.equals(setup.homeMarker().id)) {
                assertTrue(path.homeStart, "Home near a boundary must never require a conditioning excursion.");
                assertEquals(1, path.points(request.conditioningTravel).length);
                homePaths++;
            }
        }
        for (CalibrationPlan.Path path : plan.training) {
            trainedDirections.add(path.markerId + ":" + path.signA + ":" + path.signB);
        }
        assertEquals(16, trainedDirections.size());
        assertEquals(2, homePaths);
        new DigitalTwinMachine(42).prepare(request, plan);
        List<CalibrationPlan.Path> verification = CalibrationPlan.fixedVerification(setup, 100);
        assertEquals(18, verification.size());
        assertTrue(verification.stream().noneMatch(path -> path.homeStart));
        CalibrationPlan.Path direct = verification.stream().filter(path -> path.direct).findFirst().get();
        assertEquals(setup.homeMarker().id, direct.markerId);
        assertEquals(1, direct.points(48).length);
    }

    @Test
    void setupRejectsUnobservableOrAmbiguousReferencesAndKeepsAnIndependentRequestCopy() throws Exception {
        FixedMarkerSetup setup = setup();
        CalibrationRequest request = setup.toRequest();
        setup.markers.get(0).x = 150;
        assertEquals(100, request.fixedMarkers.homeMarker().x);
        FixedMarkerSetup invalid = setup();
        invalid.markers.remove(4);
        assertThrows(IllegalArgumentException.class, invalid::validate);
        invalid = setup();
        invalid.markers.get(4).id = invalid.markers.get(0).id;
        assertThrows(IllegalArgumentException.class, invalid::validate);
        invalid = setup();
        for (int index = 0; index < 5; index++) {
            invalid.markers.get(index).x = 80 + index * 10;
            invalid.markers.get(index).y = 80 + index * 10;
        }
        assertThrows(IllegalArgumentException.class, invalid::validate);
        invalid = setup();
        invalid.homeSearchRadius = .2;
        assertThrows(IllegalArgumentException.class, invalid::validate);
        PrecisionStore store = new PrecisionStore(temporary);
        assertNull(store.loadFixedMarkers());
        setup = setup();
        setup.homeIndex = 3;
        setup.verifyOnStartup = false;
        store.saveFixedMarkers(setup);
        FixedMarkerSetup loaded = store.loadFixedMarkers();
        assertEquals("point3", loaded.homeMarker().id);
        assertFalse(loaded.verifyOnStartup);
        assertFalse(store.isEnabled(), "Recording reference positions does not enable compensation.");
        Files.write(temporary.resolve("fixed-markers.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, store::loadFixedMarkers);
    }

    @Test
    void solutionsRemainImmutableWhileActiveProfileAndRollbackChange() throws Exception {
        PrecisionStore store = new PrecisionStore(temporary);
        PrecisionProfile profile = profile();
        profile.fixedMarkers = setup();
        profile.referenceOffsetX = .013;
        profile.referenceOffsetY = -.018;
        profile.calibrationDirectory = "sessions/reference-calibration";
        store.saveProfile(profile, true);
        String firstId = profile.solutionId;
        Path first = store.listSolutionDirectories().get(0).resolve("profile.json");
        byte[] firstBytes = Files.readAllBytes(first);
        PrecisionStore.Snapshot previous = store.snapshot();
        profile.createdAt++;
        profile.referenceOffsetX = .014;
        store.saveProfile(profile, false);
        assertNotEquals(firstId, profile.solutionId);
        assertEquals(2, store.listSolutionDirectories().size());
        assertArrayEquals(firstBytes, Files.readAllBytes(first));
        PrecisionProfile loaded = store.loadProfile(profile.fingerprint);
        assertEquals(.014, loaded.referenceOffsetX);
        assertEquals(5, loaded.fixedMarkers.markers.size());
        assertEquals("sessions/reference-calibration", loaded.calibrationDirectory);
        store.restore(previous);
        assertEquals(firstId, store.loadProfile(profile.fingerprint).solutionId);
        assertEquals(2, store.listSolutionDirectories().size(), "Rollback retains failed/new calibration evidence.");
    }

    @Test
    void oldSchemaOneProfilesWithoutFixedMarkerFieldsStillLoad() throws Exception {
        PrecisionStore store = new PrecisionStore(temporary);
        PrecisionProfile profile = profile();
        store.saveProfile(profile, true);
        Path active = temporary.resolve("profile.json");
        String json = new String(Files.readAllBytes(active), StandardCharsets.UTF_8);
        json = json.replaceAll("(?m)^  \\\"(?:solutionId|calibrationDirectory|referenceOffsetX|referenceOffsetY)\\\":.*\\r?\\n", "");
        // Gson includes trailing commas only on preceding fields; remove a possible last-field comma.
        json = json.replaceAll(",\\s*}", "\n}");
        Files.write(active, json.getBytes(StandardCharsets.UTF_8));
        Files.write(temporary.resolve("profile.sha256"),
                PrecisionStore.digest(json.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        PrecisionProfile loaded = store.loadProfile(profile.fingerprint);
        assertNull(loaded.fixedMarkers);
        assertEquals(0, loaded.referenceOffsetX);
        assertEquals("", loaded.solutionId);
    }

    @Test
    void verificationHistoriesKeepPassedFailedAndHomeOnlyEvidenceAndUnknownTemperature() throws Exception {
        PrecisionStore store = new PrecisionStore(temporary);
        StartupVerificationReport passed = report();
        passed.passed = true;
        passed.rawHomeX = .32;
        passed.rawHomeY = -.17;
        passed.homeCorrectionX = -.32;
        passed.homeCorrectionY = .17;
        passed.beforeHomeResidual = Math.hypot(.32, -.17);
        passed.afterHomeResidual = .001;
        passed.homeIterations = 2;
        passed.samples = samples(5, .001);
        passed.homeSamples = samples(5, .001);
        passed.metrics = PrecisionMetrics.evaluate(passed.samples, null);
        Path first = store.saveVerification(passed);
        String json = new String(Files.readAllBytes(first.resolve("report.json")), StandardCharsets.UTF_8);
        assertFalse(json.contains("NaN"));
        StartupVerificationReport loaded = store.loadVerification(first);
        assertEquals(passed.sessionId, loaded.sessionId);
        assertEquals(passed.profileId, loaded.profileId);
        assertEquals(.32, loaded.rawHomeX);
        assertTrue(Double.isNaN(loaded.samples.get(0).temperature));
        assertTrue(Files.size(first.resolve("markers.csv")) > 500);
        assertTrue(Files.size(first.resolve("home.csv")) > 500);
        StartupVerificationReport cancelled = report();
        cancelled.cancelled = true;
        cancelled.outcome = "取消，保留已经采集的 2 个 marker 数据";
        cancelled.samples = samples(2, .008);
        store.saveVerification(cancelled);
        StartupVerificationReport home = report();
        home.kind = "home";
        home.homeSamples = samples(5, .001);
        home.metrics = PrecisionMetrics.evaluate(home.homeSamples, null);
        home.passed = true;
        store.saveVerification(home);
        assertEquals(3, store.listVerificationDirectories().size());
        assertFalse(store.isEnabled(), "Saving history does not change the production profile.");
        StartupVerificationReport invalid = report();
        invalid.passed = true;
        assertThrows(IllegalArgumentException.class, () -> store.saveVerification(invalid));
        assertThrows(IllegalArgumentException.class, () -> store.loadVerification(temporary.getParent()));
    }

    @Test
    void historyPageEscapesDescriptionsLinksChinesePathsAndExposesIncompleteEvidence() throws Exception {
        Path historyRoot = temporary.resolve("精度 历史");
        PrecisionStore store = new PrecisionStore(historyRoot);
        StartupVerificationReport passed = report();
        passed.passed = true;
        passed.outcome = "<script>alert(\"unsafe\")</script> & 校验通过";
        passed.samples = samples(5, .001);
        passed.homeSamples = samples(5, .001);
        passed.metrics = PrecisionMetrics.evaluate(passed.samples, null);
        Path saved = store.saveVerification(passed);
        Files.move(saved, saved.resolveSibling("历史 一"));
        StartupVerificationReport home = report();
        home.kind = "home";
        home.passed = true;
        home.homeSamples = samples(5, .001);
        home.metrics = PrecisionMetrics.evaluate(home.homeSamples, null);
        store.saveVerification(home);
        Path brokenCheck = historyRoot.resolve("verifications/坏 记录");
        Files.createDirectories(brokenCheck);
        Files.write(brokenCheck.resolve("report.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(historyRoot.resolve("solutions/坏方案"));
        Files.createDirectories(historyRoot.resolve("sessions/未完成 校准"));
        PrecisionProfile profile = profile();
        CalibrationController.Report calibration = new CalibrationController.Report();
        calibration.request = setup().toRequest();
        calibration.outcome = "首次完成校准";
        calibration.fingerprint = profile.fingerprint;
        calibration.noise = .001;
        calibration.chosen = profile.model;
        calibration.baselineFinal = profile.baseline;
        calibration.correctedFinal = profile.corrected;
        profile.calibrationDirectory = store.saveReport(calibration).toString();
        store.saveProfile(profile, true);
        Path page = store.writeHistoryHtml();
        assertEquals(historyRoot.resolve("history.html"), page);
        String html = new String(Files.readAllBytes(page), StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("verifications/%E5%8E%86%E5%8F%B2%20%E4%B8%80/report.json"));
        assertTrue(html.contains("坏 记录"));
        assertTrue(html.contains("坏方案"));
        assertTrue(html.contains("未完成 校准"));
        assertTrue(html.contains("3 条记录损坏或未完整保存"));
        assertTrue(html.contains("正式完整校验：1 次，通过 1 次"));
        assertTrue(html.contains("Home 单点归零"));
        assertTrue(html.contains("对应校准评估"));
        assertTrue(html.contains("<svg"));
        assertTrue(store.isEnabled(), "History rendering must not alter the selected compensation.");
    }

    @Test
    void reportCommitRollbackUpdatesOnlySummaryAndRejectsOtherDirectories() throws Exception {
        PrecisionStore store = new PrecisionStore(temporary);
        CalibrationController.Report calibration = new CalibrationController.Report();
        calibration.request = setup().toRequest();
        calibration.fingerprint = "fixture-fingerprint";
        calibration.enabled = true;
        calibration.outcome = "独立评估通过，保存补偿";
        calibration.training = samples(8, .04);
        calibration.validation = samples(8, .04);
        calibration.finalObservations = samples(8, .002);
        Path directory = store.saveReport(calibration);
        List<String> csvNames = java.util.Arrays.asList("training.csv", "validation.csv", "final.csv",
                "baseline-final.csv", "previous-profile-final.csv");
        List<byte[]> originalCsv = new ArrayList<>();
        for (String name : csvNames) {
            originalCsv.add(Files.readAllBytes(directory.resolve(name)));
        }
        store.updateReportCommit(directory, false, "配置保存失败，已回滚，未启用新补偿");
        com.google.gson.JsonObject summary = new com.google.gson.JsonParser().parse(
                new String(Files.readAllBytes(directory.resolve("report.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(summary.get("enabled").getAsBoolean());
        assertEquals("配置保存失败，已回滚，未启用新补偿", summary.get("outcome").getAsString());
        for (int index = 0; index < csvNames.size(); index++) {
            assertArrayEquals(originalCsv.get(index), Files.readAllBytes(directory.resolve(csvNames.get(index))));
        }
        store.updateReportCommit(directory, true, "配置和补偿方案已完整提交");
        summary = new com.google.gson.JsonParser().parse(
                new String(Files.readAllBytes(directory.resolve("report.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(summary.get("enabled").getAsBoolean());
        assertFalse(store.isEnabled(), "Commit metadata never changes the production enabled flag.");
        assertThrows(IllegalArgumentException.class,
                () -> store.updateReportCommit(temporary.getParent().resolve("outside-report"), false, "越界"));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateReportCommit(temporary.resolve("solutions/solution"), false, "非校准目录"));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateReportCommit(temporary.resolve("sessions"), false, "父目录"));
        calibration.simulation = true;
        calibration.enabled = false;
        Path simulated = store.saveReport(calibration);
        store.updateReportCommit(simulated, false, "模拟完成，只保存报告");
        assertThrows(IllegalArgumentException.class, () -> store.updateReportCommit(simulated, true, "不能启用"));
    }

    private static FixedMarkerSetup setup() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        double[][] coordinates = {{100, 100}, {80, 80}, {80, 120}, {120, 80}, {120, 120}};
        for (int index = 0; index < 5; index++) {
            setup.markers.add(new FixedMarkerSetup.Marker("point" + index,
                    index == 0 ? "Home 基准" : "固定点 " + index,
                    coordinates[index][0], coordinates[index][1]));
        }
        return setup;
    }

    private static List<PrecisionSample> samples(int count, double correction) {
        List<PrecisionSample> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new PrecisionSample("path-" + index, "top-camera", 100 + index, 100,
                    0, 0, .5, 1000, Double.NaN, index, 4, 2, 4, 2, 300, 1, 1,
                    correction, correction / 2));
        }
        return result;
    }

    private static StartupVerificationReport report() {
        StartupVerificationReport report = new StartupVerificationReport();
        report.sessionId = java.util.UUID.randomUUID().toString();
        report.profileId = java.util.UUID.randomUUID().toString();
        report.fingerprint = "fixture-fingerprint";
        report.target = .05;
        report.noise = .001;
        report.outcome = "校验记录";
        return report;
    }

    private static PrecisionProfile profile() {
        PrecisionProfile profile = new PrecisionProfile();
        profile.createdAt = 1;
        profile.fingerprint = "fixture-fingerprint";
        profile.axisX = "X";
        profile.axisY = "Y";
        profile.noise = .001;
        profile.target = .05;
        profile.previousBacklashX = "None";
        profile.previousBacklashY = "None";
        profile.baseline = PrecisionMetrics.evaluate(samples(10, .04), null);
        profile.corrected = PrecisionMetrics.evaluate(samples(10, .002), null);
        profile.model = new PrecisionModel(EnumSet.of(PrecisionModel.Layer.COMMON_BIAS),
                new double[] {0, 300, 0, 300, .5, 1000, 24, 0}, 12, 6, 2, 2, .2);
        profile.model.setCoefficients(new double[] {.02}, new double[] {.01});
        return profile;
    }
}
