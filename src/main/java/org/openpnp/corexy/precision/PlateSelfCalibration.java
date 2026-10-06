package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Separates rotating artifact errors from a stationary bilinear machine error field. */
public final class PlateSelfCalibration {
    public static final class Observation {
        public final int view, marker;
        public final double x, y, angle, correctionX, correctionY;
        public Observation(int view, int marker, double x, double y, double angle, double dx, double dy) {
            this.view = view;
            this.marker = marker;
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.correctionX = dx;
            this.correctionY = dy;
        }
    }

    public static final class Result {
        public final Map<String, double[]> markerOffsets;
        public final double residualRms;
        public final int rank, parameters;
        public final double[] viewAngleCorrections;
        Result(Map<String, double[]> offsets, double residual, int rank, int parameters, double[] angles) {
            markerOffsets = offsets;
            residualRms = residual;
            this.rank = rank;
            this.parameters = parameters;
            viewAngleCorrections = angles;
        }
    }

    /** Origin and a certified horizontal reference fix translation, scale and rotation gauges.
     * Without an independent length reference, absolute scale is mathematically unobservable. */
    public Result solve(List<Observation> observations, int gridSize, boolean certifiedReference,
            double maximumMarkerError) {
        if (!certifiedReference) {
            throw new IllegalArgumentException("标定板自校准需要独立标准节距；普通打印板无法确定绝对比例误差。");
        }
        if ((gridSize != 5 && gridSize != 9) || observations == null
                || observations.size() < gridSize * gridSize * 4 || !Double.isFinite(maximumMarkerError)
                || maximumMarkerError <= 0) {
            throw new IllegalArgumentException("至少需要四个不同平移/旋转姿态的完整标定板观测。");
        }
        int markers = gridSize * gridSize, nodes = 9;
        int translationStart = nodes * 2 + (markers - 1) * 2;
        int rotationStart = translationStart + 6;
        int parameters = rotationStart + 3;
        double[][] origins = new double[4][2];
        List<double[]> design = new ArrayList<>();
        List<Double> rhs = new ArrayList<>();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = minX, maxY = maxX;
        boolean[][] present = new boolean[4][markers];
        for (Observation observation : observations) {
            if (observation.view < 0 || observation.view > 3 || observation.marker < 0
                    || observation.marker >= markers) {
                throw new IllegalArgumentException("Invalid plate observation identity.");
            }
            for (double v : new double[] {observation.x, observation.y, observation.angle,
                    observation.correctionX, observation.correctionY}) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("Non-finite plate observation.");
                }
            }
            present[observation.view][observation.marker] = true;
            if (observation.marker == 0) {
                origins[observation.view][0] = observation.x;
                origins[observation.view][1] = observation.y;
            }
            minX = Math.min(minX, observation.x);
            maxX = Math.max(maxX, observation.x);
            minY = Math.min(minY, observation.y);
            maxY = Math.max(maxY, observation.y);
        }
        for (boolean[] view : present) {
            for (boolean marker : view) {
                if (!marker) {
                    throw new IllegalArgumentException("存在缺失 marker 的姿态，请重新采集。");
                }
            }
        }
        if (maxX - minX < 10 || maxY - minY < 10) {
            throw new IllegalArgumentException("标定板覆盖范围不足。");
        }
        for (Observation observation : observations) {
            double[] weights = weights((observation.x - minX) / (maxX - minX),
                    (observation.y - minY) / (maxY - minY));
            for (int component = 0; component < 2; component++) {
                double[] row = new double[parameters];
                for (int node = 0; node < nodes; node++) {
                    row[node * 2 + component] = weights[node];
                }
                if (observation.marker > 0) {
                    int index = nodes * 2 + (observation.marker - 1) * 2;
                    double c = Math.cos(Math.toRadians(observation.angle));
                    double s = Math.sin(Math.toRadians(observation.angle));
                    row[index] = component == 0 ? c : s;
                    row[index + 1] = component == 0 ? -s : c;
                }
                if (observation.view > 0) {
                    row[translationStart + (observation.view - 1) * 2 + component] = 1;
                    // Small manual placement rotations are nuisance parameters, not marker errors.
                    row[rotationStart + observation.view - 1] = component == 0
                            ? -(observation.y - origins[observation.view][1])
                            : observation.x - origins[observation.view][0];
                }
                design.add(row);
                rhs.add(component == 0 ? observation.correctionX : observation.correctionY);
            }
        }
        // Certified end point of the first row defines its true distance and orientation.
        for (int component = 0; component < 2; component++) {
            double[] constraint = new double[parameters];
            constraint[nodes * 2 + (gridSize - 2) * 2 + component] = 10000;
            design.add(constraint);
            rhs.add(0.0);
        }
        double[][] matrix = design.toArray(new double[design.size()][]);
        double[] response = new double[rhs.size()];
        for (int i = 0; i < response.length; i++) {
            response[i] = rhs.get(i);
        }
        Array2DRowRealMatrix a = new Array2DRowRealMatrix(matrix, false);
        int rank = new SingularValueDecomposition(a).getRank();
        if (rank < parameters) {
            throw new IllegalArgumentException("这些姿态不能分离机器与标定板误差；改变平移量和 90°/180°/270° 姿态后重试。");
        }
        double[] solution = new QRDecomposition(a, 1e-10).getSolver().solve(new ArrayRealVector(response, false)).toArray();
        double[] angles = new double[4];
        for (int view = 1; view < 4; view++) {
            angles[view] = Math.toDegrees(solution[rotationStart + view - 1]);
            if (Math.abs(angles[view]) > 2) {
                throw new IllegalArgumentException("标定板姿态角度偏差超过 2°，请按动画方向重新摆放。");
            }
        }
        Map<String, double[]> offsets = new LinkedHashMap<>();
        offsets.put("0", new double[] {0, 0});
        for (int marker = 1; marker < markers; marker++) {
            int index = nodes * 2 + (marker - 1) * 2;
            double[] value = {solution[index], solution[index + 1]};
            if (!Double.isFinite(value[0]) || !Double.isFinite(value[1])
                    || Math.hypot(value[0], value[1]) > maximumMarkerError) {
                throw new IllegalArgumentException("标定板误差超出允许范围；检查 marker 编号、姿态和标准节距。");
            }
            offsets.put(Integer.toString(marker), value);
        }
        double sum = 0;
        for (int i = 0; i < observations.size() * 2; i++) {
            double residual = response[i];
            for (int j = 0; j < parameters; j++) {
                residual -= matrix[i][j] * solution[j];
            }
            sum += residual * residual;
        }
        return new Result(offsets, Math.sqrt(sum / (observations.size() * 2)), rank, parameters, angles);
    }

    private static double[] weights(double x, double y) {
        double u = Math.max(0, Math.min(2, x * 2)), v = Math.max(0, Math.min(2, y * 2));
        int column = Math.min(1, (int) u), row = Math.min(1, (int) v);
        double du = u - column, dv = v - row;
        double[] weights = new double[9];
        weights[row * 3 + column] = (1 - du) * (1 - dv);
        weights[row * 3 + column + 1] = du * (1 - dv);
        weights[(row + 1) * 3 + column] = (1 - du) * dv;
        weights[(row + 1) * 3 + column + 1] = du * dv;
        return weights;
    }
}
