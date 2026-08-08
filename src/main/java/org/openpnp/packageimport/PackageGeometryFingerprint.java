package org.openpnp.packageimport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;
import org.openpnp.model.LengthUnit;

public final class PackageGeometryFingerprint {
    private static final double QUANTUM_MM = 0.001;
    private PackageGeometryFingerprint() {}

    public static String compute(Footprint footprint) {
        if (footprint == null || footprint.getPads().isEmpty()) { return null; }
        double scale = footprint.getUnits() == LengthUnit.Millimeters ? 1.0
                : new org.openpnp.model.Length(1, footprint.getUnits())
                        .convertToUnits(LengthUnit.Millimeters).getValue();
        List<String> pads = new ArrayList<>();
        for (Pad p : footprint.getPads()) {
            pads.add(normalizeName(p.getName()) + ':' + q(p.getX() * scale) + ':'
                    + q(p.getY() * scale) + ':' + q(p.getWidth() * scale) + ':'
                    + q(p.getHeight() * scale) + ':' + q(angle(p.getRotation())) + ':'
                    + q(p.getRoundness()));
        }
        pads.sort(Comparator.naturalOrder());
        String canonical = String.join("|", pads) + "|B:" + q(footprint.getBodyWidth() * scale)
                + ':' + q(footprint.getBodyHeight() * scale);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) { result.append(String.format(Locale.ROOT, "%02x", b)); }
            return result.toString();
        }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
    private static long q(double value) { return Math.round(value / QUANTUM_MM); }
    private static double angle(double value) {
        double a = value % 360.0;
        return a < 0 ? a + 360.0 : a;
    }
}
