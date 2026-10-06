package org.openpnp.corexy.precision;

/** One observation in machine millimetres. Correction = expected physical point minus actual point. */
public final class PrecisionSample {
    public final String path;
    public final String tool;
    public final double x, y, z, angle, speed, acceleration, temperature, elapsed;
    public final double deltaA, deltaB, travelA, travelB, settleMillis;
    public final int stateA, stateB;
    public final double correctionX, correctionY;

    public PrecisionSample(String path, String tool, double x, double y, double z, double angle,
            double speed, double acceleration, double temperature, double elapsed,
            double deltaA, double deltaB, double travelA, double travelB, double settleMillis,
            int stateA, int stateB, double correctionX, double correctionY) {
        if (path == null || path.isEmpty() || tool == null) {
            throw new IllegalArgumentException("Observation identity is required.");
        }
        double[] values = {x, y, z, angle, speed, acceleration, elapsed, deltaA, deltaB,
                travelA, travelB, settleMillis, correctionX, correctionY};
        for (double v : values) {
            if (!Double.isFinite(v)) { throw new IllegalArgumentException("Non-finite observation.");
                }
        }
        if ((!Double.isFinite(temperature) && !Double.isNaN(temperature)) || speed <= 0
                || acceleration <= 0 || travelA < 0 || travelB < 0 || settleMillis < 0
                || Math.abs(stateA) > 1 || Math.abs(stateB) > 1) {
            throw new IllegalArgumentException("Invalid observation domain.");
        }
        this.path = path;
        this.tool = tool;
        this.x = x;
        this.y = y;
        this.z = z;
        this.angle = angle;
        this.speed = speed;
        this.acceleration = acceleration;
        this.temperature = temperature;
        this.elapsed = elapsed;
        this.deltaA = deltaA;
        this.deltaB = deltaB;
        this.travelA = travelA;
        this.travelB = travelB;
        this.settleMillis = settleMillis;
        this.stateA = stateA;
        this.stateB = stateB;
        this.correctionX = correctionX;
        this.correctionY = correctionY;
    }

    public PrecisionSample residual(double dx, double dy) {
        return new PrecisionSample(path, tool, x, y, z, angle, speed, acceleration,
                temperature, elapsed, deltaA, deltaB, travelA, travelB, settleMillis,
                stateA, stateB, correctionX - dx, correctionY - dy);
    }
}
