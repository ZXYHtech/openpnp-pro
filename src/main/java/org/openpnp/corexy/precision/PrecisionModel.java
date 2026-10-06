package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** Portable feed-forward model shared by native Pro and the external extension. */
public final class PrecisionModel {
    public enum Layer {
        COMMON_BIAS, FIXED_STATE, CONTINUOUS_REVERSAL, JOINT_STATE,
        AFFINE_GEOMETRY, SPATIAL_GRID, SPEED_ACCELERATION, THERMAL_DRIFT, TOOL_POINT, STATE_SPATIAL
    }
    private List<Layer> layers;
    private double minX, maxX, minY, maxY, speedReference, accelerationReference;
    private double temperatureReference, zReference, transitionA, transitionB;
    private int gridColumns, gridRows;
    private double[] coefficientX, coefficientY;
    private double maximumOffset;
    private double homeA, homeB;
    private boolean boundedOperatingDomain;
    private double minSpeed, maxSpeed, minAcceleration, maxAcceleration, minTemperature, maxTemperature;

    PrecisionModel(EnumSet<Layer> selected, double[] domain, double lengthA, double lengthB,
            int columns, int rows, double maxOffset) {
        layers = new ArrayList<>(selected);
        minX = domain[0];
        maxX = domain[1];
        minY = domain[2];
        maxY = domain[3];
        speedReference = domain[4];
        accelerationReference = domain[5];
        temperatureReference = domain[6];
        zReference = domain[7];
        transitionA = lengthA;
        transitionB = lengthB;
        gridColumns = columns;
        gridRows = rows;
        maximumOffset = maxOffset;
    }

    void setCoefficients(double[] x, double[] y) {
        coefficientX = x.clone();
        coefficientY = y.clone();
        validate();
    }

    void setOperatingDomain(List<PrecisionSample> samples) {
        minSpeed = minAcceleration = minTemperature = Double.POSITIVE_INFINITY;
        maxSpeed = maxAcceleration = maxTemperature = Double.NEGATIVE_INFINITY;
        for (PrecisionSample sample : samples) {
            minSpeed = Math.min(minSpeed, sample.speed);
            maxSpeed = Math.max(maxSpeed, sample.speed);
            minAcceleration = Math.min(minAcceleration, sample.acceleration);
            maxAcceleration = Math.max(maxAcceleration, sample.acceleration);
            if (Double.isFinite(sample.temperature)) {
                minTemperature = Math.min(minTemperature, sample.temperature);
                maxTemperature = Math.max(maxTemperature, sample.temperature);
            }
        }
        if (!Double.isFinite(minTemperature)) {
            minTemperature = maxTemperature = temperatureReference;
        }
        boundedOperatingDomain = true;
    }

    public List<Layer> getLayers() { return Collections.unmodifiableList(layers);
        }
    public double getTransitionA() { return transitionA;
        }
    public double getTransitionB() { return transitionB;
        }
    public double getMaximumOffset() { return maximumOffset;
        }
    public double getHomeA() {
        return homeA;
    }
    public double getHomeB() {
        return homeB;
    }
    void setHomeStates(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(a) > 1 || Math.abs(b) > 1) {
            throw new IllegalArgumentException("Invalid Home state.");
        }
        homeA = a;
        homeB = b;
    }
    public int getParameterCount() { return coefficientX.length;
        }
    public double[] getBounds() { return new double[] {minX, maxX, minY, maxY};
        }

    public void validate() {
        double[] parameters = {minX, maxX, minY, maxY, speedReference, accelerationReference,
                temperatureReference, zReference, transitionA, transitionB, maximumOffset};
        for (double v : parameters) { if (!Double.isFinite(v)) { throw new IllegalArgumentException("Invalid model number.");
                } }
        if (boundedOperatingDomain) {
            for (double value : new double[] {minSpeed, maxSpeed, minAcceleration, maxAcceleration, minTemperature, maxTemperature}) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Invalid operating domain.");
                }
            }
            if (minSpeed > maxSpeed || minAcceleration > maxAcceleration || minTemperature > maxTemperature) {
                throw new IllegalArgumentException("Invalid operating bounds.");
            }
        }
        if (layers == null || layers.isEmpty() || layers.contains(null)
                || EnumSet.copyOf(layers).size() != layers.size()
                || maxX <= minX || maxY <= minY || speedReference <= 0 || accelerationReference <= 0
                || transitionA <= 0 || transitionB <= 0 || maximumOffset <= 0 || maximumOffset > 2
                || gridColumns < 2 || gridRows < 2 || gridColumns > 15 || gridRows > 15
                || (layers.contains(Layer.FIXED_STATE) && layers.contains(Layer.CONTINUOUS_REVERSAL))
                || !Double.isFinite(homeA) || !Double.isFinite(homeB) || Math.abs(homeA) > 1 || Math.abs(homeB) > 1
                || coefficientX == null || coefficientY == null || coefficientX.length != coefficientY.length
                || coefficientX.length != featureCount()) { throw new IllegalArgumentException("Invalid model schema.");
            }
        for (double v : coefficientX) { if (!Double.isFinite(v)) { throw new IllegalArgumentException("Invalid X coefficient.");
                } }
        for (double v : coefficientY) { if (!Double.isFinite(v)) { throw new IllegalArgumentException("Invalid Y coefficient.");
                } }
    }

    public double[] predict(PrecisionSample query) {
        double a = reversalFeature(query.stateA, query.travelA, transitionA);
        double b = reversalFeature(query.stateB, query.travelB, transitionB);
        return predict(query, a, b);
    }

    public double[] predict(PrecisionSample query, double stateA, double stateB) {
        if (layers.contains(Layer.THERMAL_DRIFT) && !Double.isFinite(query.temperature)) {
            throw new IllegalStateException("Temperature telemetry is unavailable for this model.");
        }
        double[] f = features(query, stateA, stateB);
        double dx = 0, dy = 0;
        for (int i = 0; i < f.length; i++) { dx += f[i] * coefficientX[i];
            dy += f[i] * coefficientY[i];
            }
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.hypot(dx, dy) > maximumOffset + 1e-9) {
            throw new IllegalStateException("Predicted correction exceeds the calibrated displacement limit.");
        }
        return new double[] {dx, dy};
    }

    double[] features(PrecisionSample q, double historyA, double historyB) {
        List<Double> values = new ArrayList<>();
        double nx = clamp((q.x - minX) / (maxX - minX), 0, 1);
        double ny = clamp((q.y - minY) / (maxY - minY), 0, 1);
        double a = layers.contains(Layer.CONTINUOUS_REVERSAL) ? historyA : q.stateA;
        double b = layers.contains(Layer.CONTINUOUS_REVERSAL) ? historyB : q.stateB;
        for (Layer layer : layers) {
            switch (layer) {
                case COMMON_BIAS: values.add(1.0);
                break;
                case FIXED_STATE: case CONTINUOUS_REVERSAL:
                    values.add(a);
                values.add(b);
                break;
                case JOINT_STATE: values.add(a * b);
                break;
                case AFFINE_GEOMETRY: values.add(nx - .5);
                values.add(ny - .5);
                break;
                case SPATIAL_GRID:
                    double[] weights = gridWeights(nx, ny);
                    // Remove the constant gauge; COMMON_BIAS carries the shared translation.
                    for (int i = 1; i < weights.length; i++) { values.add(weights[i]);
                    }
                    break;
                case SPEED_ACCELERATION:
                    double dv = (boundedOperatingDomain ? clamp(q.speed, minSpeed, maxSpeed) : q.speed) / speedReference - 1;
                    double da = (boundedOperatingDomain ? clamp(q.acceleration, minAcceleration, maxAcceleration) : q.acceleration) / accelerationReference - 1;
                    values.add(a * dv);
                values.add(b * dv);
                values.add(a * da);
                values.add(b * da);
                    break;
                case THERMAL_DRIFT: values.add((boundedOperatingDomain
                        ? clamp(q.temperature, minTemperature, maxTemperature) : q.temperature) - temperatureReference);
                break;
                case STATE_SPATIAL:
                    double[] stateWeights = gridWeights(nx, ny);
                    for (int i = 1; i < stateWeights.length; i++) {
                        values.add(a * stateWeights[i]);
                        values.add(b * stateWeights[i]);
                    }
                    break;
                case TOOL_POINT:
                    double angle = Math.toRadians(q.angle);
                    values.add(Math.cos(angle));
                values.add(Math.sin(angle));
                    values.add(q.z - zReference);
                break;
                default: throw new IllegalStateException("Unknown layer.");
            }
        }
        double[] features = new double[values.size()];
        for (int i = 0; i < features.length; i++) { features[i] = values.get(i);
            }
        return features;
    }

    double[] features(PrecisionSample q) {
        return features(q, reversalFeature(q.stateA, q.travelA, transitionA),
                reversalFeature(q.stateB, q.travelB, transitionB));
    }

    private static double reversalFeature(int sign, double travel, double transition) {
        // Calibration legs begin at a deliberately saturated opposite preload.
        return sign == 0 ? 0 : sign * (2 * Math.min(1, travel / transition) - 1);
    }

    private double[] gridWeights(double nx, double ny) {
        double gx = nx * (gridColumns - 1), gy = ny * (gridRows - 1);
        int ix = Math.min(gridColumns - 2, (int) gx), iy = Math.min(gridRows - 2, (int) gy);
        double fx = gx - ix, fy = gy - iy;
        double[] weights = new double[gridColumns * gridRows];
        weights[iy * gridColumns + ix] = (1 - fx) * (1 - fy);
        weights[iy * gridColumns + ix + 1] = fx * (1 - fy);
        weights[(iy + 1) * gridColumns + ix] = (1 - fx) * fy;
        weights[(iy + 1) * gridColumns + ix + 1] = fx * fy;
        return weights;
    }

    private int featureCount() {
        int n = 0;
        for (Layer layer : layers) { switch (layer) {
            case COMMON_BIAS: case JOINT_STATE: case THERMAL_DRIFT: n++;
                break;
            case FIXED_STATE: case CONTINUOUS_REVERSAL: case AFFINE_GEOMETRY: n += 2;
                break;
            case SPEED_ACCELERATION: n += 4;
                break;
            case TOOL_POINT: n += 3;
                break;
            case SPATIAL_GRID: n += gridColumns * gridRows - 1;
                break;
            case STATE_SPATIAL: n += 2 * (gridColumns * gridRows - 1);
                break;
            default: throw new IllegalStateException("Unknown layer.");
        } }
        return n;
    }

    private static double clamp(double v, double low, double high) { return Math.max(low, Math.min(high, v));
        }
}
