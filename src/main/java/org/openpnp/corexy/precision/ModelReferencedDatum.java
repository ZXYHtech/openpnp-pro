package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Four opposing approaches separate coordinate drift from the frozen A/B response. */
public final class ModelReferencedDatum {
    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> false);

    private ModelReferencedDatum() { }

    public static boolean isRawStage() { return RAW.get(); }

    static void alignDuringHome(FixedMarkerVerifier.Backend backend, PrecisionProfile profile,
            StartupVerificationReport report, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        PrecisionRuntime.Settings saved = PrecisionRuntime.settings();
        if (saved == null || RAW.get()) {
            throw new IllegalStateException("模型基准只能在已加载方案的 Home 阶段执行。");
        }
        // Enforces a zero runtime offset before any uncorrected measurement.
        PrecisionRuntime.restore(saved);
        RAW.set(true);
        try {
            new FixedMarkerVerifier().alignHome(backend, profile, report, progress, cancelled);
            align(backend, profile, report, progress, cancelled);
        }
        finally {
            RAW.remove();
            // Raw moves still preload the belts. Keep their accumulated A/B states;
            // replaying mechanical Home here would forget the four-direction approaches.
        }
    }

    public static void align(FixedMarkerVerifier.Backend backend, PrecisionProfile profile,
            StartupVerificationReport report, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        FixedMarkerSetup.Marker marker = profile.fixedMarkers.markers.stream()
                .filter(m -> m.id.equals(profile.modelDatumMarkerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模型基准 marker 缺失"));
        double totalX = 0, totalY = 0;
        for (int iteration = 0; iteration < 5; iteration++) {
            double dx = 0, dy = 0;
            int direction = 0;
            for (int sa : new int[] {-1, 1}) {
                for (int sb : new int[] {-1, 1}) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("已取消四方向基准对齐");
                    }
                    progress.update(16 + iteration * 2, "补偿暂停 · " + marker.id + " 四方向基准 "
                            + (++direction) + "/4 · 对齐冻结模型（第 " + (iteration + 1) + " 轮）");
                    CalibrationPlan.Path path = new CalibrationPlan.Path("model-datum-" + iteration + "-" + direction,
                            marker.id, marker.x, marker.y, 24, 18, .6, 1, sa, sb, 2, profile.settlingMillis);
                    PrecisionSample sample = backend.observe(path);
                    report.homeSamples.add(sample);
                    double[] predicted = profile.model.predict(sample);
                    dx += (sample.correctionX - predicted[0]) / 4;
                    dy += (sample.correctionY - predicted[1]) / 4;
                }
            }
            report.afterHomeResidual = Math.hypot(dx, dy);
            if (report.afterHomeResidual <= Math.max(.002, profile.noise * 3)) {
                report.notes.add("四方向模型基准已对齐；补偿启用后不再修改坐标原点。");
                return;
            }
            totalX += dx;
            totalY += dy;
            if (Math.hypot(totalX, totalY) > profile.fixedMarkers.maximumOffset || iteration == 4) {
                throw new IllegalStateException("四方向模型基准超限或未收敛，停止 Home 并保留方案。");
            }
            backend.subtractDatum(dx, dy);
            report.homeCorrectionX -= dx;
            report.homeCorrectionY -= dy;
        }
    }

    public static List<CalibrationPlan.Path> verificationPaths(PrecisionProfile profile) {
        List<CalibrationPlan.Path> paths = new ArrayList<>();
        for (CalibrationPlan.Path path : CalibrationPlan.fixedVerification(profile.fixedMarkers, profile.settlingMillis)) {
            if (path.direct) { paths.add(path); }
        }
        for (int i = 0; i < profile.fixedMarkers.markers.size(); i++) {
            if (i == profile.fixedMarkers.homeIndex) { continue; }
            FixedMarkerSetup.Marker marker = profile.fixedMarkers.markers.get(i);
            for (double travel : new double[] {10, 31}) {
                for (int sa : new int[] {-1, 1}) {
                    for (int sb : new int[] {-1, 1}) {
                        paths.add(new CalibrationPlan.Path("coupled-check-" + marker.id + "-" + paths.size(),
                                marker.id, marker.x, marker.y, travel, travel * .72, .6, 1,
                                sa, sb, 5, profile.settlingMillis));
                    }
                }
            }
        }
        return paths;
    }
}
