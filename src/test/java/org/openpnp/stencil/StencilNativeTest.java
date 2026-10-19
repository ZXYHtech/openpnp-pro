package org.openpnp.stencil;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;

class StencilNativeTest {

    @Test
    void chineseEngravingAndCompletedHistorySurviveRestart() throws Exception {
        windows();
        String id;
        try (StencilJobs jobs = new StencilJobs(dir)) {
            StencilParameters p = StencilParameters.factory().with("model", "钢网夹具 A");
            StencilJobs.State result = await(jobs, jobs.start(p, false).id);
            assertEquals("complete", result.status, result.message);
            id = result.id;
            JsonObject report = new JsonParser().parse(StencilFiles.text(StencilJobs.file(result, "report.json"))).getAsJsonObject();
            assertTrue(report.getAsJsonArray("checks").size() >= 86);
            assertTrue(report.getAsJsonObject("weight_relief").get("removed_volume_mm3").getAsDouble() > 0);
        }
        try (StencilJobs next = new StencilJobs(dir)) {
            assertEquals("complete", next.get(id).status);
            assertTrue(Files.size(StencilJobs.file(next.get(id), "pcb_base.3mf")) > 1000);
        }
    }

    @Test
    void runningNativeWorkerCanBeCanceled() throws Exception {
        windows();
        try (StencilJobs jobs = new StencilJobs(dir)) {
            String id = jobs.start(StencilParameters.factory(), true).id;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (jobs.get(id).progress < 8 && jobs.busy() && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertTrue(jobs.busy());
            jobs.cancel();
            assertEquals("canceled", await(jobs, id).status);
            assertFalse(jobs.busy());
        }
    }

    @TempDir
    Path dir;

    private void windows() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") && System.getProperty("os.arch").contains("64"));
    }

    private StencilJobs.State await(StencilJobs j, String id) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        StencilJobs.State s;
        do {
            s = j.get(id);
            if (!s.status.equals("queued") && !s.status.equals("running")) {
                return s;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        fail("CAD job timed out");
        return null;
    }

    @Test
    void matchesThreeSourceCasesWithoutPython() throws Exception {
        windows();
        JsonArray cases;
        try (InputStream in = getClass().getResourceAsStream("reference-metrics.json")) {
            cases = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
        try (StencilJobs jobs = new StencilJobs(dir)) {
            for (JsonElement entry : cases) {
                JsonObject c = entry.getAsJsonObject();
                StencilParameters p = new StencilParameters(c.getAsJsonObject("parameters"));
                StencilJobs.State s = await(jobs, jobs.start(p, false).id);
                assertEquals("complete", s.status, s.message);
                JsonObject report = new JsonParser().parse(StencilFiles.text(StencilJobs.file(s, "report.json"))).getAsJsonObject();
                for (String name : new String[] { "pcb_base", "stencil_lower", "stencil_upper" }) {
                    JsonObject expected = c.getAsJsonObject("parts").getAsJsonObject(name), actual = report.getAsJsonObject("parts").getAsJsonObject(name);
                    double volume = expected.get("volume_mm3").getAsDouble();
                    assertEquals(volume, actual.get("volume_mm3").getAsDouble(), Math.max(.001, volume * 1e-7), c.get("name") + " " + name);
                    assertTrue(actual.get("step_roundtrip").getAsBoolean());
                    for (int i = 0; i < 3; i++) {
                        assertEquals(expected.getAsJsonArray("bbox_mm").get(i).getAsDouble(), actual.getAsJsonArray("bbox_mm").get(i).getAsDouble(), 1e-5);
                    }
                    assertTrue(Files.size(StencilJobs.file(s, name + ".3mf")) > 1000);
                }
                assertEquals(3, report.getAsJsonObject("parts").getAsJsonObject("assembly").get("solids").getAsInt());
                for (JsonElement check : report.getAsJsonArray("checks")) {
                    assertTrue(check.getAsJsonObject().get("passed").getAsBoolean());
                }
                try (ZipFile zip = new ZipFile(StencilJobs.file(s, "bundle.zip").toFile())) {
                    assertNotNull(zip.getEntry(s.files.get("cad-input.json")));
                }
            }
        }
    }

    @Test
    void nineCouponsAre18ValidatedSolids() throws Exception {
        windows();
        try (StencilJobs jobs = new StencilJobs(dir)) {
            StencilJobs.State s = await(jobs, jobs.start(StencilParameters.factory(), true).id);
            assertEquals("complete", s.status, s.message);
            JsonObject report = new JsonParser().parse(StencilFiles.text(StencilJobs.file(s, "report.json"))).getAsJsonObject();
            JsonObject m = report.getAsJsonObject("parts").getAsJsonObject("hinge_test");
            assertEquals(18, m.get("solids").getAsInt());
            assertEquals(0, m.getAsJsonArray("min").get(2).getAsDouble(), 1e-5);
            assertTrue(m.get("step_roundtrip").getAsBoolean());
            assertEquals(45, report.getAsJsonArray("checks").size());
        }
    }

    @Test
    void cancelAndRestartNeverLeaveRunningJob() throws Exception {
        windows();
        try (StencilJobs jobs = new StencilJobs(dir)) {
            String id = jobs.start(StencilParameters.factory(), false).id;
            jobs.cancel();
            assertEquals("canceled", await(jobs, id).status);
            assertFalse(jobs.busy());
        }
        try (StencilJobs next = new StencilJobs(dir)) {
            assertEquals("canceled", next.history().get(0).status);
        }
    }

    @Test
    void interruptedJobsAreRecoverableAndBadHistoryIsIsolated() throws Exception {
        Files.createDirectories(dir.resolve("jobs/old"));
        JsonObject s = new JsonObject();
        s.addProperty("id", "old");
        s.addProperty("kind", "fixture");
        s.addProperty("model", "A");
        s.add("parameters", StencilParameters.factory().json());
        s.addProperty("status", "running");
        s.addProperty("directory", dir.resolve("output/A/old").toString());
        StencilFiles.write(dir.resolve("jobs/old/state.json"), s);
        Files.createDirectories(dir.resolve("jobs/corrupt"));
        StencilFiles.writeText(dir.resolve("jobs/corrupt/state.json"), "broken");
        try (StencilJobs jobs = new StencilJobs(dir)) {
            assertEquals(1, jobs.history().size());
            assertEquals("interrupted", jobs.history().get(0).status);
        }
    }
}
