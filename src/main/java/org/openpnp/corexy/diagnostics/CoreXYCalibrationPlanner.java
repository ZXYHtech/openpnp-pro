package org.openpnp.corexy.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

/** Builds a machine-specific, read-only sampling plan from configured travel limits. */
public final class CoreXYCalibrationPlanner {
    private static final double PATH_SAFETY_MARGIN_MM = 1.0;
    private static final double DEFAULT_RANGE_UTILIZATION = 0.75;
    private static final double MINIMUM_RECOMMENDED_RANGE_MM = 10.0;
    private static final double RANGE_SEARCH_LIMIT_MM = 100000.0;
    private static final double[] DEFAULT_SPEEDS = {0.20, 0.75, 1.00};

    public static final class SamplingPlan {
        private final Location primaryFiducial;
        private final String cameraName;
        private final String xAxisName;
        private final String yAxisName;
        private final double pathSafetyMarginMm;
        private final double availableVirtualDistanceMm;
        private final double recommendedVirtualDistanceMm;
        private final List<Double> distancesMm;
        private final List<Double> speedFactors;

        private SamplingPlan(Location primaryFiducial, String cameraName,
                String xAxisName, String yAxisName, double availableVirtualDistanceMm,
                double recommendedVirtualDistanceMm, List<Double> distancesMm,
                List<Double> speedFactors) {
            this.primaryFiducial = primaryFiducial;
            this.cameraName = cameraName;
            this.xAxisName = xAxisName;
            this.yAxisName = yAxisName;
            this.pathSafetyMarginMm = PATH_SAFETY_MARGIN_MM;
            this.availableVirtualDistanceMm = availableVirtualDistanceMm;
            this.recommendedVirtualDistanceMm = recommendedVirtualDistanceMm;
            this.distancesMm = Collections.unmodifiableList(new ArrayList<>(distancesMm));
            this.speedFactors = Collections.unmodifiableList(new ArrayList<>(speedFactors));
        }

        public Location getPrimaryFiducial() { return primaryFiducial; }
        public String getCameraName() { return cameraName; }
        public String getXAxisName() { return xAxisName; }
        public String getYAxisName() { return yAxisName; }
        public double getPathSafetyMarginMm() { return pathSafetyMarginMm; }
        public double getAvailableVirtualDistanceMm() { return availableVirtualDistanceMm; }
        public double getRecommendedVirtualDistanceMm() { return recommendedVirtualDistanceMm; }
        public List<Double> getDistancesMm() { return distancesMm; }
        public List<Double> getSpeedFactors() { return speedFactors; }
        public double getMaximumPhysicalAxisExcursionMm() {
            return recommendedVirtualDistanceMm * 0.5;
        }
    }

    private CoreXYCalibrationPlanner() {
    }

    public static SamplingPlan discoverDefaultPlan() throws Exception {
        String status = CoreXYAgent.getStatus();
        if (!"true".equals(statusValue(status, "installed"))
                || !"true".equals(statusValue(status, "configured"))) {
            throw new IllegalStateException("CoreXY Agent is not installed and configured.");
        }
        if ("true".equals(statusValue(status, "enabled"))) {
            throw new IllegalStateException(
                    "Disable CoreXY compensation before planning an uncompensated calibration.");
        }
        if (!(Configuration.get().getMachine() instanceof ReferenceMachine)) {
            throw new IllegalStateException("The configured machine is not a ReferenceMachine.");
        }
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        if (!machine.isEnabled() || !machine.isHomed()) {
            throw new IllegalStateException("Enable and Home the machine before calibration planning.");
        }
        if (!(machine.getDefaultHead() instanceof ReferenceHead)) {
            throw new IllegalStateException("The default head is not a ReferenceHead.");
        }
        ReferenceHead head = (ReferenceHead) machine.getDefaultHead();
        if (!(head.getDefaultCamera() instanceof ReferenceCamera)) {
            throw new IllegalStateException("The default camera is not a ReferenceCamera.");
        }
        ReferenceCamera camera = (ReferenceCamera) head.getDefaultCamera();
        if (camera.getLooking() != Camera.Looking.Down) {
            throw new IllegalStateException("Calibration requires the down-looking camera.");
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
        requireBoundedSoftLimits(xAxis, "X");
        requireBoundedSoftLimits(yAxis, "Y");

        Location primary = head.getCalibrationPrimaryFiducialLocation()
                .convertToUnits(LengthUnit.Millimeters);
        validatePrimaryFiducial(primary, head.getCalibrationPrimaryFiducialDiameter());
        if (!reachablePath(camera, primary, 0)) {
            throw new IllegalStateException(
                    "The Primary Fiducial is outside the configured reachable area.");
        }

        double available = discoverAvailableVirtualDistance(camera, primary);
        return createPlan(primary, camera.getName(), xAxis.getName(), yAxis.getName(),
                available, DEFAULT_RANGE_UTILIZATION);
    }

    static SamplingPlan createPlan(Location primary, String cameraName,
            String xAxisName, String yAxisName, double availableVirtualDistanceMm,
            double utilization) {
        if (!Double.isFinite(availableVirtualDistanceMm)
                || availableVirtualDistanceMm <= 0) {
            throw new IllegalArgumentException("Available virtual distance must be positive.");
        }
        if (!Double.isFinite(utilization) || utilization <= 0 || utilization >= 1) {
            throw new IllegalArgumentException("Range utilization must be between 0 and 1.");
        }
        double recommended = roundToTenth(availableVirtualDistanceMm * utilization);
        if (recommended < MINIMUM_RECOMMENDED_RANGE_MM) {
            throw new IllegalStateException(String.format(
                    "Only %.3f mm virtual travel is safely available; at least %.1f mm is required.",
                    recommended, MINIMUM_RECOMMENDED_RANGE_MM));
        }
        List<Double> distances = buildPilotDistances(recommended);
        List<Double> speeds = new ArrayList<>();
        for (double speed : DEFAULT_SPEEDS) {
            speeds.add(speed);
        }
        return new SamplingPlan(primary, cameraName, xAxisName, yAxisName,
                availableVirtualDistanceMm, recommended, distances, speeds);
    }

    static List<Double> buildPilotDistances(double maximumDistanceMm) {
        if (!Double.isFinite(maximumDistanceMm)
                || maximumDistanceMm < MINIMUM_RECOMMENDED_RANGE_MM) {
            throw new IllegalArgumentException("Pilot maximum distance is too small.");
        }
        double minimum = Math.max(1.0, Math.min(5.0, maximumDistanceMm * 0.10));
        double[] candidates = {
                minimum,
                maximumDistanceMm * 0.12,
                maximumDistanceMm * 0.25,
                maximumDistanceMm * 0.45,
                maximumDistanceMm * 0.70,
                maximumDistanceMm
        };
        List<Double> distances = new ArrayList<>();
        for (double candidate : candidates) {
            double rounded = roundToTenth(candidate);
            if (distances.isEmpty()
                    || rounded - distances.get(distances.size() - 1) >= 0.1 - 1e-9) {
                distances.add(rounded);
            }
        }
        return distances;
    }

    private static double discoverAvailableVirtualDistance(ReferenceCamera camera,
            Location primary) throws Exception {
        double reachable = 0;
        double unreachable = 1.0;
        while (unreachable < RANGE_SEARCH_LIMIT_MM
                && reachablePath(camera, primary, unreachable)) {
            reachable = unreachable;
            unreachable *= 2.0;
        }
        if (unreachable >= RANGE_SEARCH_LIMIT_MM
                && reachablePath(camera, primary, unreachable)) {
            throw new IllegalStateException(
                    "Unable to derive a bounded calibration range from the configured soft limits.");
        }
        for (int iteration = 0; iteration < 40; iteration++) {
            double midpoint = (reachable + unreachable) * 0.5;
            if (reachablePath(camera, primary, midpoint)) {
                reachable = midpoint;
            }
            else {
                unreachable = midpoint;
            }
        }
        return reachable;
    }

    private static boolean reachablePath(ReferenceCamera camera, Location primary,
            double virtualDistanceMm) throws Exception {
        Location[] vectors = {
                new Location(LengthUnit.Millimeters,
                        virtualDistanceMm * 0.5, virtualDistanceMm * 0.5, 0, 0),
                new Location(LengthUnit.Millimeters,
                        virtualDistanceMm * 0.5, -virtualDistanceMm * 0.5, 0, 0)
        };
        if (!reachableWithMargin(camera, primary)) {
            return false;
        }
        for (Location vector : vectors) {
            if (!reachableWithMargin(camera, primary.add(vector))
                    || !reachableWithMargin(camera, primary.subtract(vector))) {
                return false;
            }
        }
        return true;
    }

    private static boolean reachableWithMargin(ReferenceCamera camera, Location location)
            throws Exception {
        if (!camera.isReachable(location)) {
            return false;
        }
        double[] offsets = {-PATH_SAFETY_MARGIN_MM, PATH_SAFETY_MARGIN_MM};
        for (double x : offsets) {
            for (double y : offsets) {
                Location corner = location.add(new Location(
                        LengthUnit.Millimeters, x, y, 0, 0));
                if (!camera.isReachable(corner)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void requireBoundedSoftLimits(ReferenceControllerAxis axis,
            String label) {
        if (!axis.isSoftLimitLowEnabled() || !axis.isSoftLimitHighEnabled()) {
            throw new IllegalStateException(label
                    + " axis must have both low and high soft limits enabled.");
        }
        double low = axis.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue();
        double high = axis.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue();
        if (!Double.isFinite(low) || !Double.isFinite(high) || high <= low) {
            throw new IllegalStateException(label + " axis soft limits are invalid.");
        }
    }

    private static void validatePrimaryFiducial(Location primary, Length diameter) {
        if (primary == null || !primary.isInitialized()
                || !Double.isFinite(primary.getX()) || !Double.isFinite(primary.getY())
                || Math.abs(primary.getX()) < 1e-9 || Math.abs(primary.getY()) < 1e-9) {
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

    private static String statusValue(String status, String key) {
        String prefix = key + "=";
        for (String line : status.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static double roundToTenth(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
