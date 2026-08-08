/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.util.LinkedHashMap;
import java.util.Map;

/** Built-in starting recipes. The user can still edit every original Pipeline parameter. */
public enum SmartVisionTemplate {
    AUTO("自动判断", "根据框选图像的亮度、对比度、形状和边缘密度选择起点", "自动"),
    FIDUCIAL("圆形基准点", "PCB 圆形 Mark、定位孔或高对比圆点", "圆对称 + 轮廓复核"),
    RECTANGULAR_MARK("矩形焊盘 / 标签", "矩形 Mark、焊盘、二维码外框或规则色块", "阈值 + 最小外接矩形"),
    CHIP("片式元件", "电阻、电容、电感等规则矩形器件", "边缘 + 旋转矩形"),
    IC("IC / QFN / QFP / BGA", "芯片本体、引脚或焊球阵列，适合底部视觉", "多尺度轮廓 + 方向复核"),
    NOZZLE("吸嘴 / 工具端点", "吸嘴中心、工具端点和换嘴后的偏心检查", "圆对称 + 中心稳定性"),
    FEEDER_PICK("飞达取料位", "料带口袋、元件中心和取料点复核", "口袋几何 + 元件轮廓"),
    TAPE_HOLE("料带定位孔", "料带圆孔、间距与送料偏移", "圆阵列 + 间距约束"),
    OCR("丝印 / OCR", "料号、极性字符和标签文字", "对比增强 + OCR"),
    CUSTOM("自定义（保留原 Pipeline）", "不替换现有设置，只生成参数建议和中文解释", "原 Pipeline + 建议层");

    private final String displayName;
    private final String description;
    private final String strategy;

    SmartVisionTemplate(String displayName, String description, String strategy) {
        this.displayName = displayName;
        this.description = description;
        this.strategy = strategy;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getStrategy() { return strategy; }

    public Map<String, String> recommendedParameters(RoiAnalysis analysis) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("预处理", preprocessing(analysis));
        values.put("核心方法", strategy);
        values.put("阈值范围", analysis != null && analysis.isLowContrast()
                ? "优先自适应阈值；从 15～45 搜索" : "从 Otsu 自动阈值开始");
        values.put("最小置信度", "先用 0.85 建样本，验证通过后逐步提高到 0.95+");
        values.put("旋转范围", this == FIDUCIAL || this == NOZZLE || this == TAPE_HOLE
                ? "圆形目标无需全角度搜索" : "按实际供料方向收窄，避免无意义全角度搜索");
        values.put("ROI 边距", "目标四周保留约 15%～30%，不要把相邻物体框入");
        return values;
    }

    public static SmartVisionTemplate choose(RoiAnalysis analysis) {
        if (analysis == null) {
            return CUSTOM;
        }
        double ratio = analysis.getAspectRatio();
        if (ratio > 2.8 || ratio < 0.36) {
            return RECTANGULAR_MARK;
        }
        if (analysis.getEdgeDensity() > 0.23) {
            return IC;
        }
        if (ratio > 0.72 && ratio < 1.38 && analysis.getForegroundRatio() < 0.38) {
            return FIDUCIAL;
        }
        return CHIP;
    }

    public static SmartVisionTemplate choose(String description, RoiAnalysis analysis) {
        String text = description == null ? "" : description.toLowerCase();
        if (containsAny(text, "焊盘", "pad", "矩形", "标签", "色块")) {
            return RECTANGULAR_MARK;
        }
        if (containsAny(text, "基准", "mark", "定位点", "圆点")) {
            return FIDUCIAL;
        }
        if (containsAny(text, "定位孔", "料带孔", "sprocket", "带孔")) {
            return TAPE_HOLE;
        }
        if (containsAny(text, "飞达", "料槽", "口袋", "取料")) {
            return FEEDER_PICK;
        }
        if (containsAny(text, "吸嘴", "喷嘴", "工具端点")) {
            return NOZZLE;
        }
        if (containsAny(text, "qfn", "qfp", "bga", "芯片", "引脚", "焊球", "ic")) {
            return IC;
        }
        if (containsAny(text, "电阻", "电容", "电感", "0603", "0805", "片式")) {
            return CHIP;
        }
        if (containsAny(text, "丝印", "文字", "字符", "ocr")) {
            return OCR;
        }
        return choose(analysis);
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String preprocessing(RoiAnalysis analysis) {
        if (analysis == null) {
            return "采集原图后再按样本统计决定";
        }
        if (analysis.isTooDark()) {
            return "图像偏暗：先增加光源/曝光，再做 CLAHE；避免仅软件拉亮噪声";
        }
        if (analysis.isTooBright()) {
            return "图像偏亮：降低光源/曝光，检查金属反光；必要时使用偏振或多光源样本";
        }
        if (analysis.isLowContrast()) {
            return "低对比：CLAHE + 自适应阈值，亮度/对比度纳入小范围搜索";
        }
        return "保持原始亮度，轻微高斯去噪后检测；避免过度处理";
    }

    @Override
    public String toString() {
        return displayName;
    }
}
