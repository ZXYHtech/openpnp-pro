package org.openpnp.packageimport;

import java.text.Normalizer;
import java.util.Locale;

public final class PackageNameNormalizer {
    private PackageNameNormalizer() {}

    public static String strict(String raw) {
        String value = baseName(raw);
        return value.replaceAll("[\\s_\\-]+", "").toUpperCase(Locale.ROOT);
    }

    public static String loose(String raw) {
        String value = strict(raw);
        return value.replaceAll("(?i)(HANDSOLDER(?:ING)?|THERMALVIAS?|PAD(?:SIZE)?[0-9.X]+)$", "")
                .replaceAll("(?i)(NOMINAL|STANDARD)$", "");
    }

    public static String baseName(String raw) {
        if (raw == null) { return ""; }
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) { value = value.substring(slash + 1); }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) { value = value.substring(colon + 1); }
        value = value.replaceFirst("(?i)\\.kicad_mod$", "");
        return value.trim();
    }
}
