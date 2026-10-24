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
    private static File keyedDirectory;
    private static String cachedKey;
    private static Configuration activeConfiguration;
    private static File activeDirectory;
    private static boolean enabledCache;

    private DynamicSafeMotionSettings() {
    }

    static synchronized String key(File directory) {
        if (directory.equals(keyedDirectory)) {
            return cachedKey;
        }
        try {
            String path = directory.getCanonicalPath().toLowerCase(Locale.ROOT);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("xyz.v2.");
            for (byte value : hash) {
                key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            cachedKey = key.toString();
            keyedDirectory = directory;
            return cachedKey;
        } catch (Exception exception) {
            throw new IllegalStateException("无法识别当前机器配置目录", exception);
        }
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(DynamicSafeMotionSettings.class);
    }

    public static synchronized boolean isEnabled() {
        Configuration configuration = Configuration.get();
        File directory = Configuration.get().getConfigurationDirectory();
        if (directory == null) {
            return false;
        }
        if (activeConfiguration != configuration || !directory.equals(activeDirectory)) {
            enabledCache = preferences().getBoolean(key(directory), false);
            activeConfiguration = configuration;
            activeDirectory = directory;
        }
        return enabledCache;
    }

    public static synchronized void setEnabled(boolean enabled) {
        Preferences preferences = preferences();
        String key = key(Configuration.get().getConfigurationDirectory());
        boolean previous = preferences.getBoolean(key, false);
        preferences.putBoolean(key, enabled);
        try {
            preferences.flush();
            activeConfiguration = Configuration.get();
            activeDirectory = activeConfiguration.getConfigurationDirectory();
            enabledCache = enabled;
        } catch (Exception failure) {
            preferences.putBoolean(key, previous);
            throw new IllegalStateException("无法保存 XY/Z 联动开关", failure);
        }
    }

    public static boolean isStrictEnabled() {
        File directory = Configuration.get().getConfigurationDirectory();
        return directory != null && preferences().getBoolean(key(directory) + ".strict", false);
    }

    public static void setStrictEnabled(boolean enabled) {
        preferences().putBoolean(key(Configuration.get().getConfigurationDirectory()) + ".strict", enabled);
        try { preferences().flush(); }
        catch (Exception failure) { throw new IllegalStateException("无法保存不回退模式", failure); }
    }
}
