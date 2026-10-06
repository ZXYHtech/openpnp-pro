package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;

/** Append-only evidence from one software launch or an explicitly requested recheck. */
public final class StartupVerificationReport {
    public int schema = 1;
    public long createdAt = System.currentTimeMillis(), completedAt;
    public String sessionId = "", profileId = "", fingerprint = "", outcome = "";
    public String kind = "startup";
    public boolean passed, cancelled, simulation;
    public double noise, target;
    public double rawHomeX, rawHomeY, homeCorrectionX, homeCorrectionY;
    public double beforeHomeResidual, afterHomeResidual;
    public int homeIterations;
    public PrecisionMetrics metrics;
    public List<PrecisionSample> homeSamples = new ArrayList<>();
    public List<PrecisionSample> samples = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public void validate() {
        if (schema != 1 || createdAt <= 0 || completedAt < createdAt || sessionId == null
                || sessionId.trim().isEmpty() || profileId == null || profileId.trim().isEmpty()
                || fingerprint == null || fingerprint.trim().isEmpty() || outcome == null
                || !("startup".equals(kind) || "manual".equals(kind) || "home".equals(kind))
                || homeIterations < 0 || homeSamples == null || samples == null || notes == null) {
            throw new IllegalArgumentException("启动校验记录缺少方案、会话或结果信息。");
        }
        for (double value : new double[] {noise, target, rawHomeX, rawHomeY, homeCorrectionX,
                homeCorrectionY, beforeHomeResidual, afterHomeResidual}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("启动校验记录包含无效数值。");
            }
        }
        if (noise < 0 || target <= 0 || target > 1 || beforeHomeResidual < 0 || afterHomeResidual < 0
                || (passed && (cancelled || metrics == null || metrics.count < 5))) {
            throw new IllegalArgumentException("启动校验结果不完整。");
        }
        if (metrics != null && (metrics.count < 1 || !Double.isFinite(metrics.rms)
                || !Double.isFinite(metrics.p95) || !Double.isFinite(metrics.maximum)
                || !Double.isFinite(metrics.meanX) || !Double.isFinite(metrics.meanY)
                || metrics.rms < 0 || metrics.p95 < 0 || metrics.maximum < 0)) {
            throw new IllegalArgumentException("启动校验统计数据无效。");
        }
        for (PrecisionSample sample : homeSamples) {
            if (sample == null) {
                throw new IllegalArgumentException("Home 校验原始数据缺失。");
            }
        }
        for (PrecisionSample sample : samples) {
            if (sample == null) {
                throw new IllegalArgumentException("marker 校验原始数据缺失。");
            }
        }
    }
}
