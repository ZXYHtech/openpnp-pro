package org.openpnp.corexy.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class CoreXYConfig {
    enum Mode {
        STATE_OFFSET("state-offset"),
        DISTANCE_SPEED_STATE_OFFSET("distance-speed-state-offset"),
        DISTANCE_SPEED_POSITION_STATE_OFFSET(
                "distance-speed-position-state-offset");

        final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        static Mode parse(String value) {
            for (Mode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unsupported mode: " + value);
        }
    }

    enum VersionPolicy {
        STRICT("strict"),
        COMPATIBLE_API("compatible-api");

        final String configValue;

        VersionPolicy(String configValue) {
            this.configValue = configValue;
        }

        static VersionPolicy parse(String value) {
            for (VersionPolicy policy : values()) {
                if (policy.configValue.equalsIgnoreCase(value)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException(
                    "openpnp.version-policy must be strict or compatible-api.");
        }
    }

    final Path source;
    final Mode mode;
    final boolean enabled;
    final boolean dryRun;
    final boolean failClosed;
    final boolean requireHomed;
    final VersionPolicy versionPolicy;
    final String requiredOpenPnpVersion;
    final String xAxisId;
    final String yAxisId;
    final double thresholdA;
    final double thresholdB;
    final Offset aPositive;
    final Offset aNegative;
    final Offset bPositive;
    final Offset bNegative;
    final CoreXYSurfaceModel surfaceA;
    final CoreXYSurfaceModel surfaceB;
    final CoreXYSpatialSurfaceModel spatialB;
    final double speedFactor;
    final double softLimitMargin;
    final double maxOffset;

    static final class Offset {
        final double x;
        final double y;

        Offset(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private CoreXYConfig(Path source, Properties properties) {
        this.source = source;
        int schema = integer(properties, "schema.version");
        if (schema != 1 && schema != 3 && schema != 4) {
            throw new IllegalArgumentException("Unsupported schema.version: " + schema);
        }
        mode = Mode.parse(required(properties, "mode"));
        if (schema == 1 && mode != Mode.STATE_OFFSET) {
            throw new IllegalArgumentException("Schema 1 requires mode=state-offset.");
        }
        if (schema == 3 && mode != Mode.DISTANCE_SPEED_STATE_OFFSET) {
            throw new IllegalArgumentException(
                    "Schema 3 requires mode=distance-speed-state-offset.");
        }
        if (schema == 4
                && mode != Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET) {
            throw new IllegalArgumentException(
                    "Schema 4 requires mode=distance-speed-position-state-offset.");
        }

        enabled = bool(properties, "enabled");
        dryRun = bool(properties, "dry-run");
        failClosed = bool(properties, "fail-closed");
        if (!failClosed) {
            throw new IllegalArgumentException("fail-closed must remain true.");
        }
        requireHomed = bool(properties, "require-homed");
        versionPolicy = VersionPolicy.parse(properties.getProperty(
                "openpnp.version-policy", "strict").trim());
        requiredOpenPnpVersion = optional(
                properties, "openpnp.implementation-version", "not-recorded");
        if (versionPolicy == VersionPolicy.STRICT
                && "not-recorded".equals(requiredOpenPnpVersion)) {
            throw new IllegalArgumentException(
                    "Strict version policy requires openpnp.implementation-version.");
        }
        xAxisId = required(properties, "axis.x.id");
        yAxisId = required(properties, "axis.y.id");
        if (xAxisId.equals(yAxisId)) {
            throw new IllegalArgumentException("X and Y axis IDs must be different.");
        }

        thresholdA = positive(properties, "threshold.a.mm");
        thresholdB = positive(properties, "threshold.b.mm");
        if (mode == Mode.STATE_OFFSET) {
            aPositive = offset(properties, "offset.a.positive");
            aNegative = offset(properties, "offset.a.negative");
            bPositive = offset(properties, "offset.b.positive");
            bNegative = offset(properties, "offset.b.negative");
            surfaceA = null;
            surfaceB = null;
            spatialB = null;
        }
        else {
            String kind = required(properties, "model.kind");
            if (!"GAUSSIAN_KERNEL_LOG_DISTANCE_V1".equals(kind)) {
                throw new IllegalArgumentException("Unsupported model.kind: " + kind);
            }
            double gain = finite(properties, "model.gain");
            if (gain <= 0 || gain > 1) {
                throw new IllegalArgumentException("model.gain must be in (0, 1].");
            }
            surfaceA = CoreXYSurfaceModel.load(properties, "a", gain);
            surfaceB = CoreXYSurfaceModel.load(properties, "b", gain);
            if (!"A".equalsIgnoreCase(surfaceA.direction)
                    || !"B".equalsIgnoreCase(surfaceB.direction)) {
                throw new IllegalArgumentException(
                        "Surface directions must be A and B respectively.");
            }
            spatialB = mode == Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET
                    ? CoreXYSpatialSurfaceModel.load(properties, surfaceB)
                    : null;
            aPositive = new Offset(0, 0);
            aNegative = new Offset(0, 0);
            bPositive = new Offset(0, 0);
            bNegative = new Offset(0, 0);
        }
        speedFactor = finite(properties, "speed-factor");
        if (speedFactor <= 0.0 || speedFactor > 1.0) {
            throw new IllegalArgumentException("speed-factor must be in (0, 1].");
        }
        softLimitMargin = nonNegative(properties, "safety.soft-limit-margin.mm");
        maxOffset = positive(properties, "safety.max-offset.mm");
        validateOffsetMagnitude();
    }

    static CoreXYConfig load(Path source) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(source)) {
            properties.load(input);
        }
        return new CoreXYConfig(source.toAbsolutePath().normalize(), properties);
    }

    private static Offset offset(Properties properties, String prefix) {
        return new Offset(
                finite(properties, prefix + ".x.mm"),
                finite(properties, prefix + ".y.mm"));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration key: " + key);
        }
        return value.trim();
    }

    private static String optional(Properties properties, String key,
            String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be true or false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static int integer(Properties properties, String key) {
        try {
            return Integer.parseInt(required(properties, key));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer.", e);
        }
    }

    private static double finite(Properties properties, String key) {
        try {
            double value = Double.parseDouble(required(properties, key));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a finite decimal number.", e);
        }
    }

    private static double nonNegative(Properties properties, String key) {
        double value = finite(properties, key);
        if (value < 0.0) {
            throw new IllegalArgumentException(key + " must be non-negative.");
        }
        return value;
    }

    private static double positive(Properties properties, String key) {
        double value = finite(properties, key);
        if (value <= 0.0) {
            throw new IllegalArgumentException(key + " must be greater than zero.");
        }
        return value;
    }

    private void validateOffsetMagnitude() {
        if (mode == Mode.DISTANCE_SPEED_STATE_OFFSET) {
            double conservativeMaximum =
                    surfaceA.maximumCombinedPointMagnitude(surfaceB);
            if (conservativeMaximum > maxOffset) {
                throw new IllegalArgumentException(
                        "Combined CoreXY surface offset exceeds safety.max-offset.mm: "
                                + conservativeMaximum + " > " + maxOffset + ".");
            }
            return;
        }
        if (mode == Mode.DISTANCE_SPEED_POSITION_STATE_OFFSET) {
            double primaryMaximum =
                    surfaceA.maximumCombinedPointMagnitude(surfaceB);
            if (primaryMaximum > maxOffset) {
                throw new IllegalArgumentException(
                        "Primary CoreXY surface exceeds safety.max-offset.mm: "
                                + primaryMaximum + " > " + maxOffset + ".");
            }
            if (surfaceA.maximumPointMagnitude() > maxOffset
                    || spatialB.maximumHomePointMagnitude() > maxOffset) {
                throw new IllegalArgumentException(
                        "A or Home B surface exceeds safety.max-offset.mm.");
            }
            return;
        }
        Offset zero = new Offset(0.0, 0.0);
        Offset[] aStates = {zero, aPositive, aNegative};
        Offset[] bStates = {zero, bPositive, bNegative};
        for (Offset a : aStates) {
            for (Offset b : bStates) {
                double magnitude = Math.hypot(a.x + b.x, a.y + b.y);
                if (magnitude > maxOffset) {
                    throw new IllegalArgumentException(
                            "Combined CoreXY offset exceeds safety.max-offset.mm: "
                                    + magnitude + " > " + maxOffset + ".");
                }
            }
        }
    }
}
