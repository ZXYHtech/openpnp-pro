package org.openpnp.corexy.diagnostics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Fits a noise-weighted correction surface to an irregular speed/distance grid. */
public final class CoreXYCalibrationSurface {
    private static final double[] BANDWIDTHS = {
        0.06, 0.09, 0.13, 0.18, 0.25, 0.35, 0.50, 0.75
    };
    private static final double[] CANDIDATE_GAINS = {
        0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50,
        0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00
    };

    public static final class Vector {
        private final double x;
        private final double y;

        Vector(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getMagnitude() { return Math.hypot(x, y); }
    }

    public static final class Metrics {
        private final double rmsMm;
        private final double p95Mm;
        private final double maximumMm;
        private final double maximumWorseningMm;

        Metrics(double rmsMm, double p95Mm, double maximumMm,
                double maximumWorseningMm) {
            this.rmsMm = rmsMm;
            this.p95Mm = p95Mm;
            this.maximumMm = maximumMm;
            this.maximumWorseningMm = maximumWorseningMm;
        }

        public double getRmsMm() { return rmsMm; }
        public double getP95Mm() { return p95Mm; }
        public double getMaximumMm() { return maximumMm; }
        public double getMaximumWorseningMm() { return maximumWorseningMm; }
    }

    public static final class Candidate {
        private final double gain;
        private final Metrics metrics;
        private final boolean eligible;

        Candidate(double gain, Metrics metrics, boolean eligible) {
            this.gain = gain;
            this.metrics = metrics;
            this.eligible = eligible;
        }

        public double getGain() { return gain; }
        public Metrics getMetrics() { return metrics; }
        public boolean isEligible() { return eligible; }
    }

    public static final class DirectionModel {
        private final String direction;
        private final List<CoreXYCalibrationAnalysis.Observation> observations;
        private final double speedBandwidth;
        private final double distanceBandwidth;
        private final double minimumSpeed;
        private final double maximumSpeed;
        private final double minimumDistanceMm;
        private final double maximumDistanceMm;

        private DirectionModel(String direction,
                List<CoreXYCalibrationAnalysis.Observation> observations,
                double speedBandwidth, double distanceBandwidth) {
            this.direction = direction;
            this.observations = Collections.unmodifiableList(
                    new ArrayList<>(observations));
            this.speedBandwidth = speedBandwidth;
            this.distanceBandwidth = distanceBandwidth;
            double minSpeed = Double.POSITIVE_INFINITY;
            double maxSpeed = Double.NEGATIVE_INFINITY;
            double minDistance = Double.POSITIVE_INFINITY;
            double maxDistance = Double.NEGATIVE_INFINITY;
            for (CoreXYCalibrationAnalysis.Observation observation : observations) {
                minSpeed = Math.min(minSpeed, observation.getSpeedFactor());
                maxSpeed = Math.max(maxSpeed, observation.getSpeedFactor());
                minDistance = Math.min(minDistance, observation.getDistanceMm());
                maxDistance = Math.max(maxDistance, observation.getDistanceMm());
            }
            minimumSpeed = minSpeed;
            maximumSpeed = maxSpeed;
            minimumDistanceMm = minDistance;
            maximumDistanceMm = maxDistance;
        }

        public String getDirection() { return direction; }
        public List<CoreXYCalibrationAnalysis.Observation> getObservations() {
            return observations;
        }
        public double getSpeedBandwidth() { return speedBandwidth; }
        public double getDistanceBandwidth() { return distanceBandwidth; }
        public double getMinimumSpeed() { return minimumSpeed; }
        public double getMaximumSpeed() { return maximumSpeed; }
        public double getMinimumDistanceMm() { return minimumDistanceMm; }
        public double getMaximumDistanceMm() { return maximumDistanceMm; }

        public Vector predict(double speedFactor, double distanceMm) {
            return predict(speedFactor, distanceMm, -1);
        }

        private Vector predict(double speedFactor, double distanceMm, int excluded) {
            double querySpeed = clamp(speedFactor, minimumSpeed, maximumSpeed);
            double queryDistance = clamp(distanceMm,
                    minimumDistanceMm, maximumDistanceMm);
            double queryS = normalizedSpeed(querySpeed);
            double queryD = normalizedDistance(queryDistance);
            double sumWeight = 0;
            double sumX = 0;
            double sumY = 0;
            int nearest = -1;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < observations.size(); index++) {
                if (index == excluded) {
                    continue;
                }
                CoreXYCalibrationAnalysis.Observation observation =
                        observations.get(index);
                double ds = (normalizedSpeed(observation.getSpeedFactor()) - queryS)
                        / speedBandwidth;
                double dd = (normalizedDistance(observation.getDistanceMm()) - queryD)
                        / distanceBandwidth;
                double squared = ds * ds + dd * dd;
                if (squared < nearestDistance) {
                    nearestDistance = squared;
                    nearest = index;
                }
                if (squared > 60.0) {
                    continue;
                }
                double variance = square(observation.getDetectionFloorMm())
                        + square(observation.getScatterMm());
                double weight = Math.exp(-0.5 * squared) / variance;
                sumWeight += weight;
                sumX += weight * observation.getCorrectionXmm();
                sumY += weight * observation.getCorrectionYmm();
            }
            if (sumWeight <= 1e-15) {
                if (nearest < 0) {
                    return new Vector(0, 0);
                }
                CoreXYCalibrationAnalysis.Observation observation =
                        observations.get(nearest);
                return new Vector(observation.getCorrectionXmm(),
                        observation.getCorrectionYmm());
            }
            return new Vector(sumX / sumWeight, sumY / sumWeight);
        }

        private double normalizedSpeed(double speed) {
            double range = maximumSpeed - minimumSpeed;
            return range <= 1e-12 ? 0.0 : (speed - minimumSpeed) / range;
        }

        private double normalizedDistance(double distance) {
            double range = Math.log(maximumDistanceMm / minimumDistanceMm);
            return range <= 1e-12 ? 0.0
                    : Math.log(distance / minimumDistanceMm) / range;
        }
    }

    public static final class FitResult {
        private final CoreXYCalibrationAnalysis.Analysis analysis;
        private final DirectionModel aModel;
        private final DirectionModel bModel;
        private final Metrics baselineMetrics;
        private final List<Candidate> candidates;
        private final int recommendedCandidateIndex;

        private FitResult(CoreXYCalibrationAnalysis.Analysis analysis,
                DirectionModel aModel, DirectionModel bModel,
                Metrics baselineMetrics, List<Candidate> candidates,
                int recommendedCandidateIndex) {
            this.analysis = analysis;
            this.aModel = aModel;
            this.bModel = bModel;
            this.baselineMetrics = baselineMetrics;
            this.candidates = Collections.unmodifiableList(
                    new ArrayList<>(candidates));
            this.recommendedCandidateIndex = recommendedCandidateIndex;
        }

        public CoreXYCalibrationAnalysis.Analysis getAnalysis() { return analysis; }
        public DirectionModel getAModel() { return aModel; }
        public DirectionModel getBModel() { return bModel; }
        public Metrics getBaselineMetrics() { return baselineMetrics; }
        public List<Candidate> getCandidates() { return candidates; }
        /** Returns a zero-based index, or -1 when no candidate passes safeguards. */
        public int getRecommendedCandidateIndex() { return recommendedCandidateIndex; }
    }

    public static final class ValidationResult {
        private final int observationCount;
        private final double gain;
        private final Metrics baselineMetrics;
        private final Metrics candidateMetrics;
        private final Metrics aBaselineMetrics;
        private final Metrics aCandidateMetrics;
        private final Metrics bBaselineMetrics;
        private final Metrics bCandidateMetrics;
        private final boolean passed;

        private ValidationResult(int observationCount, double gain,
                Metrics baselineMetrics, Metrics candidateMetrics,
                Metrics aBaselineMetrics, Metrics aCandidateMetrics,
                Metrics bBaselineMetrics, Metrics bCandidateMetrics,
                boolean passed) {
            this.observationCount = observationCount;
            this.gain = gain;
            this.baselineMetrics = baselineMetrics;
            this.candidateMetrics = candidateMetrics;
            this.aBaselineMetrics = aBaselineMetrics;
            this.aCandidateMetrics = aCandidateMetrics;
            this.bBaselineMetrics = bBaselineMetrics;
            this.bCandidateMetrics = bCandidateMetrics;
            this.passed = passed;
        }

        public int getObservationCount() { return observationCount; }
        public double getGain() { return gain; }
        public Metrics getBaselineMetrics() { return baselineMetrics; }
        public Metrics getCandidateMetrics() { return candidateMetrics; }
        public Metrics getABaselineMetrics() { return aBaselineMetrics; }
        public Metrics getACandidateMetrics() { return aCandidateMetrics; }
        public Metrics getBBaselineMetrics() { return bBaselineMetrics; }
        public Metrics getBCandidateMetrics() { return bCandidateMetrics; }
        public boolean isPassed() { return passed; }
    }

    private CoreXYCalibrationSurface() {
    }

    public static FitResult fitFiles(String pilotFileName,
            String refinementFileName) throws IOException {
        CoreXYCalibrationAnalysis.Analysis analysis =
                CoreXYCalibrationAnalysis.analyzeFiles(
                        pilotFileName, refinementFileName);
        if (analysis.getRecommendedModel()
                != CoreXYCalibrationAnalysis.ModelKind.DISTANCE_SPEED_LOOKUP) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Expected DISTANCE_SPEED_LOOKUP data, found %s.",
                    analysis.getRecommendedModel()));
        }
        List<CoreXYCalibrationAnalysis.Observation> a =
                direction(analysis.getObservations(), "A");
        List<CoreXYCalibrationAnalysis.Observation> b =
                direction(analysis.getObservations(), "B");
        DirectionModel aModel = tune("A", a);
        DirectionModel bModel = tune("B", b);

        List<Double> baseline = new ArrayList<>();
        List<Vector> observed = new ArrayList<>();
        List<Vector> predicted = new ArrayList<>();
        addValidation(aModel, baseline, observed, predicted);
        addValidation(bModel, baseline, observed, predicted);
        Metrics baselineMetrics = metrics(baseline, baseline, true);

        List<Candidate> candidates = new ArrayList<>();
        int recommended = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        double allowedWorsening = Math.max(0.010,
                analysis.getObservedFloorMm() * 2.0);
        for (double gain : CANDIDATE_GAINS) {
            List<Double> residuals = new ArrayList<>();
            for (int index = 0; index < observed.size(); index++) {
                Vector actual = observed.get(index);
                Vector estimate = predicted.get(index);
                residuals.add(Math.hypot(actual.x - gain * estimate.x,
                        actual.y - gain * estimate.y));
            }
            Metrics candidateMetrics = metrics(residuals, baseline, false);
            boolean eligible = candidateMetrics.rmsMm < baselineMetrics.rmsMm * 0.95
                    && candidateMetrics.p95Mm <= baselineMetrics.p95Mm * 1.05
                    && candidateMetrics.maximumMm <= baselineMetrics.maximumMm * 1.05
                    && candidateMetrics.maximumWorseningMm <= allowedWorsening;
            Candidate candidate = new Candidate(gain, candidateMetrics, eligible);
            candidates.add(candidate);
            double score = candidateMetrics.rmsMm
                    + 0.35 * candidateMetrics.p95Mm
                    + 0.15 * candidateMetrics.maximumMm;
            if (eligible && score < bestScore) {
                bestScore = score;
                recommended = candidates.size() - 1;
            }
        }
        return new FitResult(analysis, aModel, bModel, baselineMetrics,
                candidates, recommended);
    }

    /** Evaluates a fitted candidate against observations excluded from fitting. */
    public static ValidationResult validate(FitResult fit,
            List<CoreXYCalibrationAnalysis.Observation> observations,
            double gain) {
        if (fit == null || observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException(
                    "A fitted surface and held-out observations are required.");
        }
        if (!Double.isFinite(gain) || gain <= 0 || gain > 1) {
            throw new IllegalArgumentException("Validation gain must be in (0, 1].");
        }
        ValidationVectors all = validationVectors(fit, observations, gain, null);
        ValidationVectors a = validationVectors(fit, observations, gain, "A");
        ValidationVectors b = validationVectors(fit, observations, gain, "B");
        Metrics baseline = metrics(all.baseline, all.baseline, true);
        Metrics candidate = metrics(all.residual, all.baseline, false);
        Metrics aBaseline = metrics(a.baseline, a.baseline, true);
        Metrics aCandidate = metrics(a.residual, a.baseline, false);
        Metrics bBaseline = metrics(b.baseline, b.baseline, true);
        Metrics bCandidate = metrics(b.residual, b.baseline, false);
        double floor = fit.getAnalysis().getObservedFloorMm();
        boolean noTailRegression = candidate.p95Mm <= baseline.p95Mm
                && candidate.maximumMm <= baseline.maximumMm
                && candidate.maximumWorseningMm <= Math.max(0.010, floor * 2.0);
        boolean eachDirectionProtected =
                aCandidate.rmsMm <= aBaseline.rmsMm * 1.05
                && bCandidate.rmsMm <= bBaseline.rmsMm * 0.90;
        boolean passed = candidate.rmsMm <= baseline.rmsMm * 0.90
                && noTailRegression && eachDirectionProtected;
        return new ValidationResult(observations.size(), gain,
                baseline, candidate, aBaseline, aCandidate,
                bBaseline, bCandidate, passed);
    }

    private static final class ValidationVectors {
        final List<Double> baseline = new ArrayList<>();
        final List<Double> residual = new ArrayList<>();
    }

    private static ValidationVectors validationVectors(FitResult fit,
            List<CoreXYCalibrationAnalysis.Observation> observations,
            double gain, String selectedDirection) {
        ValidationVectors result = new ValidationVectors();
        for (CoreXYCalibrationAnalysis.Observation observation : observations) {
            if (selectedDirection != null && !selectedDirection.equalsIgnoreCase(
                    observation.getDirection())) {
                continue;
            }
            DirectionModel model = "A".equalsIgnoreCase(observation.getDirection())
                    ? fit.getAModel() : fit.getBModel();
            Vector prediction = model.predict(observation.getSpeedFactor(),
                    observation.getDistanceMm());
            result.baseline.add(observation.getMagnitudeMm());
            result.residual.add(Math.hypot(
                    observation.getCorrectionXmm() - gain * prediction.x,
                    observation.getCorrectionYmm() - gain * prediction.y));
        }
        if (result.baseline.isEmpty()) {
            throw new IllegalArgumentException(
                    "Held-out observations must contain both A and B directions.");
        }
        return result;
    }

    private static DirectionModel tune(String direction,
            List<CoreXYCalibrationAnalysis.Observation> observations) {
        if (observations.size() < 6) {
            throw new IllegalArgumentException(
                    direction + " requires at least six observations.");
        }
        DirectionModel best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double speedBandwidth : BANDWIDTHS) {
            for (double distanceBandwidth : BANDWIDTHS) {
                DirectionModel candidate = new DirectionModel(direction,
                        observations, speedBandwidth, distanceBandwidth);
                List<Double> errors = leaveOneOut(candidate);
                Metrics metrics = metrics(errors, errors, true);
                double score = metrics.rmsMm + 0.35 * metrics.p95Mm
                        + 0.15 * metrics.maximumMm;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static void addValidation(DirectionModel model,
            List<Double> baseline, List<Vector> observed, List<Vector> predicted) {
        for (int index = 0; index < model.observations.size(); index++) {
            CoreXYCalibrationAnalysis.Observation observation =
                    model.observations.get(index);
            Vector actual = new Vector(observation.getCorrectionXmm(),
                    observation.getCorrectionYmm());
            observed.add(actual);
            baseline.add(actual.getMagnitude());
            predicted.add(model.predict(observation.getSpeedFactor(),
                    observation.getDistanceMm(), index));
        }
    }

    private static List<Double> leaveOneOut(DirectionModel model) {
        List<Double> result = new ArrayList<>();
        for (int index = 0; index < model.observations.size(); index++) {
            CoreXYCalibrationAnalysis.Observation observation =
                    model.observations.get(index);
            Vector prediction = model.predict(observation.getSpeedFactor(),
                    observation.getDistanceMm(), index);
            result.add(Math.hypot(observation.getCorrectionXmm() - prediction.x,
                    observation.getCorrectionYmm() - prediction.y));
        }
        return result;
    }

    private static Metrics metrics(List<Double> values, List<Double> baseline,
            boolean suppressWorsening) {
        if (values.isEmpty() || values.size() != baseline.size()) {
            throw new IllegalArgumentException("Metric vectors must be non-empty and aligned.");
        }
        double sumSquares = 0;
        double maximum = 0;
        double worsening = 0;
        List<Double> sorted = new ArrayList<>(values);
        for (int index = 0; index < values.size(); index++) {
            double value = values.get(index);
            sumSquares += value * value;
            maximum = Math.max(maximum, value);
            if (!suppressWorsening) {
                worsening = Math.max(worsening, value - baseline.get(index));
            }
        }
        sorted.sort(Comparator.naturalOrder());
        int p95Index = Math.max(0,
                (int) Math.ceil(sorted.size() * 0.95) - 1);
        return new Metrics(Math.sqrt(sumSquares / values.size()),
                sorted.get(p95Index), maximum, worsening);
    }

    private static List<CoreXYCalibrationAnalysis.Observation> direction(
            List<CoreXYCalibrationAnalysis.Observation> observations,
            String direction) {
        List<CoreXYCalibrationAnalysis.Observation> result = new ArrayList<>();
        for (CoreXYCalibrationAnalysis.Observation observation : observations) {
            if (direction.equalsIgnoreCase(observation.getDirection())) {
                result.add(observation);
            }
        }
        return result;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double square(double value) {
        return value * value;
    }
}
