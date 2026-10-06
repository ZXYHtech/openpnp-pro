/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JComboBox;

import org.openpnp.model.Part;

/** Search-as-you-type support shared by every large Part selector. */
public final class PartSearchSupport {
    private PartSearchSupport() {
    }

    public static void install(JComboBox<?> comboBox) {
        comboBox.setMaximumRowCount(18);
        if (comboBox instanceof SearchablePartComboBox) {
            ((SearchablePartComboBox) comboBox).installSearchSupport();
        }
    }

    public static boolean matches(Object item, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String value : searchTerms(item)) {
            if (value.toLowerCase(Locale.ROOT).contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static String displayText(Object item) {
        if (!(item instanceof Part)) {
            return item == null ? "" : item.toString();
        }
        Part part = (Part) item;
        List<String> labels = new ArrayList<>();
        add(labels, part.getDisplayId());
        add(labels, part.getVariant());
        add(labels, part.getId());
        add(labels, part.getName());
        if (part.getPackage() != null) {
            add(labels, part.getPackage().getId());
        }
        return String.join(" | ", labels);
    }

    private static List<String> searchTerms(Object item) {
        List<String> terms = new ArrayList<>();
        if (!(item instanceof Part)) {
            add(terms, item == null ? "" : item.toString());
            return terms;
        }
        Part part = (Part) item;
        add(terms, part.getDisplayId());
        add(terms, part.getVariant());
        add(terms, part.getAliases());
        add(terms, part.getId());
        add(terms, part.getName());
        if (part.getPackage() != null) {
            add(terms, part.getPackage().getId());
            add(terms, part.getPackage().getDescription());
            add(terms, part.getPackage().getStandardName());
            add(terms, part.getPackage().getAliases());
        }
        add(terms, displayText(part));
        return terms;
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty() && !values.contains(value)) {
            values.add(value);
        }
    }
}
