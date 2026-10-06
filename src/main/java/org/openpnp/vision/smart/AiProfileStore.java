/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Stores profiles in the current Windows user account. Secrets use a separate
 * Preferences node and are never included in exported vision recipes.
 */
public final class AiProfileStore {
    private static final Preferences ROOT =
            Preferences.userNodeForPackage(AiProfileStore.class).node("aiProfiles-v1");
    private static final Preferences SECRETS = ROOT.node("secrets");

    public List<AiProviderProfile> loadProfiles() {
        List<AiProviderProfile> result = new ArrayList<>();
        try {
            for (String child : ROOT.childrenNames()) {
                if ("secrets".equals(child)) {
                    continue;
                }
                result.add(read(ROOT.node(child), child));
            }
        }
        catch (BackingStoreException ignored) {
            // A locked preferences backend should not prevent local vision work.
        }
        if (result.isEmpty()) {
            AiProviderProfile profile = new AiProviderProfile();
            save(profile, "");
            result.add(profile);
        }
        return result;
    }

    public void save(AiProviderProfile profile, String apiKey) {
        if (profile.getId() == null || profile.getId().isBlank()) {
            profile.setId(UUID.randomUUID().toString());
        }
        Preferences node = ROOT.node(profile.getId());
        node.put("name", profile.getName());
        node.put("preset", profile.getPreset().name());
        node.put("baseUrl", profile.getBaseUrl());
        node.put("model", profile.getModel());
        node.putInt("timeoutSeconds", profile.getTimeoutSeconds());
        node.put("imageDetail", profile.getImageDetail());
        if (apiKey != null && !apiKey.isBlank() && !isMasked(apiKey)) {
            SECRETS.put(profile.getId(), apiKey.trim());
        }
    }

    public String loadApiKey(AiProviderProfile profile) {
        return profile == null ? "" : SECRETS.get(profile.getId(), "");
    }

    public void clearApiKey(AiProviderProfile profile) {
        if (profile != null) {
            SECRETS.remove(profile.getId());
        }
    }

    public void delete(AiProviderProfile profile) {
        if (profile == null) {
            return;
        }
        try {
            ROOT.node(profile.getId()).removeNode();
        }
        catch (BackingStoreException ignored) {
        }
        SECRETS.remove(profile.getId());
    }

    public static String maskedKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.length() <= 8) {
            return "••••••••";
        }
        return trimmed.substring(0, 3) + "••••••••" + trimmed.substring(trimmed.length() - 4);
    }

    public static boolean isMasked(String value) {
        return value != null && value.indexOf('•') >= 0;
    }

    private static AiProviderProfile read(Preferences node, String id) {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setId(id);
        profile.setName(node.get("name", "AI 视觉账户"));
        try {
            profile.setPreset(AiProviderPreset.valueOf(node.get("preset", "SILICON_FLOW")));
        }
        catch (IllegalArgumentException ignored) {
            profile.setPreset(AiProviderPreset.CUSTOM);
        }
        profile.setBaseUrl(node.get("baseUrl", profile.getPreset().getDefaultBaseUrl()));
        profile.setModel(node.get("model", ""));
        profile.setTimeoutSeconds(node.getInt("timeoutSeconds", 60));
        profile.setImageDetail(node.get("imageDetail", "high"));
        return profile;
    }
}
