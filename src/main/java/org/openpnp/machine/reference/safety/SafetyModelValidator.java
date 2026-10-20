/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;

public final class SafetyModelValidator {
    private SafetyModelValidator() {
    }

    public static List<String> validate(SafetyWorkbenchModel model) {
        List<String> issues = new ArrayList<>();
        if (!finite(model.getMinX(), model.getMaxX(), model.getMinY(), model.getMaxY(),
                model.getMinZ(), model.getMaxZ(), model.getToolRadiusX(), model.getToolRadiusY(),
                model.getToolMinZOffset(), model.getToolMaxZOffset(), model.getSampleStep())) {
            issues.add("工作台范围、包络和采样间距必须是有限数值。");
        }
        if (!(model.getMinX() < model.getMaxX())
                || !(model.getMinY() < model.getMaxY())
                || !(model.getMinZ() < model.getMaxZ())) {
            issues.add("工作台各轴最小位置必须小于最大位置。");
        }
        if (model.getToolRadiusX() <= 0 || model.getToolRadiusY() <= 0
                || model.getToolMinZOffset() >= model.getToolMaxZOffset()) {
            issues.add("工具头安全包络无效，请检查半宽、半深和 Z 偏移。");
        }
        if (model.getSampleStep() <= 0 || model.getSampleStep() > 5) {
            issues.add("轨迹采样间距应大于 0 且不超过 5 mm。");
        }
        if (model.isFastTravelFloorEnabled()
                && (!Double.isFinite(model.getFastTravelFloorZ())
                    || model.getFastTravelFloorZ() <= model.getMinZ()
                    || model.getFastTravelFloorZ() >= model.getMaxZ())) {
            issues.add("快速进场 Z 必须位于工作台有效 Z 行程内，且不得代替实际障碍包络。");
        }
        if (model.getToolProfiles().isEmpty()) {
            issues.add("尚未建立任何贴装头/吸嘴安全包络。");
        }
        for (ToolProfile profile : model.getToolProfiles()) {
            if (profile == null) {
                continue;
            }
            String name = profile.name == null || profile.name.trim().isEmpty()
                    ? "未命名吸嘴" : profile.name;
            if (!finite(profile.bodyRadiusX, profile.bodyRadiusY, profile.bodyMinZOffset,
                    profile.bodyMaxZOffset, profile.shaftRadius, profile.clearance,
                    profile.retractedZ, profile.extendedZ, profile.retractedTipLength,
                    profile.extendedTipLength)) {
                issues.add("“" + name + "”的包络包含无效数值。");
            }
            if (!profile.enabled) {
                issues.add("“" + name + "”的安全包络被禁用；多头机器不能忽略任一吸嘴。");
                continue;
            }
            if (profile.headId == null || profile.headId.trim().isEmpty()
                    || profile.toolId == null || profile.toolId.trim().isEmpty()) {
                issues.add("“" + name + "”没有关联真实贴装头和吸嘴。");
            }
            if (profile.bodyRadiusX <= 0 || profile.bodyRadiusY <= 0
                    || profile.shaftRadius <= 0 || profile.clearance < 0) {
                issues.add("“" + name + "”的横向包络或安全间隙无效。");
            }
            if (profile.bodyMinZOffset >= profile.bodyMaxZOffset
                    || profile.retractedTipLength <= 0
                    || profile.extendedTipLength <= 0) {
                issues.add("“" + name + "”的 Z 动态包络无效。");
            }
        }
        for (SafetyObject object : model.getObjects()) {
            if (object == null || !object.enabled) {
                continue;
            }
            String name = object.name == null || object.name.trim().isEmpty()
                    ? "未命名物体" : object.name;
            if (!finite(object.x, object.y, object.z, object.width, object.depth,
                    object.height, object.clearance, object.apertureWidth, object.apertureDepth)) {
                issues.add("“" + name + "”的几何包含无效数值。");
            }
            if (object.polygon != null) {
                for (SafetyWorkbenchModel.Point2 point : object.polygon) {
                    if (point == null || !finite(point.x, point.y)) {
                        issues.add("“" + name + "”的轮廓点包含无效数值。");
                        break;
                    }
                }
            }
            if (object.width <= 0 || object.depth <= 0 || object.height < 0) {
                issues.add("“" + name + "”的尺寸无效。");
            }
            if (object.clearance < 0) {
                issues.add("“" + name + "”的安全间隙不能为负数。");
            }
            if (object.shape == Shape.POLYGON
                    && (object.polygon == null || object.polygon.size() < 3)) {
                issues.add("“" + name + "”的自定义轮廓至少需要 3 个点。");
            }
            if ((object.type == SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA
                    || object.type == SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER)
                    && (object.apertureWidth < 0 || object.apertureDepth < 0
                            || object.apertureWidth > object.width
                            || object.apertureDepth > object.depth)) {
                issues.add("“" + name + "”的中央通道尺寸超出实体外形。");
            }
        }
        return issues;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
