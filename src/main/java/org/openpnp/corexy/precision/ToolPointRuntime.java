package org.openpnp.corexy.precision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/** Both nozzle and installed-tip identity must match. */
public final class ToolPointRuntime {
    private static volatile Map<String, ToolPointModel> models = Collections.emptyMap();
    private ToolPointRuntime() {
    }
    public static Map<String, ToolPointModel> snapshot() {
        return new LinkedHashMap<>(models);
    }
    public static void setModels(Map<String, ToolPointModel> selected) {
        Map<String, ToolPointModel> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ToolPointModel> entry : selected.entrySet()) {
            entry.getValue().validate();
            if (!entry.getKey().equals(entry.getValue().nozzleId)) {
                throw new IllegalArgumentException("Tool model identity mismatch.");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        models = Collections.unmodifiableMap(copy);
    }
    public static Location correction(ReferenceNozzle nozzle, Location target) {
        ToolPointModel model = models.get(nozzle.getId());
        if (model == null || nozzle.getNozzleTip() == null || !model.tipId.equals(nozzle.getNozzleTip().getId())) {
            return new Location(target.getUnits());
        }
        if (!(nozzle.getNozzleTip() instanceof ReferenceNozzleTip)) {
            return new Location(target.getUnits());
        }
        org.openpnp.machine.reference.ReferenceNozzleTipCalibration calibration =
                ((ReferenceNozzleTip) nozzle.getNozzleTip()).getCalibration();
        if (calibration.isCalibrating() || !calibration.isEnabled() || !calibration.isCalibrated(nozzle)
                || (!model.replacesNative && !model.nativeSignature.equals(nativeSignature(nozzle)))) {
            return new Location(target.getUnits());
        }
        Location mm = target.convertToUnits(LengthUnit.Millimeters);
        double[] delta = model.predict(mm.getZ(), mm.getRotation());
        Location result = new Location(LengthUnit.Millimeters, delta[0], delta[1], 0, 0);
        if (model.replacesNative) {
            result = result.subtract(calibration.getCalibratedOffset(nozzle, target.getRotation()));
        }
        return result.convertToUnits(target.getUnits());
    }

    public static boolean replacesNative(ReferenceNozzle nozzle) {
        ToolPointModel model = models.get(nozzle.getId());
        return model != null && model.replacesNative && nozzle.getNozzleTip() != null
                && nozzle.getNozzleTip() instanceof ReferenceNozzleTip
                && model.tipId.equals(nozzle.getNozzleTip().getId())
                && ((ReferenceNozzleTip) nozzle.getNozzleTip()).getCalibration().isEnabled()
                && ((ReferenceNozzleTip) nozzle.getNozzleTip()).getCalibration().isCalibrated(nozzle)
                && !((ReferenceNozzleTip) nozzle.getNozzleTip()).getCalibration().isCalibrating();
    }

    public static String nativeSignature(ReferenceNozzle nozzle) {
        if (!(nozzle.getNozzleTip() instanceof ReferenceNozzleTip)) {
            return "";
        }
        org.openpnp.machine.reference.ReferenceNozzleTipCalibration calibration =
                ((ReferenceNozzleTip) nozzle.getNozzleTip()).getCalibration();
        StringBuilder signature = new StringBuilder(Boolean.toString(calibration.isEnabled()));
        for (double angle : new double[] {0, 90, 180, 270}) {
            Location offset = calibration.getCalibratedOffset(nozzle, angle).convertToUnits(LengthUnit.Millimeters);
            signature.append('|').append(offset.getX()).append('|').append(offset.getY());
        }
        return signature.toString();
    }
}
