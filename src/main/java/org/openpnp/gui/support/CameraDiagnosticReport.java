/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Defines the repeatable camera comparison matrix and converts its samples into portable reports.
 * It deliberately contains no camera control code, so report logic can be tested without hardware.
 */
public final class CameraDiagnosticReport {
    public static final int WARMUP_SECONDS = 4;
    public static final int SAMPLE_SECONDS = 10;

    private CameraDiagnosticReport() {
    }

    public static List<Phase> standardPlan(int cameraCount) {
        List<Phase> phases = new ArrayList<>();
        phases.add(new Phase("single_30_30", "单相机 30采集 / 30显示", false, 30, 30));
        phases.add(new Phase("single_30_15", "单相机 30采集 / 15显示", false, 30, 15));
        if (cameraCount > 1) {
            phases.add(new Phase("all_30_15", "全部相机 30采集 / 15显示", true, 30, 15));
            phases.add(new Phase("all_15_15", "全部相机 15采集 / 15显示", true, 15, 15));
        }
        return phases;
    }

    public static List<Summary> summarize(List<Sample> samples) {
        Map<String, Accumulator> groups = new LinkedHashMap<>();
        for (Sample sample : samples) {
            String key = sample.phaseId + "\u0000" + sample.camera;
            groups.computeIfAbsent(key, ignored -> new Accumulator(sample)).add(sample);
        }
        List<Summary> summaries = new ArrayList<>();
        for (Accumulator accumulator : groups.values()) {
            summaries.add(accumulator.finish());
        }
        return summaries;
    }

    public static String timeSeriesCsv(List<Sample> samples) {
        StringBuilder csv = new StringBuilder();
        csv.append("time,phase_id,phase_name,phase_index,phase_total,mode,sample_second,")
                .append("camera,primary_camera,looking,driver,configured_resolution,")
                .append("output_resolution,format,capture_target_fps,display_target_fps,")
                .append("capture_fps,display_fps,raw_capture_avg_ms,total_capture_avg_ms,")
                .append("transform_avg_ms,preview_loop_avg_ms,render_avg_ms,")
                .append("frame_interval_avg_ms,jitter_ms,max_stall_ms,received_frames,")
                .append("displayed_frames,dropped_frames,duplicate_frames,process_cpu_pct,")
                .append("system_cpu_pct,heap_used_mb,exposure,gain,brightness,light,error\n");
        for (Sample sample : samples) {
            appendCommon(csv, sample);
            csv.append(number(sample.captureFps)).append(',')
                    .append(number(sample.displayFps)).append(',')
                    .append(number(sample.rawCaptureAverageMs)).append(',')
                    .append(number(sample.captureAverageMs)).append(',')
                    .append(number(sample.processingAverageMs)).append(',')
                    .append(number(sample.previewLoopAverageMs)).append(',')
                    .append(number(sample.renderAverageMs)).append(',')
                    .append(number(sample.frameIntervalAverageMs)).append(',')
                    .append(number(sample.jitterMs)).append(',')
                    .append(number(sample.maximumStallMs)).append(',')
                    .append(sample.receivedFrames).append(',')
                    .append(sample.displayedFrames).append(',')
                    .append(sample.droppedFrames).append(',')
                    .append(sample.duplicateFrames).append(',')
                    .append(number(sample.processCpuPercent)).append(',')
                    .append(number(sample.systemCpuPercent)).append(',')
                    .append(number(sample.heapUsedMb)).append(',')
                    .append(csv(sample.exposure)).append(',')
                    .append(csv(sample.gain)).append(',')
                    .append(csv(sample.brightness)).append(',')
                    .append(csv(sample.light)).append(',')
                    .append(csv(sample.error)).append('\n');
        }
        return csv.toString();
    }

    public static String summaryCsv(List<Summary> summaries) {
        StringBuilder csv = new StringBuilder();
        csv.append("phase_id,phase_name,mode,camera,primary_camera,capture_target_fps,")
                .append("display_target_fps,samples,capture_fps_avg,display_fps_avg,")
                .append("raw_capture_avg_ms,total_capture_avg_ms,transform_avg_ms,")
                .append("preview_loop_avg_ms,render_avg_ms,frame_interval_avg_ms,")
                .append("jitter_avg_ms,max_stall_ms,received_frames,displayed_frames,")
                .append("dropped_frames,duplicate_frames,drop_pct,process_cpu_avg_pct,")
                .append("system_cpu_avg_pct,heap_used_avg_mb,error\n");
        for (Summary summary : summaries) {
            csv.append(csv(summary.phaseId)).append(',')
                    .append(csv(summary.phaseName)).append(',')
                    .append(csv(summary.allCameras ? "all" : "single")).append(',')
                    .append(csv(summary.camera)).append(',')
                    .append(summary.primaryCamera).append(',')
                    .append(number(summary.captureTargetFps)).append(',')
                    .append(number(summary.displayTargetFps)).append(',')
                    .append(summary.samples).append(',')
                    .append(number(summary.captureFps)).append(',')
                    .append(number(summary.displayFps)).append(',')
                    .append(number(summary.rawCaptureAverageMs)).append(',')
                    .append(number(summary.captureAverageMs)).append(',')
                    .append(number(summary.processingAverageMs)).append(',')
                    .append(number(summary.previewLoopAverageMs)).append(',')
                    .append(number(summary.renderAverageMs)).append(',')
                    .append(number(summary.frameIntervalAverageMs)).append(',')
                    .append(number(summary.jitterMs)).append(',')
                    .append(number(summary.maximumStallMs)).append(',')
                    .append(summary.receivedFrames).append(',')
                    .append(summary.displayedFrames).append(',')
                    .append(summary.droppedFrames).append(',')
                    .append(summary.duplicateFrames).append(',')
                    .append(number(summary.dropPercent())).append(',')
                    .append(number(summary.processCpuPercent)).append(',')
                    .append(number(summary.systemCpuPercent)).append(',')
                    .append(number(summary.heapUsedMb)).append(',')
                    .append(csv(summary.error)).append('\n');
        }
        return csv.toString();
    }

    public static String analysisText(List<Summary> summaries, String environment) {
        StringBuilder report = new StringBuilder();
        report.append("OpenPnP Pro 相机一键完整诊断\r\n")
                .append("================================\r\n\r\n")
                .append("测试方法：每阶段预热 ").append(WARMUP_SECONDS)
                .append(" 秒，随后逐秒采样 ").append(SAMPLE_SECONDS)
                .append(" 秒。诊断不移动机器、不运行视觉 Pipeline、不会主动改变灯光。\r\n\r\n")
                .append("运行环境\r\n").append(environment).append("\r\n\r\n")
                .append("自动结论\r\n");

        Summary baseline = primary(summaries, "single_30_30");
        Summary lightDisplay = primary(summaries, "single_30_15");
        Summary allFull = primary(summaries, "all_30_15");
        Summary allReduced = primary(summaries, "all_15_15");
        int finding = 1;
        if (baseline == null) {
            report.append(finding++).append(". 未获得单相机基线，检查相机打开错误。\r\n");
        }
        else {
            double targetGap = percentGap(baseline.captureTargetFps, baseline.captureFps);
            report.append(finding++).append(". 单相机基线接收 ")
                    .append(number(baseline.captureFps)).append(" FPS，距目标 ")
                    .append(number(targetGap)).append("%；抖动 ")
                    .append(number(baseline.jitterMs)).append(" ms，最大卡顿 ")
                    .append(number(baseline.maximumStallMs)).append(" ms。\r\n");
            if (baseline.captureAverageMs > 0 && baseline.processingAverageMs > 0) {
                double share = baseline.processingAverageMs / baseline.captureAverageMs * 100;
                report.append(finding++).append(". 图像变换占完整采集耗时约 ")
                        .append(number(share)).append("%；")
                        .append(share >= 50 ? "这是当前最明显的软件处理热点。"
                                : "暂未显示为压倒性的处理热点。")
                        .append("\r\n");
            }
            if (baseline.dropPercent() >= 5 || (baseline.captureFps > 0
                    && baseline.displayFps / baseline.captureFps < 0.9)) {
                report.append(finding++).append(". 30 FPS 显示阶段主动丢弃 ")
                        .append(number(baseline.dropPercent()))
                        .append("% 的预览帧；这是 UI 保持低延迟的主动覆盖，不是 USB 真丢帧。\r\n");
            }
        }
        if (baseline != null && lightDisplay != null) {
            double captureChange = percentChange(baseline.captureFps, lightDisplay.captureFps);
            double jitterChange = percentChange(baseline.jitterMs, lightDisplay.jitterMs);
            report.append(finding++).append(". 显示从 30 降到 15 FPS 后，采集变化 ")
                    .append(signed(captureChange)).append("%，抖动变化 ")
                    .append(signed(jitterChange)).append("%。")
                    .append(captureChange > 5 || jitterChange < -15
                            ? "降低界面刷新能释放明显余量，且不降低视觉采集目标。"
                            : "界面刷新不是主要采集瓶颈。")
                    .append("\r\n");
        }
        if (lightDisplay != null && allFull != null) {
            double captureChange = percentChange(lightDisplay.captureFps, allFull.captureFps);
            double jitterChange = percentChange(lightDisplay.jitterMs, allFull.jitterMs);
            report.append(finding++).append(". 全部相机同时 30 FPS 后，主相机采集变化 ")
                    .append(signed(captureChange)).append("%，抖动变化 ")
                    .append(signed(jitterChange)).append("%。")
                    .append(captureChange < -10 || jitterChange > 25
                            ? "存在 USB 带宽、解码或 CPU 竞争，需要结合各相机行定位。"
                            : "未发现明显的多相机竞争。")
                    .append("\r\n");
        }
        if (allFull != null && allReduced != null) {
            report.append(finding++).append(". 全部相机降到 15 FPS 后，主相机抖动由 ")
                    .append(number(allFull.jitterMs)).append(" ms 变为 ")
                    .append(number(allReduced.jitterMs)).append(" ms；")
                    .append(allReduced.jitterMs < allFull.jitterMs * 0.75
                            ? "降低总带宽能显著改善稳定性。"
                            : "降低总带宽改善有限，优先检查图像变换和驱动调度。")
                    .append("\r\n");
        }
        for (Summary summary : summaries) {
            if (summary.error != null && !summary.error.isEmpty()) {
                report.append(finding++).append(". ").append(summary.phaseName).append(" / ")
                        .append(summary.camera).append("：").append(summary.error).append("\r\n");
            }
        }
        report.append("\r\n说明：summary.csv 用于横向对比；timeseries.csv 用于定位具体秒级卡顿。\r\n");
        return report.toString();
    }

    private static void appendCommon(StringBuilder csv, Sample sample) {
        csv.append(csv(sample.time)).append(',')
                .append(csv(sample.phaseId)).append(',')
                .append(csv(sample.phaseName)).append(',')
                .append(sample.phaseIndex).append(',')
                .append(sample.phaseTotal).append(',')
                .append(csv(sample.allCameras ? "all" : "single")).append(',')
                .append(sample.sampleSecond).append(',')
                .append(csv(sample.camera)).append(',')
                .append(sample.primaryCamera).append(',')
                .append(csv(sample.looking)).append(',')
                .append(csv(sample.driver)).append(',')
                .append(csv(sample.configuredResolution)).append(',')
                .append(csv(sample.outputResolution)).append(',')
                .append(csv(sample.format)).append(',')
                .append(number(sample.captureTargetFps)).append(',')
                .append(number(sample.displayTargetFps)).append(',');
    }

    private static Summary primary(List<Summary> summaries, String phaseId) {
        for (Summary summary : summaries) {
            if (summary.primaryCamera && phaseId.equals(summary.phaseId)) {
                return summary;
            }
        }
        return null;
    }

    private static double percentGap(double target, double actual) {
        return target <= 0 ? 0 : Math.max(0, (target - actual) / target * 100);
    }

    private static double percentChange(double before, double after) {
        return Math.abs(before) < 0.000001 ? 0 : (after - before) / before * 100;
    }

    private static String signed(double value) {
        return (value >= 0 ? "+" : "") + number(value);
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "";
    }

    private static String csv(String value) {
        return "\"" + String.valueOf(value == null ? "" : value).replace("\"", "\"\"") + "\"";
    }

    public static final class Phase {
        public final String id;
        public final String name;
        public final boolean allCameras;
        public final double captureFps;
        public final double displayFps;

        public Phase(String id, String name, boolean allCameras, double captureFps,
                double displayFps) {
            this.id = id;
            this.name = name;
            this.allCameras = allCameras;
            this.captureFps = captureFps;
            this.displayFps = displayFps;
        }
    }

    public static class Sample {
        public String time = "";
        public String phaseId = "";
        public String phaseName = "";
        public int phaseIndex;
        public int phaseTotal;
        public boolean allCameras;
        public int sampleSecond;
        public String camera = "";
        public boolean primaryCamera;
        public String looking = "";
        public String driver = "";
        public String configuredResolution = "";
        public String outputResolution = "";
        public String format = "";
        public double captureTargetFps;
        public double displayTargetFps;
        public double captureFps;
        public double displayFps;
        public double rawCaptureAverageMs;
        public double captureAverageMs;
        public double processingAverageMs;
        public double previewLoopAverageMs;
        public double renderAverageMs;
        public double frameIntervalAverageMs;
        public double jitterMs;
        public double maximumStallMs;
        public long receivedFrames;
        public long displayedFrames;
        public long droppedFrames;
        public long duplicateFrames;
        public double processCpuPercent;
        public double systemCpuPercent;
        public double heapUsedMb;
        public String exposure = "";
        public String gain = "";
        public String brightness = "";
        public String light = "";
        public String error = "";
    }

    public static class Summary extends Sample {
        public int samples;

        public double dropPercent() {
            return receivedFrames <= 0 ? 0 : droppedFrames * 100.0 / receivedFrames;
        }
    }

    private static final class Accumulator {
        private final Summary summary = new Summary();
        private int count;

        Accumulator(Sample first) {
            summary.phaseId = first.phaseId;
            summary.phaseName = first.phaseName;
            summary.allCameras = first.allCameras;
            summary.camera = first.camera;
            summary.primaryCamera = first.primaryCamera;
            summary.captureTargetFps = first.captureTargetFps;
            summary.displayTargetFps = first.displayTargetFps;
        }

        void add(Sample value) {
            count++;
            summary.captureFps += finite(value.captureFps);
            summary.displayFps += finite(value.displayFps);
            summary.rawCaptureAverageMs += finite(value.rawCaptureAverageMs);
            summary.captureAverageMs += finite(value.captureAverageMs);
            summary.processingAverageMs += finite(value.processingAverageMs);
            summary.previewLoopAverageMs += finite(value.previewLoopAverageMs);
            summary.renderAverageMs += finite(value.renderAverageMs);
            summary.frameIntervalAverageMs += finite(value.frameIntervalAverageMs);
            summary.jitterMs += finite(value.jitterMs);
            summary.maximumStallMs = Math.max(summary.maximumStallMs, value.maximumStallMs);
            summary.processCpuPercent += finite(value.processCpuPercent);
            summary.systemCpuPercent += finite(value.systemCpuPercent);
            summary.heapUsedMb += finite(value.heapUsedMb);
            summary.receivedFrames = Math.max(summary.receivedFrames, value.receivedFrames);
            summary.displayedFrames = Math.max(summary.displayedFrames, value.displayedFrames);
            summary.droppedFrames = Math.max(summary.droppedFrames, value.droppedFrames);
            summary.duplicateFrames = Math.max(summary.duplicateFrames, value.duplicateFrames);
            if (value.error != null && !value.error.isEmpty()) {
                summary.error = value.error;
            }
        }

        Summary finish() {
            summary.samples = count;
            if (count > 0) {
                summary.captureFps /= count;
                summary.displayFps /= count;
                summary.rawCaptureAverageMs /= count;
                summary.captureAverageMs /= count;
                summary.processingAverageMs /= count;
                summary.previewLoopAverageMs /= count;
                summary.renderAverageMs /= count;
                summary.frameIntervalAverageMs /= count;
                summary.jitterMs /= count;
                summary.processCpuPercent /= count;
                summary.systemCpuPercent /= count;
                summary.heapUsedMb /= count;
            }
            return summary;
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0;
        }
    }
}
