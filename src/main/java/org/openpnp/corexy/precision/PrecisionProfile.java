package org.openpnp.corexy.precision;

/** A real measured profile, never a simulated fit. */
public final class PrecisionProfile {
    public int schema = 1;
    /** v2 excludes live controller position reports from configuration identity. */
    public int fingerprintVersion = 1;
    public String fingerprint, axisX, axisY, temperatureActuator = "", controllerRestoreCommand = "";
    public String controllerApplyCommand = "";
    public String previousBacklashX, previousBacklashY;
    public long createdAt;
    /** Camera measurement settling for calibration and verification, not a production dwell. */
    public int settlingMillis;
    public double noise, target;
    public PrecisionModel model;
    public PrecisionMetrics baseline, corrected;
    public java.util.Map<String, ToolPointModel> toolModels = new java.util.LinkedHashMap<>();
    public FixedMarkerSetup fixedMarkers;
    public double referenceOffsetX, referenceOffsetY;
    public String solutionId = "", calibrationDirectory = "";
    /** Empty preserves the original Home-marker datum and verification recipe. */
    public String modelDatumMarkerId = "";
    /** Compensated Home signature measured after a successful legacy model-datum verification. */
    public double[] homeReference;

    public void validate(String currentFingerprint) {
        if (fingerprintVersion < 1 || fingerprintVersion > 2) {
            throw new IllegalArgumentException("不支持的补偿配置指纹版本，请更新软件。");
        }
        if (fingerprint == null || !fingerprint.equals(currentFingerprint)) {
            throw new IllegalArgumentException(fingerprintVersion == 1
                    ? "旧补偿档案配置校验不匹配（旧版包含位置报告）；可停用补偿、查看点位并重新五点校准，原方案保留。"
                    : "机器几何或运动配置与校准时不一致；请核对轴、相机标定和吸嘴偏移，或重新五点校准。原方案保留。");
        }
        if (homeReference != null && (homeReference.length != 2
                || !Double.isFinite(homeReference[0]) || !Double.isFinite(homeReference[1]))) {
            throw new IllegalArgumentException("Home 参考数据无效，请重新5点精度验证。");
        }
        if (fixedMarkers != null) {
            fixedMarkers.validate();
        }
        if (modelDatumMarkerId == null || (!modelDatumMarkerId.isEmpty()
                && (fixedMarkers == null || fixedMarkers.markers.stream()
                    .noneMatch(marker -> marker.id.equals(modelDatumMarkerId))))) {
            throw new IllegalArgumentException("模型基准 marker 不在已保存的固定点中。");
        }
        if (!Double.isFinite(referenceOffsetX) || !Double.isFinite(referenceOffsetY)
                || solutionId == null || (!solutionId.isEmpty() && !solutionId.matches("[A-Za-z0-9_-]{1,96}"))
                || calibrationDirectory == null) {
            throw new IllegalArgumentException("固定 marker 基准或方案编号无效。");
        }
        if (schema != 1
                || axisX == null || axisY == null || axisX.equals(axisY) || createdAt <= 0
                || settlingMillis < 0 || settlingMillis > 5000 || !Double.isFinite(noise) || noise < 0
                || !Double.isFinite(target) || target <= 0 || model == null || baseline == null || corrected == null
                || corrected.count < 8 || baseline.count < 8 || !Double.isFinite(corrected.p95)
                || !Double.isFinite(corrected.rms) || !Double.isFinite(corrected.maximum)
                || temperatureActuator == null
                || (model.getLayers().contains(PrecisionModel.Layer.THERMAL_DRIFT) && temperatureActuator.isEmpty())) {
            throw new IllegalArgumentException("补偿档案数据不完整或数值无效，请恢复有效档案或重新校准。");
        }
        java.util.List<String> rejected = PrecisionAcceptance.reasons(baseline, corrected, noise);
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("补偿档案实测评估未通过：" + String.join("；", rejected));
        }
        model.validate();
        if (toolModels == null) {
            throw new IllegalArgumentException("Missing tool model collection.");
        }
        for (ToolPointModel tool : toolModels.values()) {
            tool.validate();
        }
        if (controllerApplyCommand == null || controllerRestoreCommand == null
                || previousBacklashX == null || previousBacklashY == null) {
            throw new IllegalArgumentException("Missing rollback settings.");
        }
        org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.valueOf(previousBacklashX);
        org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.valueOf(previousBacklashY);
        if (!controllerApplyCommand.isEmpty()) {
            ControllerShaping.validateCommand(controllerApplyCommand);
            ControllerShaping.validateCommand(controllerRestoreCommand);
        }
    }
}
