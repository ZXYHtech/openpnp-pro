package org.openpnp.gui.support;

import java.awt.Color;

/** Keep the theme foreground when legible; otherwise meet numeric-cell contrast on the final fill. */
public final class ReadableTableColors {
    private ReadableTableColors() { }
    private static double channel(int value) {
        double c = value / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }
    private static double luminance(Color c) {
        return .2126 * channel(c.getRed()) + .7152 * channel(c.getGreen()) + .0722 * channel(c.getBlue());
    }
    public static double contrast(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    public static Color foreground(Color background, Color preferred) {
        if (background == null) { background = Color.WHITE; }
        if (preferred != null && contrast(background, preferred) >= 4.5) { return preferred; }
        return contrast(background, Color.BLACK) >= contrast(background, Color.WHITE) ? Color.BLACK : Color.WHITE;
    }
}
