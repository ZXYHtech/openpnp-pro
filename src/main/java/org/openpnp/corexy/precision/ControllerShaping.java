package org.openpnp.corexy.precision;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses firmware's accelerometer-based resonance calibration, never a host endpoint-offset imitation. */
public final class ControllerShaping {
    public interface Transport {
        String query(String command, long timeout) throws Exception;
    }

    public static final class Session {
        public final String previous, calibrated;
        Session(String previous, String calibrated) {
            this.previous = previous;
            this.calibrated = calibrated;
        }
    }

    public Session calibrate(Transport transport) throws Exception {
        String help = transport.query("HELP", 10000);
        if (!help.contains("SHAPER_CALIBRATE") || !help.contains("SET_INPUT_SHAPER")) {
            throw new UnsupportedOperationException("控制器没有提供带加速度计的 SHAPER_CALIBRATE。");
        }
        String previous = parseSettings(transport.query("SET_INPUT_SHAPER", 10000));
        if (previous == null) {
            throw new UnsupportedOperationException("无法读取当前输入整形参数，无法保证失败回滚。");
        }
        try {
            transport.query("SHAPER_CALIBRATE", 180000);
            String calibrated = parseSettings(transport.query("SET_INPUT_SHAPER", 10000));
            if (calibrated == null) {
                throw new IllegalStateException("控制器没有返回有效整形参数。");
            }
            return new Session(previous, calibrated);
        }
        catch (Exception failure) {
            try {
                transport.query(previous, 10000);
            }
            catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    public static String parseSettings(String response) {
        StringBuilder command = new StringBuilder("SET_INPUT_SHAPER");
        for (String axis : new String[] {"x", "y"}) {
            String type = parameter(response, "shaper_type_" + axis, "[a-zA-Z0-9_]+");
            String frequency = parameter(response, "shaper_freq_" + axis, "[0-9]+(?:\\.[0-9]+)?");
            String damping = parameter(response, "damping_ratio_" + axis, "[0-9]+(?:\\.[0-9]+)?");
            if (type == null || frequency == null || damping == null
                    || !java.util.Arrays.asList("zv", "mzv", "zvd", "ei", "2hump_ei", "3hump_ei", "none")
                        .contains(type.toLowerCase(Locale.ROOT))) {
                return null;
            }
            double f = Double.parseDouble(frequency), d = Double.parseDouble(damping);
            if (f < 0 || f > 500 || d < 0 || d >= 1) {
                return null;
            }
            command.append(" SHAPER_TYPE_").append(axis.toUpperCase(Locale.ROOT)).append('=').append(type);
            command.append(" SHAPER_FREQ_").append(axis.toUpperCase(Locale.ROOT)).append('=').append(frequency);
            command.append(" DAMPING_RATIO_").append(axis.toUpperCase(Locale.ROOT)).append('=').append(damping);
        }
        return command.toString();
    }

    public static void validateCommand(String command) {
        if (command == null || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0
                || !command.startsWith("SET_INPUT_SHAPER ") || parseSettings(command) == null) {
            throw new IllegalArgumentException("Invalid stored input shaper command.");
        }
        String normalized = parseSettings(command);
        if (!normalized.equals(command)) {
            throw new IllegalArgumentException("Unexpected input shaper arguments.");
        }
    }

    private static String parameter(String response, String key, String valuePattern) {
        Pattern pattern = Pattern.compile("(?i)\\b" + key + "\\s*[:=]\\s*(" + valuePattern + ")");
        Matcher matcher = pattern.matcher(response == null ? "" : response);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }
}
