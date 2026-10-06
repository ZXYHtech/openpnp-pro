package org.openpnp.corexy.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.machine.reference.solutions.VisionSolutions;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvStage.Result.Circle;

/** Read-only visual repeatability measurements for an unmodified OpenPnP runtime. */
public final class CoreXYVisualDiagnostics {
    private static final double PATH_SAFETY_MARGIN_MM = 1.0;

    public interface ProgressListener {
        void onProgress(String stage, int completed, int total);
    }

    public static final class Sample {
        private final int repeat;
        private final Location positiveLocation;
        private final Location negativeLocation;
        private final double correctionXmm;
        private final double correctionYmm;

        Sample(int repeat, Location positiveLocation, Location negativeLocation) {
            this.repeat = repeat;
            this.positiveLocation = positiveLocation;
            this.negativeLocation = negativeLocation;
            Location correction = positiveLocation.subtract(negativeLocation)
                    .convertToUnits(LengthUnit.Millimeters);
            correctionXmm = correction.getX();
            correctionYmm = correction.getY();
        }

        public int getRepeat() { return repeat; }
        public Location getPositiveLocation() { return positiveLocation; }
        public Location getNegativeLocation() { return negativeLocation; }
        public double getCorrectionXmm() { return correctionXmm; }
        public double getCorrectionYmm() { return correctionYmm; }
        public double getMagnitudeMm() { return Math.hypot(correctionXmm, correctionYmm); }
    }

    public static final class DirectionResult {
        private final String direction;
        private final double virtualDistanceMm;
        private final List<Sample> samples;
        private final double correctionXmm;
        private final double correctionYmm;
        private final double scatterMm;

        DirectionResult(String direction, double virtualDistanceMm, List<Sample> samples) {
            this.direction = direction;
            this.virtualDistanceMm = virtualDistanceMm;
            this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (Sample sample : samples) {
                xs.add(sample.getCorrectionXmm());
                ys.add(sample.getCorrectionYmm());
            }
            correctionXmm = median(xs);
            correctionYmm = median(ys);
            List<Double> deviations = new ArrayList<>();
            for (Sample sample : samples) {
                deviations.add(Math.hypot(sample.getCorrectionXmm() - correctionXmm,
                        sample.getCorrectionYmm() - correctionYmm));
            }
            scatterMm = maximum(deviations);
        }

        public String getDirection() { return direction; }
        public double getVirtualDistanceMm() { return virtualDistanceMm; }
        public List<Sample> getSamples() { return samples; }
        public double getCorrectionXmm() { return correctionXmm; }
        public double getCorrectionYmm() { return correctionYmm; }
        public double getMagnitudeMm() { return Math.hypot(correctionXmm, correctionYmm); }
        public double getScatterMm() { return scatterMm; }
    }

    public static final class BaselineResult {
        private final Location configuredReference;
        private final Location detectedReference;
        private final double fiducialDiameterMm;
        private final double stationaryVisionNoiseMm;
        private final DirectionResult aResult;
        private final DirectionResult bResult;

        BaselineResult(Location configuredReference, Location detectedReference,
                double fiducialDiameterMm, double stationaryVisionNoiseMm,
                DirectionResult aResult, DirectionResult bResult) {
            this.configuredReference = configuredReference;
            this.detectedReference = detectedReference;
            this.fiducialDiameterMm = fiducialDiameterMm;
            this.stationaryVisionNoiseMm = stationaryVisionNoiseMm;
            this.aResult = aResult;
            this.bResult = bResult;
        }

        public Location getConfiguredReference() { return configuredReference; }
        public Location getDetectedReference() { return detectedReference; }
        public double getFiducialDiameterMm() { return fiducialDiameterMm; }
        public double getStationaryVisionNoiseMm() { return stationaryVisionNoiseMm; }
        public DirectionResult getAResult() { return aResult; }
        public DirectionResult getBResult() { return bResult; }
    }

    public static final class AxisFit {
        private final String direction;
        private final double thresholdMm;
        private final double correctionXmm;
        private final double correctionYmm;
        private final double baselineWorstMm;
        private final double expectedWorstMm;

        AxisFit(String direction, double thresholdMm, double correctionXmm,
                double correctionYmm, double baselineWorstMm, double expectedWorstMm) {
            this.direction = direction;
            this.thresholdMm = thresholdMm;
            this.correctionXmm = correctionXmm;
            this.correctionYmm = correctionYmm;
            this.baselineWorstMm = baselineWorstMm;
            this.expectedWorstMm = expectedWorstMm;
        }

        public String getDirection() { return direction; }
        public double getThresholdMm() { return thresholdMm; }
        public double getCorrectionXmm() { return correctionXmm; }
        public double getCorrectionYmm() { return correctionYmm; }
        public double getMagnitudeMm() { return Math.hypot(correctionXmm, correctionYmm); }
        public double getBaselineWorstMm() { return baselineWorstMm; }
        public double getExpectedWorstMm() { return expectedWorstMm; }
        public double getExpectedImprovementMm() {
            return baselineWorstMm - expectedWorstMm;
        }
    }

    public static final class CompensationCandidate {
        private final double scale;
        private final double thresholdAMm;
        private final double thresholdBMm;
        private final double aCorrectionXmm;
        private final double aCorrectionYmm;
        private final double bCorrectionXmm;
        private final double bCorrectionYmm;
        private final double expectedWorstMm;

        CompensationCandidate(double scale, AxisFit aFit, AxisFit bFit,
                double expectedWorstMm) {
            this.scale = scale;
            thresholdAMm = aFit.getThresholdMm();
            thresholdBMm = bFit.getThresholdMm();
            aCorrectionXmm = aFit.getCorrectionXmm() * scale;
            aCorrectionYmm = aFit.getCorrectionYmm() * scale;
            bCorrectionXmm = bFit.getCorrectionXmm() * scale;
            bCorrectionYmm = bFit.getCorrectionYmm() * scale;
            this.expectedWorstMm = expectedWorstMm;
        }

        public double getScale() { return scale; }
        public double getThresholdAMm() { return thresholdAMm; }
        public double getThresholdBMm() { return thresholdBMm; }
        public double getACorrectionXmm() { return aCorrectionXmm; }
        public double getACorrectionYmm() { return aCorrectionYmm; }
        public double getBCorrectionXmm() { return bCorrectionXmm; }
        public double getBCorrectionYmm() { return bCorrectionYmm; }
        public double getExpectedWorstMm() { return expectedWorstMm; }
    }

    public static final class DistanceScanResult {
        private final Location configuredReference;
        private final Location detectedReference;
        private final double fiducialDiameterMm;
        private final double stationaryVisionNoiseMm;
        private final double visionDetectionFloorMm;
        private final List<DirectionResult> aResults;
        private final List<DirectionResult> bResults;
        private final AxisFit aFit;
        private final AxisFit bFit;
        private final List<CompensationCandidate> candidates;

        DistanceScanResult(Location configuredReference, Location detectedReference,
                double fiducialDiameterMm, double stationaryVisionNoiseMm,
                double visionDetectionFloorMm, List<DirectionResult> aResults,
                List<DirectionResult> bResults, AxisFit aFit, AxisFit bFit,
                List<CompensationCandidate> candidates) {
            this.configuredReference = configuredReference;
            this.detectedReference = detectedReference;
            this.fiducialDiameterMm = fiducialDiameterMm;
            this.stationaryVisionNoiseMm = stationaryVisionNoiseMm;
            this.visionDetectionFloorMm = visionDetectionFloorMm;
            this.aResults = Collections.unmodifiableList(new ArrayList<>(aResults));
            this.bResults = Collections.unmodifiableList(new ArrayList<>(bResults));
            this.aFit = aFit;
            this.bFit = bFit;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        public Location getConfiguredReference() { return configuredReference; }
        public Location getDetectedReference() { return detectedReference; }
        public double getFiducialDiameterMm() { return fiducialDiameterMm; }
        public double getStationaryVisionNoiseMm() { return stationaryVisionNoiseMm; }
        public double getVisionDetectionFloorMm() { return visionDetectionFloorMm; }
        public List<DirectionResult> getAResults() { return aResults; }
        public List<DirectionResult> getBResults() { return bResults; }
        public AxisFit getAFit() { return aFit; }
        public AxisFit getBFit() { return bFit; }
        public List<CompensationCandidate> getCandidates() { return candidates; }
    }

    private CoreXYVisualDiagnostics() {
    }

    public static BaselineResult measureBaseline(double virtualDistanceMm, int repeats,
            double speedFactor, int settleMilliseconds, int stationaryCaptures,
            ProgressListener listener) throws Exception {
        DistanceScanResult scan = measureDistanceScan(new double[] {virtualDistanceMm},
                repeats, speedFactor, settleMilliseconds, stationaryCaptures, listener);
        return new BaselineResult(scan.getConfiguredReference(), scan.getDetectedReference(),
                scan.getFiducialDiameterMm(), scan.getStationaryVisionNoiseMm(),
                scan.getAResults().get(0), scan.getBResults().get(0));
    }

    public static DistanceScanResult measureDistanceScan(double[] requestedDistancesMm,
            int repeats, double speedFactor, int settleMilliseconds,
            int stationaryCaptures, ProgressListener listener) throws Exception {
        return measureDistanceScanInternal(requestedDistancesMm, repeats, speedFactor,
                settleMilliseconds, stationaryCaptures, listener, false);
    }

    public static DistanceScanResult measureCompensatedDistanceScan(
            double[] requestedDistancesMm, int repeats, double speedFactor,
            int settleMilliseconds, int stationaryCaptures,
            ProgressListener listener) throws Exception {
        return measureDistanceScanInternal(requestedDistancesMm, repeats, speedFactor,
                settleMilliseconds, stationaryCaptures, listener, true);
    }

    private static DistanceScanResult measureDistanceScanInternal(
            double[] requestedDistancesMm, int repeats, double speedFactor,
            int settleMilliseconds, int stationaryCaptures,
            ProgressListener listener, boolean compensated) throws Exception {
        List<Double> distances = validateScanArguments(requestedDistancesMm, repeats,
                speedFactor, settleMilliseconds, stationaryCaptures);
        String agentStatus = CoreXYAgent.getStatus();
        if (!"true".equals(statusValue(agentStatus, "installed"))
                || !"true".equals(statusValue(agentStatus, "configured"))) {
            throw new IllegalStateException("CoreXY Agent is not installed and configured.");
        }
        if (!compensated && "true".equals(statusValue(agentStatus, "enabled"))) {
            throw new IllegalStateException(
                    "Disable CoreXY compensation before capturing an uncompensated baseline.");
        }
        if (compensated) {
            if (!"true".equals(statusValue(agentStatus, "enabled"))
                    || !"false".equals(statusValue(agentStatus, "dry-run"))) {
                throw new IllegalStateException(
                        "Physical validation requires enabled=true and dry-run=false.");
            }
            String mode = statusValue(agentStatus, "mode");
            if (!isSupportedCompensatedScanMode(mode)) {
                throw new IllegalStateException(
                        "Physical validation requires a supported real compensation mode.");
            }
        }

        if (!(Configuration.get().getMachine() instanceof ReferenceMachine)) {
            throw new IllegalStateException("The configured machine is not a ReferenceMachine.");
        }
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        if (!machine.isEnabled() || !machine.isHomed()) {
            throw new IllegalStateException("Enable and Home the machine before visual diagnostics.");
        }
        if (!(machine.getDefaultHead() instanceof ReferenceHead)) {
            throw new IllegalStateException("The default head is not a ReferenceHead.");
        }
        ReferenceHead head = (ReferenceHead) machine.getDefaultHead();
        if (!(head.getDefaultCamera() instanceof ReferenceCamera)) {
            throw new IllegalStateException("The default camera is not a ReferenceCamera.");
        }
        ReferenceCamera camera = (ReferenceCamera) head.getDefaultCamera();
        validateMachineMapping(camera);
        ReferenceControllerAxis xAxis = (ReferenceControllerAxis) camera.getAxisX();
        ReferenceControllerAxis yAxis = (ReferenceControllerAxis) camera.getAxisY();

        Location configuredReference = head.getCalibrationPrimaryFiducialLocation()
                .convertToUnits(LengthUnit.Millimeters);
        Length fiducialDiameter = head.getCalibrationPrimaryFiducialDiameter();
        validatePrimaryFiducial(configuredReference, fiducialDiameter);
        for (double distance : distances) {
            validatePath(camera, configuredReference, distance);
        }
        double diameterMm = fiducialDiameter.convertToUnits(LengthUnit.Millimeters).getValue();
        Location unitsPerPixel = camera.getUnitsPerPixel()
                .convertToUnits(LengthUnit.Millimeters);
        double visionDetectionFloorMm = Math.max(0.005,
                Math.max(Math.abs(unitsPerPixel.getX()),
                        Math.abs(unitsPerPixel.getY())) * 0.25);

        VisionSolutions vision = machine.getVisionSolutions();
        int total = 2 + stationaryCaptures + distances.size() * repeats * 4;
        int completed = 0;
        progress(listener, "reference", completed, total);
        BacklashCompensationMethod oldXMethod = xAxis.getBacklashCompensationMethod();
        BacklashCompensationMethod oldYMethod = yAxis.getBacklashCompensationMethod();
        try {
            xAxis.setBacklashCompensationMethod(BacklashCompensationMethod.None);
            yAxis.setBacklashCompensationMethod(BacklashCompensationMethod.None);
            MovableUtils.moveToLocationAtSafeZ(camera, configuredReference, speedFactor);
            Location detectedReference = vision.centerInOnSubjectLocation(camera, camera,
                    fiducialDiameter, compensated
                            ? "CoreXY External Compensated Reference"
                            : "CoreXY External Baseline Reference", false)
                    .convertToUnits(LengthUnit.Millimeters);
            for (double distance : distances) {
                validatePath(camera, detectedReference, distance);
            }
            completed++;
            progress(listener, "vision-noise", completed, total);
            double noise = measureStationaryNoise(machine, camera, detectedReference,
                    fiducialDiameter, stationaryCaptures, listener, completed, total);
            completed += stationaryCaptures;

            List<DirectionResult> aResults = new ArrayList<>();
            List<DirectionResult> bResults = new ArrayList<>();
            for (int index = 0; index < distances.size(); index++) {
                double distance = distances.get(index);
                String suffix = "|" + (index + 1) + "|" + distances.size()
                        + "|" + distance;
                progress(listener, "distance-a" + suffix, completed, total);
                aResults.add(measureDirection(machine, camera, detectedReference,
                        fiducialDiameter, distance, 0.5, 0.5, repeats,
                        speedFactor, settleMilliseconds, "A", listener, completed, total,
                        "distance-a" + suffix));
                completed += repeats * 2;

                progress(listener, "distance-b" + suffix, completed, total);
                bResults.add(measureDirection(machine, camera, detectedReference,
                        fiducialDiameter, distance, 0.5, -0.5, repeats,
                        speedFactor, settleMilliseconds, "B", listener, completed, total,
                        "distance-b" + suffix));
                completed += repeats * 2;
            }

            double diagnosticFloor = Math.max(visionDetectionFloorMm, noise * 3.0);
            AxisFit aFit = fitAxis("A", aResults, diagnosticFloor);
            AxisFit bFit = fitAxis("B", bResults, diagnosticFloor);
            List<CompensationCandidate> candidates = buildCandidates(
                    aResults, bResults, aFit, bFit);

            progress(listener, "return-reference", completed, total);
            MovableUtils.moveToLocationAtSafeZ(camera, detectedReference, speedFactor);
            completed++;
            progress(listener, "complete", completed, total);
            return new DistanceScanResult(configuredReference, detectedReference,
                    diameterMm, noise, visionDetectionFloorMm, aResults, bResults,
                    aFit, bFit, candidates);
        }
        catch (Exception error) {
            progress(listener, "failed", completed, total);
            throw error;
        }
        finally {
            xAxis.setBacklashCompensationMethod(oldXMethod);
            yAxis.setBacklashCompensationMethod(oldYMethod);
        }
    }

    static boolean isSupportedCompensatedScanMode(String mode) {
        return "state-offset".equals(mode)
                || "distance-speed-state-offset".equals(mode)
                || "distance-speed-position-state-offset".equals(mode);
    }

    public static Location detectLocation(ReferenceMachine machine, ReferenceCamera camera,
            Location expectedLocation, Length expectedDiameter, String diagnostics)
            throws Exception {
        VisionSolutions vision = machine.getVisionSolutions();
        Circle expected = vision.getExpectedOffsetsAndDiameter(
                camera, camera, expectedLocation, expectedDiameter, false);
        Circle detected = vision.getSubjectPixelLocation(
                camera, camera, expected, 0.0, diagnostics, null, false);
        return VisionUtils.getPixelLocation(camera, camera, detected.x, detected.y)
                .convertToUnits(expectedLocation.getUnits());
    }

    private static DirectionResult measureDirection(ReferenceMachine machine,
            ReferenceCamera camera, Location reference, Length fiducialDiameter,
            double distanceMm, double xScale, double yScale, int repeats,
            double speedFactor, int settleMilliseconds, String direction,
            ProgressListener listener, int completed, int total) throws Exception {
        return measureDirection(machine, camera, reference, fiducialDiameter,
                distanceMm, xScale, yScale, repeats, speedFactor, settleMilliseconds,
                direction, listener, completed, total,
                "direction-" + direction.toLowerCase());
    }

    private static DirectionResult measureDirection(ReferenceMachine machine,
            ReferenceCamera camera, Location reference, Length fiducialDiameter,
            double distanceMm, double xScale, double yScale, int repeats,
            double speedFactor, int settleMilliseconds, String direction,
            ProgressListener listener, int completed, int total, String progressStage)
            throws Exception {
        Location vector = new Location(LengthUnit.Millimeters,
                distanceMm * xScale, distanceMm * yScale, 0, 0);
        List<Sample> samples = new ArrayList<>();
        for (int repeat = 0; repeat < repeats; repeat++) {
            camera.moveTo(reference.subtract(vector), speedFactor);
            camera.waitForCompletion(CompletionType.WaitForStillstand);
            camera.moveTo(reference, speedFactor);
            camera.waitForCompletion(CompletionType.WaitForStillstand);
            Thread.sleep(settleMilliseconds);
            Location positive = detectLocation(machine, camera, reference, fiducialDiameter,
                    "CoreXY External " + direction + "+ #" + (repeat + 1));
            completed++;
            progress(listener, progressStage, completed, total);

            camera.moveTo(reference.add(vector), speedFactor);
            camera.waitForCompletion(CompletionType.WaitForStillstand);
            camera.moveTo(reference, speedFactor);
            camera.waitForCompletion(CompletionType.WaitForStillstand);
            Thread.sleep(settleMilliseconds);
            Location negative = detectLocation(machine, camera, reference, fiducialDiameter,
                    "CoreXY External " + direction + "- #" + (repeat + 1));
            samples.add(new Sample(repeat + 1, positive, negative));
            completed++;
            progress(listener, progressStage, completed, total);
        }
        return new DirectionResult(direction, distanceMm, samples);
    }

    private static double measureStationaryNoise(ReferenceMachine machine,
            ReferenceCamera camera, Location reference, Length fiducialDiameter,
            int captures, ProgressListener listener, int completed, int total) throws Exception {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int capture = 0; capture < captures; capture++) {
            Location detected = detectLocation(machine, camera, reference, fiducialDiameter,
                    "CoreXY External Vision Noise #" + (capture + 1));
            xs.add(detected.getX());
            ys.add(detected.getY());
            progress(listener, "vision-noise", completed + capture + 1, total);
        }
        double medianX = median(xs);
        double medianY = median(ys);
        List<Double> radial = new ArrayList<>();
        for (int index = 0; index < xs.size(); index++) {
            radial.add(Math.hypot(xs.get(index) - medianX, ys.get(index) - medianY));
        }
        return maximum(radial);
    }

    static AxisFit fitAxis(String direction, List<DirectionResult> results,
            double diagnosticFloorMm) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("Direction results are required.");
        }
        List<DirectionResult> ordered = new ArrayList<>(results);
        Collections.sort(ordered, (left, right) -> Double.compare(
                left.getVirtualDistanceMm(), right.getVirtualDistanceMm()));
        double baselineWorst = 0;
        for (DirectionResult result : ordered) {
            baselineWorst = Math.max(baselineWorst,
                    result.getMagnitudeMm() + result.getScatterMm());
        }
        if (baselineWorst <= diagnosticFloorMm) {
            double inactiveThreshold = ordered.get(ordered.size() - 1)
                    .getVirtualDistanceMm() + diagnosticFloorMm;
            return new AxisFit(direction, inactiveThreshold, 0, 0,
                    baselineWorst, baselineWorst);
        }

        AxisFit best = null;
        for (int thresholdIndex = 0; thresholdIndex < ordered.size(); thresholdIndex++) {
            int activeCount = ordered.size() - thresholdIndex;
            if (ordered.size() >= 3 && activeCount < 2) {
                continue;
            }
            double threshold = ordered.get(thresholdIndex).getVirtualDistanceMm();
            List<double[]> active = new ArrayList<>();
            for (int index = thresholdIndex; index < ordered.size(); index++) {
                DirectionResult result = ordered.get(index);
                active.add(new double[] {
                        result.getCorrectionXmm(), result.getCorrectionYmm()});
            }
            List<double[]> offsets = new ArrayList<>(active);
            for (int first = 0; first < active.size(); first++) {
                for (int second = first + 1; second < active.size(); second++) {
                    offsets.add(new double[] {
                            (active.get(first)[0] + active.get(second)[0]) * 0.5,
                            (active.get(first)[1] + active.get(second)[1]) * 0.5});
                }
            }
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (double[] offset : active) {
                xs.add(offset[0]);
                ys.add(offset[1]);
            }
            offsets.add(new double[] {median(xs), median(ys)});

            for (double[] offset : offsets) {
                double expectedWorst = expectedAxisWorst(
                        ordered, threshold, offset[0], offset[1], 1.0);
                if (best == null || expectedWorst < best.getExpectedWorstMm() - 1e-9) {
                    best = new AxisFit(direction, threshold, offset[0], offset[1],
                            baselineWorst, expectedWorst);
                }
            }
        }
        if (best == null) {
            DirectionResult result = ordered.get(0);
            best = new AxisFit(direction, result.getVirtualDistanceMm(),
                    result.getCorrectionXmm(), result.getCorrectionYmm(),
                    baselineWorst, result.getScatterMm());
        }
        return best;
    }

    static List<CompensationCandidate> buildCandidates(List<DirectionResult> aResults,
            List<DirectionResult> bResults, AxisFit aFit, AxisFit bFit) {
        List<CompensationCandidate> candidates = new ArrayList<>();
        double[] scales = new double[] {0.25, 0.50, 0.75, 1.00};
        for (double scale : scales) {
            double aWorst = expectedAxisWorst(aResults, aFit.getThresholdMm(),
                    aFit.getCorrectionXmm(), aFit.getCorrectionYmm(), scale);
            double bWorst = expectedAxisWorst(bResults, bFit.getThresholdMm(),
                    bFit.getCorrectionXmm(), bFit.getCorrectionYmm(), scale);
            candidates.add(new CompensationCandidate(
                    scale, aFit, bFit, Math.max(aWorst, bWorst)));
        }
        return candidates;
    }

    private static double expectedAxisWorst(List<DirectionResult> results,
            double thresholdMm, double correctionXmm, double correctionYmm,
            double scale) {
        double worst = 0;
        for (DirectionResult result : results) {
            double appliedX = result.getVirtualDistanceMm() >= thresholdMm
                    ? correctionXmm * scale : 0;
            double appliedY = result.getVirtualDistanceMm() >= thresholdMm
                    ? correctionYmm * scale : 0;
            double residual = Math.hypot(
                    result.getCorrectionXmm() - appliedX,
                    result.getCorrectionYmm() - appliedY) + result.getScatterMm();
            worst = Math.max(worst, residual);
        }
        return worst;
    }

    private static void validateArguments(double distance, int repeats, double speed,
            int settleMilliseconds, int stationaryCaptures) {
        if (!Double.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("Virtual distance must be positive and finite.");
        }
        if (repeats < 3) {
            throw new IllegalArgumentException("At least three repetitions are required.");
        }
        if (!Double.isFinite(speed) || speed <= 0 || speed > 1) {
            throw new IllegalArgumentException("Speed factor must be greater than 0 and at most 1.");
        }
        if (settleMilliseconds < 0 || settleMilliseconds > 10000) {
            throw new IllegalArgumentException("Settle time must be between 0 and 10000 ms.");
        }
        if (stationaryCaptures < 5) {
            throw new IllegalArgumentException("At least five stationary captures are required.");
        }
    }

    private static List<Double> validateScanArguments(double[] requestedDistances,
            int repeats, double speed, int settleMilliseconds, int stationaryCaptures) {
        if (requestedDistances == null || requestedDistances.length == 0) {
            throw new IllegalArgumentException("At least one virtual distance is required.");
        }
        List<Double> distances = new ArrayList<>();
        for (double distance : requestedDistances) {
            validateArguments(distance, repeats, speed, settleMilliseconds,
                    stationaryCaptures);
            if (!distances.contains(distance)) {
                distances.add(distance);
            }
        }
        Collections.sort(distances);
        return distances;
    }

    private static void validateMachineMapping(ReferenceCamera camera) {
        if (camera.getLooking() != Camera.Looking.Down) {
            throw new IllegalStateException("Visual diagnostics require the down-looking camera.");
        }
        if (!(camera.getAxisX() instanceof ReferenceControllerAxis)
                || !(camera.getAxisY() instanceof ReferenceControllerAxis)) {
            throw new IllegalStateException("The camera must map to direct X and Y controller axes.");
        }
        ReferenceControllerAxis xAxis = (ReferenceControllerAxis) camera.getAxisX();
        ReferenceControllerAxis yAxis = (ReferenceControllerAxis) camera.getAxisY();
        if (xAxis.getDriver() != yAxis.getDriver()) {
            throw new IllegalStateException("CoreXY X and Y axes must use the same driver.");
        }
    }

    private static void validatePrimaryFiducial(Location reference, Length diameter) {
        if (reference == null || !reference.isInitialized()
                || !Double.isFinite(reference.getX()) || !Double.isFinite(reference.getY())
                || Math.abs(reference.getX()) < 1e-9 || Math.abs(reference.getY()) < 1e-9) {
            throw new IllegalStateException(
                    "Configure a non-zero Primary Fiducial X/Y location in the head calibration suite.");
        }
        if (diameter == null || !diameter.isInitialized()) {
            throw new IllegalStateException("Configure the Primary Fiducial feature diameter.");
        }
        double diameterMm = diameter.convertToUnits(LengthUnit.Millimeters).getValue();
        if (!Double.isFinite(diameterMm) || diameterMm <= 0) {
            throw new IllegalStateException("Primary Fiducial diameter must be positive.");
        }
    }

    private static void validatePath(ReferenceCamera camera, Location reference,
            double virtualDistanceMm) throws Exception {
        Location[] vectors = new Location[] {
                new Location(LengthUnit.Millimeters,
                        virtualDistanceMm * 0.5, virtualDistanceMm * 0.5, 0, 0),
                new Location(LengthUnit.Millimeters,
                        virtualDistanceMm * 0.5, -virtualDistanceMm * 0.5, 0, 0)
        };
        if (!reachableWithMargin(camera, reference)) {
            throw new IllegalStateException("The Primary Fiducial is outside the reachable area.");
        }
        for (Location vector : vectors) {
            if (!reachableWithMargin(camera, reference.add(vector))
                    || !reachableWithMargin(camera, reference.subtract(vector))) {
                throw new IllegalStateException(
                        "The requested A/B path violates the reachable area or 1 mm soft-limit margin.");
            }
        }
    }

    private static boolean reachableWithMargin(ReferenceCamera camera, Location location)
            throws Exception {
        double[] offsets = new double[] {-PATH_SAFETY_MARGIN_MM, PATH_SAFETY_MARGIN_MM};
        if (!camera.isReachable(location)) {
            return false;
        }
        for (double x : offsets) {
            for (double y : offsets) {
                if (!camera.isReachable(location.add(new Location(
                        LengthUnit.Millimeters, x, y, 0, 0)))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String statusValue(String status, String key) {
        String prefix = key + "=";
        for (String line : status.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static void progress(ProgressListener listener, String stage,
            int completed, int total) {
        if (listener != null) {
            listener.onProgress(stage, completed, total);
        }
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> ordered = new ArrayList<>(values);
        Collections.sort(ordered);
        int middle = ordered.size() / 2;
        if ((ordered.size() & 1) == 1) {
            return ordered.get(middle);
        }
        return (ordered.get(middle - 1) + ordered.get(middle)) * 0.5;
    }

    private static double maximum(List<Double> values) {
        double maximum = 0;
        for (double value : values) {
            maximum = Math.max(maximum, value);
        }
        return maximum;
    }
}
