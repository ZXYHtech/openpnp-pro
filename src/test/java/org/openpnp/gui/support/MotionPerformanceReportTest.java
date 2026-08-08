package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.MotionPerformanceReport.Mode;
import org.openpnp.gui.support.MotionPerformanceReport.Result;

class MotionPerformanceReportTest {
    @Test
    void buildsThreeIncreasingSpeedLevels() {
        List<Double> speeds = MotionPerformanceReport.speedPlan(0.60);
        assertEquals(Arrays.asList(0.30, 0.45, 0.60), speeds);
    }

    @Test
    void predictsTriangularAndTrapezoidalRoutes() {
        assertEquals(0.2, MotionPerformanceReport.segmentSeconds(10, 100, 1000), 0.0001);
        assertEquals(1.1, MotionPerformanceReport.segmentSeconds(100, 100, 1000), 0.0001);
        assertEquals(1400, MotionPerformanceReport.predictedRouteMillis(
                10, 1, 1, 100, 1000, 100, 1000), 0.0001);
    }

    @Test
    void exportsAndExplainsSpeedPlateauAndWaitPenalty() {
        Result slow = result(1, "低速", Mode.CONTINUOUS_QUEUE, 0.3, 1500, 900);
        Result middle = result(2, "中速", Mode.CONTINUOUS_QUEUE, 0.45, 1100, 800);
        Result fast = result(3, "高速", Mode.CONTINUOUS_QUEUE, 0.6, 1080, 760);
        Result wait = result(4, "逐段等待", Mode.WAIT_EACH_SEGMENT, 0.6, 1450, 760);
        List<Result> results = Arrays.asList(slow, middle, fast, wait);

        String analysis = MotionPerformanceReport.analyze(results);
        assertTrue(analysis.contains("已接近平台区"));
        assertTrue(analysis.contains("候选速度比例为 45.000%"));
        assertTrue(analysis.contains("减少不必要的 WaitForStillstand"));
        assertTrue(analysis.contains("不能声称测得真实丢步"));

        String csv = MotionPerformanceReport.csv(results);
        assertTrue(csv.contains("command_submit_ms"));
        assertTrue(csv.contains("WAIT_EACH_SEGMENT"));
    }

    private static Result result(int run, String label, Mode mode, double speed,
            double elapsed, double predicted) {
        Result result = new Result();
        result.run = run;
        result.label = label;
        result.mode = mode;
        result.speedFraction = speed;
        result.elapsedMs = elapsed;
        result.predictedMs = predicted;
        result.routeDistanceMm = 160;
        result.driver = "GcodeAsyncDriver";
        return result;
    }
}
