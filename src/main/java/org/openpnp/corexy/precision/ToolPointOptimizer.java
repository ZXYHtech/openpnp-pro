package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolPointOptimizer {
    public interface Backend {
        ToolPointModel.Observation observe(double z, double angle) throws Exception;
        void apply(ToolPointModel model) throws Exception;
        default ToolPointModel.Observation observeRaw(double z, double angle) throws Exception {
            return observe(z, angle);
        }
        default double noiseFloor() {
            return 0;
        }
    }
    public ToolPointModel calibrate(Backend backend, String nozzle, String tip, double centerZ,
            double heightSpan, double target, double maximumOffset) throws Exception {
        if (!Double.isFinite(centerZ) || !Double.isFinite(heightSpan) || heightSpan < .2 || heightSpan > 10) {
            throw new IllegalArgumentException("Invalid tool calibration height span.");
        }
        backend.apply(null);
        ToolPointModel model = new ToolPointModel();
        model.nozzleId = nozzle;
        model.tipId = tip;
        model.minZ = centerZ - heightSpan / 2;
        model.maxZ = centerZ + heightSpan / 2;
        model.maximumOffset = maximumOffset;
        model.replacesNative = true;
        List<ToolPointModel.Observation> stationary = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            stationary.add(backend.observeRaw(centerZ, 0));
        }
        double meanX = 0, meanY = 0;
        for (ToolPointModel.Observation observation : stationary) {
            meanX += observation.x / stationary.size();
            meanY += observation.y / stationary.size();
        }
        double variance = 0;
        for (ToolPointModel.Observation observation : stationary) {
            variance += Math.pow(observation.x - meanX, 2) + Math.pow(observation.y - meanY, 2);
        }
        model.noise = Math.max(backend.noiseFloor(), Math.sqrt(variance / (2 * stationary.size())));
        if (model.noise * 3 >= target) {
            return null;
        }
        List<ToolPointModel.Observation> training = new ArrayList<>(), validation = new ArrayList<>();
        for (double z : new double[] {model.minZ, centerZ, model.maxZ}) {
            for (int angle = 0; angle < 360; angle += 45) {
                training.add(backend.observeRaw(z, angle));
            }
        }
        for (double z : new double[] {centerZ - heightSpan / 4, centerZ + heightSpan / 4}) {
            for (double angle = 22.5; angle < 360; angle += 45) {
                validation.add(backend.observeRaw(z, angle));
            }
        }
        model.fit(training);
        List<ToolPointModel.Observation> nativeBaseline = new ArrayList<>();
        for (ToolPointModel.Observation observation : validation) {
            nativeBaseline.add(backend.observe(observation.z, observation.angle));
        }
        model.beforeP95 = p95(nativeBaseline, null);
        model.afterP95 = p95(validation, model);
        if (model.afterP95 >= model.beforeP95 - Math.max(model.noise * 2, model.beforeP95 * .08)) {
            return null;
        }
        model.validate();
        boolean keep = false;
        try {
            backend.apply(model);
            List<ToolPointModel.Observation> finalCheck = new ArrayList<>();
            for (double z : new double[] {centerZ - heightSpan * .4, centerZ + heightSpan * .4}) {
                for (double angle = 11.25; angle < 360; angle += 60) {
                    finalCheck.add(backend.observe(z, angle));
                }
            }
            model.afterP95 = p95(finalCheck, null);
            keep = model.afterP95 < model.beforeP95 - Math.max(model.noise * 2, model.beforeP95 * .08);
            return keep ? model : null;
        }
        finally {
            if (!keep) {
                backend.apply(null);
            }
        }
    }
    private static double p95(List<ToolPointModel.Observation> observations, ToolPointModel model) {
        List<Double> errors = new ArrayList<>();
        for (ToolPointModel.Observation observation : observations) {
            double[] correction = model == null ? new double[] {0, 0} : model.predict(observation.z, observation.angle);
            errors.add(Math.hypot(observation.x - correction[0], observation.y - correction[1]));
        }
        Collections.sort(errors);
        return errors.get((int) Math.ceil(errors.size() * .95) - 1);
    }
}
