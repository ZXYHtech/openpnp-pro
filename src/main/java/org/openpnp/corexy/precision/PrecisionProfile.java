package org.openpnp.corexy.precision;

/** A real measured profile, never a simulated fit. */
public final class PrecisionProfile {
    public int schema = 1;
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
        if (schema != 1 || fingerprint == null || !fingerprint.equals(currentFingerprint)
                || axisX == null || axisY == null || axisX.equals(axisY) || createdAt <= 0
                || settlingMillis < 0 || settlingMillis > 5000 || !Double.isFinite(noise) || noise < 0
                || !Double.isFinite(target) || target <= 0 || model == null || baseline == null || corrected == null
                || corrected.count < 8 || baseline.count < 8 || !Double.isFinite(corrected.p95)
                || !Double.isFinite(corrected.rms) || !Double.isFinite(corrected.maximum)
                || !PrecisionAcceptance.reasons(baseline, corrected, noise).isEmpty()
                || temperatureActuator == null
                || (model.getLayers().contains(PrecisionModel.Layer.THERMAL_DRIFT) && temperatureActuator.isEmpty())) {
            throw new IllegalArgumentException("补偿档案无效、机器配置已变化或独立验证不合格。");
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
