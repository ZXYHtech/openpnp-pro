package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One transaction: noise -> baseline -> competing fits -> fresh physical checks -> keep/rollback. */
public final class CalibrationController {
    public interface Progress {
        void update(int percent, String message);
    }

    public static final class Report {
        public int schema = 1;
        public long createdAt = System.currentTimeMillis();
        public boolean simulation, enabled, targetReached;
        public String fingerprint, outcome;
        public double noise;
        public int settlingMillis;
        public CalibrationRequest request;
        public PrecisionFitter.Result evaluation;
        public PrecisionModel chosen;
        public PrecisionMetrics baselineFinal, correctedFinal;
        public List<PrecisionSample> training = new ArrayList<>();
        public List<PrecisionSample> validation = new ArrayList<>();
        public List<PrecisionSample> finalObservations = new ArrayList<>();
        public List<PrecisionSample> baselineObservations = new ArrayList<>();
        public List<List<PrecisionSample>> finalAttempts = new ArrayList<>();
        public List<PrecisionSample> previousObservations = new ArrayList<>();
        public PrecisionMetrics previousMetrics;
        public List<String> notes = new ArrayList<>();
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Report lastReport;

    public void cancel() {
        cancelled.set(true);
    }

    public Report getLastReport() {
        return lastReport;
    }

    public void prepareRun() {
        cancelled.set(false);
    }

    public Report run(CalibrationMachine machine, CalibrationRequest request, Progress progress) throws Exception {
        request.validate();
        CalibrationPlan plan = new CalibrationPlan(request);
        Report report = new Report();
        lastReport = report;
        report.request = request;
        report.simulation = machine.isSimulation();
        report.fingerprint = machine.fingerprint();
        report.settlingMillis = request.settleMillis;
        boolean prepared = false, keep = false;
        try {
            progress.update(0, "检查相机、软限位和全部校准轨迹");
            checkCancelled();
            // prepare must be internally transactional even if it fails part way through.
            prepared = true;
            machine.setCancellationGuard(cancelled::get);
            machine.prepare(request, plan);
            report.notes.addAll(machine.preparationNotes());
            report.previousObservations.addAll(machine.previousObservations());
            if (!report.previousObservations.isEmpty()) {
                report.previousMetrics = PrecisionMetrics.evaluate(report.previousObservations, null);
            }
            report.noise = machine.measureNoise();
            if (report.noise * 3 >= request.target) {
                report.outcome = "测量噪声过大：改善照明、对焦和 marker 后重试；保持原方案。";
                return report;
            }
            report.notes.addAll(machine.optimizeControllerAndTools(request));
            report.settlingMillis = request.settleMillis;
            plan = new CalibrationPlan(request);
            machine.validatePlan(plan);
            collect(machine, plan.training, report.training, progress, 5, 43);
            collect(machine, plan.validation, report.validation, progress, 43, 65);
            checkCancelled();
            progress.update(66, "比较各补偿模型及组合，使用独立路径评估");
            report.evaluation = new PrecisionFitter().evaluate(report.training, report.validation,
                    report.noise, request.target, request.maximumOffset,
                    request.spatial && request.fixedMarkers == null ? (request.gridSize + 1) / 2 : 2,
                    request.spatial && request.fixedMarkers == null ? (request.gridSize + 1) / 2 : 2, machine.conditioningLimit(request));
            report.notes.addAll(report.evaluation.unavailable);
            if (report.evaluation.selected == null) {
                report.outcome = "没有方案通过独立评估，已保持原配置。";
                return report;
            }
            List<PrecisionSample> baseline = report.baselineObservations;
            collect(machine, plan.finalCheck, baseline, progress, 70, 77);
            report.baselineFinal = PrecisionMetrics.evaluate(baseline, null);
            List<PrecisionFitter.Candidate> finalists = new ArrayList<>();
            finalists.add(report.evaluation.selected);
            for (PrecisionFitter.Candidate candidate : report.evaluation.candidates) {
                if (candidate.acceptable && !finalists.contains(candidate) && finalists.size() < 3) {
                    finalists.add(candidate);
                }
            }
            for (PrecisionFitter.Candidate candidate : finalists) {
                checkCancelled();
                progress.update(78, "重新 Home，实测候选方案在第三组路径上的表现");
                machine.calibrateHomeState(candidate.model);
                machine.apply(candidate.model, report.settlingMillis);
                List<PrecisionSample> corrected = new ArrayList<>();
                report.finalAttempts.add(corrected);
                collect(machine, plan.finalCheck, corrected, progress, 80, 96);
                PrecisionMetrics metrics = PrecisionMetrics.evaluate(corrected, null);
                double improvement = Math.max(report.noise * 2, report.baselineFinal.p95 * .08);
                if (metrics.p95 < report.baselineFinal.p95 - improvement
                        && metrics.rms < report.baselineFinal.rms
                        && metrics.maximum <= report.baselineFinal.maximum + report.noise * 2
                        && (report.previousMetrics == null
                            || (metrics.p95 <= report.previousMetrics.p95 + report.noise * 2
                                && metrics.maximum <= report.previousMetrics.maximum + report.noise * 2))
                        && homeMaximum(corrected) <= homeMaximum(baseline) + report.noise * 2) {
                    report.chosen = candidate.model;
                    report.correctedFinal = metrics;
                    report.finalObservations = corrected;
                    report.targetReached = metrics.p95 <= request.target;
                    keep = request.autoEnable;
                    report.enabled = keep && !report.simulation;
                    report.outcome = report.simulation ? "模拟流程通过；模拟参数不会启用到真实机器。"
                            : keep ? "补偿已通过第三组路径验证并启用。" : "方案已验证并保存，暂未启用。";
                    if (!report.targetReached) {
                        report.notes.add("偏差有改善，但尚未达到设定的 P95 目标；不能将相对改善当作达标。");
                    }
                    return report;
                }
                machine.apply(null, request.settleMillis);
            }
            report.outcome = "拟合结果未通过补偿后的第三组实测，已回滚。";
            if (report.previousMetrics != null) {
                report.notes.add("新方案还必须不劣于原来已启用的补偿；没有通过时保留旧方案。");
            }
            return report;
        }
        catch (Exception failure) {
            report.enabled = false;
            report.outcome = failure instanceof CancellationException ? "已取消，恢复原配置。"
                    : "校准失败：" + failure.getMessage();
            throw failure;
        }
        finally {
            if (prepared) {
                try {
                    machine.finish(keep, report.chosen, report.settlingMillis);
                    report.fingerprint = machine.fingerprint();
                }
                catch (Exception rollbackFailure) {
                    report.enabled = false;
                    report.outcome = "恢复配置失败，请重新 Home 后检查配置：" + rollbackFailure.getMessage();
                    throw rollbackFailure;
                }
            }
            progress.update(100, report.outcome == null ? "校准已结束" : report.outcome);
            cancelled.set(false);
        }
    }

    private void collect(CalibrationMachine machine, List<CalibrationPlan.Path> paths,
            List<PrecisionSample> samples, Progress progress, int start, int end) throws Exception {
        for (int i = 0; i < paths.size(); i++) {
            checkCancelled();
            progress.update(start + (end - start) * i / paths.size(),
                    "采集 " + (i + 1) + "/" + paths.size() + "：" + paths.get(i).id);
            samples.add(machine.observe(paths.get(i)));
        }
    }

    private void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("用户取消");
        }
    }
    private double homeMaximum(List<PrecisionSample> samples) {
        double maximum = 0;
        for (PrecisionSample sample : samples) {
            if (sample.path.startsWith("final-home-") || sample.path.startsWith("final-fixed-home-")) {
                maximum = Math.max(maximum, Math.hypot(sample.correctionX, sample.correctionY));
            }
        }
        return maximum;
    }
}
