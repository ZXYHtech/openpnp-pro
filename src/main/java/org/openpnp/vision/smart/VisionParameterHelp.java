/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

import java.util.Locale;

/** Chinese contextual help for every original Pipeline property name. */
public final class VisionParameterHelp {
    private VisionParameterHelp() {
    }

    public static String describe(String name) {
        String raw = name == null ? "" : name;
        String key = raw.toLowerCase(Locale.ROOT);
        if ("enabled".equals(key)) { return "是否启用当前阶段。关闭可用于逐阶段排查，不会删除参数。"; }
        if ("name".equals(key)) { return "阶段名称。其他阶段可能按此名称读取图像或模型结果，请保持唯一且稳定。"; }
        if (key.contains("threshold")) { return "阈值用于区分目标和背景；过严会漏检，过松会误检，需用正负样本验证。"; }
        if (key.contains("score") || key.contains("confidence")) { return "最低匹配分数/置信度。提高可减少误检，但可能增加漏检。"; }
        if (key.contains("angle") || key.contains("rotation")) { return "目标角度或角度搜索范围。按真实供料方向收窄可提高速度和稳定性。"; }
        if (key.contains("width") || key.contains("height") || key.contains("size")) { return "目标或搜索区域尺寸限制。请结合实物尺寸、相机 UPP 和容差设置。"; }
        if (key.contains("area")) { return "目标面积限制，用于排除灰尘、小噪点或大块背景。"; }
        if (key.contains("diameter") || key.contains("radius")) { return "圆形特征的直径/半径范围。范围越贴近实物，检测越快且误检越少。"; }
        if (key.contains("distance") || key.contains("spacing")) { return "特征之间的最小/最大距离，用于防止重复检测或约束阵列间距。"; }
        if (key.contains("kernel")) { return "滤波或形态学核尺寸。增大会去除更多细节，也可能吞掉小目标。"; }
        if (key.contains("sigma") || key.contains("blur")) { return "模糊/平滑强度。可降噪，但过大会削弱细边缘和小引脚。"; }
        if (key.contains("invert")) { return "反转前景和背景。目标比背景更暗时通常需要。"; }
        if (key.contains("color") || key.contains("hue") || key.contains("saturation")) { return "颜色空间或颜色范围。HSV 适合颜色分离，灰度更适合形状与亮暗特征。"; }
        if (key.contains("margin") || key.contains("padding")) { return "目标周围边距。过小会裁掉特征，过大会混入相邻物体。"; }
        if (key.contains("model") || key.contains("property")) { return "引用的前序模型或 Pipeline 上下文属性；名称必须与来源完全一致。"; }
        if (key.contains("debug") || key.contains("diagnostic")) { return "调试输出。定位问题时开启，生产时关闭可减少 CPU、内存或磁盘开销。"; }
        if (key.contains("camera")) { return "指定取图相机或相机相关属性。修改前确认顶部/底部相机方向和标定。"; }
        return "参数“" + raw + "”控制当前视觉阶段的行为。原值和编辑能力保持不变；请小步调整，并用独立样本比较误检与漏检。";
    }
}
