package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.JobTelemetryManager.BottleneckInsight;
import org.openpnp.gui.support.JobTelemetryManager.ErrorRecord;
import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;

class JobTelemetryAnalysisTest {
    @Test
    void attributesPredictionDriftToFeederAndRepeatedAlignment() {
        JobRunRecord record = new JobRunRecord();
        record.elapsedMillis = 180000;
        record.activeMillis = 150000;
        record.pausedMillis = 30000;
        record.placedPlacements = 10;
        record.totalPlacements = 10;
        record.initialEstimatedDurationMillis = 100000;
        record.currentEstimatedDurationMillis = 180000;
        record.predictionDeviationMillis = 80000;
        record.stageCounts.put("视觉对位", 15);
        record.stageMillis.put("视觉对位", 70000L);
        record.predictedStageMillis.put("视觉对位", 30000L);
        record.errors.add(feederError("飞达：F1"));
        record.errors.add(feederError("飞达：F1"));

        List<BottleneckInsight> insights = JobTelemetryManager.analyzeBottlenecks(record);

        assertTrue(insights.stream().anyMatch(insight -> insight.title.contains("F1")));
        assertTrue(insights.stream().anyMatch(insight -> insight.title.contains("重复底部视觉")));
        assertTrue(insights.stream().anyMatch(insight -> insight.title.contains("超过预计")));
    }

    private ErrorRecord feederError(String source) {
        ErrorRecord error = new ErrorRecord();
        error.category = "飞达送料";
        error.source = source;
        error.estimatedLostMillis = 75000;
        return error;
    }
}