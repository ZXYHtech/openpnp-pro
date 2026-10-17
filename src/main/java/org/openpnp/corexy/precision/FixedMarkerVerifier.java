package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Measures the permanent datum again; never fits a new model from startup checks. */
public final class FixedMarkerVerifier {
    public interface Backend {
        PrecisionSample observe(CalibrationPlan.Path path) throws Exception;
        /** Subtract measured marker drift from the current XY working coordinates, without motion. */
        void subtractDatum(double x, double y) throws Exception;
    }

    private static void cancelled(BooleanSupplier guard) {
        if (guard.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("已取消固定 marker 校验");
        }
    }

    public void alignHome(Backend backend, PrecisionProfile profile, StartupVerificationReport report,
            CalibrationController.Progress progress, BooleanSupplier guard) throws Exception {
        FixedMarkerSetup setup = profile.fixedMarkers;
        setup.validate();
        FixedMarkerSetup.Marker marker = setup.homeMarker();
        double accumulatedX = 0, accumulatedY = 0;
        for (int iteration = 0; iteration < 4; iteration++) {
            cancelled(guard);
            progress.update(iteration * 5, "识别 Home 基准 marker，消除本次复位坐标漂移");
            List<PrecisionSample> frames = new ArrayList<>();
            for (int frame = 0; frame < 5; frame++) {
                cancelled(guard);
                CalibrationPlan.Path path = new CalibrationPlan.Path("home-datum-" + iteration + "-" + frame,
                        marker.x, marker.y, 0, 0, .35, 1, 0, 0, 1, profile.settlingMillis);
                path.markerId = marker.id;
                path.direct = true;
                PrecisionSample sample = backend.observe(path);
                frames.add(sample);
                report.homeSamples.add(sample);
            }
            PrecisionMetrics metrics = PrecisionMetrics.evaluate(frames, null);
            double variance = 0;
            for (PrecisionSample sample : frames) {
                variance += Math.pow(sample.correctionX - metrics.meanX, 2)
                        + Math.pow(sample.correctionY - metrics.meanY, 2);
            }
            double noise = Math.sqrt(variance / (frames.size() * 2));
            report.noise = Math.max(report.noise, Math.max(profile.noise, noise));
            if (report.noise * 3 >= profile.target) {
                throw new IllegalStateException("Home marker 测量不稳定，请检查照明、对焦和固定状态。");
            }
            double dx = metrics.meanX - (profile.homeReference == null ? 0 : profile.homeReference[0]);
            double dy = metrics.meanY - (profile.homeReference == null ? 0 : profile.homeReference[1]);
            double residual = Math.hypot(dx, dy);
            report.homeIterations = iteration + 1;
            report.afterHomeResidual = residual;
            if (iteration == 0) {
                report.rawHomeX = metrics.meanX;
                report.rawHomeY = metrics.meanY;
                report.beforeHomeResidual = residual;
            }
            // Model-referenced profiles use this corner marker only for coarse registration.
            // Tiny reversals here can sit inside mechanical deadband; the opposing approaches
            // that follow establish the precise datum against the frozen model.
            double tolerance = profile.modelDatumMarkerId.isEmpty() || profile.homeReference != null
                    ? Math.min(profile.target, Math.max(.002, report.noise * 3)) : profile.target;
            if (residual <= tolerance) {
                return;
            }
            accumulatedX += dx;
            accumulatedY += dy;
            if (Math.hypot(accumulatedX, accumulatedY) > setup.maximumHomeOffset) {
                throw new IllegalStateException("Home 漂移超出设置的修正上限；保持原档案并停止复位。");
            }
            if (iteration == 3) {
                throw new IllegalStateException("Home marker 坐标修正未收敛，请重新校准。");
            }
            backend.subtractDatum(dx, dy);
            report.homeCorrectionX -= dx;
            report.homeCorrectionY -= dy;
        }
    }

    /** Keep the established model datum while transferring its Home signature to durable storage. */
    public double[] captureHomeReference(Backend backend, PrecisionProfile profile,
            StartupVerificationReport report, BooleanSupplier guard) throws Exception {
        FixedMarkerSetup.Marker home = profile.fixedMarkers.homeMarker();
        List<PrecisionSample> frames = new ArrayList<>();
        for (int frame = 0; frame < 5; frame++) {
            cancelled(guard);
            CalibrationPlan.Path path = new CalibrationPlan.Path("home-reference-" + frame,
                    home.x, home.y, 0, 0, .35, 1, 0, 0, 1, profile.settlingMillis);
            path.markerId = home.id;
            path.direct = true;
            PrecisionSample sample = backend.observe(path);
            frames.add(sample);
            report.homeSamples.add(sample);
        }
        PrecisionMetrics metrics = PrecisionMetrics.evaluate(frames, null);
        double variance = 0;
        for (PrecisionSample sample : frames) {
            variance += Math.pow(sample.correctionX - metrics.meanX, 2)
                    + Math.pow(sample.correctionY - metrics.meanY, 2);
        }
        if (3 * Math.sqrt(variance / (frames.size() * 2)) >= profile.target) {
            throw new IllegalStateException("Home 参考采样不稳定，未保存快速复位基准。");
        }
        return new double[] {metrics.meanX, metrics.meanY};
    }

    public void verify(Backend backend, PrecisionProfile profile, StartupVerificationReport report,
            CalibrationController.Progress progress, BooleanSupplier guard) throws Exception {
        FixedMarkerSetup setup = profile.fixedMarkers;
        List<CalibrationPlan.Path> paths = profile.modelDatumMarkerId.isEmpty()
                ? verificationPaths(setup, profile.settlingMillis) : ModelReferencedDatum.verificationPaths(profile);
        for (int index = 0; index < paths.size(); index++) {
            cancelled(guard);
            CalibrationPlan.Path path = paths.get(index);
            progress.update(25 + index * 70 / paths.size(), "新补偿已启用 · " + path.markerId
                    + " · 独立路径 " + (index + 1) + "/" + paths.size() + " · 移动、静止、识别圆心");
            report.samples.add(backend.observe(paths.get(index)));
        }
        report.metrics = PrecisionMetrics.evaluate(report.samples, null);
        double allowance = Math.max(report.noise * 3, .002);
        // Recheck stability against the measured solution; a relative improvement never proves the absolute target.
        report.passed = report.metrics.p95 <= Math.max(profile.target, profile.corrected.p95 + allowance)
                && report.metrics.maximum <= Math.max(profile.target, profile.corrected.maximum) + allowance
                && report.metrics.p95 <= profile.corrected.p95 + allowance
                && report.metrics.rms <= profile.corrected.rms + allowance
                && report.metrics.maximum <= profile.corrected.maximum + allowance;
        report.outcome = report.passed ? "Home 基准已修正，5 个固定 marker 校验通过。"
                : "固定 marker 校验未通过，禁止继续生产；原补偿方案和本次原始数据已保留。";
        if (report.passed && report.metrics.p95 > profile.target) {
            report.notes.add("本次校验与已保存方案一致，但 P95 尚未达到设置的目标；稳定性通过不代表目标精度达标。");
        }
        progress.update(100, report.outcome);
        if (!report.passed) {
            throw new IllegalStateException(report.outcome);
        }
    }

    public static List<CalibrationPlan.Path> verificationPaths(FixedMarkerSetup setup, int settling) {
        return CalibrationPlan.fixedVerification(setup, settling);
    }
}
