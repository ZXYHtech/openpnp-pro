package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import org.openpnp.corexy.precision.PrecisionModel.Layer;

/** Fits competing models using complete held-out paths, never individual repeated frames. */
public final class PrecisionFitter {
    public static final class Candidate {
        public final PrecisionModel model;
        public final PrecisionMetrics training, validation;
        public final boolean acceptable;
        public final String reason;
        Candidate(PrecisionModel model, PrecisionMetrics training, PrecisionMetrics validation,
                boolean acceptable, String reason) {
            this.model = model;
            this.training = training;
            this.validation = validation;
            this.acceptable = acceptable;
            this.reason = reason;
        }
    }

    public static final class Result {
        public final PrecisionMetrics baseline;
        public final List<Candidate> candidates;
        public final List<String> unavailable;
        public final Candidate selected;
        Result(PrecisionMetrics baseline, List<Candidate> candidates, List<String> unavailable,
                Candidate selected) {
            this.baseline = baseline;
            this.candidates = java.util.Collections.unmodifiableList(candidates);
            this.unavailable = java.util.Collections.unmodifiableList(unavailable);
            this.selected = selected;
        }
    }

    public Result evaluate(List<PrecisionSample> training, List<PrecisionSample> validation,
            double noise, double target, double maximumOffset, int columns, int rows) {
        return evaluate(training, validation, noise, target, maximumOffset, columns, rows, 96);
    }

    public Result evaluate(List<PrecisionSample> training, List<PrecisionSample> validation,
            double noise, double target, double maximumOffset, int columns, int rows,
            double conditioningTravel) {
        if (training == null || training.size() < 12 || validation == null || validation.size() < 8
                || !Double.isFinite(noise) || noise < 0 || !Double.isFinite(target) || target <= 0
                || !Double.isFinite(maximumOffset) || maximumOffset <= 0 || maximumOffset > 2
                || columns < 2 || rows < 2 || columns > 15 || rows > 15
                || !Double.isFinite(conditioningTravel) || conditioningTravel < 1) {
            throw new IllegalArgumentException("Insufficient or invalid calibration data.");
        }
        Set<String> paths = new LinkedHashSet<>();
        for (PrecisionSample s : training) { paths.add(s.path);
            }
        for (PrecisionSample s : validation) { if (paths.contains(s.path)) {
            throw new IllegalArgumentException("Validation path leaks into the training set: " + s.path);
        } }
        double[] domain = domain(training);
        List<String> unavailable = new ArrayList<>();
        EnumSet<Layer> available = EnumSet.allOf(Layer.class);
        if (domain[1] - domain[0] < 10 || domain[3] - domain[2] < 10) {
            available.remove(Layer.AFFINE_GEOMETRY);
            available.remove(Layer.SPATIAL_GRID);
            available.remove(Layer.STATE_SPATIAL);
            unavailable.add("空间模型：采样没有覆盖足够的二维工作区。");
        }
        if (range(training, 0) < .1 || range(training, 1) < .1) {
            available.remove(Layer.SPEED_ACCELERATION);
            unavailable.add("速度/加速度模型：至少需要分别变化的速度与加速度样本。");
        }
        if (!hasTemperature(training) || !hasTemperature(validation) || range(training, 2) < 2) {
            available.remove(Layer.THERMAL_DRIFT);
            unavailable.add("温漂模型：需要有效温度传感器与至少 2°C 的样本跨度。");
        }
        if (range(training, 3) < 90 || range(training, 4) < .2) {
            available.remove(Layer.TOOL_POINT);
            unavailable.add("工具点模型：需要同一吸嘴的多角度与多高度观测。");
        }
        PrecisionMetrics baseline = PrecisionMetrics.evaluate(validation, null);
        List<Candidate> candidates = new ArrayList<>();
        double[] transitions = findTransitions(training, available, domain, columns, rows,
                maximumOffset, conditioningTravel);
        // Enumerate all supported layer combinations; redundant affine/grid and fixed/continuous
        // bases are alternatives rather than stacked twice.
        Layer[] all = Layer.values();
        for (int mask = 1; mask < (1 << all.length); mask++) {
            EnumSet<Layer> selected = EnumSet.noneOf(Layer.class);
            for (int j = 0; j < all.length; j++) { if ((mask & (1 << j)) != 0) { selected.add(all[j]);
                    } }
            if (!available.containsAll(selected)
                    || (selected.contains(Layer.FIXED_STATE) && selected.contains(Layer.CONTINUOUS_REVERSAL))
                    || (selected.contains(Layer.AFFINE_GEOMETRY) && selected.contains(Layer.SPATIAL_GRID))
                    || ((selected.contains(Layer.JOINT_STATE) || selected.contains(Layer.SPEED_ACCELERATION)
                        || selected.contains(Layer.STATE_SPATIAL))
                        && !selected.contains(Layer.FIXED_STATE) && !selected.contains(Layer.CONTINUOUS_REVERSAL))) { continue;
                }
            if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException();
                }
            Candidate best = null;
            // Identify the shared mechanical transition once from training, then compare bases.
            double a = selected.contains(Layer.CONTINUOUS_REVERSAL) ? transitions[0] : 1;
            double b = selected.contains(Layer.CONTINUOUS_REVERSAL) ? transitions[1] : 1;
                try {
                    PrecisionModel model = new PrecisionModel(selected, domain, a, b,
                            columns, rows, maximumOffset);
                    fit(model, training);
                    PrecisionMetrics metrics = PrecisionMetrics.evaluate(training, model);
                    if (best == null || metrics.rms < best.training.rms) {
                        PrecisionMetrics heldOut = PrecisionMetrics.evaluate(validation, model);
                        java.util.List<String> reasons = PrecisionAcceptance.reasons(baseline, heldOut, noise);
                        boolean accepted = reasons.isEmpty();
                        String reason = accepted ? "独立路径误差降低且最大偏差未退化" : String.join("；", reasons);
                        best = new Candidate(model, metrics, heldOut, accepted, reason);
                    }
                }
                catch (IllegalArgumentException | IllegalStateException rejected) {
                    // Singular and out-of-envelope candidates are ineligible, not silently enabled.
                }
            if (best != null) { candidates.add(best);
                }
        }
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.validation.p95)
                .thenComparingDouble(c -> c.validation.maximum).thenComparingInt(c -> c.model.getParameterCount()));
        Candidate selected = null;
        for (Candidate c : candidates) { if (c.acceptable) {
            if (selected == null) { selected = c;
                    }
            // Prefer the simpler model when the more complex one's difference is within noise.
            else if (c.validation.p95 <= selected.validation.p95 + Math.max(noise, .0005)
                    && c.model.getParameterCount() < selected.model.getParameterCount()) { selected = c;
                    }
        } }
        return new Result(baseline, candidates, unavailable, selected);
    }

    private double[] findTransitions(List<PrecisionSample> samples, EnumSet<Layer> available,
            double[] domain, int columns, int rows, double maximumOffset, double conditioning) {
        EnumSet<Layer> basis = EnumSet.copyOf(available);
        basis.remove(Layer.FIXED_STATE);
        basis.remove(Layer.SPATIAL_GRID);
        basis.remove(Layer.STATE_SPATIAL);
        double score = Double.POSITIVE_INFINITY;
        double[] result = {1, 1};
        for (double a : new double[] {1, 3, 6, 12, 24, 48, 96}) {
            for (double b : new double[] {1, 3, 6, 12, 24, 48, 96}) {
                if (a > conditioning || b > conditioning) {
                    continue;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                try {
                    PrecisionModel model = new PrecisionModel(basis, domain, a, b, columns, rows, maximumOffset);
                    fit(model, samples);
                    double rms = PrecisionMetrics.evaluate(samples, model).rms;
                    if (rms < score) {
                        score = rms;
                        result = new double[] {a, b};
                    }
                }
                catch (IllegalArgumentException | IllegalStateException rejected) {
                    // Identifiability is checked again for each smaller candidate basis.
                }
            }
        }
        return result;
    }

    private void fit(PrecisionModel model, List<PrecisionSample> samples) {
        model.setOperatingDomain(samples);
        double[][] first = new double[samples.size()][];
        double[] x = new double[samples.size()], y = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            first[i] = model.features(samples.get(i));
            x[i] = samples.get(i).correctionX;
            y[i] = samples.get(i).correctionY;
        }
        int width = first[0].length;
        if (samples.size() < width * 4) { throw new IllegalArgumentException("Too few independent observations.");
            }
        if (new SingularValueDecomposition(new Array2DRowRealMatrix(first, false)).getRank() < width) {
            throw new IllegalArgumentException("Unobservable model parameters.");
        }
        double[] weights = new double[samples.size()];
        java.util.Arrays.fill(weights, 1);
        double[] solutionX = null, solutionY = null;
        // Robust iteratively reweighted least squares rejects occasional image localization outliers.
        for (int iteration = 0; iteration < 4; iteration++) {
            double[][] design = new double[first.length + width][width];
            double[] bx = new double[design.length], by = new double[design.length];
            for (int i = 0; i < first.length; i++) {
                double w = Math.sqrt(weights[i]);
                for (int j = 0; j < width; j++) { design[i][j] = first[i][j] * w;
                    }
                bx[i] = x[i] * w;
                by[i] = y[i] * w;
            }
            for (int j = 0; j < width; j++) { design[first.length + j][j] = 1e-5;
                }
            RealMatrix matrix = new Array2DRowRealMatrix(design, false);
            org.apache.commons.math3.linear.DecompositionSolver solver = new QRDecomposition(matrix, 1e-12).getSolver();
            solutionX = solver.solve(new ArrayRealVector(bx, false)).toArray();
            solutionY = solver.solve(new ArrayRealVector(by, false)).toArray();
            double[] residuals = new double[first.length];
            for (int i = 0; i < first.length; i++) {
                double dx = x[i], dy = y[i];
                for (int j = 0; j < width; j++) { dx -= first[i][j] * solutionX[j];
                    dy -= first[i][j] * solutionY[j];
                    }
                residuals[i] = Math.hypot(dx, dy);
            }
            double[] sorted = residuals.clone();
            java.util.Arrays.sort(sorted);
            double cutoff = Math.max(.0005, sorted[sorted.length / 2] * 2.5);
            for (int i = 0; i < weights.length; i++) { weights[i] = Math.min(1, cutoff / Math.max(cutoff, residuals[i]));
                }
        }
        model.setCoefficients(solutionX, solutionY);
    }

    private static double[] domain(List<PrecisionSample> samples) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = minX, maxY = maxX, speed = 0, acceleration = 0, temperature = 0, z = 0;
        int temperatures = 0;
        for (PrecisionSample s : samples) {
            minX = Math.min(minX, s.x);
            maxX = Math.max(maxX, s.x);
            minY = Math.min(minY, s.y);
            maxY = Math.max(maxY, s.y);
            speed += s.speed;
            acceleration += s.acceleration;
            z += s.z;
            if (Double.isFinite(s.temperature)) { temperature += s.temperature;
                temperatures++;
                }
        }
        // Single-marker state fits have no spatial terms but still need a nondegenerate gauge.
        if (maxX == minX) { minX -= 1;
            maxX += 1;
            }
        if (maxY == minY) { minY -= 1;
            maxY += 1;
            }
        return new double[] {minX, maxX, minY, maxY, speed / samples.size(),
                acceleration / samples.size(), temperatures == 0 ? 0 : temperature / temperatures, z / samples.size()};
    }

    private static boolean hasTemperature(List<PrecisionSample> samples) {
        for (PrecisionSample s : samples) { if (!Double.isFinite(s.temperature)) { return false;
                } }
        return true;
    }

    private static double range(List<PrecisionSample> samples, int kind) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (PrecisionSample s : samples) {
            double v = kind == 0 ? s.speed : kind == 1 ? s.acceleration : kind == 2 ? s.temperature
                    : kind == 3 ? s.angle : s.z;
            if (Double.isFinite(v)) { low = Math.min(low, v);
                high = Math.max(high, v);
                }
        }
        return high >= low ? high - low : 0;
    }
}
