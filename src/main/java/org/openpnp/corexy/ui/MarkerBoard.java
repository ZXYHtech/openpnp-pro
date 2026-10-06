package org.openpnp.corexy.ui;

import java.nio.file.Path;
import java.util.Locale;

import org.openpnp.corexy.precision.PrecisionStore;

public final class MarkerBoard {
    private MarkerBoard() {
    }

    public static void write(Path file, int size, double pitch, double diameter) throws Exception {
        if ((size != 5 && size != 9) || !Double.isFinite(pitch) || pitch < 2 || pitch > 100
                || !Double.isFinite(diameter) || diameter < .1 || diameter > 10 || diameter >= pitch * .5) {
            throw new IllegalArgumentException("Invalid marker board dimensions.");
        }
        double side = pitch * (size - 1) + 24;
        StringBuilder svg = new StringBuilder(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.3fmm\" height=\"%.3fmm\" viewBox=\"0 0 %.3f %.3f\">\n",
                side, side, side, side));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        svg.append("<text x=\"4\" y=\"5\" font-size=\"2.5\">CoreXY · Print at 100% · Verify pitch</text>\n");
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                double x = 12 + column * pitch, y = 12 + row * pitch;
                svg.append(String.format(Locale.ROOT, "<circle cx=\"%.3f\" cy=\"%.3f\" r=\"%.3f\" fill=\"black\"/>\n",
                        x, y, diameter / 2));
                svg.append(String.format(Locale.ROOT, "<text x=\"%.3f\" y=\"%.3f\" font-size=\"1.8\">%d</text>\n",
                        x + diameter / 2 + 1, y - diameter / 2 - 1, row * size + column));
            }
        }
        svg.append(String.format(Locale.ROOT, "<text x=\"4\" y=\"%.3f\" font-size=\"2.5\">Pitch %.3f mm · Marker diameter %.3f mm</text>\n</svg>\n",
                side - 4, pitch, diameter));
        PrecisionStore.atomicWrite(file, svg.toString());
    }
}
