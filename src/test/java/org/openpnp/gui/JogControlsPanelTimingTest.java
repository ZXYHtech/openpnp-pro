package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JogControlsPanelTimingTest {
    @Test
    void measuresDelayBeforeSwingDispatchesTheClick() {
        assertEquals(87, JogControlsPanel.inputDispatchDelayMillis(1_087, 1_000));
    }

    @Test
    void ignoresUnavailableOrInvalidEventTimestamps() {
        assertEquals(0, JogControlsPanel.inputDispatchDelayMillis(1_000, 0));
        assertEquals(0, JogControlsPanel.inputDispatchDelayMillis(1_000, 1_001));
    }
}
