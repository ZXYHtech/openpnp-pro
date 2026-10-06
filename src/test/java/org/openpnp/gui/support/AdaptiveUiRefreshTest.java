package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AdaptiveUiRefreshTest {
    @Test
    void keepsFullRefreshRateWhileActive() {
        assertEquals(33, AdaptiveUiRefresh.timerDelay(true, true));
        assertEquals(45, AdaptiveUiRefresh.snapshotInterval(true));
    }

    @Test
    void backsOffOnlyWhenIdleOrHidden() {
        assertEquals(150, AdaptiveUiRefresh.timerDelay(true, false));
        assertEquals(500, AdaptiveUiRefresh.timerDelay(false, true));
        assertEquals(150, AdaptiveUiRefresh.snapshotInterval(false));
    }

    @Test
    void prioritizesQueuedInputWithoutStarvingSnapshots() {
        assertEquals(false, AdaptiveUiRefresh.shouldTakeSnapshot(44, 0, true, false));
        assertEquals(true, AdaptiveUiRefresh.shouldTakeSnapshot(45, 0, true, false));
        assertEquals(false, AdaptiveUiRefresh.shouldTakeSnapshot(45, 0, true, true));
        assertEquals(true, AdaptiveUiRefresh.shouldTakeSnapshot(250, 0, true, true));
    }
}
