package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;

import javax.swing.JDialog;
import javax.swing.JFrame;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.ModernTheme;

public class ProCompatibleDarkLafTest {
    @Test
    public void suppliesCompleteOsIndependentDarkDefaults() throws Exception {
        UIManager.setLookAndFeel(new ProCompatibleDarkLaf());
        ModernTheme.apply();

        assertTrue(UIManager.getBoolean("OpenPnPPro.compatibleDark"));
        assertTrue(luminance(UIManager.getColor("Panel.background")) < 0.25);
        assertTrue(luminance(UIManager.getColor("Table.background")) < 0.25);
        assertTrue(luminance(UIManager.getColor("TextField.background")) < 0.25);
        assertTrue(luminance(UIManager.getColor("MenuBar.background")) < 0.25);
        assertTrue(JFrame.isDefaultLookAndFeelDecorated());
        assertTrue(JDialog.isDefaultLookAndFeelDecorated());
        assertEquals(new Color(0x718A95), UIManager.getColor("ProgressBar.foreground"));
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen()
                + 0.0722 * color.getBlue()) / 255.0;
    }
}
