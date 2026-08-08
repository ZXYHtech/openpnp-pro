/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

/**
 * Shared refresh policy that preserves full frame rate while work is active and
 * reduces Swing wake-ups while a view is static or hidden.
 */
public final class AdaptiveUiRefresh {
    public static final int ACTIVE_DELAY_MS = 33;
    public static final int IDLE_DELAY_MS = 150;
    public static final int HIDDEN_DELAY_MS = 500;
    public static final int ACTIVE_SNAPSHOT_MS = 45;
    public static final int IDLE_SNAPSHOT_MS = 150;
    public static final int MAX_INPUT_DEFERRAL_MS = 250;

    private AdaptiveUiRefresh() {
    }

    public static int timerDelay(boolean showing, boolean active) {
        if (!showing) {
            return HIDDEN_DELAY_MS;
        }
        return active ? ACTIVE_DELAY_MS : IDLE_DELAY_MS;
    }

    public static int snapshotInterval(boolean active) {
        return active ? ACTIVE_SNAPSHOT_MS : IDLE_SNAPSHOT_MS;
    }

    /**
     * Defers non-essential diagnostic snapshots while user input is waiting on
     * the Swing queue, but guarantees that the view is refreshed after a short
     * maximum deferral.
     */
    public static boolean shouldTakeSnapshot(long nowMillis, long lastSnapshotMillis,
            boolean active, boolean inputPending) {
        long elapsed = nowMillis - lastSnapshotMillis;
        if (elapsed < snapshotInterval(active)) {
            return false;
        }
        return !inputPending || elapsed >= MAX_INPUT_DEFERRAL_MS;
    }
}
