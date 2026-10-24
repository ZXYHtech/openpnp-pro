package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

import org.openpnp.gui.importer.KicadFootprintParser;
import org.openpnp.gui.support.ExperimentalGuide;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.gui.support.PathSelectionDialog.Mode;
import org.openpnp.model.Configuration;
import org.openpnp.packageimport.PackageCategoryRegistry;
import org.openpnp.packageimport.PackageImportPlan;
import org.openpnp.packageimport.PackageImportPlan.Decision;
import org.openpnp.packageimport.PackageImportPlan.Item;

@SuppressWarnings("serial")
public class KicadPackageImportDialog extends JDialog {
    private final Configuration configuration;
    private final DefaultListModel<File> sourceModel = new DefaultListModel<>();
    private final JList<File> sourceList = new JList<>(sourceModel);
    private final JCheckBox recursive = new JCheckBox("递归扫描子目录", true);
    private final JButton chooseButton = new JButton("选择文件或 .pretty 目录...");
    private final JButton scanButton = new JButton("扫描与解析");
    private final JButton cancelScanButton = new JButton("取消扫描");
    private final JButton importButton = new JButton("确认并执行导入");
    private final JButton guideButton = new JButton("跟着做");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("请选择单个/多个 .kicad_mod 文件或 .pretty 目录");
    private final ImportTableModel tableModel = new ImportTableModel();
    private final JTable table = new JTable(tableModel);
    private final PreviewPanel preview = new PreviewPanel();
    private final JTextArea details = new JTextArea();
    private final JTextArea report = new JTextArea();
    private final JTabbedPane steps = new JTabbedPane();
    private PackageImportPlan plan = new PackageImportPlan();
    private SwingWorker<Void, ScanResult> worker;
    private ExperimentalGuide guide;

    public KicadPackageImportDialog(java.awt.Frame owner, Configuration configuration) {
        super(owner, "封装库 · KiCad 导入中心", true);
        this.configuration = configuration;
        buildUi();
        wireActions();
        setMinimumSize(new Dimension(880, 560));
        java.awt.Rectangle screen = owner == null
                ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                : owner.getGraphicsConfiguration().getBounds();
        setSize(Math.min(1320, screen.width - 40), Math.min(820, screen.height - 60));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel("<html><b>KiCad 封装批量导入</b>　选择 → 扫描 → 分类与方向 → 匹配决策 → 预览执行</html>");
        title.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        header.add(title, BorderLayout.CENTER);
        header.add(guideButton, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel sourcePage = new JPanel(new BorderLayout(8, 8));
        sourcePage.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        sourcePage.add(new JScrollPane(sourceList), BorderLayout.CENTER);
        JPanel sourceActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sourceActions.add(chooseButton);
        sourceActions.add(recursive);
        sourceActions.add(scanButton);
        sourceActions.add(cancelScanButton);
        sourcePage.add(sourceActions, BorderLayout.NORTH);
        JPanel progressPanel = new JPanel(new BorderLayout(8, 0));
        progressPanel.add(progress, BorderLayout.CENTER);
        progressPanel.add(status, BorderLayout.SOUTH);
        sourcePage.add(progressPanel, BorderLayout.SOUTH);
        steps.addTab("1 选择与扫描", sourcePage);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(210);
        table.getColumnModel().getColumn(2).setPreferredWidth(55);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(120);
        table.getColumnModel().getColumn(6).setPreferredWidth(220);
        table.getColumnModel().getColumn(7).setPreferredWidth(190);
        table.getColumnModel().getColumn(7).setCellEditor(
                new DefaultCellEditor(new JComboBox<>(Decision.values())));
        preview.setPreferredSize(new Dimension(420, 300));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, preview,
                new JScrollPane(details));
        right.setResizeWeight(.65);
        JSplitPane reviewSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(table), right);
        reviewSplit.setResizeWeight(.68);
        JPanel reviewPage = new JPanel(new BorderLayout(6, 6));
        reviewPage.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        reviewPage.add(reviewSplit, BorderLayout.CENTER);
        JPanel rotate = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton left = new JButton("旋转 -90°");
        JButton right90 = new JButton("旋转 +90°");
        JButton half = new JButton("旋转 180°");
        JButton selectAll = new JButton("全选行");
        JButton clearSelection = new JButton("取消行选择");
        left.addActionListener(e -> rotateSelected(-90));
        right90.addActionListener(e -> rotateSelected(90));
        half.addActionListener(e -> rotateSelected(180));
        selectAll.addActionListener(e -> table.selectAll());
        clearSelection.addActionListener(e -> table.clearSelection());
        rotate.add(selectAll);
        rotate.add(clearSelection);
        rotate.add(new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL));
        rotate.add(new JLabel("方向复核："));
        rotate.add(left);
        rotate.add(right90);
        rotate.add(half);
        rotate.add(new JLabel("　冲突项默认不导入；替换几何需要二次确认"));
        reviewPage.add(rotate, BorderLayout.SOUTH);
        steps.addTab("2 分类、方向与冲突决策", reviewPage);

        report.setEditable(false);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        JPanel executePage = new JPanel(new BorderLayout(8, 8));
        executePage.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        executePage.add(new JScrollPane(report), BorderLayout.CENTER);
        JPanel executeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton export = new JButton("导出中文报告...");
        export.addActionListener(e -> exportReport());
        executeButtons.add(export);
        executeButtons.add(importButton);
        executePage.add(executeButtons, BorderLayout.SOUTH);
        steps.addTab("3 汇总、执行与报告", executePage);
        add(steps, BorderLayout.CENTER);

        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        closePanel.add(close);
        add(closePanel, BorderLayout.SOUTH);
        cancelScanButton.setEnabled(false);
        importButton.setEnabled(false);
        installTablePopupMenu();
        setupGuide();
    }

    private void installTablePopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem selectAll = new JMenuItem("全选所有行");
        selectAll.addActionListener(e -> table.selectAll());
        popup.add(selectAll);
        JMenuItem clearSelection = new JMenuItem("取消行选择");
        clearSelection.addActionListener(e -> table.clearSelection());
        popup.add(clearSelection);
        popup.addSeparator();

        JMenuItem include = new JMenuItem("勾选导入所选项");
        include.addActionListener(e -> setSelectedImport(true));
        popup.add(include);
        JMenuItem exclude = new JMenuItem("取消导入所选项");
        exclude.addActionListener(e -> setSelectedImport(false));
        popup.add(exclude);
        popup.addSeparator();

        JMenu decision = new JMenu("批量设置处理决定");
        for (Decision value : Decision.values()) {
            JMenuItem item = new JMenuItem(value.toString());
            item.addActionListener(e -> setSelectedDecision(value));
            decision.add(item);
        }
        popup.add(decision);
        JMenu rotate = new JMenu("批量旋转方向");
        addRotateMenuItem(rotate, "旋转 -90°", -90);
        addRotateMenuItem(rotate, "旋转 +90°", 90);
        addRotateMenuItem(rotate, "旋转 180°", 180);
        popup.add(rotate);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { showPopup(event); }
            @Override public void mouseReleased(MouseEvent event) { showPopup(event); }
            private void showPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
                if (row >= 0) {
                    org.openpnp.gui.support.TableSelectionSupport.augment(table, popup);
                    popup.show(table, event.getX(), event.getY());
                }
            }
        });
    }

    private void addRotateMenuItem(JMenu menu, String text, double degrees) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> rotateSelected(degrees));
        menu.add(item);
    }

    private void wireActions() {
        chooseButton.addActionListener(e -> chooseSources());
        scanButton.addActionListener(e -> startScan());
        cancelScanButton.addActionListener(e -> { if (worker != null) { worker.cancel(true); } });
        importButton.addActionListener(e -> applyPlan());
        guideButton.addActionListener(e -> guide.start());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) { updateSelectedPreview(); }
        });
        steps.addChangeListener(e -> {
            if (steps.getSelectedIndex() == 2) { report.setText(plan.previewSummary()
                    + "\n\n执行前会先使用 OpenPnP 现有保存机制备份配置。\n"
                    + "取消窗口不会修改任何封装。\n"); }
        });
    }

    private void setupGuide() {
        guide = new ExperimentalGuide(this, "kicad-package-import", "KiCad 封装导入",
                new ExperimentalGuide.Step("选择来源",
                        "可多选 .kicad_mod，也可选择一个或多个 .pretty 目录。这里只登记来源，不会修改配置。",
                        () -> chooseButton, () -> sourceModel.size() > 0),
                new ExperimentalGuide.Step("后台扫描",
                        "点击扫描后在后台解析跨行 S 表达式、焊盘、外形和 1/A1 脚。可随时取消。",
                        () -> scanButton, () -> tableModel.getRowCount() > 0),
                new ExperimentalGuide.Step("逐项审核",
                        "检查分类依据、方向、警告和已有封装差异。冲突默认跳过，系统不会静默覆盖。",
                        () -> table, () -> table.getSelectedRow() >= 0),
                new ExperimentalGuide.Step("确认执行",
                        "先查看汇总，再执行已选决策。高风险替换会再次确认；此引导不会替你点击导入。",
                        () -> importButton, () -> false));
        guide.startOnFirstOpen();
    }

    private void chooseSources() {
        File[] selected = PathSelectionDialog.chooseOpen(this, "kicad-package-sources",
                "选择 .kicad_mod 文件或 .pretty 目录", Mode.FILES_AND_DIRECTORIES, true,
                configuration.getConfigurationDirectory(),
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "KiCad 封装 (*.kicad_mod) 或 .pretty 目录", "kicad_mod"));
        if (selected == null) { return; }
        for (File file : selected) { if (!sourceModel.contains(file)) { sourceModel.addElement(file); } }
        status.setText("已选择 " + sourceModel.size() + " 个来源；尚未写入配置");
    }

    private void startScan() {
        if (sourceModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先选择文件或目录。", "KiCad 导入",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<File> files = collectFiles();
        if (files.isEmpty()) {
            JOptionPane.showMessageDialog(this, "所选来源中没有 .kicad_mod 文件。", "KiCad 导入",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        plan = new PackageImportPlan();
        tableModel.setPlan(plan);
        scanButton.setEnabled(false);
        cancelScanButton.setEnabled(true);
        progress.setMaximum(files.size());
        progress.setValue(0);
        worker = new SwingWorker<Void, ScanResult>() {
            @Override protected Void doInBackground() {
                KicadFootprintParser parser = new KicadFootprintParser();
                int index = 0;
                for (File file : files) {
                    if (isCancelled()) { break; }
                    try { publish(new ScanResult(file, parser.parse(file), null)); }
                    catch (Exception ex) { publish(new ScanResult(file, null, ex.getMessage())); }
                    setProgress(++index * 100 / files.size());
                }
                return null;
            }
            @Override protected void process(List<ScanResult> values) {
                for (ScanResult value : values) {
                    if (value.parsed != null) { plan.add(value.parsed, configuration); }
                    else { tableModel.addFailure(value.file, value.error); }
                    progress.setValue(Math.min(progress.getMaximum(), progress.getValue() + 1));
                }
                tableModel.fireTableDataChanged();
                status.setText("已处理 " + progress.getValue() + " / " + progress.getMaximum());
            }
            @Override protected void done() {
                scanButton.setEnabled(true);
                cancelScanButton.setEnabled(false);
                importButton.setEnabled(!plan.getItems().isEmpty());
                status.setText(isCancelled() ? "扫描已取消，已解析结果仍可审核"
                        : "扫描完成：成功 " + plan.getItems().size() + "，失败 " + tableModel.failures.size());
                steps.setSelectedIndex(1);
                if (table.getRowCount() > 0) { table.setRowSelectionInterval(0, 0); }
            }
        };
        worker.execute();
    }

    private List<File> collectFiles() {
        List<File> result = new ArrayList<>();
        for (int i = 0; i < sourceModel.size() && result.size() < 5000; i++)
            { collect(sourceModel.get(i), result, recursive.isSelected() ? 6 : 1); }
        Collections.sort(result);
        return result;
    }
    private void collect(File file, List<File> result, int depth) {
        if (file.isFile() && file.getName().toLowerCase().endsWith(".kicad_mod")) {
            result.add(file);
            return;
        }
        if (!file.isDirectory() || depth <= 0 || result.size() >= 5000) { return; }
        File[] children = file.listFiles();
        if (children == null) { return; }
        for (File child : children) {
            collect(child, result, depth - 1);
            if (result.size() >= 5000) { break; }
        }
    }

    private void rotateSelected(double degrees) {
        int[] rows = selectedModelRows(table);
        for (int row : rows) {
            if (row < plan.getItems().size()) {
                plan.getItems().get(row).rotate(degrees);
                tableModel.fireTableRowsUpdated(row, row);
            }
        }
        updateSelectedPreview();
    }

    private void setSelectedImport(boolean selected) {
        for (int row : selectedModelRows(table)) {
            if (row < plan.getItems().size()) {
                plan.getItems().get(row).selected = selected;
                tableModel.fireTableRowsUpdated(row, row);
            }
        }
    }

    private void setSelectedDecision(Decision decision) {
        for (int row : selectedModelRows(table)) {
            if (row < plan.getItems().size()) {
                plan.getItems().get(row).decision = decision;
                tableModel.fireTableRowsUpdated(row, row);
            }
        }
    }

    static int[] selectedModelRows(JTable table) {
        int[] viewRows = table.getSelectedRows();
        int[] modelRows = new int[viewRows.length];
        for (int i = 0; i < viewRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        return modelRows;
    }
    private Item selectedItem() {
        int row = table.getSelectedRow();
        if (row < 0) { return null; }
        int modelRow = table.convertRowIndexToModel(row);
        return modelRow < plan.getItems().size() ? plan.getItems().get(modelRow) : null;
    }
    private void updateSelectedPreview() {
        Item item = selectedItem();
        preview.setItem(item);
        if (item == null) { details.setText("");
        return; }
        String candidate = item.match.match == null ? "无" : item.match.match.getId();
        details.setText("来源：" + (item.parsed.sourceFile == null ? "文本" : item.parsed.sourceFile)
                + "\n本体尺寸来源：" + item.parsed.bodySource
                + "\n分类：" + PackageCategoryRegistry.label(item.classification.categoryId)
                + String.format("（置信度 %.0f%%）", item.classification.confidence * 100)
                + "\n分类依据：" + item.classification.reason
                + "\n方向：" + item.normalized.reason
                + "\n已有候选：" + candidate + "；" + item.match.reason
                + "\n差异：" + (item.match.difference.isEmpty() ? "无关键差异" : item.match.difference)
                + "\n警告：" + (item.normalized.warnings.isEmpty() ? "无"
                        : String.join("；", item.normalized.warnings)));
        details.setCaretPosition(0);
    }

    private void applyPlan() {
        String summary = plan.previewSummary();
        if (JOptionPane.showConfirmDialog(this, summary + "\n\n确认按当前逐项决策执行？",
                "导入预览", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
        boolean replacing = plan.getItems().stream().anyMatch(i -> i.selected
                && i.decision == Decision.REPLACE_GEOMETRY);
        if (replacing && JOptionPane.showConfirmDialog(this,
                "当前计划包含“替换已有几何”。Part 引用和视觉设置会保留，但焊盘轮廓将改变。\n再次确认继续？",
                "高风险操作确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                        != JOptionPane.YES_OPTION) { return; }
        try {
            configuration.save();
            String result = plan.apply(configuration);
            configuration.save();
            report.setText(result + "\n\n执行完成；配置已保存并生成常规备份。");
            report.setCaretPosition(0);
            importButton.setEnabled(false);
            steps.setSelectedIndex(2);
        }
        catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导入未完成，已回滚本批内存变更：\n" + ex.getMessage(),
                    "KiCad 导入失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportReport() {
        File file = PathSelectionDialog.chooseSave(this, "kicad-package-report", "导出 KiCad 导入报告",
                new File("kicad-package-import-" + plan.getBatchId() + ".txt"),
                configuration.getConfigurationDirectory(),
                new javax.swing.filechooser.FileNameExtensionFilter("文本报告 (*.txt)", "txt"));
        if (file == null) { return; }
        try { Files.write(file.toPath(), report.getText().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "报告导出失败", JOptionPane.ERROR_MESSAGE); }
    }

    private static final class ScanResult {
        final File file;
        final KicadFootprintParser.Result parsed;
        final String error;
        ScanResult(File file, KicadFootprintParser.Result parsed, String error) {
            this.file = file;
            this.parsed = parsed;
            this.error = error;
        }
    }

    private final class ImportTableModel extends AbstractTableModel {
        private final String[] columns = { "导入", "封装名", "焊盘", "分类", "旋转", "状态", "匹配/差异", "处理决定" };
        private final List<ScanResult> failures = new ArrayList<>();
        void setPlan(PackageImportPlan value) { failures.clear();
        fireTableDataChanged(); }
        void addFailure(File file, String error) { failures.add(new ScanResult(file, null, error)); }
        @Override public int getRowCount() { return plan.getItems().size() + failures.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : Object.class; }
        @Override public boolean isCellEditable(int row, int column) { return row < plan.getItems().size() && (column == 0 || column == 7); }
        @Override public Object getValueAt(int row, int column) {
            if (row >= plan.getItems().size()) {
                ScanResult failure = failures.get(row - plan.getItems().size());
                return column == 1 ? failure.file.getName() : column == 5 ? "解析失败" : column == 6 ? failure.error : column == 0 ? false : "";
            }
            Item item = plan.getItems().get(row);
            switch(column) {
                case 0: return item.selected;
                case 1: return item.parsed.name;
                case 2: return item.normalized.footprint.getPads().size();
                case 3: return PackageCategoryRegistry.label(item.classification.categoryId);
                case 4: return String.format("%.0f°", item.normalized.rotation);
                case 5: return item.statusText();
                case 6: return item.match.match == null ? item.match.reason
                        : item.match.match.getId() + (item.match.difference.isEmpty() ? "" : "：" + item.match.difference);
                case 7: return item.decision;
                default: return "";
            }
        }
        @Override public void setValueAt(Object value, int row, int column) {
            Item item = plan.getItems().get(row);
            if (column == 0) { item.selected = Boolean.TRUE.equals(value); }
            if (column == 7 && value instanceof Decision) { item.decision = (Decision) value; }
            fireTableRowsUpdated(row, row);
        }
    }

    private static final class PreviewPanel extends JPanel {
        private Item item;
        PreviewPanel() { setBorder(BorderFactory.createTitledBorder("KiCad 原始方向 / OpenPnP 转换后方向")); }
        void setItem(Item item) { this.item = item;
        repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (item == null) { return; }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int half = getWidth() / 2;
                drawAxes(g, half / 2, getHeight() / 2, "KiCad 原始", half);
                drawOriginal(g, half / 2, getHeight() / 2, half);
                drawAxes(g, half + half / 2, getHeight() / 2, "OpenPnP", half);
                drawConverted(g, half + half / 2, getHeight() / 2, half);
            }
            finally { g.dispose(); }
        }
        private void drawAxes(Graphics2D g, int cx, int cy, String label, int width) {
            g.setColor(new Color(120, 130, 140));
            g.drawLine(cx - width / 2 + 15, cy, cx + width / 2 - 15, cy);
            g.drawLine(cx, 35, cx, getHeight() - 15);
            g.drawString(label, cx - 35, 24);
        }
        private double scale(int width) {
            double max = 1;
            for (org.openpnp.gui.importer.KicadFootprintParser.ParsedPad p : item.parsed.pads)
                { max = Math.max(max, Math.max(Math.abs(p.x) + p.width, Math.abs(p.y) + p.height)); }
            return Math.min(width * .38 / max, (getHeight() - 70) * .42 / max);
        }
        private void drawOriginal(Graphics2D g, int cx, int cy, int width) {
            double s = scale(width);
            for (org.openpnp.gui.importer.KicadFootprintParser.ParsedPad p : item.parsed.pads) {
                g.setColor("1".equals(p.name) || "A1".equalsIgnoreCase(p.name) ? Color.ORANGE : new Color(80, 170, 220));
                double w = Math.max(2, p.width * s);
                double h = Math.max(2, p.height * s);
                double radius = Math.min(w, h) * p.roundness / 100.0;
                Shape pad = new RoundRectangle2D.Double(-w / 2, -h / 2, w, h,
                        radius, radius);
                AffineTransform transform = new AffineTransform();
                transform.translate(cx + p.x * s, cy + p.y * s);
                // KiCad's positive pad angle is counter-clockwise in its editor view.
                transform.rotate(Math.toRadians(-p.rotation));
                g.fill(transform.createTransformedShape(pad));
            }
        }
        private void drawConverted(Graphics2D g, int cx, int cy, int width) {
            double s = scale(width);
            for (org.openpnp.model.Footprint.Pad p : item.normalized.footprint.getPads()) {
                g.setColor("1".equals(p.getName()) || "A1".equalsIgnoreCase(p.getName()) ? Color.ORANGE : new Color(90, 200, 130));
                AffineTransform transform = new AffineTransform();
                transform.translate(cx, cy);
                transform.scale(s, -s);
                g.fill(transform.createTransformedShape(p.getShape()));
            }
        }
    }
}
