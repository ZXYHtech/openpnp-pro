package org.openpnp.corexy.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreXYVisualDiagnosticsTest {
    @Test
    void compensatedScanAcceptsEveryRealCompensationMode() {
        assertTrue(CoreXYVisualDiagnostics.isSupportedCompensatedScanMode(
                "state-offset"));
        assertTrue(CoreXYVisualDiagnostics.isSupportedCompensatedScanMode(
                "distance-speed-state-offset"));
        assertTrue(CoreXYVisualDiagnostics.isSupportedCompensatedScanMode(
                "distance-speed-position-state-offset"));
    }

    @Test
    void compensatedScanRejectsUnknownOrMissingModes() {
        assertFalse(CoreXYVisualDiagnostics.isSupportedCompensatedScanMode(null));
        assertFalse(CoreXYVisualDiagnostics.isSupportedCompensatedScanMode("none"));
    }
}
