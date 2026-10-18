package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.ImageCapture;

class BottomVisionTimingTest {

    @BeforeEach
    void clear() {
        BottomVisionTiming.clear();
    }

    @Test
    void recordsCaptureAndProcessingSeparatelyWithoutInvokingEither() throws Exception {
        ImageCapture capture = new ImageCapture();
        CvStage processing = mock(CvStage.class);
        CvPipeline pipeline = mock(CvPipeline.class);
        when(pipeline.getStages()).thenReturn(Arrays.asList(capture, processing));
        when(pipeline.getResult(capture)).thenReturn(new CvStage.Result(null, null, null, 3_000_000, capture));
        when(pipeline.getResult(processing)).thenReturn(new CvStage.Result(null, null, null, 2_000_000, processing));
        try (BottomVisionTiming.Scope scope = BottomVisionTiming.begin("part", "预旋转静拍")) {
            BottomVisionTiming.pass();
            BottomVisionTiming.movement(1_000_000);
            BottomVisionTiming.pipeline(pipeline);
            scope.success();
        }
        BottomVisionTiming.Sample sample = BottomVisionTiming.samples().get(0);
        assertTrue(sample.successful);
        assertEquals(1, sample.passes);
        assertEquals(1, sample.shots);
        assertEquals(3_000_000, sample.captureNs);
        assertEquals(2_000_000, sample.pipelineNs);
        assertEquals(1_000_000, sample.movementNs);
        verifyNoMoreInteractions(processing);
        verify(pipeline, never()).process();
    }

    @Test
    void failedScopeAndRepeatedCloseDoNotLeakIntoTheNextOperation() {
        BottomVisionTiming.Scope failed = BottomVisionTiming.begin("bad", "静拍");
        BottomVisionTiming.pass();
        failed.close();
        failed.close();
        BottomVisionTiming.pass();
        try (BottomVisionTiming.Scope good = BottomVisionTiming.begin("good", "静拍")) {
            BottomVisionTiming.pass();
            good.success();
        }
        assertEquals(2, BottomVisionTiming.samples().size());
        assertFalse(BottomVisionTiming.samples().get(0).successful);
        assertEquals(1, BottomVisionTiming.samples().get(1).passes);
    }

    @Test
    void nestedAndOtherThreadCallsCannotMixMeasurements() throws Exception {
        try (BottomVisionTiming.Scope outer = BottomVisionTiming.begin("outer", "静拍")) {
            BottomVisionTiming.pass();
            Thread other = new Thread(() -> {
                BottomVisionTiming.pass();
                try (BottomVisionTiming.Scope worker = BottomVisionTiming.begin("worker", "静拍")) {
                    BottomVisionTiming.pass();
                    worker.success();
                }
            });
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            try (BottomVisionTiming.Scope inner = BottomVisionTiming.begin("inner", "静拍")) {
                BottomVisionTiming.pass();
                inner.success();
            }
            BottomVisionTiming.pass();
            outer.success();
        }
        assertEquals(3, BottomVisionTiming.samples().size());
        assertEquals(2, BottomVisionTiming.samples().stream().filter(sample -> sample.partId.equals("outer")).findFirst().get().passes);
    }

    @Test
    void boundedHistoryDropsOldestSamples() {
        for (int index = 0; index < 1010; index++) {
            try (BottomVisionTiming.Scope scope = BottomVisionTiming.begin("P" + index, "静拍")) {
                scope.success();
            }
        }
        assertEquals(1000, BottomVisionTiming.samples().size());
        assertEquals("P10", BottomVisionTiming.samples().get(0).partId);
        assertEquals("P1009", BottomVisionTiming.samples().get(999).partId);
    }

    @Test
    void csvQuotesIdentifiersAndKeepsSpreadsheetCellsAsText() {
        try (BottomVisionTiming.Scope scope = BottomVisionTiming.begin("=1+1,\"P\"", "静拍")) {
            scope.success();
        }
        String csv = BottomVisionTiming.csv();
        assertTrue(csv.contains("\"'=1+1,\"\"P\"\"\""));
        assertTrue(csv.startsWith("part,mode,success"));
    }

    @Test
    void emptyAndMultiPassSummariesExplainExactlyWhatIsMeasured() {
        assertTrue(BottomVisionTiming.summary().contains("无需额外拍照"));
        try (BottomVisionTiming.Scope scope = BottomVisionTiming.begin("P", "静拍")) {
            BottomVisionTiming.pass();
            BottomVisionTiming.pass();
            scope.success();
        }
        String summary = BottomVisionTiming.summary();
        assertTrue(summary.contains("多轮修正：1"));
        assertTrue(summary.contains("移动指令准备/下发"));
        assertTrue(summary.contains("物理运动到位等待可能计入采图阶段"));
    }
}
