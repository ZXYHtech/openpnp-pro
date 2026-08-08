package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class MachineSetupPanelTest {
    @Test
    public void navigationFilterMatchesTitleTypeAndBreadcrumb() {
        assertTrue(MachineSetupPanel.matchesNavigationFilter("相机",
                "底部相机", "机器 › 相机 › 底部相机", "ReferenceCamera"));
        assertTrue(MachineSetupPanel.matchesNavigationFilter("referencecamera",
                "底部相机", "机器 › 相机 › 底部相机", "ReferenceCamera"));
        assertTrue(MachineSetupPanel.matchesNavigationFilter("", "轴", "机器 › 轴", "Axis"));
        assertFalse(MachineSetupPanel.matchesNavigationFilter("飞达",
                "底部相机", "机器 › 相机 › 底部相机", "ReferenceCamera"));
    }
}
