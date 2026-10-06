package org.openpnp.corexy.agent;

import java.util.Properties;

/** Two-anchor position interpolation for the B correction surface. */
final class CoreXYSpatialSurfaceModel {
    static final String KIND = "CLAMPED_ANCHOR_PROJECTION_V1";

    final CoreXYSurfaceModel primary;
    final CoreXYSurfaceModel home;
    final double primaryX;
    final double primaryY;
    final double homeX;
    final double homeY;
    final double separation;
    final double primaryProtectionRadius;
    private final double axisX;
    private final double axisY;
    private final double axisSquared;

    private CoreXYSpatialSurfaceModel(CoreXYSurfaceModel primary,
            CoreXYSurfaceModel home, double primaryX, double primaryY,
            double homeX, double homeY, double primaryProtectionRadius) {
        this.primary = primary;
        this.home = home;
        this.primaryX = primaryX;
        this.primaryY = primaryY;
        this.homeX = homeX;
        this.homeY = homeY;
        axisX = homeX - primaryX;
        axisY = homeY - primaryY;
        axisSquared = axisX * axisX + axisY * axisY;
        separation = Math.sqrt(axisSquared);
        this.primaryProtectionRadius = primaryProtectionRadius;
    }

    static CoreXYSpatialSurfaceModel load(Properties properties,
            CoreXYSurfaceModel primary) {
        String kind = required(properties, "spatial.kind");
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported spatial.kind: " + kind);
        }
        double homeGain = finite(properties, "model.home-b.gain");
        if (homeGain <= 0 || homeGain > 1) {
            throw new IllegalArgumentException(
                    "model.home-b.gain must be in (0, 1].");
        }
        CoreXYSurfaceModel home = CoreXYSurfaceModel.load(
                properties, "home-b", homeGain);
        if (!"B".equalsIgnoreCase(primary.direction)
                || !"B".equalsIgnoreCase(home.direction)) {
            throw new IllegalArgumentException(
                    "Both spatial surfaces must describe direction B.");
        }
        CoreXYSpatialSurfaceModel model = new CoreXYSpatialSurfaceModel(
                primary, home,
                finite(properties, "spatial.anchor.primary.x.mm"),
                finite(properties, "spatial.anchor.primary.y.mm"),
                finite(properties, "spatial.anchor.home.x.mm"),
                finite(properties, "spatial.anchor.home.y.mm"),
                optionalFinite(properties,
                        "spatial.primary-protection-radius.mm", 0.0));
        if (model.separation < 50.0) {
            throw new IllegalArgumentException(
                    "Spatial anchors must be separated by at least 50 mm.");
        }
        if (model.primaryProtectionRadius < 0.0
                || model.primaryProtectionRadius >= model.separation) {
            throw new IllegalArgumentException(
                    "spatial.primary-protection-radius.mm must be in [0, anchor separation). ");
        }
        return model;
    }

    CoreXYSurfaceModel.Vector predict(double speed, double distance,
            double x, double y) {
        double weight = weight(x, y);
        CoreXYSurfaceModel.Vector primaryValue = primary.predict(speed, distance);
        CoreXYSurfaceModel.Vector homeValue = home.predict(speed, distance);
        return new CoreXYSurfaceModel.Vector(
                primaryValue.x + weight * (homeValue.x - primaryValue.x),
                primaryValue.y + weight * (homeValue.y - primaryValue.y));
    }

    double weight(double x, double y) {
        double projection = ((x - primaryX) * axisX
                + (y - primaryY) * axisY) / axisSquared;
        double protectedFraction = primaryProtectionRadius / separation;
        double adjusted = (projection - protectedFraction)
                / (1.0 - protectedFraction);
        return Math.max(0.0, Math.min(1.0, adjusted));
    }

    double maximumHomePointMagnitude() {
        return home.maximumPointMagnitude();
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration key: " + key);
        }
        return value.trim();
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

    private static double optionalFinite(Properties properties, String key,
            double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return finite(properties, key);
    }
}
