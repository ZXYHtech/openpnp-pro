package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.precision.CalibrationController;
import org.openpnp.corexy.precision.PrecisionMetrics;
import org.openpnp.corexy.precision.PrecisionProfile;
import org.openpnp.corexy.precision.PrecisionStore;
import org.openpnp.corexy.precision.StartupVerificationReport;
import org.openpnp.model.Configuration;

/** Evidence-driven results. Historical success cannot mask a failed current attempt. */
public final class CalibrationResultPresentation {
    private CalibrationResultPresentation() { }

    public static void attachActions(JPanel panel, JTabbedPane tabs, JButton verify,
            JButton enable, JButton calibrate, JButton data) {
        panel.putClientProperty("result.actions", new Object[] {tabs, verify, enable, calibrate, data});
    }

    static boolean meetsTarget(PrecisionMetrics metrics, double target, boolean accepted) {
        return accepted && metrics != null && metrics.count > 0
                && Double.isFinite(metrics.p95) && metrics.p95 <= target;
    }

    public static void waiting(JPanel panel) {
        render(panel, "等待测量结果", "完成校准或 Home 校验后显示实测结果", null, null, 0, false, false,
                "先检查并保存五个点位，再开始校准。没有测量数据时不判断精度。", "尚无本次结果");
    }

    public static boolean profile(JPanel panel, PrecisionProfile profile, boolean enabled) {
        if (Boolean.TRUE.equals(panel.getClientProperty("result.transient"))) { return false; }
        boolean good = meetsTarget(profile.corrected, profile.target, true);
        render(panel, good ? "已保存方案达到设定目标" : "已保存方案的精度还不理想",
                enabled ? "补偿已启用 · 已保存" : "补偿当前停用 · 保存方案仍保留",
                profile.corrected, profile.baseline, profile.target, good, !good,
                good ? "需要使用时先完成 Home 校验；更改点位、照明或机械状态后重新检查。" : accuracySteps(),
                "历史校准 · " + time(profile.createdAt) + " · 不替代本次 Home 校验");
        return good;
    }

    public static void latest(JPanel panel, Configuration configuration, PrecisionProfile profile, boolean enabled) throws Exception {
        if (Boolean.TRUE.equals(panel.getClientProperty("result.transient"))) { return; }
        PrecisionStore store = CoreXYIntegration.store(configuration);
        List<Path> directories = store.listVerificationDirectories();
        for (int i = directories.size() - 1; i >= 0; i--) {
            StartupVerificationReport check = store.loadVerification(directories.get(i));
            if (check.profileId.equals(profile.solutionId) && !check.simulation
                    && !("home".equals(check.kind) && check.passed)) {
                if (check.passed && !configuration.getMachine().isHomed()) {
                    render(panel, "等待本次 Home 校验", "方案已保存；当前尚未完成 Home", null, null,
                            profile.target, false, false, "执行 Home 并重新验证，通过后再使用补偿。",
                            "上次完整校验 · " + time(check.completedAt) + " · 不代表当前已就绪");
                    return;
                }
                verification(panel, check, profile, enabled);
                return;
            }
        }
    }

    public static void verification(JPanel panel, StartupVerificationReport check, PrecisionProfile profile, boolean enabled) {
        boolean good = meetsTarget(check.metrics, check.target, check.passed);
        String title = check.cancelled ? "本次校验已取消" : !check.passed ? "这次校验没有通过"
                : good ? "本次校验达到设定目标" : "校验稳定，但精度还不够好";
        String next = check.cancelled ? "确认机器状态后，可点击“Home 并重新验证”。取消不代表验证通过。"
                : good ? "在相同工作条件下使用；更改点位、速度或机械状态后重新验证。" : accuracySteps();
        render(panel, title, (enabled ? "补偿已启用" : "补偿未启用") + " · "
                + (check.passed ? "稳定性检查通过" : "验证未通过，请先排查"),
                "home".equals(check.kind) ? null : check.metrics, null, check.target, good, !good && !check.cancelled,
                escape(check.outcome) + "<br>" + next,
                ("home".equals(check.kind) ? "最近 Home 检查 · " : "最近五点校验 · ")
                        + time(check.completedAt) + " · 历史方案 P95 "
                        + String.format(Locale.ROOT, "%.4f mm", profile.corrected.p95));
    }

    public static void report(JPanel panel, CalibrationController.Report report) {
        panel.putClientProperty("result.transient", true);
        double target = report.request == null ? .05 : report.request.target;
        boolean good = !report.simulation && meetsTarget(report.correctedFinal, target, report.targetReached);
        render(panel, report.simulation ? "离线演练结果" : good ? "本次校准达到设定目标" : "这次补偿效果不理想",
                report.simulation ? "模拟数据 · 不代表实机精度" : report.enabled ? "新方案已启用" : "本次候选未启用 · 请核对下方运行状态",
                report.correctedFinal, report.baselineFinal, target, good, !good && !report.simulation,
                escape(report.outcome) + "<br>" + (good ? "下次启动先完成 Home 校验。" : accuracySteps()),
                "本次校准 · " + time(report.createdAt));
    }

    public static void failure(JPanel panel, Throwable failure) {
        panel.putClientProperty("result.transient", true);
        boolean cancelled = failure instanceof java.util.concurrent.CancellationException;
        render(panel, cancelled ? "操作已取消" : "这次没有完成，先别急着重试",
                "本次操作未通过 · 当前补偿状态见下方状态栏", null, null, 0, false, !cancelled,
                escape(failure.getMessage()) + "<br>" + (cancelled
                        ? "确认机器已停止，再选择 Home 复验或重新校准。" : failureSteps(failure.getMessage())),
                "当前操作 · " + time(System.currentTimeMillis()) + " · 不展示旧成绩冒充本次结果");
    }

    static String failureSteps(String message) {
        String text = message == null ? "" : message;
        if (text.contains("限位") || text.contains("路径")) {
            return "1. 返回点位页检查坐标和可用行程。<br>2. 调整点位或采样行程；不要关闭软限位。<br>3. 确认路径可达后重新校准。";
        }
        if (text.contains("marker") || text.contains("噪声") || text.contains("圆形")) {
            return "1. 查看 marker 画面，检查圆形完整、直径、光照和对焦。<br>2. 位置未变则 Home 并重新验证；点位移动过则重新记录并校准。";
        }
        if (text.contains("Home") || text.contains("基准") || text.contains("收敛")) {
            return "1. 检查 Home 标记、固定状态和圆心识别。<br>2. Home 并重新验证；若重复不收敛，检查皮带、同步轮和导轨，再重新校准。";
        }
        return "1. 查看原始数据和错误原因，先恢复连接或机器状态。<br>2. 状态恢复后执行 Home 复验；持续失败时检查机械与视觉，再重新校准。";
    }

    private static String accuracySteps() {
        return "1. 先检查 marker、照明、对焦和固定状态。<br>2. 条件未变：Home 并重新验证，判断是否为偶发漂移。"
                + "<br>3. 仍不理想：查看最差点与方向；检查皮带/同步轮/导轨，重新校准后再验收。";
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    private static String escape(String text) {
        return text == null ? "未提供详细原因" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font("Microsoft YaHei", Font.BOLD, size));
        label.setForeground(color);
        return label;
    }

    private static void render(JPanel panel, String title, String status, PrecisionMetrics metrics,
            PrecisionMetrics baseline, double target, boolean good, boolean unhappy, String next, String source) {
        panel.putClientProperty("result.success", good);
        panel.putClientProperty("result.unhappy", unhappy);
        panel.removeAll();
        JPanel heading = new JPanel(new BorderLayout(8, 10));
        heading.setOpaque(false);
        JLabel titleLabel = label(title, 29, Color.WHITE);
        if (unhappy) { titleLabel.setIcon(new UnhappyFace()); }
        heading.add(titleLabel, BorderLayout.CENTER);
        heading.add(label(status, 16, good ? new Color(115, 255, 195) : new Color(255, 204, 130)), BorderLayout.SOUTH);
        panel.add(heading, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(8, 12));
        body.setOpaque(false);
        if (metrics != null) {
            JPanel cards = new JPanel(new GridLayout(1, 3, 14, 0));
            cards.setOpaque(false);
            String[] names = {"RMS", "P95", "最大实测误差"};
            double[] values = {metrics.rms, metrics.p95, metrics.maximum};
            for (int i = 0; i < 3; i++) {
                JPanel card = new JPanel(new GridLayout(2, 1));
                card.setBackground(new Color(25, 43, 68));
                card.setBorder(BorderFactory.createEmptyBorder(12, 6, 12, 6));
                card.add(label(String.format(Locale.ROOT, "%.1f μm", values[i] * 1000), 30, new Color(255, 221, 108)));
                card.add(label(names[i], 16, Color.WHITE));
                cards.add(card);
            }
            body.add(cards, BorderLayout.NORTH);
        }
        String measured = metrics == null ? "本次没有完整精度结果" : String.format(Locale.ROOT,
                "%d 次观测 · P95 目标 %.3f mm：%s · 最大值%s目标", metrics.count, target,
                metrics.p95 <= target ? "已达到" : "未达到", metrics.maximum <= target ? "不超过" : "超过");
        if (baseline != null && metrics != null && baseline.rms > 0) {
            measured += String.format(Locale.ROOT, " · RMS 变化 %+.1f%%", (metrics.rms / baseline.rms - 1) * 100);
        }
        JLabel detail = label("<html><center>" + escape(source) + "<br>" + measured
                + "<br><br><b>下一步</b><br>" + next + "</center></html>", 14, Color.WHITE);
        body.add(detail, BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);
        Object[] actions = (Object[]) panel.getClientProperty("result.actions");
        if (actions != null) {
            JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 8, 0));
            buttons.setOpaque(false);
            JButton points = new JButton("返回点位检查");
            points.addActionListener(event -> ((JTabbedPane) actions[0]).setSelectedIndex(0));
            buttons.add(points);
            JButton retry = ((JButton) actions[1]).isEnabled() ? (JButton) actions[1] : (JButton) actions[2];
            addAction(buttons, "Home 并重新验证", retry);
            addAction(buttons, "重新校准", (JButton) actions[3]);
            addAction(buttons, "查看原始数据", (JButton) actions[4]);
            panel.add(buttons, BorderLayout.SOUTH);
        }
        panel.revalidate();
        panel.repaint();
    }

    private static void addAction(JPanel panel, String text, JButton source) {
        JButton button = new JButton(text);
        button.setEnabled(source.isEnabled());
        button.addActionListener(event -> { if (source.isEnabled()) { source.doClick(); } });
        panel.add(button);
    }

    private static final class UnhappyFace implements Icon {
        @Override public int getIconWidth() { return 72; }
        @Override public int getIconHeight() { return 66; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(240, 167, 78));
            g.fillOval(5, 4, 60, 56);
            g.setColor(new Color(67, 31, 26));
            g.setStroke(new java.awt.BasicStroke(4));
            g.drawLine(17, 18, 29, 23);
            g.drawLine(42, 23, 54, 15);
            g.fillOval(21, 27, 5, 7);
            g.fillOval(45, 25, 6, 9);
            g.drawArc(21, 41, 30, 20, 15, 150);
            g.drawLine(32, 47, 39, 42);
            g.dispose();
        }
    }
}
