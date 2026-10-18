package org.openpnp.machine.reference.vision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.ImageCapture;

/**
 * Bounded, image-free timing of the existing static vision calls.
 */
public final class BottomVisionTiming {

    private static final int CAPACITY = 1000;

    private static final Deque<Sample> SAMPLES = new ArrayDeque<>();

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    public static final class Sample {

        public final String partId, mode;

        public final boolean successful;

        public final long totalNs, movementNs, captureNs, pipelineNs;

        public final int passes, shots;

        Sample(Scope scope, long totalNs) {
            partId = scope.partId;
            mode = scope.mode;
            successful = scope.successful;
            this.totalNs = totalNs;
            movementNs = scope.movementNs;
            captureNs = scope.captureNs;
            pipelineNs = scope.pipelineNs;
            passes = scope.passes;
            shots = scope.shots;
        }
    }

    public static final class Scope implements AutoCloseable {

        final String partId, mode;

        final long started = System.nanoTime();

        final Scope previous;

        boolean successful, closed;

        long movementNs, captureNs, pipelineNs;

        int passes, shots;

        private Scope(String partId, String mode) {
            this.partId = partId;
            this.mode = mode;
            previous = CURRENT.get();
            CURRENT.set(this);
        }

        public void success() {
            successful = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            CURRENT.set(previous);
            synchronized (SAMPLES) {
                SAMPLES.addLast(new Sample(this, Math.max(0, System.nanoTime() - started)));
                while (SAMPLES.size() > CAPACITY) {
                    SAMPLES.removeFirst();
                }
            }
        }
    }

    private BottomVisionTiming() {
    }

    public static Scope begin(String partId, String mode) {
        return new Scope(partId, mode);
    }

    public static void pass() {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.passes++;
        }
    }

    public static void movement(long nanoseconds) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.movementNs += Math.max(0, nanoseconds);
        }
    }

    public static void pipeline(CvPipeline pipeline) {
        Scope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        scope.shots++;
        for (CvStage stage : pipeline.getStages()) {
            CvStage.Result result = pipeline.getResult(stage);
            if (result == null) {
                continue;
            }
            long duration = Math.max(0, result.processingTimeNs);
            if (stage instanceof ImageCapture) {
                scope.captureNs += duration;
            } else {
                scope.pipelineNs += duration;
            }
        }
    }

    public static List<Sample> samples() {
        synchronized (SAMPLES) {
            return new ArrayList<>(SAMPLES);
        }
    }

    public static void clear() {
        synchronized (SAMPLES) {
            SAMPLES.clear();
        }
    }

    public static String summary() {
        List<Sample> samples = samples();
        if (samples.isEmpty()) {
            return "尚无静拍识别记录。下一次作业识别时自动统计，无需额外拍照。\n" + "统计只保留本次启动最近 1000 次静拍调用，不保存图像，不改变相机、运动或识别阈值。";
        }
        long total = 0, movement = 0, capture = 0, pipeline = 0;
        int failures = 0, passes = 0, shots = 0, repeated = 0;
        for (Sample sample : samples) {
            total += sample.totalNs;
            movement += sample.movementNs;
            capture += sample.captureNs;
            pipeline += sample.pipelineNs;
            passes += sample.passes;
            shots += sample.shots;
            failures += sample.successful ? 0 : 1;
            repeated += sample.passes > 1 ? 1 : 0;
        }
        double scale = 1e6 * samples.size();
        String advice = capture > total * .5 ? "主要耗时在采图、稳定或等待到位，优先检查相机稳定诊断和实际采集延迟。" : pipeline > total * .4 ? "流水线阶段占比较高，优先评估图像区域和重复处理步骤。" : "先结合单次记录及运动路径查看主要耗时；本统计不自动降低稳定或识别要求。";
        return String.format(Locale.ROOT, "静拍调用：%d 次；失败：%d 次；多轮修正：%d 次（%.1f%%）\n" + "平均总耗时：%.1f ms\n移动指令准备/下发：%.1f ms\n" + "采图与稳定阶段：%.1f ms\n其他流水线阶段：%.1f ms\n" + "平均处理轮次：%.2f；平均拍摄步骤：%.2f\n\n%s\n\n" + "移动指令可能异步执行，物理运动到位等待可能计入采图阶段；其他时间包含参数准备、结果处理和显示。" + "拍摄步骤与帧数不等同，多步骤合成不是多轮修正。这里只记录静拍，飞拍另有实验报告。", samples.size(), failures, repeated, 100. * repeated / samples.size(), total / scale, movement / scale, capture / scale, pipeline / scale, 1. * passes / samples.size(), 1. * shots / samples.size(), advice);
    }

    public static String csv() {
        StringBuilder csv = new StringBuilder("part,mode,success,total_ms,movement_submission_ms,capture_settle_ms,other_pipeline_ms,passes,shots\n");
        for (Sample sample : samples()) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%d,%d%n", quoted(sample.partId), quoted(sample.mode), sample.successful, sample.totalNs / 1e6, sample.movementNs / 1e6, sample.captureNs / 1e6, sample.pipelineNs / 1e6, sample.passes, sample.shots));
        }
        return csv.toString();
    }

    private static String quoted(String value) {
        // CSV cells are read by spreadsheet software; keep identifiers as text.
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
