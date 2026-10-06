package org.openpnp.corexy.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Runtime form of the validated Gaussian kernel correction surface. */
final class CoreXYSurfaceModel {
    static final class Vector {
        final double x;
        final double y;

        Vector(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Point {
        final double speed;
        final double distance;
        final double x;
        final double y;
        final double variance;

        Point(double speed, double distance, double x, double y,
                double scatter, double floor) {
            this.speed = speed;
            this.distance = distance;
            this.x = x;
            this.y = y;
            variance = scatter * scatter + floor * floor;
        }
    }

    final String direction;
    final double gain;
    final double speedBandwidth;
    final double distanceBandwidth;
    final double minimumSpeed;
    final double maximumSpeed;
    final double minimumDistance;
    final double maximumDistance;
    private final List<Point> points;

    private CoreXYSurfaceModel(String direction, double gain,
            double speedBandwidth, double distanceBandwidth,
            double minimumSpeed, double maximumSpeed,
            double minimumDistance, double maximumDistance,
            List<Point> points) {
        this.direction = direction;
        this.gain = gain;
        this.speedBandwidth = speedBandwidth;
        this.distanceBandwidth = distanceBandwidth;
        this.minimumSpeed = minimumSpeed;
        this.maximumSpeed = maximumSpeed;
        this.minimumDistance = minimumDistance;
        this.maximumDistance = maximumDistance;
        this.points = points;
    }

    static CoreXYSurfaceModel load(Properties properties, String key,
            double gain) {
        String prefix = "model." + key;
        String direction = required(properties, prefix + ".direction");
        double speedBandwidth = positive(properties,
                prefix + ".speed-bandwidth");
        double distanceBandwidth = positive(properties,
                prefix + ".log-distance-bandwidth");
        double minimumSpeed = finite(properties, prefix + ".minimum-speed");
        double maximumSpeed = finite(properties, prefix + ".maximum-speed");
        double minimumDistance = positive(properties,
                prefix + ".minimum-distance.mm");
        double maximumDistance = positive(properties,
                prefix + ".maximum-distance.mm");
        if (minimumSpeed <= 0 || maximumSpeed > 1
                || maximumSpeed <= minimumSpeed) {
            throw new IllegalArgumentException(
                    prefix + " speed range must be within (0, 1].");
        }
        if (maximumDistance <= minimumDistance) {
            throw new IllegalArgumentException(
                    prefix + " distance range is invalid.");
        }
        int count = positiveInteger(properties, prefix + ".point.count");
        List<Point> points = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String point = prefix + ".point." + index;
            double speed = finite(properties, point + ".speed-factor");
            double distance = positive(properties, point + ".distance.mm");
            double scatter = nonNegative(properties, point + ".scatter.mm");
            double floor = positive(properties, point + ".detection-floor.mm");
            if (speed < minimumSpeed - 1e-9 || speed > maximumSpeed + 1e-9
                    || distance < minimumDistance - 1e-9
                    || distance > maximumDistance + 1e-9) {
                throw new IllegalArgumentException(
                        point + " is outside the declared model range.");
            }
            points.add(new Point(speed, distance,
                    finite(properties, point + ".correction.x.mm"),
                    finite(properties, point + ".correction.y.mm"),
                    scatter, floor));
        }
        if (points.size() < 6) {
            throw new IllegalArgumentException(
                    prefix + " requires at least six points.");
        }
        return new CoreXYSurfaceModel(direction, gain, speedBandwidth,
                distanceBandwidth, minimumSpeed, maximumSpeed,
                minimumDistance, maximumDistance, points);
    }

    Vector predict(double speed, double distance) {
        double querySpeed = clamp(speed, minimumSpeed, maximumSpeed);
        double queryDistance = clamp(distance, minimumDistance, maximumDistance);
        double queryS = normalizedSpeed(querySpeed);
        double queryD = normalizedDistance(queryDistance);
        double sumWeight = 0;
        double sumX = 0;
        double sumY = 0;
        Point nearest = null;
        double nearestSquared = Double.POSITIVE_INFINITY;
        for (Point point : points) {
            double ds = (normalizedSpeed(point.speed) - queryS) / speedBandwidth;
            double dd = (normalizedDistance(point.distance) - queryD)
                    / distanceBandwidth;
            double squared = ds * ds + dd * dd;
            if (squared < nearestSquared) {
                nearestSquared = squared;
                nearest = point;
            }
            if (squared > 60) {
                continue;
            }
            double weight = Math.exp(-0.5 * squared) / point.variance;
            sumWeight += weight;
            sumX += weight * point.x;
            sumY += weight * point.y;
        }
        if (sumWeight <= 1e-15) {
            return new Vector(gain * nearest.x, gain * nearest.y);
        }
        return new Vector(gain * sumX / sumWeight, gain * sumY / sumWeight);
    }

    double maximumPointMagnitude() {
        double maximum = 0;
        for (Point point : points) {
            maximum = Math.max(maximum, gain * Math.hypot(point.x, point.y));
        }
        return maximum;
    }

    double maximumCombinedPointMagnitude(CoreXYSurfaceModel other) {
        double maximum = Math.max(maximumPointMagnitude(),
                other.maximumPointMagnitude());
        for (Point point : points) {
            for (Point otherPoint : other.points) {
                maximum = Math.max(maximum, Math.hypot(
                        gain * point.x + other.gain * otherPoint.x,
                        gain * point.y + other.gain * otherPoint.y));
            }
        }
        return maximum;
    }

    private double normalizedSpeed(double speed) {
        return (speed - minimumSpeed) / (maximumSpeed - minimumSpeed);
    }

    private double normalizedDistance(double distance) {
        return Math.log(distance / minimumDistance)
                / Math.log(maximumDistance / minimumDistance);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration key: " + key);
        }
        return value.trim();
    }

    private static int positiveInteger(Properties properties, String key) {
        try {
            int value = Integer.parseInt(required(properties, key));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be a positive integer.", error);
        }
    }

    private static double finite(Properties properties, String key) {
        try {
            double value = Double.parseDouble(required(properties, key));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be finite.", error);
        }
    }

    private static double nonNegative(Properties properties, String key) {
        double value = finite(properties, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative.");
        }
        return value;
    }

    private static double positive(Properties properties, String key) {
        double value = finite(properties, key);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive.");
        }
        return value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
