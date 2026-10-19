package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

class ProgressContrastTest {
    @Test void bothProgressTextColorsRemainReadableInLightDarkAndCompatibleThemes() {
        Object oldPanel = UIManager.get("Panel.background"), oldTrack = UIManager.get("ProgressBar.background");
        Object oldCompatible = UIManager.get("OpenPnPPro.compatibleDark");
        Object oldFill = UIManager.get("ProgressBar.foreground"), oldOn = UIManager.get("ProgressBar.selectionForeground");
        Object oldOff = UIManager.get("ProgressBar.selectionBackground");
        try {
            for (Color background : new Color[] {Color.WHITE, new Color(0x25282C), new Color(0x718A95)}) {
                for (boolean compatible : new boolean[] {false, true}) {
                    UIManager.put("Panel.background", background);
                    UIManager.put("ProgressBar.background", background);
                    UIManager.put("OpenPnPPro.compatibleDark", compatible);
                    ModernTheme.apply();
                    assertTrue(contrast(UIManager.getColor("ProgressBar.foreground"),
                            UIManager.getColor("ProgressBar.selectionForeground")) >= 4.5);
                    assertTrue(contrast(background, UIManager.getColor("ProgressBar.selectionBackground")) >= 4.5);
                }
            }
        }
        finally {
            UIManager.put("Panel.background", oldPanel);
            UIManager.put("ProgressBar.background", oldTrack);
            UIManager.put("OpenPnPPro.compatibleDark", oldCompatible);
            ModernTheme.apply();
            UIManager.put("ProgressBar.foreground", oldFill);
            UIManager.put("ProgressBar.selectionForeground", oldOn);
            UIManager.put("ProgressBar.selectionBackground", oldOff);
        }
    }

    private static double contrast(Color a, Color b) {
        double first = relativeLuminance(a), second = relativeLuminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private static double relativeLuminance(Color color) {
        double[] weights = {.2126, .7152, .0722};
        float[] channels = color.getRGBColorComponents(null);
        double sum = 0;
        for (int index = 0; index < 3; index++) {
            sum += weights[index] * (channels[index] <= .04045 ? channels[index] / 12.92
                    : Math.pow((channels[index] + .055) / 1.055, 2.4));
        }
        return sum;
    }
}
