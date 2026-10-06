package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.List;

/** Chooses the shortest delay whose measured endpoint agrees with the long-settled reference. */
public final class SettlingOptimizer {
    public interface Probe {
        double[] measure(int milliseconds) throws Exception;
    }

    public int select(Probe probe, double noise, double target) throws Exception {
        if (noise < 0 || !Double.isFinite(noise) || target <= 0 || !Double.isFinite(target)) {
            throw new IllegalArgumentException("Invalid settling budget.");
        }
        List<double[]> reference = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            reference.add(valid(probe.measure(1000)));
        }
        double[] center = mean(reference);
        double tolerance = Math.max(noise * 3, target * .2);
        for (int delay : new int[] {0, 25, 75, 150, 300, 600, 1000}) {
            List<Double> errors = new ArrayList<>();
            for (int repeat = 0; repeat < 8; repeat++) {
                double[] value = valid(probe.measure(delay));
                errors.add(Math.hypot(value[0] - center[0], value[1] - center[1]));
            }
            java.util.Collections.sort(errors);
            if (errors.get(7) <= tolerance) {
                return delay;
            }
        }
        throw new IllegalStateException("1000 ms 后仍不能稳定定位，请检查振动、照明或视觉误检。");
    }

    private static double[] valid(double[] value) {
        if (value == null || value.length != 2 || !Double.isFinite(value[0]) || !Double.isFinite(value[1])) {
            throw new IllegalArgumentException("Invalid endpoint observation.");
        }
        return value;
    }

    private static double[] mean(List<double[]> values) {
        double[] result = new double[2];
        for (double[] value : values) {
            result[0] += value[0] / values.size();
            result[1] += value[1] / values.size();
        }
        return result;
    }
}
