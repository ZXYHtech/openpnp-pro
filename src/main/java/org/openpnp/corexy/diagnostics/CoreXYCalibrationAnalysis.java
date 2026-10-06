package org.openpnp.corexy.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Analyzes a pilot grid and recommends only measurements justified by its noise floor. */
public final class CoreXYCalibrationAnalysis {
    public enum ModelKind {
        NONE,
        CONSTANT_STATE_OFFSET,
        DISTANCE_LOOKUP,
        DISTANCE_SPEED_LOOKUP
    }

    public static final class Observation {
        private final String direction;
        private final double speedFactor;
        private final double distanceMm;
        private final double correctionXmm;
        private final double correctionYmm;
        private final double scatterMm;
        private final double detectionFloorMm;

        public Observation(String direction, double speedFactor, double distanceMm,
                double correctionXmm, double correctionYmm, double scatterMm,
                double detectionFloorMm) {
            this.direction = direction;
            this.speedFactor = speedFactor;
            this.distanceMm = distanceMm;
            this.correctionXmm = correctionXmm;
            this.correctionYmm = correctionYmm;
            this.scatterMm = scatterMm;
            this.detectionFloorMm = detectionFloorMm;
        }

        public String getDirection() { return direction; }
        public double getSpeedFactor() { return speedFactor; }
        public double getDistanceMm() { return distanceMm; }
        public double getCorrectionXmm() { return correctionXmm; }
        public double getCorrectionYmm() { return correctionYmm; }
        public double getScatterMm() { return scatterMm; }
        public double getDetectionFloorMm() { return detectionFloorMm; }
        public double getMagnitudeMm() {
            return Math.hypot(correctionXmm, correctionYmm);
        }
    }

    public static final class RefinementPoint {
        private final String kind;
        private final String direction;
        private final double speedFactor;
        private final double distanceMm;
        private final double priority;
        private final String reason;

        private RefinementPoint(String kind, String direction, double speedFactor,
                double distanceMm, double priority, String reason) {
            this.kind = kind;
            this.direction = direction;
            this.speedFactor = speedFactor;
            this.distanceMm = distanceMm;
            this.priority = priority;
            this.reason = reason;
        }

        public String getKind() { return kind; }
        public String getDirection() { return direction; }
        public double getSpeedFactor() { return speedFactor; }
        public double getDistanceMm() { return distanceMm; }
        public double getPriority() { return priority; }
        public String getReason() { return reason; }
    }

    public static final class DirectionSummary {
        private final String direction;
        private final double maximumMagnitudeMm;
        private final double maximumScatterMm;
        private final double maximumDistanceChangeMm;
        private final double maximumSpeedChangeMm;

        private DirectionSummary(String direction, double maximumMagnitudeMm,
                double maximumScatterMm, double maximumDistanceChangeMm,
                double maximumSpeedChangeMm) {
            this.direction = direction;
            this.maximumMagnitudeMm = maximumMagnitudeMm;
            this.maximumScatterMm = maximumScatterMm;
            this.maximumDistanceChangeMm = maximumDistanceChangeMm;
            this.maximumSpeedChangeMm = maximumSpeedChangeMm;
        }

        public String getDirection() { return direction; }
        public double getMaximumMagnitudeMm() { return maximumMagnitudeMm; }
        public double getMaximumScatterMm() { return maximumScatterMm; }
        public double getMaximumDistanceChangeMm() { return maximumDistanceChangeMm; }
        public double getMaximumSpeedChangeMm() { return maximumSpeedChangeMm; }
    }

    public static final class Analysis {
        private final ModelKind recommendedModel;
        private final double observedFloorMm;
        private final DirectionSummary aSummary;
        private final DirectionSummary bSummary;
        private final boolean stepModelRejected;
        private final List<RefinementPoint> refinementPoints;
        private final List<Observation> observations;

        private Analysis(ModelKind recommendedModel, double observedFloorMm,
                DirectionSummary aSummary, DirectionSummary bSummary,
                boolean stepModelRejected, List<RefinementPoint> refinementPoints,
                List<Observation> observations) {
            this.recommendedModel = recommendedModel;
            this.observedFloorMm = observedFloorMm;
            this.aSummary = aSummary;
            this.bSummary = bSummary;
            this.stepModelRejected = stepModelRejected;
            this.refinementPoints = Collections.unmodifiableList(
                    new ArrayList<>(refinementPoints));
            this.observations = Collections.unmodifiableList(
                    new ArrayList<>(observations));
        }

        public ModelKind getRecommendedModel() { return recommendedModel; }
        public double getObservedFloorMm() { return observedFloorMm; }
        public DirectionSummary getASummary() { return aSummary; }
        public DirectionSummary getBSummary() { return bSummary; }
        public boolean isStepModelRejected() { return stepModelRejected; }
        public List<RefinementPoint> getRefinementPoints() { return refinementPoints; }
        public List<Observation> getObservations() { return observations; }
    }

    private static final int MAX_REFINEMENT_POINTS = 16;

    private CoreXYCalibrationAnalysis() {
    }

    public static Analysis analyzeFile(String fileName) throws IOException {
        Path path = Paths.get(fileName).toAbsolutePath().normalize();
        Properties properties = loadProperties(path);
        if (!"completed".equals(properties.getProperty("state"))) {
            throw new IllegalArgumentException(
                    "Pilot session must be completed before analysis.");
        }
        int speedCount = positiveInt(properties, "speed.count");
        int distanceCount = positiveInt(properties, "distance.count");
        List<Observation> observations = new ArrayList<>();
        double maximumStepResidual = 0;
        for (int speedIndex = 1; speedIndex <= speedCount; speedIndex++) {
            String speedPrefix = "speed." + speedIndex;
            double speed = finiteDouble(properties, speedPrefix + ".factor");
            double floor = finiteNonNegative(properties,
                    speedPrefix + ".vision-detection-floor.mm");
            maximumStepResidual = Math.max(maximumStepResidual,
                    finiteNonNegative(properties,
                            speedPrefix + ".a.step-fit.step-modeled-worst.mm"));
            maximumStepResidual = Math.max(maximumStepResidual,
                    finiteNonNegative(properties,
                            speedPrefix + ".b.step-fit.step-modeled-worst.mm"));
            for (String direction : new String[] {"A", "B"}) {
                String keyDirection = direction.toLowerCase(Locale.ROOT);
                for (int distanceIndex = 1; distanceIndex <= distanceCount;
                        distanceIndex++) {
                    String prefix = speedPrefix + "." + keyDirection
                            + ".distance." + distanceIndex;
                    observations.add(new Observation(direction, speed,
                            finiteDouble(properties, prefix + ".virtual-distance.mm"),
                            finiteDouble(properties, prefix + ".correction.x.mm"),
                            finiteDouble(properties, prefix + ".correction.y.mm"),
                            finiteNonNegative(properties, prefix + ".scatter.mm"),
                            floor));
                }
            }
        }
        return analyze(observations, maximumStepResidual);
    }

    /**
     * Merges the pilot session with the newest refinement and its ancestor chain.
     * Repeated cells are consolidated so a repeat cannot silently replace prior data.
     */
    public static Analysis analyzeFiles(String pilotFileName,
            String refinementFileName) throws IOException {
        Path pilotPath = Paths.get(pilotFileName).toAbsolutePath().normalize();
        Properties pilot = loadProperties(pilotPath);
        if (!"completed".equals(pilot.getProperty("state"))) {
            throw new IllegalArgumentException(
                    "Pilot session must be completed before analysis.");
        }
        List<Observation> observations = new ArrayList<>();
        double[] maximumStepResidual = new double[] {0};
        addPilotObservations(pilot, observations, maximumStepResidual);

        if (refinementFileName != null && !refinementFileName.trim().isEmpty()) {
            Path refinementPath = Paths.get(refinementFileName)
                    .toAbsolutePath().normalize();
            addRefinementChain(refinementPath, observations,
                    maximumStepResidual, new HashSet<>());
        }
        return analyze(consolidate(observations), maximumStepResidual[0]);
    }

    public static Analysis analyze(List<Observation> observations,
            double maximumStepResidualMm) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("Pilot observations are required.");
        }
        List<Observation> a = direction(observations, "A");
        List<Observation> b = direction(observations, "B");
        if (a.isEmpty() || b.isEmpty()) {
            throw new IllegalArgumentException("Both A and B observations are required.");
        }
        double observedFloor = 0;
        for (Observation observation : observations) {
            observedFloor = Math.max(observedFloor, observation.getDetectionFloorMm());
        }
        if (observedFloor <= 0) {
            throw new IllegalArgumentException("A positive vision detection floor is required.");
        }

        DirectionSummary aSummary = summarize(a);
        DirectionSummary bSummary = summarize(b);
        double maximumMagnitude = Math.max(aSummary.getMaximumMagnitudeMm(),
                bSummary.getMaximumMagnitudeMm());
        double maximumDistanceChange = Math.max(aSummary.getMaximumDistanceChangeMm(),
                bSummary.getMaximumDistanceChangeMm());
        double maximumSpeedChange = Math.max(aSummary.getMaximumSpeedChangeMm(),
                bSummary.getMaximumSpeedChangeMm());
        double meaningful = observedFloor * 4.0;

        ModelKind model;
        if (maximumMagnitude <= meaningful) {
            model = ModelKind.NONE;
        }
        else if (maximumSpeedChange > meaningful) {
            model = ModelKind.DISTANCE_SPEED_LOOKUP;
        }
        else if (maximumDistanceChange > meaningful) {
            model = ModelKind.DISTANCE_LOOKUP;
        }
        else {
            model = ModelKind.CONSTANT_STATE_OFFSET;
        }

        List<RefinementPoint> points = new ArrayList<>();
        points.addAll(refinementsForDirection(a));
        points.addAll(refinementsForDirection(b));
        points = deduplicateAndLimit(points, MAX_REFINEMENT_POINTS);
        boolean stepRejected = maximumStepResidualMm > Math.max(
                meaningful, Math.max(aSummary.getMaximumScatterMm(),
                        bSummary.getMaximumScatterMm()) * 1.5);
        return new Analysis(model, observedFloor, aSummary, bSummary,
                stepRejected, points, observations);
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        }
        return properties;
    }

    private static void addPilotObservations(Properties properties,
            List<Observation> observations, double[] maximumStepResidual) {
        int speedCount = positiveInt(properties, "speed.count");
        int fallbackDistanceCount = positiveInt(properties, "distance.count");
        for (int speedIndex = 1; speedIndex <= speedCount; speedIndex++) {
            String speedPrefix = "speed." + speedIndex;
            addStageObservations(properties, speedPrefix, "factor",
                    fallbackDistanceCount, observations, maximumStepResidual);
        }
    }

    private static void addRefinementChain(Path path,
            List<Observation> observations, double[] maximumStepResidual,
            Set<Path> visited) throws IOException {
        Path canonical = path.toAbsolutePath().normalize();
        if (!visited.add(canonical)) {
            throw new IllegalArgumentException(
                    "Refinement source chain contains a cycle: " + canonical);
        }
        Properties properties = loadProperties(canonical);
        if (!"completed".equals(properties.getProperty("state"))) {
            throw new IllegalArgumentException(
                    "Refinement session must be completed before analysis: "
                            + canonical);
        }
        String previous = properties.getProperty("source-previous-refinement");
        if (previous != null && !previous.trim().isEmpty()) {
            addRefinementChain(Paths.get(previous.trim()), observations,
                    maximumStepResidual, visited);
        }
        int stageCount = positiveInt(properties, "stage.count");
        for (int stageIndex = 1; stageIndex <= stageCount; stageIndex++) {
            addStageObservations(properties, "stage." + stageIndex,
                    "speed-factor", -1, observations, maximumStepResidual);
        }
    }

    private static void addStageObservations(Properties properties,
            String prefix, String speedKey, int fallbackDistanceCount,
            List<Observation> observations, double[] maximumStepResidual) {
        double speed = finiteDouble(properties, prefix + "." + speedKey);
        double floor = finiteNonNegative(properties,
                prefix + ".vision-detection-floor.mm");
        for (String direction : new String[] {"A", "B"}) {
            String keyDirection = direction.toLowerCase(Locale.ROOT);
            String countKey = prefix + "." + keyDirection + ".distance.count";
            int count = properties.getProperty(countKey) == null
                    ? fallbackDistanceCount : positiveInt(properties, countKey);
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "Missing distance count for " + prefix + " " + direction);
            }
            String fitPrefix = prefix + "." + keyDirection + ".step-fit";
            maximumStepResidual[0] = Math.max(maximumStepResidual[0],
                    finiteNonNegative(properties,
                            fitPrefix + ".step-modeled-worst.mm"));
            for (int distanceIndex = 1; distanceIndex <= count; distanceIndex++) {
                String cell = prefix + "." + keyDirection + ".distance."
                        + distanceIndex;
                observations.add(new Observation(direction, speed,
                        finiteDouble(properties, cell + ".virtual-distance.mm"),
                        finiteDouble(properties, cell + ".correction.x.mm"),
                        finiteDouble(properties, cell + ".correction.y.mm"),
                        finiteNonNegative(properties, cell + ".scatter.mm"),
                        floor));
            }
        }
    }

    private static List<Observation> consolidate(List<Observation> observations) {
        Map<String, List<Observation>> cells = new LinkedHashMap<>();
        for (Observation observation : observations) {
            String key = String.format(Locale.ROOT, "%s|%.6f|%.6f",
                    observation.getDirection().toUpperCase(Locale.ROOT),
                    observation.getSpeedFactor(), observation.getDistanceMm());
            cells.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(observation);
        }
        List<Observation> result = new ArrayList<>();
        for (List<Observation> cell : cells.values()) {
            Observation first = cell.get(0);
            double x = 0;
            double y = 0;
            double scatter = 0;
            double floor = 0;
            for (Observation observation : cell) {
                x += observation.getCorrectionXmm();
                y += observation.getCorrectionYmm();
                scatter = Math.max(scatter, observation.getScatterMm());
                floor = Math.max(floor, observation.getDetectionFloorMm());
            }
            x /= cell.size();
            y /= cell.size();
            for (Observation observation : cell) {
                scatter = Math.max(scatter, Math.hypot(
                        observation.getCorrectionXmm() - x,
                        observation.getCorrectionYmm() - y));
            }
            result.add(new Observation(first.getDirection(),
                    first.getSpeedFactor(), first.getDistanceMm(), x, y,
                    scatter, floor));
        }
        return result;
    }

    private static DirectionSummary summarize(List<Observation> observations) {
        double maximumMagnitude = 0;
        double maximumScatter = 0;
        for (Observation observation : observations) {
            maximumMagnitude = Math.max(maximumMagnitude, observation.getMagnitudeMm());
            maximumScatter = Math.max(maximumScatter, observation.getScatterMm());
        }
        double maximumDistanceChange = 0;
        for (List<Observation> profile : profilesBySpeed(observations).values()) {
            for (int index = 1; index < profile.size(); index++) {
                maximumDistanceChange = Math.max(maximumDistanceChange,
                        vectorDifference(profile.get(index - 1), profile.get(index)));
            }
        }
        double maximumSpeedChange = 0;
        for (List<Observation> profile : profilesByDistance(observations).values()) {
            for (int index = 1; index < profile.size(); index++) {
                maximumSpeedChange = Math.max(maximumSpeedChange,
                        vectorDifference(profile.get(index - 1), profile.get(index)));
            }
        }
        return new DirectionSummary(observations.get(0).getDirection(),
                maximumMagnitude, maximumScatter,
                maximumDistanceChange, maximumSpeedChange);
    }

    private static List<RefinementPoint> refinementsForDirection(
            List<Observation> observations) {
        List<RefinementPoint> points = new ArrayList<>();
        for (Observation observation : observations) {
            double limit = observation.getDetectionFloorMm() * 2.5;
            if (observation.getMagnitudeMm() > observation.getDetectionFloorMm() * 3.0
                    && observation.getScatterMm() > limit) {
                double priority = observation.getScatterMm() / limit;
                points.add(new RefinementPoint("repeat", observation.getDirection(),
                        observation.getSpeedFactor(), observation.getDistanceMm(),
                        priority, String.format(Locale.ROOT,
                                "scatter %.6f mm exceeds %.1fx the vision floor",
                                observation.getScatterMm(), 2.5)));
            }
        }

        for (List<Observation> profile : profilesBySpeed(observations).values()) {
            for (int index = 1; index < profile.size(); index++) {
                Observation left = profile.get(index - 1);
                Observation right = profile.get(index);
                double change = vectorDifference(left, right);
                double evidence = Math.max(left.getDetectionFloorMm() * 4.0,
                        left.getScatterMm() + right.getScatterMm()
                                + left.getDetectionFloorMm());
                if (change > evidence) {
                    double midpoint = roundToTenth(
                            (left.getDistanceMm() + right.getDistanceMm()) * 0.5);
                    points.add(new RefinementPoint("distance-midpoint",
                            left.getDirection(), left.getSpeedFactor(), midpoint,
                            change / evidence, String.format(Locale.ROOT,
                                    "adjacent distance vectors differ by %.6f mm",
                                    change)));
                }
            }
        }

        for (List<Observation> profile : profilesByDistance(observations).values()) {
            for (int index = 1; index < profile.size(); index++) {
                Observation lower = profile.get(index - 1);
                Observation upper = profile.get(index);
                double change = vectorDifference(lower, upper);
                double evidence = Math.max(lower.getDetectionFloorMm() * 4.0,
                        lower.getScatterMm() + upper.getScatterMm()
                                + lower.getDetectionFloorMm());
                if (change > evidence) {
                    double midpoint = roundToThousandth(
                            (lower.getSpeedFactor() + upper.getSpeedFactor()) * 0.5);
                    points.add(new RefinementPoint("speed-midpoint",
                            lower.getDirection(), midpoint, lower.getDistanceMm(),
                            change / evidence, String.format(Locale.ROOT,
                                    "adjacent speed vectors differ by %.6f mm",
                                    change)));
                }
            }
        }
        return points;
    }

    private static List<RefinementPoint> deduplicateAndLimit(
            List<RefinementPoint> points, int limit) {
        points.sort(Comparator.comparingDouble(RefinementPoint::getPriority).reversed());
        Map<String, RefinementPoint> unique = new LinkedHashMap<>();
        for (RefinementPoint point : points) {
            String key = String.format(Locale.ROOT, "%.3f|%.1f",
                    point.getSpeedFactor(), point.getDistanceMm());
            RefinementPoint existing = unique.get(key);
            if (existing == null) {
                unique.put(key, point);
            }
            else if (!existing.getDirection().contains(point.getDirection())) {
                unique.put(key, new RefinementPoint(existing.getKind(),
                        existing.getDirection() + "/" + point.getDirection(),
                        existing.getSpeedFactor(), existing.getDistanceMm(),
                        Math.max(existing.getPriority(), point.getPriority()),
                        existing.getReason() + "; " + point.getReason()));
            }
            if (unique.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<Double, List<Observation>> profilesBySpeed(
            List<Observation> observations) {
        Map<Double, List<Observation>> profiles = new TreeMap<>();
        for (Observation observation : observations) {
            profiles.computeIfAbsent(observation.getSpeedFactor(),
                    ignored -> new ArrayList<>()).add(observation);
        }
        for (List<Observation> profile : profiles.values()) {
            profile.sort(Comparator.comparingDouble(Observation::getDistanceMm));
        }
        return profiles;
    }

    private static Map<Double, List<Observation>> profilesByDistance(
            List<Observation> observations) {
        Map<Double, List<Observation>> profiles = new TreeMap<>();
        for (Observation observation : observations) {
            profiles.computeIfAbsent(observation.getDistanceMm(),
                    ignored -> new ArrayList<>()).add(observation);
        }
        for (List<Observation> profile : profiles.values()) {
            profile.sort(Comparator.comparingDouble(Observation::getSpeedFactor));
        }
        return profiles;
    }

    private static List<Observation> direction(List<Observation> observations,
            String direction) {
        List<Observation> selected = new ArrayList<>();
        for (Observation observation : observations) {
            if (direction.equalsIgnoreCase(observation.getDirection())) {
                selected.add(observation);
            }
        }
        return selected;
    }

    private static double vectorDifference(Observation first, Observation second) {
        return Math.hypot(first.getCorrectionXmm() - second.getCorrectionXmm(),
                first.getCorrectionYmm() - second.getCorrectionYmm());
    }

    private static int positiveInt(Properties properties, String key) {
        String value = required(properties, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid positive integer " + key
                    + "=" + value + ".", error);
        }
    }

    private static double finiteDouble(Properties properties, String key) {
        String value = required(properties, key);
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid finite number " + key
                    + "=" + value + ".", error);
        }
    }

    private static double finiteNonNegative(Properties properties, String key) {
        double value = finiteDouble(properties, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative.");
        }
        return value;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing pilot property: " + key);
        }
        return value.trim();
    }

    private static double roundToTenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double roundToThousandth(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
