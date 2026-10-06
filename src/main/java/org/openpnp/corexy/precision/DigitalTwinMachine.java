package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Independent physical plant. It integrates every physical leg, including correction movements. */
public final class DigitalTwinMachine implements CalibrationMachine {
    private final Random random;
    private CalibrationRequest request;
    private PrecisionModel model;
    private ReversalState runtimeA = new ReversalState(), runtimeB = new ReversalState();
    private double physicalA, physicalB, physicalX = 100, physicalY = 100;
    private double logicalX = 100, logicalY = 100, offsetX, offsetY;
    private int observations;
    private int settling;
    private boolean toolCorrected, shaped;
    public double imageNoise = .0008;
    public boolean corruptAppliedCorrection;
    public boolean zeroSystematicError;
    public boolean finished, kept;

    public DigitalTwinMachine(long seed) {
        random = new Random(seed);
    }

    @Override
    public boolean isSimulation() {
        return true;
    }

    @Override
    public String fingerprint() {
        return "SIMULATION-ONLY-CoreXY-independent-plant-v1";
    }

    @Override
    public void prepare(CalibrationRequest settings, CalibrationPlan plan) {
        settings.validate();
        List<CalibrationPlan.Path> paths = new ArrayList<>(plan.training);
        paths.addAll(plan.validation);
        paths.addAll(plan.finalCheck);
        for (CalibrationPlan.Path path : paths) {
            for (double[] point : path.points(settings.conditioningTravel)) {
                if (point[0] < 2 || point[0] > 298 || point[1] < 2 || point[1] > 298) {
                    throw new IllegalArgumentException("模拟工作区软限位：全部路径必须位于 2..298 mm。");
                }
            }
        }
        request = settings;
        settling = settings.settleMillis;
        apply(null, settling);
    }

    @Override
    public double measureNoise() {
        return imageNoise;
    }

    @Override
    public PrecisionSample observe(CalibrationPlan.Path path) {
        if (path.homeStart) {
            apply(model, settling);
        }
        double temperature = 24 + 2 * Math.sin(observations * .47);
        for (double[] point : path.points(request.conditioningTravel)) {
            move(point[0], point[1], path.speed, path.accelerationFactor * 1000, temperature);
        }
        double[] required = plant(path, temperature);
        double measuredX = required[0] - offsetX + random.nextGaussian() * imageNoise;
        double measuredY = required[1] - offsetY + random.nextGaussian() * imageNoise;
        observations++;
        return new PrecisionSample(path.id, "top-camera", path.x, path.y, request.planeZ, 0,
                path.speed, path.accelerationFactor * 1000, temperature, observations,
                path.signA * path.a, path.signB * path.b, path.a, path.b, settling,
                path.signA, path.signB, measuredX, measuredY);
    }

    private void move(double x, double y, double speed, double acceleration, double temperature) {
        double dx = x - logicalX, dy = y - logicalY;
        physicalMove(x + offsetX, y + offsetY);
        if (model != null) {
            runtimeA.advance(dx + dy, model.getTransitionA());
            runtimeB.advance(dx - dy, model.getTransitionB());
            PrecisionSample query = new PrecisionSample("sim-runtime", "top-camera", x, y, request.planeZ,
                    0, speed, acceleration, temperature, observations, dx + dy, dx - dy,
                    runtimeA.getTravel(), runtimeB.getTravel(), settling,
                    runtimeA.getDirection(), runtimeB.getDirection(), 0, 0);
            double[] correction = model.predict(query, runtimeA.getValue(), runtimeB.getValue());
            offsetX = correction[0] * (corruptAppliedCorrection ? -1 : 1);
            offsetY = correction[1] * (corruptAppliedCorrection ? -1 : 1);
            physicalMove(x + offsetX, y + offsetY);
        }
        logicalX = x;
        logicalY = y;
    }

    private void physicalMove(double x, double y) {
        double dx = x - physicalX, dy = y - physicalY;
        physicalA = Math.max(-1, Math.min(1, physicalA + 2 * (dx + dy) / 12));
        physicalB = Math.max(-1, Math.min(1, physicalB + 2 * (dx - dy) / 6));
        physicalX = x;
        physicalY = y;
    }

    private double[] plant(CalibrationPlan.Path path, double temperature) {
        if (zeroSystematicError) {
            return new double[] {0, 0};
        }
        double u = (path.x - request.originX) / (request.pitch * (request.gridSize - 1));
        double v = (path.y - request.originY) / (request.pitch * (request.gridSize - 1));
        double dv = path.speed - .6, da = path.accelerationFactor - .75;
        double vibration = (shaped ? .002 : .025) * Math.exp(-settling / 85.0);
        return new double[] {.023 + .028 * physicalA - .013 * physicalB
                    + .009 * physicalA * physicalB + .012 * u * v + .008 * u + .012 * u * physicalA
                    + .008 * physicalA * dv + .006 * physicalB * da
                    + .002 * (temperature - 24) + vibration + (toolCorrected ? 0 : .004),
                -.018 + .012 * physicalA + .026 * physicalB - .007 * physicalA * physicalB
                    - .009 * u * v + .006 * v + .015 * v * physicalB + .007 * physicalB * dv
                    - .005 * physicalA * da - .0015 * (temperature - 24)};
    }

    @Override
    public void apply(PrecisionModel candidate, int settlingMillis) {
        model = candidate;
        settling = settlingMillis;
        runtimeA.reset();
        runtimeB.reset();
        physicalA = -1;
        physicalB = 1;
        runtimeA.initialize(candidate == null ? 0 : candidate.getHomeA());
        runtimeB.initialize(candidate == null ? 0 : candidate.getHomeB());
        offsetX = 0;
        offsetY = 0;
        logicalX = physicalX = 0;
        logicalY = physicalY = 0;
    }

    @Override
    public void calibrateHomeState(PrecisionModel candidate) {
        List<HomeStateFitter.Observation> observations = new ArrayList<>();
        for (int repetition = 0; repetition < 3; repetition++) {
            apply(null, settling);
            CalibrationPlan.Path path = new CalibrationPlan.Path("home-fitting-" + repetition,
                    request.originX, request.originY, 0, 0, .6, 1, 0, 0, 1, settling);
            path.homeStart = true;
            PrecisionSample sample = observe(path);
            observations.add(new HomeStateFitter.Observation(sample, java.util.Collections.emptyList(), path.x, path.y));
        }
        new HomeStateFitter().fit(candidate, observations);
    }

    @Override
    public List<String> optimizeControllerAndTools(CalibrationRequest settings) throws Exception {
        List<String> notes = new ArrayList<>();
        shaped = settings.optimizeShaping;
        toolCorrected = settings.calibrateTools;
        settling = new SettlingOptimizer().select(delay -> new double[] {
                .023 + (shaped ? .002 : .025) * Math.exp(-delay / 85.0)
                    + random.nextGaussian() * imageNoise,
                -.018 + random.nextGaussian() * imageNoise}, imageNoise, settings.target);
        settings.settleMillis = settling;
        notes.add("已根据静止端点测量自动选择等待时间：" + settling + " ms。");
        notes.add(shaped ? "模拟控制器输入整形已参与最终路径验证。" : "输入整形未选择。");
        notes.add(toolCorrected ? "模拟吸嘴工具点校准完成。" : "吸嘴工具点校准未选择。");
        return notes;
    }

    @Override
    public void finish(boolean keep, PrecisionModel candidate, int settlingMillis) {
        finished = true;
        kept = keep;
        if (!keep) {
            apply(null, request == null ? 300 : request.settleMillis);
        }
    }
}
