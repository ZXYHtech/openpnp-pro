package org.openpnp.machine.reference.safety;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.pmw.tinylog.Logger;

/** Bounded, read-only runtime evidence. Does not poll hardware or change a path. */
public final class DynamicMotionDiagnostics {
    private static final Deque<String> rows = new ArrayDeque<>();
    private static final Map<String, Integer> reasons = new LinkedHashMap<>();
    private static long accepted, declined, skipped, retractions;
    private static double totalMs, maxMs;
    private static String lastLogged = "";

    private DynamicMotionDiagnostics() { }

    static synchronized void skipped() { skipped++; }

    static synchronized void record(String tool, String reason, boolean optimized,
            double planningMs, double originalSeconds, double coordinatedSeconds) {
        if (optimized) {
            accepted++;
        }
        else {
            declined++;
        }
        totalMs += planningMs;
        maxMs = Math.max(maxMs, planningMs);
        if (!optimized) {
            String category = reason.replaceAll("“[^”]*”", "…");
            if (reasons.size() < 100 || reasons.containsKey(category)) {
                reasons.merge(category, 1, Integer::sum);
            }
        }
        rows.addLast(String.format(Locale.ROOT, "%d,%s,%s,%.3f,%.6f,%.6f,%s%n",
                System.currentTimeMillis(), quoted(tool), optimized, planningMs,
                originalSeconds, coordinatedSeconds, quoted(reason)));
        while (rows.size() > 1000) {
            rows.removeFirst();
        }
        String state = reason;
        if (!state.equals(lastLogged) || (accepted + declined) % 50 == 0) {
            Logger.info("XY/Z {} · 规划 {} ms · 原路径 {} s / 联动 {} s · {}",
                    tool, Math.round(planningMs * 100) / 100., originalSeconds, coordinatedSeconds, state);
            lastLogged = state;
        }
    }

    static synchronized void recordRetraction(String tool, String reason, double planningMs,
            double originalSeconds, double coordinatedSeconds) {
        record(tool, reason, true, planningMs, originalSeconds, coordinatedSeconds);
        accepted--;
        retractions++;
    }

    public static synchronized String summary() {
        long count = accepted + declined + retractions;
        StringBuilder result = new StringBuilder(String.format(Locale.ROOT,
                "本次启动：已联动 %d 段；已评估回退 %d 段；不适用 %d 段；局部安全退出 %d 次。\n"
                + "平均规划 %.2f ms；最大 %.2f ms。\n\n回退原因：\n",
                accepted, declined, skipped, retractions, count == 0 ? 0 : totalMs / count, maxMs));
        reasons.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> result.append(entry.getValue()).append(" 次：").append(entry.getKey()).append('\n'));
        return result.append("\n纯 Z、原地旋转和特殊工具不参与联动，也不计为失败。"
                + "实时规划预算 40 ms，超时保留原安全 Z；首次模型准备可能超出预算。"
                + "预计节省必须超过本次规划开销和 5 ms 余量。"
                + "计时为软件规划与轴模型估算，不是实测贴片提速。CSV 保留最近 1000 段评估。\n"
                + "模型修改须重新认证；物料须填写封装本体宽/长，以及高度或有效的吸嘴尖高度上界；精度补偿附加路径维持原安全 Z。")
                .toString();
    }

    public static synchronized String csv() {
        return "timestamp_ms,tool,optimized,planning_ms,original_seconds,coordinated_seconds,reason\n"
                + String.join("", rows);
    }

    public static synchronized void clear() {
        rows.clear();
        reasons.clear();
        accepted = declined = skipped = retractions = 0;
        totalMs = maxMs = 0;
        lastLogged = "";
    }

    private static String quoted(String text) {
        if (text == null) {
            text = "";
        }
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
