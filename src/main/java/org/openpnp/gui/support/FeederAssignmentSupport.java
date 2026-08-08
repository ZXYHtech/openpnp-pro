/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.util.Locale;

import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;

/**
 * Non-UI rules shared by the placement-to-feeder assignment dialog and tests.
 */
public final class FeederAssignmentSupport {
    private FeederAssignmentSupport() {
    }

    public static boolean matches(Feeder feeder, String query) {
        if (feeder == null) {
            return false;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        Part loadedPart = feeder.getPart();
        String searchable = safe(feeder.getName()) + "\n" + safe(feeder.getId()) + "\n"
                + feeder.getClass().getSimpleName() + "\n"
                + (loadedPart == null ? "" : safe(loadedPart.getId()) + "\n"
                        + safe(loadedPart.getName()));
        return searchable.toLowerCase(Locale.ROOT).contains(normalized);
    }

    public static boolean supportsRotationOverride(Feeder feeder) {
        return feeder instanceof ReferenceFeeder;
    }

    public static double getRotation(Feeder feeder) throws Exception {
        if (feeder instanceof ReferenceFeeder) {
            return ((ReferenceFeeder) feeder).getLocation().getRotation();
        }
        return feeder.getPickLocation().getRotation();
    }

    public static void assign(Feeder feeder, Part part, boolean overrideRotation,
            double rotation) throws Exception {
        if (feeder == null) {
            throw new IllegalArgumentException("Please select a feeder.");
        }
        if (part == null) {
            throw new IllegalArgumentException("The placement has no assigned part.");
        }
        if (overrideRotation && !(feeder instanceof ReferenceFeeder)) {
            throw new IllegalArgumentException(
                    "This feeder type does not expose a writable pick rotation.");
        }
        feeder.setPart(part);
        if (overrideRotation) {
            ReferenceFeeder referenceFeeder = (ReferenceFeeder) feeder;
            Location location = referenceFeeder.getLocation();
            referenceFeeder.setLocation(location.derive(null, null, null, rotation));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
