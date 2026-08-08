/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

/** Aggregated measurements from an offline historical image set. */
public final class VisionDatasetAnalysis {
    private final int sampleCount;
    private final RoiAnalysis representative;
    private final double minBrightness;
    private final double maxBrightness;
    private final double minContrast;
    private final double maxContrast;

    VisionDatasetAnalysis(int sampleCount, RoiAnalysis representative, double minBrightness,
            double maxBrightness, double minContrast, double maxContrast) {
        this.sampleCount = sampleCount;
        this.representative = representative;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
        this.minContrast = minContrast;
        this.maxContrast = maxContrast;
    }

    public int getSampleCount() { return sampleCount; }
    public RoiAnalysis getRepresentative() { return representative; }
    public double getMinBrightness() { return minBrightness; }
    public double getMaxBrightness() { return maxBrightness; }
    public double getMinContrast() { return minContrast; }
    public double getMaxContrast() { return maxContrast; }
    public double getBrightnessSpread() { return maxBrightness - minBrightness; }
    public double getContrastSpread() { return maxContrast - minContrast; }

    public String toChineseSummary() {
        return String.format("历史样本 %d 张 · 亮度 %.1f～%.1f（跨度 %.1f）· 对比度 %.1f～%.1f（跨度 %.1f）",
                sampleCount, minBrightness, maxBrightness, getBrightnessSpread(),
                minContrast, maxContrast, getContrastSpread());
    }

    public String buildAdvice() {
        StringBuilder result = new StringBuilder(toChineseSummary());
        if (sampleCount < 5) {
            result.append("\n• 样本偏少：建议补充不同批次、角度和光照图片，至少 5 张后再收紧参数。");
        }
        if (getBrightnessSpread() > 60) {
            result.append("\n• 光照变化较大：优先固定曝光与灯光，再考虑归一化亮度，避免用过宽阈值掩盖问题。");
        }
        if (minContrast < 24) {
            result.append("\n• 存在低对比度样本：建议增加局部对比度或选择轮廓/模板特征，并纳入独立验证集。");
        }
        if (getContrastSpread() > 35) {
            result.append("\n• 对比度波动明显：单一全局阈值可能不稳，建议使用自适应阈值或先做光照校正。");
        }
        if (sampleCount >= 5 && getBrightnessSpread() <= 60 && minContrast >= 24) {
            result.append("\n• 当前历史集的亮度与对比度较集中，可基于代表值构建候选方案并进行独立验证。");
        }
        return result.toString();
    }
}
