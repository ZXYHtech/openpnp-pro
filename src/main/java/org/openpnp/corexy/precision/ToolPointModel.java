package org.openpnp.corexy.precision;

import java.util.List;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Residual correction over native runout: angular eccentricity, Z tilt and Z-dependent runout. */
public final class ToolPointModel {
    public static final class Observation {
        public final double z, angle, x, y;
        public Observation(double z, double angle, double x, double y) {
            for (double value : new double[] {z, angle, x, y}) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Non-finite tool observation.");
                }
            }
            this.z = z;
            this.angle = angle;
            this.x = x;
            this.y = y;
        }
    }
    public String nozzleId, tipId;
    public String nativeSignature = "";
    public boolean replacesNative;
    public double minZ, maxZ, maximumOffset;
    public double[] x, y;
    public double beforeP95, afterP95, noise;

    public void validate() {
        if (nozzleId == null || tipId == null || x == null || y == null || x.length != 6 || y.length != 6
                || !Double.isFinite(minZ) || !Double.isFinite(maxZ) || maxZ - minZ < .2
                || !Double.isFinite(maximumOffset) || maximumOffset <= 0 || maximumOffset > 2
                || !Double.isFinite(noise) || noise < 0 || !Double.isFinite(beforeP95)
                || !Double.isFinite(afterP95) || afterP95 >= beforeP95 - noise * 2) {
            throw new IllegalArgumentException("Invalid tool-point model or independent tool verification.");
        }
        for (int i = 0; i < 6; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
                throw new IllegalArgumentException("Invalid tool-point coefficient.");
            }
        }
    }

    public double[] predict(double z, double angle) {
        if (!Double.isFinite(z) || !Double.isFinite(angle)) {
            return new double[] {0, 0};
        }
        double[] features = features(Math.max(minZ, Math.min(maxZ, z)), angle);
        double dx = 0, dy = 0;
        for (int i = 0; i < features.length; i++) {
            dx += x[i] * features[i];
            dy += y[i] * features[i];
        }
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.hypot(dx, dy) > maximumOffset) {
            throw new IllegalStateException("工具点补偿超出校准范围。");
        }
        return new double[] {dx, dy};
    }

    public void fit(List<Observation> observations) {
        if (observations == null || observations.size() < 24) {
            throw new IllegalArgumentException("Too few multi-height tool observations.");
        }
        double[][] matrix = new double[observations.size()][6];
        double[] bx = new double[observations.size()], by = new double[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            Observation observation = observations.get(i);
            matrix[i] = features(observation.z, observation.angle);
            bx[i] = observation.x;
            by[i] = observation.y;
        }
        Array2DRowRealMatrix design = new Array2DRowRealMatrix(matrix, false);
        if (new SingularValueDecomposition(design).getRank() < 6) {
            throw new IllegalArgumentException("工具点角度或高度激励不足。");
        }
        org.apache.commons.math3.linear.DecompositionSolver solver = new QRDecomposition(design).getSolver();
        x = solver.solve(new ArrayRealVector(bx, false)).toArray();
        y = solver.solve(new ArrayRealVector(by, false)).toArray();
    }

    private double[] features(double z, double angle) {
        double radians = Math.toRadians(angle), dz = z - (minZ + maxZ) / 2;
        double c = Math.cos(radians), s = Math.sin(radians);
        return new double[] {1, c, s, dz, dz * c, dz * s};
    }
}
