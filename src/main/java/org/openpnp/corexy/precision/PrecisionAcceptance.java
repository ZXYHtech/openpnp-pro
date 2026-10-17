package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Acceptance is measured improvement over an uncompensated baseline, independent of the goal. */
public final class PrecisionAcceptance {
    private PrecisionAcceptance() { }

    public static List<String> reasons(PrecisionMetrics baseline, PrecisionMetrics measured, double noise) {
        List<String> reasons = new ArrayList<>();
        if (!valid(baseline) || !valid(measured) || !Double.isFinite(noise) || noise < 0) {
            reasons.add("缺少有效的未补偿基线或本次实测数据，不能判断补偿是否改善。");
            return reasons;
        }
        double margin = noise * 2;
        if (!(measured.p95 < baseline.p95 - margin)) {
            reasons.add(String.format(Locale.ROOT,
                    "P95 改善不足：本次 %.5f mm，未补偿 %.5f mm；改善需超过测量噪声余量 %.5f mm。",
                    measured.p95, baseline.p95, margin));
        }
        if (!(measured.rms < baseline.rms)) {
            reasons.add(String.format(Locale.ROOT, "RMS 未降低：本次 %.5f mm，未补偿 %.5f mm。",
                    measured.rms, baseline.rms));
        }
        if (measured.maximum > baseline.maximum + margin) {
            reasons.add(String.format(Locale.ROOT, "最大误差退化：本次 %.5f mm，未补偿 %.5f mm，允许上限 %.5f mm。",
                    measured.maximum, baseline.maximum, baseline.maximum + margin));
        }
        return reasons;
    }

    private static boolean valid(PrecisionMetrics metrics) {
        return metrics != null && metrics.count >= 8 && Double.isFinite(metrics.rms)
                && Double.isFinite(metrics.p95) && Double.isFinite(metrics.maximum)
                && metrics.rms >= 0 && metrics.p95 >= 0 && metrics.maximum >= 0;
    }
}
