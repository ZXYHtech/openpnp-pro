package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Radial endpoint metrics; no relative PASS result is confused with absolute accuracy. */
public final class PrecisionMetrics {
    public final int count;
    public final double rms, p95, maximum, meanX, meanY;

    private PrecisionMetrics(int count, double rms, double p95, double maximum,
            double meanX, double meanY) {
        this.count = count;
        this.rms = rms;
        this.p95 = p95;
        this.maximum = maximum;
        this.meanX = meanX;
        this.meanY = meanY;
    }

    public static PrecisionMetrics evaluate(List<PrecisionSample> observations, PrecisionModel model) {
        if (observations == null || observations.isEmpty()) { throw new IllegalArgumentException("No observations.");
            }
        List<Double> radii = new ArrayList<>();
        double sum = 0, sumX = 0, sumY = 0;
        for (PrecisionSample s : observations) {
            double[] correction = model == null ? new double[] {0, 0} : model.predict(s);
            double dx = s.correctionX - correction[0], dy = s.correctionY - correction[1];
            double radius = Math.hypot(dx, dy);
            radii.add(radius);
            sum += radius * radius;
            sumX += dx;
            sumY += dy;
        }
        Collections.sort(radii);
        int n = radii.size();
        return new PrecisionMetrics(n, Math.sqrt(sum / n), radii.get((int) Math.ceil(n * .95) - 1),
                radii.get(n - 1), sumX / n, sumY / n);
    }
}
