package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.CameraDiagnosticReport.Phase;
import org.openpnp.gui.support.CameraDiagnosticReport.Sample;
import org.openpnp.gui.support.CameraDiagnosticReport.Summary;

class CameraDiagnosticReportTest {
    @Test
    void createsSingleAndMultiCameraComparisonPlan() {
        List<Phase> single = CameraDiagnosticReport.standardPlan(1);
        assertEquals(2, single.size());
        assertEquals("single_30_30", single.get(0).id);
        assertEquals(15, single.get(1).displayFps, 0.001);

        List<Phase> multiple = CameraDiagnosticReport.standardPlan(2);
        assertEquals(4, multiple.size());
        assertTrue(multiple.get(2).allCameras);
        assertEquals("all_15_15", multiple.get(3).id);
    }

    @Test
    void summarizesSamplesAndExplainsTransformAndContention() {
        List<Sample> samples = new ArrayList<>();
        samples.add(sample("single_30_30", "单相机 30采集 / 30显示", false,
                27, 24, 9, 72, 22, 16, 410, 50));
        samples.add(sample("single_30_15", "单相机 30采集 / 15显示", false,
                29, 15, 6, 48, 21, 15, 430, 3));
        samples.add(sample("all_30_15", "全部相机 30采集 / 15显示", true,
                22, 15, 12, 90, 24, 16, 330, 4));
        samples.add(sample("all_15_15", "全部相机 15采集 / 15显示", true,
                15, 14, 5, 50, 20, 14, 220, 2));

        List<Summary> summaries = CameraDiagnosticReport.summarize(samples);
        assertEquals(4, summaries.size());
        assertEquals(27, summaries.get(0).captureFps, 0.001);
        assertEquals(50.0 / 410 * 100, summaries.get(0).dropPercent(), 0.001);

        String analysis = CameraDiagnosticReport.analysisText(summaries, "测试环境");
        assertTrue(analysis.contains("软件处理热点"));
        assertTrue(analysis.contains("USB 带宽、解码或 CPU 竞争"));
        assertTrue(analysis.contains("降低总带宽能显著改善稳定性"));
    }

    @Test
    void exportsDetailedAndSummaryCsv() {
        Sample sample = sample("single_30_30", "阶段,一", false,
                30, 25, 2, 40, 10, 4, 300, 10);
        sample.camera = "Top \"A\"";
        List<Sample> samples = java.util.Collections.singletonList(sample);

        String timeSeries = CameraDiagnosticReport.timeSeriesCsv(samples);
        String summary = CameraDiagnosticReport.summaryCsv(
                CameraDiagnosticReport.summarize(samples));
        assertTrue(timeSeries.contains("raw_capture_avg_ms"));
        assertTrue(timeSeries.contains("\"Top \"\"A\"\"\""));
        assertTrue(summary.contains("drop_pct"));
    }

    private static Sample sample(String id, String name, boolean all, double capture,
            double display, double jitter, double stall, double totalCapture,
            double transform, long received, long dropped) {
        Sample sample = new Sample();
        sample.phaseId = id;
        sample.phaseName = name;
        sample.allCameras = all;
        sample.camera = "Top";
        sample.primaryCamera = true;
        sample.captureTargetFps = id.equals("all_15_15") ? 15 : 30;
        sample.displayTargetFps = id.equals("single_30_30") ? 30 : 15;
        sample.captureFps = capture;
        sample.displayFps = display;
        sample.jitterMs = jitter;
        sample.maximumStallMs = stall;
        sample.captureAverageMs = totalCapture;
        sample.processingAverageMs = transform;
        sample.receivedFrames = received;
        sample.displayedFrames = Math.max(0, received - dropped);
        sample.droppedFrames = dropped;
        return sample;
    }
}
