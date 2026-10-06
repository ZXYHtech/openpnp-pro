/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds actionable, Chinese timeout/failure advice without changing machine state. */
public final class SmartVisionDiagnosis {
    private SmartVisionDiagnosis() {
    }

    public static List<String> suggestions(RoiAnalysis analysis, int samples,
            double bestAccuracy, boolean timedOut) {
        List<String> result = new ArrayList<>();
        if (analysis == null) {
            result.add("尚无有效框选样本：先框住目标并采集至少一张清晰图像。");
            return result;
        }
        if (analysis.isTooDark()) {
            result.add("亮度偏低：优先增加物理光源或曝光，避免只靠软件增益放大噪声。");
        }
        if (analysis.isTooBright()) {
            result.add("高光可能饱和：降低曝光/灯光，改变入射角，并补采不过曝样本。");
        }
        if (analysis.isLowContrast()) {
            result.add("前景与背景分离不足：尝试 CLAHE、自适应阈值或更换背景/光源角度。");
        }
        if (samples < 20) {
            result.add("样本量偏少：建议包含旋转、亮暗、不同批次和视野边缘图像，至少 20 张。");
        }
        if (bestAccuracy < 0.995) {
            result.add("尚未达到 99.5% 目标：收窄旋转/尺寸范围，加入易混淆负样本，并复核 ROI 是否含相邻物体。");
        }
        if (timedOut) {
            result.add("已到达总超时：保留当前最佳方案；下一轮只搜索最敏感的 2～3 个参数，缩小范围。");
        }
        if (result.isEmpty()) {
            result.add("基础图像质量良好；下一步应使用独立验证集确认误检率和边缘视野稳定性。");
        }
        return Collections.unmodifiableList(result);
    }
}
