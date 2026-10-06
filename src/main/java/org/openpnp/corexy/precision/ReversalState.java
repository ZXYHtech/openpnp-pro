package org.openpnp.corexy.precision;

/** Accumulates logical belt travel. Compensation moves never enter this state. */
public final class ReversalState {
    private int direction;
    private double travel;
    private double value;

    public ReversalState copy() {
        ReversalState copy = new ReversalState();
        copy.direction = direction;
        copy.travel = travel;
        copy.value = value;
        return copy;
    }

    public double advance(double delta, double transitionLength) {
        if (!Double.isFinite(delta) || !Double.isFinite(transitionLength) || transitionLength <= 0) {
            throw new IllegalArgumentException("Invalid reversal displacement.");
        }
        if (Math.abs(delta) <= 1e-12) { return value;
            }
        int next = delta > 0 ? 1 : -1;
        if (next != direction) { direction = next;
            travel = 0;
            }
        travel += Math.abs(delta);
        // A bounded displacement state retains partial preload through short reversals.
        value = Math.max(-1, Math.min(1, value + 2 * delta / transitionLength));
        return value;
    }

    public int getDirection() { return direction;
        }
    public double getTravel() { return travel;
        }
    public double getValue() { return value;
        }
    public void reset() { direction = 0;
        travel = 0;
        value = 0;
        }
    public void initialize(double preload) {
        if (!Double.isFinite(preload) || Math.abs(preload) > 1) {
            throw new IllegalArgumentException("Invalid Home preload.");
        }
        value = preload;
        direction = preload > 0 ? 1 : preload < 0 ? -1 : 0;
        travel = 0;
    }
}
