package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JOptionPane;

import org.junit.jupiter.api.Test;

class FloatingCameraMonitorCommandTest {
    @Test
    void exposesComprehensiveCommandsIncludingSafeNozzlePause() {
        assertTrue(FloatingCameraMonitor.Command.values().length >= 50);
        assertNotNull(FloatingCameraMonitor.Command.valueOf("PAUSE_NOZZLES_EMPTY"));
        assertTrue(FloatingCameraMonitor.Command.PAUSE_NOZZLES_EMPTY.toString()
                .contains("吸嘴贴完暂停"));
        assertTrue(FloatingCameraMonitor.standardOptionValues(JOptionPane.YES_NO_OPTION)
                .contains(JOptionPane.YES_OPTION));
        assertTrue(FloatingCameraMonitor.standardOptionValues(JOptionPane.YES_NO_OPTION)
                .contains(JOptionPane.NO_OPTION));
    }
}