/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.Timer;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/** Records live and historical production metrics for the production dashboard. */
public class JobTelemetryManager {
    private static final int MAX_HISTORY = 100;
    private static final long DUPLICATE_ERROR_WINDOW_MS = 1500;
    private static final Gson COPY_GSON = new Gson();

    public interface Listener {
        void telemetryChanged();
    }

    public static class ErrorRecord {
        public long time;
        public String title;
        public String message;
        public String category;
        public String source;
        public String suggestion;
        public String runId;
        public String jobName;
        public String jobPath;
        public long runStartTime;
        public long activeMillisAtError;
        public long estimatedLostMillis;
        public int placedPlacementsAtError;
    }

    public static class BottleneckInsight {
        public String title;
        public String evidence;
        public String suggestion;
        public long estimatedDelayMillis;
        public int occurrences;
    }

    public static class JobRunRecord {
        public String id;
        public String jobName;
        public String jobPath;
        public long startTime;
        public long endTime;
        public long elapsedMillis;
        public long activeMillis;
        public long pausedMillis;
        public int boardCount;
        public int completedBoards;
        public int totalPlacements;
        public int placedPlacements;
        public int remainingPlacements;
        public int uniqueParts;
        public double averageCph;
        public double placementsPerBoard;
        public double feederCoveragePercent;
        public boolean completed;
        public boolean running;
        public String state;
        public int runCount = 1;
        public long firstRunStartTime;
        public long lastRunEndTime;
        public long initialEstimatedDurationMillis;
        public long currentEstimatedDurationMillis;
        public long estimatedCompletionTime;
        public long predictionDeviationMillis;
        public double predictionAccuracyPercent;
        public double predictionConfidencePercent;
        public int predictionSampleCount;
        public Map<String, Long> stageMillis = new LinkedHashMap<>();
        public Map<String, Integer> stageCounts = new LinkedHashMap<>();
        public Map<String, Long> sourceMillis = new LinkedHashMap<>();
        public Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        public Map<String, Long> predictedStageMillis = new LinkedHashMap<>();
        public List<ErrorRecord> errors = new ArrayList<>();
        public transient List<JobRunRecord> sourceRuns = new ArrayList<>();

        public int getErrorCount() {
            return errors == null ? 0 : errors.size();
        }

        public double getProgressPercent() {
            return totalPlacements == 0 ? 0 : placedPlacements * 100.0 / totalPlacements;
        }

        public long getEstimatedCompletionTime() {
            if (!running || remainingPlacements <= 0) {
                return 0;
            }
            if (estimatedCompletionTime > 0) {
                return estimatedCompletionTime;
            }
            if (placedPlacements <= 0 || activeMillis <= 0) {
                return 0;
            }
            return System.currentTimeMillis()
                    + Math.round(remainingPlacements * (activeMillis / (double) placedPlacements));
        }

        public int getRunCount() {
            return Math.max(1, runCount);
        }

        public long getEstimatedDurationMillis() {
            return currentEstimatedDurationMillis > 0 ? currentEstimatedDurationMillis
                    : initialEstimatedDurationMillis;
        }
    }

    private final Configuration configuration;
    private final File historyFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<JobRunRecord> history = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Timer timer;
    private final MessageBoxes.ErrorListener errorListener = this::recordError;

    private JobRunRecord current;
    private Job currentJob;
    private long pauseStartedAt;
    private String lastErrorKey;
    private long lastErrorAt;
    private String currentStage;
    private String currentStageSource;
    private long currentStageStartedAt;

    public JobTelemetryManager(Configuration configuration) {
        this.configuration = configuration;
        historyFile = new File(configuration.getConfigurationDirectory(),
                "openpnp-pro-job-history.json");
        loadHistory();
        MessageBoxes.addErrorListener(errorListener);
        timer = new Timer(1000, event -> tick());
        timer.start();
    }

    public synchronized void start(Job job) {
        if (current != null && current.running) {
            finish(currentJob, false);
        }
        currentJob = job;
        current = new JobRunRecord();
        current.id = UUID.randomUUID().toString();
        current.startTime = System.currentTimeMillis();
        current.jobPath = job.getFile() == null ? null : job.getFile().getAbsolutePath();
        current.jobName = job.getFile() == null ? "未命名任务" : job.getFile().getName();
        current.running = true;
        current.state = "运行中";
        current.runCount = 1;
        current.firstRunStartTime = current.startTime;
        pauseStartedAt = 0;
        currentStage = "任务准备";
        currentStageSource = null;
        currentStageStartedAt = current.startTime;
        updateCounts(job, current);
        initializePrediction(current);
        updatePrediction(current, current.startTime);
        fireChanged();
    }

    public synchronized void stateChanged(String state, Job job) {
        if (current == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if ("Paused".equals(state) || "Pausing".equals(state)) {
            if (pauseStartedAt == 0) {
                pauseStartedAt = now;
            }
            current.state = "已暂停";
        }
        else if ("Running".equals(state)) {
            if (pauseStartedAt > 0) {
                current.pausedMillis += now - pauseStartedAt;
                pauseStartedAt = 0;
            }
            current.state = "运行中";
        }
        else if ("Stopping".equals(state)) {
            current.state = "正在停止";
        }
        else if ("Stopped".equals(state) && current.running) {
            finish(job, true);
            return;
        }
        updateLiveTimes(now);
        fireChanged();
    }

    public synchronized void recordError(String title, Throwable cause) {
        String message = cause == null ? "未知错误"
                : cause.getMessage() == null ? cause.toString() : cause.getMessage();
        recordError(title, message, sourceFromThrowable(cause));
    }

    public synchronized void recordError(String title, String message) {
        recordError(title, message, null);
    }

    private synchronized void recordError(String title, String message, String structuredSource) {
        if (current == null || !current.running) {
            return;
        }
        String safeTitle = title == null ? "错误" : title;
        String safeMessage = message == null ? "未知错误" : stripHtml(message);
        String key = safeMessage;
        long now = System.currentTimeMillis();
        if (key.equals(lastErrorKey) && now - lastErrorAt < DUPLICATE_ERROR_WINDOW_MS) {
            return;
        }
        lastErrorKey = key;
        lastErrorAt = now;
        ErrorRecord error = analyzeError(safeTitle, safeMessage);
        error.time = now;
        error.runId = current.id;
        error.jobName = current.jobName;
        error.jobPath = current.jobPath;
        error.runStartTime = current.startTime;
        error.activeMillisAtError = current.activeMillis;
        error.placedPlacementsAtError = current.placedPlacements;
        error.source = structuredSource == null
                ? identifyErrorSource(safeTitle + " " + safeMessage) : structuredSource;
        error.estimatedLostMillis = estimatedLossFor(error.category);
        current.errors.add(error);
        saveCurrentProgress();
        fireChanged();
    }

    private ErrorRecord analyzeError(String title, String message) {
        ErrorRecord result = new ErrorRecord();
        result.title = title;
        result.message = message;
        String text = (title + " " + message).toLowerCase(Locale.ROOT);
        if (containsAny(text, "vision", "alignment", "camera", "识别", "视觉", "对位")) {
            result.category = "视觉与对位";
            result.suggestion = "检查光源、曝光、模板阈值、相机标定和器件高度；清洁镜头后重试。";
        }
        else if (containsAny(text, "vacuum", "pick", "no part", "吸取", "真空", "取料")) {
            result.category = "取料与真空";
            result.suggestion = "检查飞达出料位置、吸嘴型号、真空阈值、吸取高度及物料是否翻转或卡料。";
        }
        else if (containsAny(text, "feeder", "feed", "飞达", "送料")) {
            result.category = "飞达送料";
            result.suggestion = "检查飞达启用状态、料带间距、送料步长、余料和机械卡滞。";
        }
        else if (containsAny(text, "timeout", "serial", "communication", "connect", "超时",
                "串口", "通信", "连接")) {
            result.category = "通信连接";
            result.suggestion = "检查串口/网络连接、电源、线缆和控制器日志，降低通信频率后重试。";
        }
        else if (containsAny(text, "motion", "limit", "home", "position", "运动", "限位",
                "回零", "坐标")) {
            result.category = "运动与坐标";
            result.suggestion = "检查回零、软限位、板原点、安全高度和轴方向，确认路径不会超出行程。";
        }
        else if (containsAny(text, "save", "file", "xml", "保存", "文件", "配置")) {
            result.category = "文件与配置";
            result.suggestion = "检查文件路径、写入权限、配置完整性及自动恢复目录中的最近快照。";
        }
        else {
            result.category = "其他异常";
            result.suggestion = "结合错误发生步骤、日志和摄像头画面复现；记录器件、吸嘴和飞达位置后排查。";
        }
        return result;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String stripHtml(String value) {
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String sourceFromThrowable(Throwable cause) {
        for (Throwable cursor = cause; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof org.openpnp.spi.JobProcessor.JobProcessorException) {
                Object source = ((org.openpnp.spi.JobProcessor.JobProcessorException) cursor)
                        .getSource();
                if (source instanceof Feeder) {
                    return "飞达：" + ((Feeder) source).getName();
                }
                if (source instanceof org.openpnp.spi.Nozzle) {
                    return "吸嘴：" + ((org.openpnp.spi.Nozzle) source).getName();
                }
                if (source instanceof org.openpnp.model.Part) {
                    return "物料：" + ((org.openpnp.model.Part) source).getId();
                }
            }
        }
        return null;
    }
    private String identifyErrorSource(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (configuration.getMachine() != null) {
            for (Feeder feeder : configuration.getMachine().getFeeders()) {
                String name = feeder.getName();
                String id = feeder.getId();
                if (name != null && !name.trim().isEmpty()
                        && normalized.contains(name.toLowerCase(Locale.ROOT))) {
                    return "飞达：" + name;
                }
                if (id != null && !id.trim().isEmpty()
                        && normalized.contains(id.toLowerCase(Locale.ROOT))) {
                    return "飞达：" + (name == null || name.trim().isEmpty() ? id : name);
                }
            }
        }
        return null;
    }

    private long estimatedLossFor(String category) {
        if ("飞达送料".equals(category)) {
            return 75000;
        }
        if ("取料与真空".equals(category)) {
            return 55000;
        }
        if ("视觉与对位".equals(category)) {
            return 30000;
        }
        if ("运动与坐标".equals(category) || "通信连接".equals(category)) {
            return 90000;
        }
        return 45000;
    }

    /** Records processor status transitions so prediction drift can be attributed to real stages. */
    public synchronized void recordStatus(String text) {
        if (current == null || !current.running || text == null || text.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        closeCurrentStage(now);
        StageInfo info = classifyStatus(text.trim());
        currentStage = info.stage;
        currentStageSource = info.source;
        currentStageStartedAt = now;
        current.stageCounts.put(info.stage,
                current.stageCounts.getOrDefault(info.stage, 0) + 1);
        if (info.source != null) {
            current.sourceCounts.put(info.source,
                    current.sourceCounts.getOrDefault(info.source, 0) + 1);
        }
    }

    private void closeCurrentStage(long now) {
        if (current == null || currentStage == null || currentStageStartedAt <= 0) {
            return;
        }
        long duration = Math.max(0, now - currentStageStartedAt);
        current.stageMillis.put(currentStage,
                current.stageMillis.getOrDefault(currentStage, 0L) + duration);
        if (currentStageSource != null) {
            current.sourceMillis.put(currentStageSource,
                    current.sourceMillis.getOrDefault(currentStageSource, 0L) + duration);
        }
        currentStageStartedAt = now;
    }

    private StageInfo classifyStatus(String status) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^Feed (.+) on (.+)\\.$").matcher(status);
        if (matcher.matches()) {
            return new StageInfo("飞达送料", "飞达：" + matcher.group(1));
        }
        matcher = java.util.regex.Pattern.compile(
                "^Pick (.+?) from (.+?) for (.+?) using nozzle (.+)\\.$").matcher(status);
        if (matcher.matches()) {
            return new StageInfo("取料", "飞达：" + matcher.group(2));
        }
        matcher = java.util.regex.Pattern.compile(
                "^Aligning (.+?) for (.+?) using nozzle (.+)\\.$").matcher(status);
        if (matcher.matches()) {
            return new StageInfo("视觉对位", "对位物料：" + matcher.group(1));
        }
        matcher = java.util.regex.Pattern.compile(
                "^Placing (.+?) for (.+?) using nozzle (.+)\\.$").matcher(status);
        if (matcher.matches()) {
            return new StageInfo("贴装", "贴装物料：" + matcher.group(1));
        }
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("fiducial")) {
            return new StageInfo("Mark对位", null);
        }
        if (lower.contains("nozzle tip") || lower.contains("calibrate nozzle")) {
            return new StageInfo("吸嘴准备", null);
        }
        if (lower.contains("preparing feeder")) {
            return new StageInfo("飞达准备", null);
        }
        if (lower.contains("planning")) {
            return new StageInfo("任务规划", null);
        }
        if (lower.contains("cleaning") || lower.contains("park head")) {
            return new StageInfo("收尾停靠", null);
        }
        if (lower.contains("finished") || lower.contains("aborted")) {
            return new StageInfo("任务结束", null);
        }
        return new StageInfo("机器准备", null);
    }

    private static class StageInfo {
        final String stage;
        final String source;

        StageInfo(String stage, String source) {
            this.stage = stage;
            this.source = source;
        }
    }

    private void initializePrediction(JobRunRecord record) {
        List<JobRunRecord> sameJob = new ArrayList<>();
        List<JobRunRecord> completedRuns = new ArrayList<>();
        String identity = jobIdentity(record);
        for (JobRunRecord previous : history) {
            if (!previous.completed || previous.elapsedMillis <= 0
                    || previous.placedPlacements <= 0) {
                continue;
            }
            completedRuns.add(previous);
            if (!identity.isEmpty() && identity.equals(jobIdentity(previous))) {
                sameJob.add(previous);
            }
        }
        List<JobRunRecord> baseline = sameJob.isEmpty() ? completedRuns : sameJob;
        long totalElapsed = 0;
        long totalPlacements = 0;
        Map<String, Long> normalizedStages = new LinkedHashMap<>();
        for (JobRunRecord previous : baseline) {
            totalElapsed += previous.elapsedMillis;
            totalPlacements += previous.placedPlacements;
            if (previous.stageMillis != null) {
                for (Map.Entry<String, Long> entry : previous.stageMillis.entrySet()) {
                    long normalized = Math.round(entry.getValue()
                            * record.totalPlacements
                            / (double) Math.max(1, previous.placedPlacements));
                    normalizedStages.put(entry.getKey(),
                            normalizedStages.getOrDefault(entry.getKey(), 0L) + normalized);
                }
            }
        }
        record.predictionSampleCount = baseline.size();
        if (totalPlacements > 0) {
            record.initialEstimatedDurationMillis = Math.max(1000,
                    Math.round(totalElapsed * record.totalPlacements
                            / (double) totalPlacements));
            for (Map.Entry<String, Long> entry : normalizedStages.entrySet()) {
                record.predictedStageMillis.put(entry.getKey(),
                        Math.round(entry.getValue() / (double) baseline.size()));
            }
            record.predictionConfidencePercent = sameJob.isEmpty()
                    ? Math.min(65, 25 + baseline.size() * 5)
                    : Math.min(95, 55 + baseline.size() * 10);
        }
        else {
            // A conservative seed is replaced progressively by the live rate. It is marked with
            // low confidence so operators do not mistake a first-run estimate for learned data.
            record.initialEstimatedDurationMillis = 15000L
                    + Math.max(1, record.totalPlacements) * 2500L;
            record.predictionConfidencePercent = 15;
        }
        record.currentEstimatedDurationMillis = record.initialEstimatedDurationMillis;
    }

    private void updatePrediction(JobRunRecord record, long now) {
        if (record.totalPlacements <= 0 || record.initialEstimatedDurationMillis <= 0) {
            return;
        }
        double progress = Math.min(1, record.placedPlacements
                / (double) Math.max(1, record.totalPlacements));
        long estimate = record.initialEstimatedDurationMillis;
        if (record.placedPlacements >= 2 && record.elapsedMillis > 0 && progress > 0) {
            long liveProjection = Math.round(record.elapsedMillis / progress);
            double liveWeight = record.predictionSampleCount > 0
                    ? Math.min(0.65, progress) : Math.min(0.88, 0.25 + progress);
            estimate = Math.round(record.initialEstimatedDurationMillis * (1 - liveWeight)
                    + liveProjection * liveWeight);
        }
        record.currentEstimatedDurationMillis = Math.max(record.elapsedMillis, estimate);
        record.predictionDeviationMillis = record.currentEstimatedDurationMillis
                - record.initialEstimatedDurationMillis;
        record.predictionAccuracyPercent = predictionAccuracy(
                record.currentEstimatedDurationMillis, record.initialEstimatedDurationMillis);
        long remaining = Math.max(0, record.currentEstimatedDurationMillis - record.elapsedMillis);
        if (remaining == 0 && record.remainingPlacements > 0) {
            remaining = record.placedPlacements > 0
                    ? Math.round(record.remainingPlacements
                            * (record.activeMillis / (double) record.placedPlacements))
                    : record.remainingPlacements * 2500L;
        }
        record.estimatedCompletionTime = record.remainingPlacements == 0 ? 0 : now + remaining;
    }

    private static double predictionAccuracy(long actual, long predicted) {
        if (actual <= 0 || predicted <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100,
                100.0 * (1.0 - Math.abs(actual - predicted)
                        / (double) Math.max(actual, predicted))));
    }

    public static List<JobRunRecord> aggregateConsecutiveRuns(List<JobRunRecord> records) {
        List<JobRunRecord> result = new ArrayList<>();
        String previousIdentity = null;
        for (JobRunRecord input : records) {
            JobRunRecord run = displayCopy(input);
            String identity = jobIdentity(run);
            JobRunRecord aggregate = result.isEmpty() ? null : result.get(result.size() - 1);
            if (aggregate == null || identity.isEmpty() || !identity.equals(previousIdentity)) {
                run.runCount = Math.max(1, run.runCount);
                run.firstRunStartTime = run.firstRunStartTime > 0
                        ? run.firstRunStartTime : run.startTime;
                run.lastRunEndTime = Math.max(run.lastRunEndTime, run.endTime);
                run.sourceRuns.add(displayCopy(input));
                decorateErrors(run, input);
                result.add(run);
            }
            else {
                mergeRun(aggregate, run, input);
            }
            previousIdentity = identity;
        }
        return result;
    }

    private static JobRunRecord displayCopy(JobRunRecord record) {
        JobRunRecord copy = COPY_GSON.fromJson(COPY_GSON.toJson(record), JobRunRecord.class);
        ensureCollections(copy);
        copy.sourceRuns = new ArrayList<>();
        return copy;
    }

    private static void mergeRun(JobRunRecord aggregate, JobRunRecord run,
            JobRunRecord source) {
        int priorRuns = aggregate.getRunCount();
        int addedRuns = run.getRunCount();
        double aggregateCoverage = aggregate.feederCoveragePercent * priorRuns;
        double runCoverage = run.feederCoveragePercent * addedRuns;
        aggregate.runCount = priorRuns + addedRuns;
        aggregate.firstRunStartTime = Math.min(
                aggregate.firstRunStartTime > 0 ? aggregate.firstRunStartTime : aggregate.startTime,
                run.firstRunStartTime > 0 ? run.firstRunStartTime : run.startTime);
        aggregate.lastRunEndTime = Math.max(aggregate.lastRunEndTime,
                Math.max(run.lastRunEndTime, run.endTime));
        aggregate.endTime = Math.max(aggregate.endTime, run.endTime);
        aggregate.elapsedMillis += run.elapsedMillis;
        aggregate.activeMillis += run.activeMillis;
        aggregate.pausedMillis += run.pausedMillis;
        aggregate.boardCount += run.boardCount;
        aggregate.completedBoards += run.completedBoards;
        aggregate.totalPlacements += run.totalPlacements;
        aggregate.placedPlacements += run.placedPlacements;
        aggregate.remainingPlacements += run.remainingPlacements;
        aggregate.uniqueParts = Math.max(aggregate.uniqueParts, run.uniqueParts);
        aggregate.completed = aggregate.completed && run.completed;
        aggregate.running = aggregate.running || run.running;
        aggregate.initialEstimatedDurationMillis += run.initialEstimatedDurationMillis;
        aggregate.currentEstimatedDurationMillis += run.currentEstimatedDurationMillis;
        aggregate.predictionSampleCount += run.predictionSampleCount;
        aggregate.predictionConfidencePercent = (aggregate.predictionConfidencePercent * priorRuns
                + run.predictionConfidencePercent * addedRuns) / aggregate.runCount;
        aggregate.feederCoveragePercent = (aggregateCoverage + runCoverage)
                / aggregate.runCount;
        aggregate.averageCph = aggregate.activeMillis <= 0 ? 0
                : aggregate.placedPlacements * 3600000.0 / aggregate.activeMillis;
        aggregate.placementsPerBoard = aggregate.boardCount == 0 ? 0
                : aggregate.totalPlacements / (double) aggregate.boardCount;
        aggregate.predictionDeviationMillis = aggregate.elapsedMillis
                - aggregate.initialEstimatedDurationMillis;
        aggregate.predictionAccuracyPercent = predictionAccuracy(aggregate.elapsedMillis,
                aggregate.initialEstimatedDurationMillis);
        aggregate.state = "连续 " + aggregate.runCount + " 次";
        mergeLongMap(aggregate.stageMillis, run.stageMillis);
        mergeIntegerMap(aggregate.stageCounts, run.stageCounts);
        mergeLongMap(aggregate.sourceMillis, run.sourceMillis);
        mergeIntegerMap(aggregate.sourceCounts, run.sourceCounts);
        mergeLongMap(aggregate.predictedStageMillis, run.predictedStageMillis);
        decorateErrors(run, source);
        aggregate.errors.addAll(run.errors);
        aggregate.sourceRuns.add(displayCopy(source));
    }

    private static void decorateErrors(JobRunRecord target, JobRunRecord source) {
        ensureCollections(target);
        for (ErrorRecord error : target.errors) {
            if (error.runId == null) {
                error.runId = source.id;
            }
            if (error.jobName == null) {
                error.jobName = source.jobName;
            }
            if (error.jobPath == null) {
                error.jobPath = source.jobPath;
            }
            if (error.runStartTime == 0) {
                error.runStartTime = source.startTime;
            }
        }
    }

    private static void ensureCollections(JobRunRecord record) {
        if (record.errors == null) {
            record.errors = new ArrayList<>();
        }
        if (record.stageMillis == null) {
            record.stageMillis = new LinkedHashMap<>();
        }
        if (record.stageCounts == null) {
            record.stageCounts = new LinkedHashMap<>();
        }
        if (record.sourceMillis == null) {
            record.sourceMillis = new LinkedHashMap<>();
        }
        if (record.sourceCounts == null) {
            record.sourceCounts = new LinkedHashMap<>();
        }
        if (record.predictedStageMillis == null) {
            record.predictedStageMillis = new LinkedHashMap<>();
        }
        if (record.sourceRuns == null) {
            record.sourceRuns = new ArrayList<>();
        }
    }

    private static void mergeLongMap(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            target.put(entry.getKey(), target.getOrDefault(entry.getKey(), 0L) + entry.getValue());
        }
    }

    private static void mergeIntegerMap(Map<String, Integer> target,
            Map<String, Integer> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            target.put(entry.getKey(), target.getOrDefault(entry.getKey(), 0) + entry.getValue());
        }
    }

    private static String jobIdentity(JobRunRecord record) {
        String value = record.jobPath == null || record.jobPath.trim().isEmpty()
                ? record.jobName : record.jobPath;
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT).trim();
    }

    public static List<BottleneckInsight> analyzeBottlenecks(JobRunRecord record) {
        ensureCollections(record);
        List<BottleneckInsight> insights = new ArrayList<>();
        if (record.predictionDeviationMillis > 10000) {
            addInsight(insights, "任务超过预计时间", 1, record.predictionDeviationMillis,
                    "实际/实时预测比初始预计多 "
                            + formatDuration(record.predictionDeviationMillis),
                    "优先处理下列高频阶段和异常源；积累三次同任务记录后，预测会自动按本机节拍学习。");
        }
        if (record.pausedMillis > 10000) {
            addInsight(insights, "人工或异常暂停占用时间", 1, record.pausedMillis,
                    "累计暂停 " + formatDuration(record.pausedMillis),
                    "区分换料、确认和故障暂停；把固定换料安排到任务前，并检查触发暂停的首个异常。");
        }
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Long> categoryLoss = new LinkedHashMap<>();
        Map<String, Integer> sourceErrors = new LinkedHashMap<>();
        Map<String, Long> sourceLoss = new LinkedHashMap<>();
        for (ErrorRecord error : record.errors) {
            String category = error.category == null ? "其他异常" : error.category;
            long loss = error.estimatedLostMillis > 0 ? error.estimatedLostMillis : 45000;
            categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
            categoryLoss.put(category, categoryLoss.getOrDefault(category, 0L) + loss);
            if (error.source != null && !error.source.trim().isEmpty()) {
                sourceErrors.put(error.source, sourceErrors.getOrDefault(error.source, 0) + 1);
                sourceLoss.put(error.source, sourceLoss.getOrDefault(error.source, 0L) + loss);
            }
        }
        for (Map.Entry<String, Integer> entry : sourceErrors.entrySet()) {
            if (entry.getValue() >= 2) {
                addInsight(insights, entry.getKey() + " 反复异常", entry.getValue(),
                        sourceLoss.get(entry.getKey()),
                        "在所选任务记录中出现 " + entry.getValue() + " 次关联异常",
                        "飞达相关请检查出料坐标、步距、压料与余料；物料相关请检查吸嘴、吸取高度和真空阈值，修复后做连续取料验证。");
            }
        }
        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            if (entry.getValue() >= 2) {
                addInsight(insights, entry.getKey() + " 是主要异常", entry.getValue(),
                        categoryLoss.get(entry.getKey()),
                        "累计 " + entry.getValue() + " 次，估算影响 "
                                + formatDuration(categoryLoss.get(entry.getKey())),
                        categorySuggestion(entry.getKey()));
            }
        }
        int alignCount = record.stageCounts.getOrDefault("视觉对位", 0);
        int expectedAlignments = Math.max(1, record.placedPlacements);
        if (alignCount > expectedAlignments + Math.max(2, expectedAlignments / 10)) {
            int repeats = alignCount - expectedAlignments;
            long average = record.stageMillis.getOrDefault("视觉对位", 0L)
                    / Math.max(1, alignCount);
            addInsight(insights, "重复底部视觉对位", repeats, average * repeats,
                    "对位 " + alignCount + " 次，但完成贴装 " + record.placedPlacements
                            + " 件，额外约 " + repeats + " 次",
                    "按物料查看对位耗时；优先调整高频物料的曝光、封装尺寸、吸取中心和容差，避免拾取偏移造成重复识别。");
        }
        long active = Math.max(1, record.activeMillis);
        for (Map.Entry<String, Long> entry : record.stageMillis.entrySet()) {
            long predicted = record.predictedStageMillis.getOrDefault(entry.getKey(), 0L);
            long overrun = predicted > 0 ? entry.getValue() - predicted : 0;
            boolean dominant = entry.getValue() > active * 0.38 && entry.getValue() > 20000;
            if (overrun > Math.max(10000, predicted / 5) || dominant) {
                addInsight(insights, entry.getKey() + " 阶段耗时偏高", 1,
                        Math.max(overrun, entry.getValue() / 5),
                        "本阶段 " + formatDuration(entry.getValue())
                                + (predicted > 0 ? "，历史预计 " + formatDuration(predicted) : ""),
                        stageSuggestion(entry.getKey()));
            }
        }
        insights.sort((left, right) -> Long.compare(right.estimatedDelayMillis,
                left.estimatedDelayMillis));
        if (insights.isEmpty()) {
            addInsight(insights, "当前未发现明确瓶颈", 0, 0,
                    "异常、暂停和阶段耗时均未形成稳定的高频模式",
                    "继续积累同一任务运行记录；三次以上预测和飞达/对位归因会更可靠。");
        }
        return insights;
    }

    private static void addInsight(List<BottleneckInsight> target, String title,
            int occurrences, long delay, String evidence, String suggestion) {
        BottleneckInsight insight = new BottleneckInsight();
        insight.title = title;
        insight.occurrences = occurrences;
        insight.estimatedDelayMillis = Math.max(0, delay);
        insight.evidence = evidence;
        insight.suggestion = suggestion;
        target.add(insight);
    }

    private static String categorySuggestion(String category) {
        if ("飞达送料".equals(category)) {
            return "按飞达名称排序异常次数，优先校准高频飞达的送料步距、取料坐标和机械压料。";
        }
        if ("取料与真空".equals(category)) {
            return "检查吸嘴型号、吸取高度、真空阈值和物料中心，执行连续取料测试确认一次成功率。";
        }
        if ("视觉与对位".equals(category)) {
            return "查看高频对位物料，缩短曝光并校正封装尺寸、光源和吸取中心，减少重复对位。";
        }
        if ("通信连接".equals(category)) {
            return "检查串口线缆、控制器队列和超时；先降低异步窗口，再逐步提高。";
        }
        return "从首次异常发生的任务、阶段和设备源开始复现，修复后用同一任务验证预测偏差是否收敛。";
    }

    private static String stageSuggestion(String stage) {
        if (stage.contains("飞达") || stage.contains("取料")) {
            return "查看耗时最高的飞达，检查送料动作、出料坐标、吸取高度和取料重试。";
        }
        if (stage.contains("视觉") || stage.contains("对位")) {
            return "按物料统计识别次数和耗时，优化曝光、光源、封装框、拾取中心及视觉容差。";
        }
        if (stage.contains("贴装")) {
            return "检查安全高度、运动路径和板原点，避免不必要的完全到位等待与过高 Z 抬升。";
        }
        return "检查该阶段的首个状态和后续异常，减少重复准备、人工确认及不必要等待。";
    }
    private synchronized void tick() {
        if (current != null && current.running && currentJob != null) {
            long now = System.currentTimeMillis();
            updateCounts(currentJob, current);
            updateLiveTimes(now);
            updatePrediction(current, now);
            fireChanged();
        }
    }

    private void updateLiveTimes(long now) {
        if (current == null) {
            return;
        }
        long livePause = pauseStartedAt == 0 ? 0 : now - pauseStartedAt;
        current.elapsedMillis = Math.max(0, now - current.startTime);
        current.activeMillis = Math.max(0,
                current.elapsedMillis - current.pausedMillis - livePause);
        current.averageCph = current.activeMillis == 0 ? 0
                : current.placedPlacements * 3600000.0 / current.activeMillis;
    }

    private void updateCounts(Job job, JobRunRecord record) {
        if (job == null) {
            return;
        }
        record.totalPlacements = job.getTotalActivePlacements(job.getRootPanelLocation());
        record.remainingPlacements = job.getActivePlacements(job.getRootPanelLocation());
        record.placedPlacements = Math.max(0,
                record.totalPlacements - record.remainingPlacements);
        record.boardCount = 0;
        record.completedBoards = 0;
        Set<String> uniqueParts = new HashSet<>();
        Set<String> configuredFeederParts = new HashSet<>();
        if (configuration.getMachine() != null) {
            for (Feeder feeder : configuration.getMachine().getFeeders()) {
                if (feeder.isEnabled() && feeder.getPart() != null) {
                    configuredFeederParts.add(feeder.getPart().getId());
                }
            }
        }
        for (BoardLocation board : job.getBoardLocations()) {
            if (!board.isEnabled() || board.getBoard() == null) {
                continue;
            }
            record.boardCount++;
            int boardTotal = job.getTotalActivePlacements(board);
            if (boardTotal > 0 && job.getActivePlacements(board) == 0) {
                record.completedBoards++;
            }
            Side side = board.getGlobalSide();
            for (Placement placement : board.getBoard().getPlacements()) {
                if (placement.isEnabled() && placement.getType() == Placement.Type.Placement
                        && placement.getSide() == side && placement.getPart() != null) {
                    uniqueParts.add(placement.getPart().getId());
                }
            }
        }
        record.uniqueParts = uniqueParts.size();
        int covered = 0;
        for (String partId : uniqueParts) {
            if (configuredFeederParts.contains(partId)) {
                covered++;
            }
        }
        record.feederCoveragePercent = uniqueParts.isEmpty() ? 100
                : covered * 100.0 / uniqueParts.size();
        record.placementsPerBoard = record.boardCount == 0 ? 0
                : record.totalPlacements / (double) record.boardCount;
    }

    private synchronized void finish(Job job, boolean normalStop) {
        if (current == null || !current.running) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pauseStartedAt > 0) {
            current.pausedMillis += now - pauseStartedAt;
            pauseStartedAt = 0;
        }
        closeCurrentStage(now);
        updateCounts(job, current);
        updateLiveTimes(now);
        updatePrediction(current, now);
        current.endTime = now;
        current.lastRunEndTime = now;
        current.running = false;
        current.completed = current.remainingPlacements == 0 && normalStop;
        current.state = current.completed ? "已完成" : "已停止";
        current.estimatedCompletionTime = 0;
        if (current.initialEstimatedDurationMillis > 0) {
            current.predictionDeviationMillis = current.elapsedMillis
                    - current.initialEstimatedDurationMillis;
            current.predictionAccuracyPercent = predictionAccuracy(
                    current.elapsedMillis, current.initialEstimatedDurationMillis);
        }
        history.add(0, copy(current));
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        saveHistory();
        fireChanged();
    }

    private JobRunRecord copy(JobRunRecord record) {
        return gson.fromJson(gson.toJson(record), JobRunRecord.class);
    }

    private void saveCurrentProgress() {
        // History is finalized on stop. The live record is intentionally kept in memory to avoid
        // disk writes during machine motion.
    }

    private void loadHistory() {
        if (!historyFile.isFile()) {
            return;
        }
        Type listType = new TypeToken<List<JobRunRecord>>() { }.getType();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(historyFile), StandardCharsets.UTF_8)) {
            List<JobRunRecord> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                history.addAll(loaded);
            }
        }
        catch (Exception e) {
            Logger.warn(e, "Unable to read production history.");
        }
    }

    private synchronized void saveHistory() {
        File parent = historyFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temporary = new File(historyFile.getParentFile(), historyFile.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            gson.toJson(history, writer);
        }
        catch (Exception e) {
            Logger.warn(e, "Unable to write production history.");
            return;
        }
        try {
            Files.move(temporary.toPath(), historyFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e) {
            Logger.warn(e, "Unable to replace production history.");
        }
    }

    public synchronized JobRunRecord getCurrent() {
        return current == null ? null : copy(current);
    }

    public synchronized List<JobRunRecord> getHistory() {
        List<JobRunRecord> copy = new ArrayList<>();
        for (JobRunRecord record : history) {
            copy.add(copy(record));
        }
        return Collections.unmodifiableList(copy);
    }

    public synchronized Map<String, Integer> getErrorCategoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JobRunRecord run : history) {
            if (run.errors == null) {
                continue;
            }
            for (ErrorRecord error : run.errors) {
                counts.put(error.category, counts.getOrDefault(error.category, 0) + 1);
            }
        }
        return counts;
    }

    public synchronized Map<String, Double> getSummary() {
        Map<String, Double> summary = new HashMap<>();
        double completed = 0;
        double placements = 0;
        double boards = 0;
        double errors = 0;
        double duration = 0;
        double active = 0;
        double bestSpeed = 0;
        double lastSevenDaysRuns = 0;
        double lastSevenDaysPlacements = 0;
        long weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (JobRunRecord run : history) {
            completed += run.completed ? 1 : 0;
            placements += run.placedPlacements;
            boards += run.completedBoards;
            errors += run.getErrorCount();
            duration += run.elapsedMillis;
            active += run.activeMillis;
            bestSpeed = Math.max(bestSpeed, run.averageCph);
            if (run.startTime >= weekAgo) {
                lastSevenDaysRuns++;
                lastSevenDaysPlacements += run.placedPlacements;
            }
        }
        double runs = history.size();
        summary.put("runs", runs);
        summary.put("completed", completed);
        summary.put("completionRate", runs == 0 ? 0 : completed * 100 / runs);
        summary.put("placements", placements);
        summary.put("boards", boards);
        summary.put("errors", errors);
        summary.put("averageDuration", runs == 0 ? 0 : duration / runs);
        summary.put("averageSpeed", active == 0 ? 0 : placements * 3600000 / active);
        summary.put("bestSpeed", bestSpeed);
        summary.put("errorsPerThousand", placements == 0 ? 0 : errors * 1000 / placements);
        summary.put("firstPassYield", placements + errors == 0 ? 100
                : placements * 100 / (placements + errors));
        summary.put("weekRuns", lastSevenDaysRuns);
        summary.put("weekPlacements", lastSevenDaysPlacements);
        summary.put("estimatedDowntime", errors * 120000);
        return summary;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void fireChanged() {
        for (Listener listener : listeners) {
            listener.telemetryChanged();
        }
    }

    public void dispose() {
        timer.stop();
        MessageBoxes.removeErrorListener(errorListener);
    }

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static String formatSignedDuration(long millis) {
        if (millis == 0) {
            return "±00:00:00";
        }
        return (millis > 0 ? "+" : "−") + formatDuration(Math.abs(millis));
    }

    public static String formatDate(long timestamp) {
        return timestamp == 0 ? "—"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(timestamp));
    }
}
