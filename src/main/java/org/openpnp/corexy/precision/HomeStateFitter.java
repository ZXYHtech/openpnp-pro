package org.openpnp.corexy.precision;

import java.util.List;

/** Identifies controller Home preload separately from the fully conditioned reversal data. */
public final class HomeStateFitter {
    public static final class Observation {
        public final PrecisionSample sample;
        public final List<double[]> trace;
        public final double dx, dy;
        public Observation(PrecisionSample sample, List<double[]> trace, double dx, double dy) {
            this.sample = sample;
            this.trace = trace;
            this.dx = dx;
            this.dy = dy;
        }
    }
    public void fit(PrecisionModel model, List<Observation> observations) {
        if (observations == null || observations.size() < 3) {
            throw new IllegalArgumentException("Three independent Home observations are required.");
        }
        double best = Double.POSITIVE_INFINITY, chosenA = 0, chosenB = 0;
        double[] states = {0, -.25, .25, -.5, .5, -.75, .75, -1, 1};
        for (double initialA : states) {
            for (double initialB : states) {
                double error = 0;
                for (Observation observation : observations) {
                    ReversalState a = new ReversalState(), b = new ReversalState();
                    a.initialize(initialA);
                    b.initialize(initialB);
                    for (double[] leg : observation.trace) {
                        a.advance(leg[0] + leg[1], model.getTransitionA());
                        b.advance(leg[0] - leg[1], model.getTransitionB());
                    }
                    a.advance(observation.dx + observation.dy, model.getTransitionA());
                    b.advance(observation.dx - observation.dy, model.getTransitionB());
                    PrecisionSample q = observation.sample;
                    PrecisionSample query = new PrecisionSample(q.path, q.tool, q.x, q.y, q.z, q.angle,
                            q.speed, q.acceleration, q.temperature, q.elapsed,
                            observation.dx + observation.dy, observation.dx - observation.dy,
                            a.getTravel(), b.getTravel(), q.settleMillis, a.getDirection(), b.getDirection(), 0, 0);
                    double[] prediction = model.predict(query, a.getValue(), b.getValue());
                    error += Math.pow(q.correctionX - prediction[0], 2) + Math.pow(q.correctionY - prediction[1], 2);
                }
                if (error < best - 1e-12) {
                    best = error;
                    chosenA = initialA;
                    chosenB = initialB;
                }
            }
        }
        model.setHomeStates(chosenA, chosenB);
    }
}
