package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.JobTelemetryManager.ErrorRecord;
import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;

class ProductionDashboardPanelTest {
    @Test
    void recentMenuJobsAppearWithoutProductionHistoryAndAreDeduplicated() throws Exception {
        File recentFile = Files.createTempFile("openpnp-recent-", ".job.xml").toFile();
        recentFile.deleteOnExit();

        List<JobRunRecord> withoutHistory = ProductionDashboardPanel.mergeRecentJobs(
                Collections.emptyList(), Collections.singletonList(recentFile));
        assertEquals(1, withoutHistory.size());
        assertEquals(recentFile.getCanonicalPath(),
                new File(withoutHistory.get(0).jobPath).getCanonicalPath());

        JobRunRecord existingRun = new JobRunRecord();
        existingRun.jobName = recentFile.getName();
        existingRun.jobPath = recentFile.getAbsolutePath();
        existingRun.startTime = System.currentTimeMillis();
        List<JobRunRecord> withExistingRun = ProductionDashboardPanel.mergeRecentJobs(
                Collections.singletonList(existingRun), Arrays.asList(recentFile));
        assertEquals(1, withExistingRun.size());
        assertEquals(existingRun.jobPath, withExistingRun.get(0).jobPath);
        assertEquals(1, withExistingRun.get(0).getRunCount());
    }

    @Test
    void consecutiveSameJobsAreAggregatedButSeparatedByAnotherJob() {
        JobRunRecord newestA = run("A.job.xml", 4000, 2, 20);
        newestA.startTime = 400;
        JobRunRecord olderA = run("A.job.xml", 6000, 3, 30);
        olderA.startTime = 300;
        ErrorRecord error = new ErrorRecord();
        error.category = "飞达送料";
        error.message = "Feeder F1 failed";
        olderA.errors.add(error);
        JobRunRecord middleB = run("B.job.xml", 5000, 1, 10);
        middleB.startTime = 200;
        JobRunRecord oldestA = run("A.job.xml", 7000, 4, 40);
        oldestA.startTime = 100;

        List<JobRunRecord> merged = ProductionDashboardPanel.mergeRecentJobs(
                Arrays.asList(newestA, olderA, middleB, oldestA), Collections.emptyList());

        assertEquals(3, merged.size());
        assertEquals(2, merged.get(0).getRunCount());
        assertEquals(10000, merged.get(0).elapsedMillis);
        assertEquals(5, merged.get(0).completedBoards);
        assertEquals(50, merged.get(0).placedPlacements);
        assertEquals(1, merged.get(0).getErrorCount());
        assertNotNull(merged.get(0).errors.get(0).jobName);
        assertEquals(1, merged.get(2).getRunCount());
    }

    private JobRunRecord run(String name, long elapsed, int boards, int placements) {
        JobRunRecord run = new JobRunRecord();
        run.jobName = name;
        run.jobPath = new File(name).getAbsolutePath();
        run.elapsedMillis = elapsed;
        run.activeMillis = elapsed;
        run.boardCount = boards;
        run.completedBoards = boards;
        run.totalPlacements = placements;
        run.placedPlacements = placements;
        run.completed = true;
        run.state = "已完成";
        return run;
    }}
