package org.openpnp.corexy.precision;

/** User choices expressed in millimetres; hardware identities are resolved by the backend. */
public final class CalibrationRequest {
    public double originX = 100;
    public double originY = 100;
    public double planeZ;
    public double plateAngle;
    public double pitch = 10;
    public double markerDiameter = 1;
    public double target = .05;
    public double maximumOffset = .2;
    public double conditioningTravel = 48;
    public double toolHeightSpan = .5;
    public int gridSize = 5;
    public int repeats = 2;
    public int settleMillis = 300;
    public boolean spatial = true;
    public boolean optimizeShaping;
    public boolean calibrateTools;
    public boolean autoEnable = true;
    public java.util.Map<String, double[]> artifactOffsets = new java.util.LinkedHashMap<>();
    public String temperatureActuator = "";
    /** Null retains the original single marker / grid workflow. */
    public FixedMarkerSetup fixedMarkers;

    public void validate() {
        if (fixedMarkers != null) {
            fixedMarkers.validate();
        }
        double[] values = {originX, originY, planeZ, plateAngle, pitch, markerDiameter,
                target, maximumOffset, conditioningTravel, toolHeightSpan};
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("校准设置必须是有限数值。");
            }
        }
        if (pitch < 2 || pitch > 100 || markerDiameter < .1 || markerDiameter > 10
                || target <= 0 || target > 1 || maximumOffset <= 0 || maximumOffset > 2
                || conditioningTravel < 3 || conditioningTravel > 128 || toolHeightSpan < .2 || toolHeightSpan > 10
                || repeats < 1 || repeats > 5
                || (gridSize != 5 && gridSize != 9) || settleMillis < 0 || settleMillis > 2000
                || temperatureActuator == null || artifactOffsets == null) {
            throw new IllegalArgumentException("校准设置超出支持范围。");
        }
        for (java.util.Map.Entry<String, double[]> entry : artifactOffsets.entrySet()) {
            int marker = Integer.parseInt(entry.getKey());
            double[] value = entry.getValue();
            if (marker < 0 || marker >= gridSize * gridSize || value == null || value.length != 2
                    || !Double.isFinite(value[0]) || !Double.isFinite(value[1])
                    || Math.hypot(value[0], value[1]) > maximumOffset) {
                throw new IllegalArgumentException("标定板误差档案无效。");
            }
        }
    }
}
