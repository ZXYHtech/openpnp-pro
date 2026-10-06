/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure calculations and reporting for guarded motion performance tests. */
public final class MotionPerformanceReport {
    private MotionPerformanceReport() {
    }

    public static List<Double> speedPlan(double maximumSpeedFraction) {
        double maximum = clamp(maximumSpeedFraction, 0.10, 1.00);
        List<Double> speeds = new ArrayList<>();
        addDistinct(speeds, Math.max(0.10, maximum * 0.50));
        addDistinct(speeds, Math.max(0.10, maximum * 0.75));
        addDistinct(speeds, maximum);
        return speeds;
    }

    public static double predictedRouteMillis(double spanMm, int cycles, double speedFraction,
            double xFeedrateMmPerSecond, double xAccelerationMmPerSecond2,
            double yFeedrateMmPerSecond, double yAccelerationMmPerSecond2) {
        double x = axisRouteMillis(spanMm, cycles,
                xFeedrateMmPerSecond * speedFraction, xAccelerationMmPerSecond2);
        double y = axisRouteMillis(spanMm, cycles,
                yFeedrateMmPerSecond * speedFraction, yAccelerationMmPerSecond2);
        return x + y;
    }

    private static double axisRouteMillis(double spanMm, int cycles, double velocity,
            double acceleration) {
        if (spanMm <= 0 || cycles <= 0 || velocity <= 0 || acceleration <= 0) {
            return Double.NaN;
        }
        double oneCycleSeconds = segmentSeconds(spanMm, velocity, acceleration)
                + segmentSeconds(spanMm * 2, velocity, acceleration)
                + segmentSeconds(spanMm, velocity, acceleration);
        return oneCycleSeconds * cycles * 1000;
    }

    static double segmentSeconds(double distance, double velocity, double acceleration) {
        double accelerationDistance = velocity * velocity / acceleration;
        if (distance >= accelerationDistance) {
            return distance / velocity + velocity / acceleration;
        }
        return 2 * Math.sqrt(distance / acceleration);
    }

    public static String analyze(List<Result> results) {
        StringBuilder text = new StringBuilder();
        text.append("速度提升结论\r\n============\r\n");
        List<Result> queued = successful(results, Mode.CONTINUOUS_QUEUE);
        Result slow = queued.isEmpty() ? null : queued.get(0);
        Result fast = queued.isEmpty() ? null : queued.get(queued.size() - 1);
        Result waitEach = lastSuccessful(results, Mode.WAIT_EACH_SEGMENT);
        int item = 1;
        if (slow == null || fast == null) {
            text.append(item++).append(". 没有形成完整速度阶梯，不能推荐提速。\r\n");
        }
        else {
            double gain = improvementPercent(slow.elapsedMs, fast.elapsedMs);
            text.append(item++).append(". 从 ").append(percent(slow.speedFraction))
                    .append(" 提高到 ").append(percent(fast.speedFraction))
                    .append(" 后，同一路线耗时由 ").append(number(slow.elapsedMs))
                    .append(" ms 变为 ").append(number(fast.elapsedMs))
                    .append(" ms，吞吐提升 ").append(number(gain)).append("%。\r\n");
            if (queued.size() >= 3) {
                Result middle = queued.get(queued.size() - 2);
                double lastGain = improvementPercent(middle.elapsedMs, fast.elapsedMs);
                text.append(item++).append(". 最后一档速度带来的额外提升为 ")
                        .append(number(lastGain)).append("%；")
                        .append(lastGain < 5
                                ? "已接近平台区，继续提高最高速度意义很小，应优先提高加速度或减少等待。"
                                : "最高测试档仍有有效收益，可作为下一轮稳定性验证候选。")
                        .append("\r\n");
                Result candidate = lastGain < 5 ? middle : fast;
                text.append(item++).append(". 建议的下一轮候选速度比例为 ")
                        .append(percent(candidate.speedFraction)).append("（驱动：")
                        .append(candidate.driver).append("）。该数值只是复测起点，不会自动写入配置。\r\n");
            }
            if (Double.isFinite(fast.predictedMs) && fast.predictedMs > 0) {
                text.append(item++).append(". 最高档实测/理论效率为 ")
                        .append(number(fast.efficiencyPercent())).append("%；")
                        .append(fast.efficiencyPercent() < 65
                                ? "协议往返、控制器规划、加速度/加加速度或重复到位等待仍占较大比例。"
                                : "运动规划与配置理论值基本匹配。")
                        .append("\r\n");
            }
        }
        if (fast != null && waitEach != null) {
            double waitPenalty = fast.elapsedMs <= 0 ? 0
                    : (waitEach.elapsedMs / fast.elapsedMs - 1) * 100;
            text.append(item++).append(". 同一最高档逐段等待比连续队列慢 ")
                    .append(number(waitPenalty)).append("%；")
                    .append(waitPenalty > 10
                            ? "正式流程应减少不必要的 WaitForStillstand，并优先使用可验证的异步队列。"
                            : "逐段等待不是主要瓶颈，优先检查加速度、加加速度和控制器上限。")
                    .append("\r\n");
        }
        for (Result result : results) {
            if (result.error != null && !result.error.isEmpty()) {
                text.append(item++).append(". 测试失败：").append(result.label)
                        .append(" — ").append(result.error).append("\r\n");
            }
        }
        text.append("\r\n验证边界\r\n")
                .append("- 本测试测量软件提交、规划器等待和控制器报告的到位时间。\r\n")
                .append("- 没有编码器、加速度计或相机靶标时，不能声称测得真实丢步、振动、回差或物理稳定时间。\r\n")
                .append("- 建议速度不会自动写入轴配置；候选档必须继续通过声音、丢步、吸料和视觉稳定性验证。\r\n");
        return text.toString();
    }

    public static String csv(List<Result> results) {
        StringBuilder csv = new StringBuilder();
        csv.append("time,run,label,mode,driver,tool,span_mm,cycles,speed_fraction,")
                .append("route_distance_mm,elapsed_ms,command_submit_ms,completion_wait_ms,")
                .append("predicted_ms,efficiency_pct,average_speed_mm_s,error\n");
        for (Result result : results) {
            csv.append(quote(result.time)).append(',')
                    .append(result.run).append(',')
                    .append(quote(result.label)).append(',')
                    .append(quote(result.mode.name())).append(',')
                    .append(quote(result.driver)).append(',')
                    .append(quote(result.tool)).append(',')
                    .append(number(result.spanMm)).append(',')
                    .append(result.cycles).append(',')
                    .append(number(result.speedFraction)).append(',')
                    .append(number(result.routeDistanceMm)).append(',')
                    .append(number(result.elapsedMs)).append(',')
                    .append(number(result.commandSubmitMs)).append(',')
                    .append(number(result.completionWaitMs)).append(',')
                    .append(number(result.predictedMs)).append(',')
                    .append(number(result.efficiencyPercent())).append(',')
                    .append(number(result.averageSpeedMmPerSecond())).append(',')
                    .append(quote(result.error)).append('\n');
        }
        return csv.toString();
    }

    private static List<Result> successful(List<Result> results, Mode mode) {
        List<Result> selected = new ArrayList<>();
        for (Result result : results) {
            if (result.mode == mode && (result.error == null || result.error.isEmpty())
                    && result.elapsedMs > 0) {
                selected.add(result);
            }
        }
        return selected;
    }

    private static Result lastSuccessful(List<Result> results, Mode mode) {
        List<Result> selected = successful(results, mode);
        return selected.isEmpty() ? null : selected.get(selected.size() - 1);
    }

    private static double improvementPercent(double before, double after) {
        return before <= 0 ? 0 : (before - after) / before * 100;
    }

    private static void addDistinct(List<Double> values, double candidate) {
        double rounded = Math.round(candidate * 1000) / 1000.0;
        if (values.isEmpty() || Math.abs(values.get(values.size() - 1) - rounded) > 0.0001) {
            values.add(rounded);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String percent(double fraction) {
        return number(fraction * 100) + "%";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "";
    }

    private static String quote(String value) {
        return "\"" + String.valueOf(value == null ? "" : value).replace("\"", "\"\"") + "\"";
    }

    public enum Mode {
        CONTINUOUS_QUEUE,
        WAIT_EACH_SEGMENT
    }

    public static class Result {
        public String time = "";
        public int run;
        public String label = "";
        public Mode mode = Mode.CONTINUOUS_QUEUE;
        public String driver = "";
        public String tool = "";
        public double spanMm;
        public int cycles;
        public double speedFraction;
        public double routeDistanceMm;
        public double elapsedMs;
        public double commandSubmitMs;
        public double completionWaitMs;
        public double predictedMs;
        public String error = "";

        public double efficiencyPercent() {
            return elapsedMs <= 0 || !Double.isFinite(predictedMs)
                    ? Double.NaN : predictedMs / elapsedMs * 100;
        }

        public double averageSpeedMmPerSecond() {
            return elapsedMs <= 0 ? Double.NaN : routeDistanceMm * 1000 / elapsedMs;
        }
    }
}
