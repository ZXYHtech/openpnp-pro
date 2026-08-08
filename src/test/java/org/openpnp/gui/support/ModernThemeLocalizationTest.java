package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

class ModernThemeLocalizationTest {
    @Test
    void optionPaneButtonsRemainChineseAfterThemeApplication() {
        ModernTheme.apply();

        assertEquals("确定", UIManager.get("OptionPane.okButtonText"));
        assertEquals("是", UIManager.get("OptionPane.yesButtonText"));
        assertEquals("否", UIManager.get("OptionPane.noButtonText"));
        assertEquals("取消", UIManager.get("OptionPane.cancelButtonText"));
    }
}
