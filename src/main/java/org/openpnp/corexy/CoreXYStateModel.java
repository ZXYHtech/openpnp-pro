package org.openpnp.corexy;

/**
 * Tracks the two belt/motor direction states used by a CoreXY mechanism.
 * A = X + Y and B = X - Y. Zero or sub-threshold movement preserves a state.
 */
public final class CoreXYStateModel {
    private int aState;
    private int bState;

    public void update(double deltaX, double deltaY, double minimumA, double minimumB) {
        double deltaA = deltaX + deltaY;
        double deltaB = deltaX - deltaY;
        if (deltaA != 0.0 && Math.abs(deltaA) >= Math.abs(minimumA)) {
            aState = deltaA > 0.0 ? 1 : -1;
        }
        if (deltaB != 0.0 && Math.abs(deltaB) >= Math.abs(minimumB)) {
            bState = deltaB > 0.0 ? 1 : -1;
        }
    }

    public void reset() {
        aState = 0;
        bState = 0;
    }

    public int getAState() {
        return aState;
    }

    public int getBState() {
        return bState;
    }
}
