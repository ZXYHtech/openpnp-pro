/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.prefs.Preferences;
import org.openpnp.model.Configuration;

/**
 * Opt-in is scoped to the machine configuration, never shared with another profile.
 */
public final class DynamicSafeMotionSettings {

    private DynamicSafeMotionSettings() {
    }

    static String key(File directory) {
        try {
            String path = directory.getCanonicalPath().toLowerCase(Locale.ROOT);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("xyz.v2.");
            for (byte value : hash) {
                key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return key.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法识别当前机器配置目录", exception);
        }
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(DynamicSafeMotionSettings.class);
    }

    public static boolean isEnabled() {
        File directory = Configuration.get().getConfigurationDirectory();
        return directory != null && preferences().getBoolean(key(directory), false);
    }

    public static void setEnabled(boolean enabled) {
        Preferences preferences = preferences();
        String key = key(Configuration.get().getConfigurationDirectory());
        boolean previous = preferences.getBoolean(key, false);
        preferences.putBoolean(key, enabled);
        try {
            preferences.flush();
        } catch (Exception failure) {
            preferences.putBoolean(key, previous);
            throw new IllegalStateException("无法保存 XY/Z 联动开关", failure);
        }
    }
}
