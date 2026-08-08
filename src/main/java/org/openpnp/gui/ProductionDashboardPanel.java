/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.openpnp.gui.components.AutoSelectTextTable;
import javax.swing.table.AbstractTableModel;

import org.openpnp.gui.support.JobTelemetryManager;
import org.openpnp.gui.support.JobTelemetryManager.BottleneckInsight;
import org.openpnp.gui.support.JobTelemetryManager.ErrorRecord;
import org.openpnp.gui.support.JobTelemetryManager.JobRunRecord;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.MessageBoxes;

/** Full-page production history, efficiency, quality and error-analysis dashboard. */
@SuppressWarnings("serial")
public class ProductionDashboardPanel extends JPanel {
    private static final String AGGREGATE_DETAILS_KEY = "aggregate";

    private final JobPanel jobPanel;
    private final JobTelemetryManager telemetry;
    private final JPanel metricGrid = new JPanel(new GridLayout(0, 5, 10, 10));
    private final HistoryTableModel historyModel = new HistoryTableModel();
    private final JTable historyTable = new AutoSelectTextTable(historyModel);
    private final JTextPane details = new JTextPane();
    private final JScrollPane detailsScroll = new JScrollPane(details);
    private final JTextField filter = new JTextField(18);
    private final JLabel currentSummary = new JLabel();
    private final TrendPanel trendPanel = new TrendPanel();
    private final Timer refreshTimer;
    private boolean refreshingHistory;
    private String renderedDetailsKey;
    private String renderedDetailsHtml;

    public ProductionDashboardPanel(JobPanel jobPanel) {
        this.jobPanel = jobPanel;
        telemetry = jobPanel.getTelemetryManager();
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        setBackground(ModernTheme.surface(false));
        add(createHeader(), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);
        refreshTimer = new Timer(1500, event -> refresh());
        refreshTimer.start();
        refresh();
    }

    private Component createHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("生产看板");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("任务历史 · 产能效率 · 质量趋势 · 异常原因与改善建议");
        subtitle.setForeground(ModernTheme.mutedForeground());
        titles.add(title);
        titles.add(Box.createVerticalStrut(3));
        titles.add(subtitle);
        header.add(titles, BorderLayout.WEST);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 2));
        actions.setOpaque(false);
        JButton load = new JButton("加载所选任务");
        load.addActionListener(event -> loadSelectedJob());
        JButton folder = new JButton("打开任务目录");
        folder.addActionListener(event -> openSelectedJobFolder());
        JButton refresh = new JButton("刷新数据");
        refresh.addActionListener(event -> refresh());
        actions.add(load);
        actions.add(folder);
        actions.add(refresh);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private Component createBody() {
        JPanel page = new JPanel();
        page.setOpaque(false);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));

        currentSummary.setOpaque(true);
        currentSummary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x34506F)),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        currentSummary.setBackground(new Color(0x182536));
        currentSummary.setForeground(new Color(0xE7EEF7));
        currentSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        currentSummary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        page.add(currentSummary);
        page.add(Box.createVerticalStrut(10));

        metricGrid.setOpaque(false);
        metricGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(metricGrid);
        page.add(Box.createVerticalStrut(12));

        trendPanel.setPreferredSize(new Dimension(900, 145));
        trendPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 145));
        trendPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(trendPanel);
        page.add(Box.createVerticalStrut(12));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createHistoryPanel(), createDetailsPanel());
        split.setResizeWeight(0.64);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setPreferredSize(new Dimension(1000, 360));
        split.setMaximumSize(new Dimension(Integer.MAX_VALUE, 520));
        split.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(split);

        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private Component createHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createTitledBorder("最近任务"));
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.add(new JLabel("筛选"), BorderLayout.WEST);
        filter.setToolTipText("按任务名称、路径或状态筛选历史任务");
        filter.addActionListener(event -> refreshHistory());
        search.add(filter, BorderLayout.CENTER);
        panel.add(search, BorderLayout.NORTH);

        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.setRowHeight(27);
        historyTable.setAutoCreateRowSorter(true);
        historyTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !refreshingHistory) {
                updateDetails(false);
            }
        });
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    loadSelectedJob();
                }
            }
        });
        panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        return panel;
    }

    private Component createDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("异常统计与改善建议"));
        details.setContentType("text/html");
        details.setEditable(false);
        details.putClientProperty("JEditorPane.honorDisplayProperties", Boolean.TRUE);
        panel.add(detailsScroll, BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        refreshMetrics();
        refreshHistory();
        trendPanel.setRecords(telemetry.getHistory());
        updateCurrentSummary();
    }

    private void refreshMetrics() {
        Map<String, Double> summary = telemetry.getSummary();
        JobRunRecord current = telemetry.getCurrent();
        metricGrid.removeAll();
        addMetric("◉", "当前进度", current == null ? "—"
                : format(current.getProgressPercent()) + "%", "当前任务已完成器件比例");
        addMetric("◷", "本次耗时", current == null ? "00:00:00"
                : JobTelemetryManager.formatDuration(current.elapsedMillis), "当前任务从启动至今的总耗时");
        addMetric("⌛", "预计完成", current == null ? "—"
                : JobTelemetryManager.formatDate(current.getEstimatedCompletionTime()),
                "按当前平均速度估算的完成时间");
        addMetric("Σ", "剩余器件", current == null ? "0"
                : String.valueOf(current.remainingPlacements), "当前任务尚未贴装的器件数量");
        addMetric("▣", "当前飞达覆盖", current == null ? "—"
                : format(current.feederCoveragePercent) + "%", "任务物料中已配置启用飞达的比例");
        addMetric("◫", "预计总耗时", current == null ? "—"
                : JobTelemetryManager.formatDuration(current.getEstimatedDurationMillis()),
                "结合相同任务历史与本次实时节拍预测的总耗时");
        addMetric("◎", "预测准确度", current == null ? "—"
                : format(current.predictionAccuracyPercent) + "%",
                current == null ? "尚无预测" : "当前预测稳定度；历史样本 "
                        + current.predictionSampleCount + " 次，置信度 "
                        + format(current.predictionConfidencePercent) + "%");
        addMetric("Δ", "预计偏差", current == null ? "—"
                : JobTelemetryManager.formatSignedDuration(current.predictionDeviationMillis),
                "相对任务启动时预计值的变化；正数表示预计变慢");

        addMetric("▤", "历史任务", integer(summary, "runs"), "已记录的任务运行次数");
        addMetric("✓", "完成率", format(summary.get("completionRate")) + "%", "正常完成任务占比");
        addMetric("▦", "完成单板", integer(summary, "boards"), "历史任务累计完成的单板数量");
        addMetric("◆", "累计器件", integer(summary, "placements"), "历史任务累计贴装器件数量");
        addMetric("↗", "平均速度", format(summary.get("averageSpeed")) + " CPH", "按有效生产时间计算的平均小时产能");
        addMetric("⚡", "最佳速度", format(summary.get("bestSpeed")) + " CPH", "单次任务达到的最高平均速度");
        addMetric("◴", "平均耗时", JobTelemetryManager.formatDuration(
                Math.round(summary.get("averageDuration"))), "每次任务的平均总耗时");
        addMetric("!", "累计异常", integer(summary, "errors"), "任务运行期间捕获的异常总数");
        addMetric("‰", "千件异常", format(summary.get("errorsPerThousand")), "每一千个已贴器件对应的异常次数");
        addMetric("◎", "一次通过率", format(summary.get("firstPassYield")) + "%", "贴装数量相对贴装与异常总和的估算一次通过率");
        addMetric("7D", "近7天任务", integer(summary, "weekRuns"), "最近七天运行的任务数量");
        addMetric("7D", "近7天产量", integer(summary, "weekPlacements"), "最近七天累计贴装器件数量");
        addMetric("⌁", "估算异常损失", JobTelemetryManager.formatDuration(
                Math.round(summary.get("estimatedDowntime"))), "按每次异常平均影响两分钟估算的损失时间");
        metricGrid.revalidate();
        metricGrid.repaint();
    }

    private void addMetric(String icon, String title, String value, String tooltip) {
        JPanel card = new JPanel(new BorderLayout(8, 3));
        card.setToolTipText(tooltip);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernTheme.border()),
                BorderFactory.createEmptyBorder(10, 11, 10, 11)));
        card.setBackground(ModernTheme.surface(true));
        JLabel symbol = new JLabel(icon, SwingConstants.CENTER);
        symbol.setFont(symbol.getFont().deriveFont(Font.BOLD, 20f));
        symbol.setForeground(new Color(0x4C92E8));
        symbol.setPreferredSize(new Dimension(34, 34));
        symbol.setToolTipText(tooltip);
        card.add(symbol, BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel caption = new JLabel(title);
        caption.setForeground(ModernTheme.mutedForeground());
        caption.setFont(caption.getFont().deriveFont(11f));
        JLabel number = new JLabel(value);
        number.setFont(number.getFont().deriveFont(Font.BOLD, 15f));
        caption.setToolTipText(tooltip);
        number.setToolTipText(tooltip);
        text.add(caption);
        text.add(number);
        card.add(text, BorderLayout.CENTER);
        metricGrid.add(card);
    }

    private void updateCurrentSummary() {
        JobRunRecord current = telemetry.getCurrent();
        if (current == null) {
            currentSummary.setText("<html><b>当前没有运行记录</b>　启动贴片任务后将实时显示耗时、预计完成时间与产能。</html>");
            return;
        }
        currentSummary.setText("<html><b>" + escape(current.jobName) + "</b>　"
                + current.state + "　单板 " + current.completedBoards + "/" + current.boardCount
                + "　器件 " + current.placedPlacements + "/" + current.totalPlacements
                + "　物料种类 " + current.uniqueParts + "　速度 "
                + format(current.averageCph) + " CPH　暂停 "
                + JobTelemetryManager.formatDuration(current.pausedMillis) + "　预计 "
                + JobTelemetryManager.formatDuration(current.getEstimatedDurationMillis())
                + "　准确度 " + format(current.predictionAccuracyPercent) + "%　异常 "
                + current.getErrorCount() + "</html>");
    }

    private void refreshHistory() {
        String previousKey = detailsKey(selectedRecord());
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        List<JobRunRecord> available = mergeRecentJobs(telemetry.getHistory(),
                jobPanel.getRecentJobs());
        List<JobRunRecord> records = new ArrayList<>();
        for (JobRunRecord record : available) {
            String searchable = String.valueOf(record.jobName) + " "
                    + String.valueOf(record.jobPath) + " " + String.valueOf(record.state);
            if (query.isEmpty() || searchable.toLowerCase(Locale.ROOT).contains(query)) {
                records.add(record);
            }
        }
        refreshingHistory = true;
        try {
            historyModel.setRecords(records);
            int selectedModelRow = historyModel.findRecord(previousKey);
            if (selectedModelRow >= 0) {
                int selectedViewRow = historyTable.convertRowIndexToView(selectedModelRow);
                if (selectedViewRow >= 0) {
                    historyTable.setRowSelectionInterval(selectedViewRow, selectedViewRow);
                }
            }
            else if (historyModel.getRowCount() > 0) {
                historyTable.setRowSelectionInterval(0, 0);
            }
        }
        finally {
            refreshingHistory = false;
        }
        updateDetails(previousKey.equals(detailsKey(selectedRecord())));
    }

    static List<JobRunRecord> mergeRecentJobs(List<JobRunRecord> history,
            List<File> recentJobs) {
        List<JobRunRecord> available = new ArrayList<>(history);
        Set<String> knownPaths = new HashSet<>();
        for (JobRunRecord record : available) {
            if (record.jobPath != null) {
                knownPaths.add(normalizedPath(new File(record.jobPath)));
            }
        }
        // File > Recent is authoritative for jobs opened but not run yet.
        for (File file : recentJobs) {
            if (!knownPaths.add(normalizedPath(file))) {
                continue;
            }
            JobRunRecord recent = new JobRunRecord();
            recent.id = "recent:" + normalizedPath(file);
            recent.jobName = file.getName();
            recent.jobPath = file.getAbsolutePath();
            recent.startTime = file.lastModified();
            recent.state = "\u6700\u8fd1\u6253\u5f00";
            available.add(recent);
        }
        available.sort(Comparator.comparingLong((JobRunRecord record) -> record.startTime)
                .reversed());
        return JobTelemetryManager.aggregateConsecutiveRuns(available);
    }

    private static String normalizedPath(File file) {
        try {
            return file.getCanonicalPath().toLowerCase(Locale.ROOT);
        }
        catch (Exception e) {
            return file.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }
    private void updateDetails(boolean preserveView) {
        JobRunRecord record = selectedRecord();
        if (record == null) {
            showAggregateErrors(preserveView);
            return;
        }
        StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif'>");
        html.append("<h3>").append(escape(record.jobName));
        if (record.getRunCount() > 1) {
            html.append(" <span style='color:#4C92E8'>· 连续 ")
                    .append(record.getRunCount()).append(" 次合并</span>");
        }
        html.append("</h3>");
        html.append("<p><b>状态：</b>").append(escape(record.state))
                .append("　<b>累计耗时：</b>")
                .append(JobTelemetryManager.formatDuration(record.elapsedMillis))
                .append("　<b>有效生产：</b>")
                .append(JobTelemetryManager.formatDuration(record.activeMillis))
                .append("　<b>完成单板：</b>").append(record.completedBoards)
                .append("　<b>贴装器件：</b>").append(record.placedPlacements)
                .append("</p>");
        html.append("<p><b>预计耗时：</b>")
                .append(JobTelemetryManager.formatDuration(record.getEstimatedDurationMillis()))
                .append("　<b>预测准确度：</b>")
                .append(format(record.predictionAccuracyPercent)).append("%")
                .append("　<b>样本置信度：</b>")
                .append(format(record.predictionConfidencePercent)).append("%")
                .append("　<b>偏离启动预测：</b>")
                .append(JobTelemetryManager.formatSignedDuration(record.predictionDeviationMillis))
                .append("</p>");
        if (record.jobPath != null) {
            html.append("<p style='color:#65758A'><b>任务文件：</b>")
                    .append(escape(record.jobPath)).append("</p>");
        }

        if (record.sourceRuns != null && !record.sourceRuns.isEmpty()) {
            html.append("<h4>每次运行明细</h4><ol>");
            for (JobRunRecord run : record.sourceRuns) {
                html.append("<li><b>")
                        .append(JobTelemetryManager.formatDate(run.startTime)).append(" · ")
                        .append(escape(run.state)).append("</b><br>")
                        .append("耗时 ").append(JobTelemetryManager.formatDuration(run.elapsedMillis))
                        .append("，单板 ").append(run.completedBoards).append("/")
                        .append(run.boardCount).append("，器件 ").append(run.placedPlacements)
                        .append("/").append(run.totalPlacements).append("，异常 ")
                        .append(run.getErrorCount()).append("，预计 ")
                        .append(JobTelemetryManager.formatDuration(run.getEstimatedDurationMillis()))
                        .append("，准确度 ").append(format(run.predictionAccuracyPercent))
                        .append("%</li>");
            }
            html.append("</ol>");
        }

        html.append("<h4>时间偏差、瓶颈与有效建议</h4>");
        for (BottleneckInsight insight : JobTelemetryManager.analyzeBottlenecks(record)) {
            html.append("<div style='margin-bottom:11px;padding:8px;border-left:3px solid #4C92E8'>")
                    .append("<b>").append(escape(insight.title)).append("</b>");
            if (insight.estimatedDelayMillis > 0) {
                html.append("　<span style='color:#C56A26'>估算影响 ")
                        .append(JobTelemetryManager.formatDuration(insight.estimatedDelayMillis))
                        .append("</span>");
            }
            html.append("<br>").append(escape(insight.evidence))
                    .append("<br><span style='color:#2878D0'>建议：")
                    .append(escape(insight.suggestion)).append("</span></div>");
        }

        if (record.errors == null || record.errors.isEmpty()) {
            html.append("<div style='color:#16845B'><b>所选运行记录未捕获到异常。</b></div>");
        }
        else {
            Map<String, Integer> categories = new java.util.LinkedHashMap<>();
            for (ErrorRecord error : record.errors) {
                String category = error.category == null ? "其他异常" : error.category;
                categories.put(category, categories.getOrDefault(category, 0) + 1);
            }
            html.append("<h4>合并异常统计</h4><ul>");
            for (Map.Entry<String, Integer> entry : categories.entrySet()) {
                html.append("<li><b>").append(escape(entry.getKey())).append("：</b>")
                        .append(entry.getValue()).append(" 次</li>");
            }
            html.append("</ul><h4>全部异常、所属运行与改善建议</h4>");
            for (ErrorRecord error : record.errors) {
                html.append("<div style='margin-bottom:12px;padding:7px;border-left:3px solid #D16B78'>")
                        .append("<b>").append(JobTelemetryManager.formatDate(error.time))
                        .append(" · ").append(escape(error.category)).append("</b><br>")
                        .append("所属运行：").append(JobTelemetryManager.formatDate(error.runStartTime))
                        .append(" · ").append(escape(error.jobName));
                if (error.source != null) {
                    html.append(" · ").append(escape(error.source));
                }
                html.append(" · 当时已贴 ").append(error.placedPlacementsAtError)
                        .append(" 件 · 有效运行 ")
                        .append(JobTelemetryManager.formatDuration(error.activeMillisAtError))
                        .append("<br>").append(escape(error.title)).append("：")
                        .append(escape(error.message))
                        .append("<br><span style='color:#2878D0'>建议：")
                        .append(escape(error.suggestion)).append("</span></div>");
            }
        }
        html.append("</body></html>");
        setDetailsHtml(detailsKey(record), html.toString(), preserveView);
    }
    private void showAggregateErrors(boolean preserveView) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(
                telemetry.getErrorCategoryCounts().entrySet());
        entries.sort(Collections.reverseOrder(Comparator.comparingInt(Map.Entry::getValue)));
        StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif'>");
        html.append("<h3>全部任务异常分布</h3>");
        if (entries.isEmpty()) {
            html.append("<p>尚无异常统计。</p>");
        }
        else {
            html.append("<ol>");
            for (Map.Entry<String, Integer> entry : entries) {
                html.append("<li>").append(escape(entry.getKey())).append("：")
                        .append(entry.getValue()).append(" 次</li>");
            }
            html.append("</ol>");
        }
        html.append("</body></html>");
        setDetailsHtml(AGGREGATE_DETAILS_KEY, html.toString(), preserveView);
    }

    private void setDetailsHtml(String key, String html, boolean preserveView) {
        if (!detailsChanged(key, html, renderedDetailsKey, renderedDetailsHtml)) {
            return;
        }
        int scrollValue = detailsScroll.getVerticalScrollBar().getValue();
        int caretPosition = details.getCaretPosition();
        boolean sameRecord = key.equals(renderedDetailsKey);
        renderedDetailsKey = key;
        renderedDetailsHtml = html;
        details.setText(html);
        if (!preserveView || !sameRecord) {
            details.setCaretPosition(0);
            detailsScroll.getVerticalScrollBar().setValue(0);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            details.setCaretPosition(Math.min(caretPosition, details.getDocument().getLength()));
            detailsScroll.getVerticalScrollBar().setValue(clampScrollValue(scrollValue,
                    detailsScroll.getVerticalScrollBar().getMaximum(),
                    detailsScroll.getVerticalScrollBar().getVisibleAmount()));
        });
    }

    static boolean detailsChanged(String key, String html, String previousKey,
            String previousHtml) {
        return !key.equals(previousKey) || !html.equals(previousHtml);
    }

    static int clampScrollValue(int requested, int maximum, int visibleAmount) {
        return Math.max(0, Math.min(requested, Math.max(0, maximum - visibleAmount)));
    }

    static String detailsKey(JobRunRecord record) {
        if (record == null) {
            return AGGREGATE_DETAILS_KEY;
        }
        if (record.id != null && !record.id.trim().isEmpty()) {
            return "id:" + record.id;
        }
        return "run:" + String.valueOf(record.jobPath) + ":" + record.startTime;
    }

    private JobRunRecord selectedRecord() {
        int row = historyTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return historyModel.getRecord(historyTable.convertRowIndexToModel(row));
    }

    private void loadSelectedJob() {
        JobRunRecord record = selectedRecord();
        if (record == null || record.jobPath == null) {
            JOptionPane.showMessageDialog(this, "所选记录没有可加载的任务文件。", "加载任务",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File file = new File(record.jobPath);
        if (!file.isFile()) {
            MessageBoxes.errorBox(this, "加载任务", "任务文件不存在：" + record.jobPath);
            return;
        }
        try {
            jobPanel.loadJobFromDashboard(file);
            MainFrame.get().getTabs().setSelectedComponent(jobPanel);
        }
        catch (Exception e) {
            MessageBoxes.errorBox(this, "加载任务失败", e);
        }
    }

    private void openSelectedJobFolder() {
        JobRunRecord record = selectedRecord();
        if (record == null || record.jobPath == null) {
            JOptionPane.showMessageDialog(this, "\u8bf7\u5148\u9009\u62e9\u4e00\u4e2a\u4efb\u52a1\u3002",
                    "\u6253\u5f00\u4efb\u52a1\u76ee\u5f55", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File jobFile = new File(record.jobPath);
        File directory = jobFile.isDirectory() ? jobFile : jobFile.getParentFile();
        if (directory == null || !directory.isDirectory()) {
            MessageBoxes.errorBox(this, "\u6253\u5f00\u4efb\u52a1\u76ee\u5f55\u5931\u8d25",
                    "\u4efb\u52a1\u76ee\u5f55\u4e0d\u5b58\u5728\uff1a" + record.jobPath);
            return;
        }
        try {
            boolean opened = false;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                new ProcessBuilder("explorer.exe", directory.getAbsolutePath()).start();
                opened = true;
            }
            if (!opened && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory);
                opened = true;
            }
            if (!opened) {
                throw new UnsupportedOperationException("No supported file manager is available.");
            }
        }
        catch (Exception e) {
            MessageBoxes.errorBox(this, "\u6253\u5f00\u4efb\u52a1\u76ee\u5f55\u5931\u8d25", e);
        }
    }
    private String integer(Map<String, Double> values, String key) {
        return String.valueOf(Math.round(values.get(key)));
    }

    private String format(Double value) {
        return value == null ? "0" : String.format(Locale.ROOT, "%.1f", value);
    }

    private String escape(String value) {
        if (value == null) {
            return "—";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class HistoryTableModel extends AbstractTableModel {
        private final String[] columns = {"最近开始", "任务", "连续次数", "状态", "累计耗时", "单板",
                "器件", "预计耗时", "准确度", "预测偏差", "平均速度", "异常"};
        private List<JobRunRecord> records = new ArrayList<>();

        void setRecords(List<JobRunRecord> records) {
            this.records = records;
            fireTableDataChanged();
        }

        JobRunRecord getRecord(int row) {
            return row < 0 || row >= records.size() ? null : records.get(row);
        }

        int findRecord(String key) {
            for (int row = 0; row < records.size(); row++) {
                if (key.equals(detailsKey(records.get(row)))) {
                    return row;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return records.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JobRunRecord record = records.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return JobTelemetryManager.formatDate(record.startTime);
                case 1:
                    return record.jobName;
                case 2:
                    return record.getRunCount();
                case 3:
                    return record.state;
                case 4:
                    return JobTelemetryManager.formatDuration(record.elapsedMillis);
                case 5:
                    return record.completedBoards + "/" + record.boardCount;
                case 6:
                    return record.placedPlacements + "/" + record.totalPlacements;
                case 7:
                    return JobTelemetryManager.formatDuration(record.getEstimatedDurationMillis());
                case 8:
                    return String.format(Locale.ROOT, "%.1f%%", record.predictionAccuracyPercent);
                case 9:
                    return JobTelemetryManager.formatSignedDuration(record.predictionDeviationMillis);
                case 10:
                    return String.format(Locale.ROOT, "%.1f CPH", record.averageCph);
                case 11:
                    return record.getErrorCount();
                default:
                    return "";
            }
        }
    }

    private static class TrendPanel extends JPanel {
        private List<JobRunRecord> records = new ArrayList<>();

        TrendPanel() {
            setBorder(BorderFactory.createTitledBorder("最近任务速度趋势（蓝）与异常次数（红）"));
            setBackground(ModernTheme.surface(true));
        }

        void setRecords(List<JobRunRecord> records) {
            this.records = new ArrayList<>(records.subList(0, Math.min(20, records.size())));
            Collections.reverse(this.records);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (records.size() < 2) {
                graphics.setColor(ModernTheme.mutedForeground());
                graphics.drawString("完成两次任务后显示趋势", 18, 72);
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int left = 24;
            int right = getWidth() - 24;
            int top = 34;
            int bottom = getHeight() - 20;
            double maxSpeed = 1;
            int maxErrors = 1;
            for (JobRunRecord record : records) {
                maxSpeed = Math.max(maxSpeed, record.averageCph);
                maxErrors = Math.max(maxErrors, record.getErrorCount());
            }
            for (int index = 1; index < records.size(); index++) {
                int x1 = left + (right - left) * (index - 1) / (records.size() - 1);
                int x2 = left + (right - left) * index / (records.size() - 1);
                int speedY1 = bottom - (int) ((bottom - top)
                        * records.get(index - 1).averageCph / maxSpeed);
                int speedY2 = bottom - (int) ((bottom - top)
                        * records.get(index).averageCph / maxSpeed);
                g2.setColor(new Color(0x3B82F6));
                g2.setStroke(new java.awt.BasicStroke(2.4f));
                g2.drawLine(x1, speedY1, x2, speedY2);
                int errorY1 = bottom - (bottom - top) * records.get(index - 1).getErrorCount()
                        / maxErrors;
                int errorY2 = bottom - (bottom - top) * records.get(index).getErrorCount()
                        / maxErrors;
                g2.setColor(new Color(0xE05260));
                g2.setStroke(new java.awt.BasicStroke(1.8f));
                g2.drawLine(x1, errorY1, x2, errorY2);
            }
            g2.dispose();
        }
    }
}
