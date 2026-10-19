package org.openpnp.gui.support;

import java.beans.Introspector;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Search metadata only; never instantiate configuration wizards or invoke hardware getters. */
public final class ConfigurationNavigationSearch {
    private static final Map<Class<?>, String> PROPERTIES = new ConcurrentHashMap<>();
    private ConfigurationNavigationSearch() { }

    public static boolean matches(String query, String path, Class<?> type) {
        String text = (path + " " + type.getSimpleName() + " " + PROPERTIES.computeIfAbsent(type, key -> {
            StringBuilder names = new StringBuilder();
            try {
                for (java.beans.PropertyDescriptor property : Introspector.getBeanInfo(key).getPropertyDescriptors()) {
                    names.append(' ').append(property.getName());
                }
            }
            catch (java.beans.IntrospectionException ignored) { /* Title and class remain searchable. */ }
            return names.toString();
        })).toLowerCase(Locale.ROOT);
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[][] aliases = {{"相机", "camera"}, {"吸嘴", "nozzle"}, {"驱动", "driver"},
                {"飞达", "feeder"}, {"回零", "homing"}, {"回差", "backlash"}, {"旋转", "rotation"},
                {"执行器", "actuator"}, {"速度", "speed"}, {"加速度", "acceleration"}};
        for (String token : normalized.split("\\s+")) {
            if (text.contains(token)) { continue; }
            boolean translated = false;
            for (String[] alias : aliases) {
                if (alias[0].equals(token) && text.contains(alias[1])) {
                    translated = true;
                    break;
                }
            }
            if (!translated) { return false; }
        }
        return true;
    }
}
