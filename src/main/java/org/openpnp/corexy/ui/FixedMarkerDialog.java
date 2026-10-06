package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.precision.CalibrationController;
import org.openpnp.corexy.precision.CalibrationRequest;
import org.openpnp.corexy.precision.DigitalTwinMachine;
import org.openpnp.corexy.precision.FixedMarkerSetup;
import org.openpnp.corexy.precision.OpenPnpCalibrationMachine;
import org.openpnp.corexy.precision.PrecisionMetrics;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.UiUtils;

/** Five fixed references: record once, calibrate, then inspect durable startup checks. */
public final class FixedMarkerDialog extends JDialog {
    private final Configuration configuration;
    private final CalibrationController controller = new CalibrationController();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final FixedMarkerGuidePanel guide = new FixedMarkerGuidePanel();
    private final DefaultTableModel coordinates = new DefaultTableModel(
            new String[] {"固定点", "用途", "X（mm）", "Y（mm）"}, 5) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column >= 2;
        }
        @Override
        public Class<?> getColumnClass(int column) {
            return column >= 2 ? Double.class : String.class;
        }
    };
    private final JTable table = new JTable(coordinates);
    private final JSpinner diameter = number(1, .1, 10, .1);
    private final JSpinner target = number(.05, .001, 1, .005);
    private final JSpinner conditioning = number(48, 3, 128, 1);
    private final JSpinner planeZ = number(0, -10000, 10000, .1);
    private final JSpinner homeOffset = number(1, .001, 5, .1);
    private final JSpinner homeRadius = number(2, .001, 10, .1);
    private final JCheckBox autoVerify = new JCheckBox("每次启动，在首次 Home 中自动校验 5 点", true);
    private final JButton record = new JButton("记录顶部相机当前位置 → 下一点");
    private final JButton save = new JButton("保存这 5 个固定点");
    private final JButton start = new JButton("自动校准并启用通过方案");
    private final JButton verify = new JButton("Home 并校验已启用方案");
    private final JButton simulation = new JButton("离线演练");
    private final JButton stop = new JButton("取消并恢复");
    private final JButton history = new JButton("打开校准与校验历史");
    private final JButton report = new JButton("打开本次原始数据");
    private final JLabel state = new JLabel("先完成首次机械 Home，再依次对齐和记录这 5 个固定点。");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea results = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private boolean running, ownsWorkflow;
    private double maximumOffset = .2;
    private int settleMillis = 300;
    private Path reportDirectory;
    private CalibrationRequest currentRequest;
    private final String[] markerIds = {"home", "marker-2", "marker-3", "marker-4", "marker-5"};
    private final String[] markerNames = {"Home 视觉基准", "覆盖点 2", "覆盖点 3", "覆盖点 4", "覆盖点 5"};

    public FixedMarkerDialog(JDialog owner, Configuration configuration) throws Exception {
        super(owner, "5 个固定 marker · 自动补偿与 Home 校验", false);
        this.configuration = configuration;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 650));
        setSize(1090, 780);
        setLocationRelativeTo(owner);
        JPanel preparation = new JPanel(new BorderLayout(16, 16));
        preparation.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        preparation.add(new JLabel("<html><b>固定安装一次，后续自动使用。</b> 第 1 点是 Home 视觉基准；其余 4 点分布在工作区。"
                + "<br>Home 点须顶部相机可见且安全可达；其余点留足换向预行程。全部 marker 保持固定、清晰、同平面。</html>"), BorderLayout.NORTH);
        preparation.add(guide, BorderLayout.WEST);
        JPanel input = new JPanel(new BorderLayout(8, 10));
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setPreferredScrollableViewportSize(new Dimension(520, 190));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        for (int row = 0; row < 5; row++) {
            coordinates.setValueAt("第 " + (row + 1) + " 点", row, 0);
            coordinates.setValueAt(row == 0 ? "Home 视觉基准" : "工作区覆盖", row, 1);
        }
        JPanel coordinatePanel = new JPanel(new BorderLayout(8, 8));
        coordinatePanel.add(new JScrollPane(table), BorderLayout.CENTER);
        coordinatePanel.add(record, BorderLayout.SOUTH);
        record.setToolTipText("将顶部相机准心对齐所选固定 marker 的中心，再记录；此按钮只读取位置。");
        input.add(coordinatePanel, BorderLayout.CENTER);
        JPanel settings = new JPanel(new GridLayout(0, 2, 8, 10));
        field(settings, "圆形 marker 直径（mm）", diameter);
        field(settings, "目标 P95 偏差（mm）", target);
        field(settings, "换向预行程（mm）", conditioning);
        field(settings, "marker 相机测量 Z（mm）", planeZ);
        field(settings, "Home 允许偏移（mm）", homeOffset);
        field(settings, "Home marker 搜索半径（mm）", homeRadius);
        JPanel parameters = new JPanel(new BorderLayout(8, 12));
        parameters.add(settings, BorderLayout.CENTER);
        parameters.add(autoVerify, BorderLayout.SOUTH);
        input.add(parameters, BorderLayout.SOUTH);
        preparation.add(input, BorderLayout.CENTER);
        preparation.add(new JLabel("<html>① 完成首次机械 Home → ② 逐点对齐、记录 → ③ 自动校准。补偿会在独立评估通过后启用。"
                + "<br>以后启动先加载补偿；Home 完成前用第 1 点校正复位偏移，再校验 5 点。改点位或移动 marker 后需要重新校准。"
                + "<br>坐标可手工修订。相机录入用于重复定位；绝对尺寸精度需要经过标定的标准坐标。</html>"), BorderLayout.SOUTH);
        tabs.addTab("1  固定点与开始", preparation);
        results.setEditable(false);
        results.setLineWrap(true);
        results.setWrapStyleWord(true);
        results.setMargin(new java.awt.Insets(14, 14, 14, 14));
        tabs.addTab("2  本次结果与历史", new JScrollPane(results));
        add(tabs, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(10, 18, 16, 18));
        JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
        JPanel management = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        management.add(history);
        management.add(report);
        management.add(save);
        management.add(verify);
        JPanel calibration = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        calibration.add(simulation);
        calibration.add(stop);
        calibration.add(start);
        actions.add(management);
        actions.add(calibration);
        progress.setStringPainted(true);
        footer.add(state, BorderLayout.NORTH);
        footer.add(progress, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        stop.setEnabled(false);
        report.setEnabled(false);
        FixedMarkerSetup existing = CoreXYIntegration.store(configuration).loadFixedMarkers();
        if (existing != null) {
            showSettings(existing);
            state.setText("已读取固定点设置；校验使用当前已启用方案的点位。更改点位后请重新校准。");
        }
        table.setRowSelectionInterval(0, 0);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !running && table.getSelectedRow() >= 0) {
                guide.setGuide(1, table.getSelectedRow());
            }
        });
        record.addActionListener(event -> recordCurrent());
        save.addActionListener(event -> UiUtils.messageBoxOnException(() -> {
            CoreXYIntegration.store(configuration).saveFixedMarkers(readSettings());
            state.setText("5 个固定点已保存。执行自动校准并通过评估后才会启用新方案。");
        }));
        start.addActionListener(event -> calibrate(false));
        simulation.addActionListener(event -> calibrate(true));
        verify.addActionListener(event -> verify());
        stop.addActionListener(event -> requestCancellation());
        history.addActionListener(event -> UiUtils.messageBoxOnException(() -> {
            Path page = CoreXYIntegration.store(configuration).writeHistoryHtml();
            java.awt.Desktop.getDesktop().browse(page.toUri());
        }));
        report.addActionListener(event -> UiUtils.messageBoxOnException(() -> {
            if (reportDirectory != null) {
                java.awt.Desktop.getDesktop().open(reportDirectory.toFile());
            }
        }));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (running) {
                    requestCancellation();
                }
                else {
                    dispose();
                }
            }
        });
        coordinates.addTableModelListener(event -> updateSetupReadiness());
        updateSetupReadiness();
    }

    private void showSettings(FixedMarkerSetup setup) {
        setup.validate();
        // Row 1 always carries the Home reference, even if an imported setup has another ordering.
        java.util.List<FixedMarkerSetup.Marker> ordered = new java.util.ArrayList<>();
        ordered.add(setup.homeMarker());
        for (int i = 0; i < setup.markers.size(); i++) {
            if (i != setup.homeIndex) {
                ordered.add(setup.markers.get(i));
            }
        }
        for (int row = 0; row < 5; row++) {
            coordinates.setValueAt(ordered.get(row).x, row, 2);
            coordinates.setValueAt(ordered.get(row).y, row, 3);
            markerIds[row] = ordered.get(row).id;
            markerNames[row] = ordered.get(row).name;
        }
        diameter.setValue(setup.markerDiameter);
        target.setValue(setup.target);
        conditioning.setValue(setup.conditioningTravel);
        planeZ.setValue(setup.planeZ);
        autoVerify.setSelected(setup.verifyOnStartup);
        homeOffset.setValue(setup.maximumHomeOffset);
        homeRadius.setValue(setup.homeSearchRadius);
        maximumOffset = setup.maximumOffset;
        settleMillis = setup.settleMillis;
    }

    private FixedMarkerSetup readSettings() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("请完成正在编辑的坐标；只能输入有限数字。");
        }
        FixedMarkerSetup setup = new FixedMarkerSetup();
        for (int row = 0; row < 5; row++) {
            Object x = coordinates.getValueAt(row, 2), y = coordinates.getValueAt(row, 3);
            if (!(x instanceof Number) || !(y instanceof Number)) {
                throw new IllegalArgumentException("请先记录或填写第 " + (row + 1) + " 个 marker 的 X/Y 坐标。");
            }
            setup.markers.add(new FixedMarkerSetup.Marker(markerIds[row], markerNames[row],
                    ((Number) x).doubleValue(), ((Number) y).doubleValue()));
        }
        setup.homeIndex = 0;
        setup.markerDiameter = value(diameter);
        setup.target = value(target);
        setup.conditioningTravel = value(conditioning);
        setup.planeZ = value(planeZ);
        setup.verifyOnStartup = autoVerify.isSelected();
        setup.maximumOffset = maximumOffset;
        setup.settleMillis = settleMillis;
        setup.maximumHomeOffset = value(homeOffset);
        setup.homeSearchRadius = value(homeRadius);
        if (setup.homeSearchRadius < setup.maximumHomeOffset) {
            throw new IllegalArgumentException("Home marker 搜索半径必须不小于 Home 允许偏移。");
        }
        setup.validate();
        return setup;
    }

    private void recordCurrent() {
        if (!begin()) {
            return;
        }
        final int row = Math.max(0, table.getSelectedRow());
        state.setText("正在读取第 " + (row + 1) + " 点的相机位置；不会移动机器。");
        try {
            UiUtils.submitUiMachineTask(() -> {
                if (!configuration.getMachine().isEnabled() || !configuration.getMachine().isHomed()) {
                    throw new IllegalStateException("首次录入固定点需要机器已连接并完成 Home；请先对齐顶部相机。");
                }
                Location location = configuration.getMachine().getDefaultHead().getDefaultCamera()
                        .getLocation().convertToUnits(LengthUnit.Millimeters);
                if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) {
                    throw new IllegalStateException("相机位置不是有效坐标。");
                }
                return location;
            }, location -> {
                if (cancelled.get()) {
                    finish();
                    state.setText("相机位置录入已取消。");
                    return;
                }
                coordinates.setValueAt(location.getX(), row, 2);
                coordinates.setValueAt(location.getY(), row, 3);
                if (row == 0) {
                    planeZ.setValue(location.getZ());
                }
                finish();
                int next = -1;
                for (int i = 0; i < 5; i++) {
                    if (coordinates.getValueAt(i, 2) == null || coordinates.getValueAt(i, 3) == null) {
                        next = i;
                        break;
                    }
                }
                if (next >= 0) {
                    table.setRowSelectionInterval(next, next);
                    state.setText("第 " + (row + 1) + " 点已记录；请对齐第 " + (next + 1) + " 点后继续记录。");
                }
                else {
                    state.setText("5 个固定点已记录；检查同一测量 Z 与安全预行程，然后开始自动校准。");
                }
            }, this::showFailure);
        }
        catch (Exception failure) {
            showFailure(failure);
        }
    }

    private void calibrate(boolean simulated) {
        final FixedMarkerSetup setup;
        try {
            setup = simulated ? demonstrationSettings() : readSettings();
        }
        catch (Exception failure) {
            UiUtils.showError(this, "固定点设置无效", failure);
            return;
        }
        if (!begin()) {
            return;
        }
        controller.prepareRun();
        guide.setGuide(2, 0);
        CalibrationRequest request = setup.toRequest();
        currentRequest = request;
        CalibrationController.Progress listener = listener(simulated ? "离线演练 · " : "");
        if (simulated) {
            new SwingWorker<CalibrationController.Report, Void>() {
                @Override
                protected CalibrationController.Report doInBackground() throws Exception {
                    try {
                        CalibrationController.Report report = controller.run(new DigitalTwinMachine(20261006), request, listener);
                        reportDirectory = CoreXYIntegration.store(configuration).saveReport(report);
                        return report;
                    }
                    catch (Exception failure) {
                        saveInterruptedReport(request, failure);
                        throw failure;
                    }
                }
                @Override
                protected void done() {
                    try {
                        showReport(get());
                        finish();
                    }
                    catch (Exception failure) {
                        showFailure(failure.getCause() == null ? failure : failure.getCause());
                    }
                }
            }.execute();
        }
        else {
            try {
                UiUtils.submitUiMachineTask(() -> {
                    CoreXYIntegration.store(configuration).saveFixedMarkers(setup);
                    OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
                    try {
                        CalibrationController.Report report = controller.run(backend, request, listener);
                        reportDirectory = CoreXYIntegration.persistResult(configuration, backend, report);
                        return report;
                    }
                    catch (Exception failure) {
                        saveInterruptedReport(request, failure);
                        throw failure;
                    }
                }, report -> {
                    showReport(report);
                    finish();
                }, this::showFailure);
            }
            catch (Exception failure) {
                showFailure(failure);
            }
        }
    }

    private void verify() {
        // The integration API owns the shared lock for manual verification and its Home stages.
        if (!begin(false)) {
            return;
        }
        guide.setGuide(3, 0);
        state.setText("将执行 Home 与固定点校验；使用已启用方案的点位。");
        try {
            UiUtils.submitUiMachineTask(() -> CoreXYIntegration.verifyFixedMarkers(configuration, listener(""), cancelled::get), directory -> {
                reportDirectory = directory;
                results.setText("本次 5 点校验已完成。\n\n" + CoreXYIntegration.getStartupStatus()
                        + "\n\n校验使用已启用方案中的固定点。\n报告与原始观测：" + directory
                        + "\n\n点击“打开校准与校验历史”查看历次校准、方案和校验数据。");
                tabs.setSelectedIndex(1);
                finish();
            }, this::showFailure);
        }
        catch (Exception failure) {
            showFailure(failure);
        }
    }

    private CalibrationController.Progress listener(String prefix) {
        return (percent, message) -> SwingUtilities.invokeLater(() -> {
            progress.setValue(percent);
            state.setText(prefix + message);
        });
    }

    private boolean begin() {
        return begin(true);
    }

    private boolean begin(boolean acquireWorkflow) {
        if (running) {
            return false;
        }
        if (acquireWorkflow && !CoreXYIntegration.acquireWorkflow()) {
            state.setText("另一项 CoreXY 校准或校验正在进行，请等待完成。");
            return false;
        }
        ownsWorkflow = acquireWorkflow;
        cancelled.set(false);
        running = true;
        currentRequest = null;
        reportDirectory = null;
        progress.setValue(0);
        setControls(false);
        return true;
    }

    private void finish() {
        running = false;
        if (ownsWorkflow) {
            ownsWorkflow = false;
            CoreXYIntegration.releaseWorkflow();
        }
        setControls(true);
        guide.setGuide(1, Math.max(0, table.getSelectedRow()));
    }

    private void setControls(boolean enabled) {
        for (java.awt.Component component : new java.awt.Component[] {table, diameter, target, conditioning, planeZ,
                homeOffset, homeRadius, autoVerify, record, save, start, verify, simulation, history}) {
            component.setEnabled(enabled);
        }
        stop.setEnabled(!enabled);
        report.setEnabled(enabled && reportDirectory != null);
        updateSetupReadiness();
    }

    private void updateSetupReadiness() {
        boolean complete = true;
        for (int row = 0; row < 5; row++) {
            for (int column = 2; column < 4; column++) {
                Object coordinate = coordinates.getValueAt(row, column);
                complete &= coordinate instanceof Number && Double.isFinite(((Number) coordinate).doubleValue());
            }
        }
        save.setEnabled(!running && complete);
        start.setEnabled(!running && complete);
    }

    private void requestCancellation() {
        cancelled.set(true);
        controller.cancel();
        state.setText("取消请求已发送，采集停止并恢复完成后可以关闭窗口。");
    }

    private void showFailure(Throwable failure) {
        CalibrationController.Report report = controller.getLastReport();
        if (report != null && report.request == currentRequest) {
            showReport(report);
        }
        finish();
        state.setText("操作未完成：" + failure.getMessage());
        if (!(failure instanceof CancellationException)) {
            UiUtils.showError(this, "5 点操作未完成", failure);
        }
    }

    private void saveInterruptedReport(CalibrationRequest request, Exception failure) {
        CalibrationController.Report report = controller.getLastReport();
        if (report != null && report.request == request) {
            try {
                reportDirectory = CoreXYIntegration.store(configuration).saveReport(report);
            }
            catch (Exception storageFailure) {
                failure.addSuppressed(storageFailure);
            }
        }
    }

    private void showReport(CalibrationController.Report report) {
        StringBuilder text = new StringBuilder(report.simulation ? "5 固定点离线演练（不代表实机精度，不启用生产补偿）\n\n" : "5 固定点自动校准结果\n\n");
        text.append(report.outcome).append('\n');
        text.append(String.format(Locale.ROOT, "测量噪声 %.4f mm · 等待时间 %d ms\n", report.noise, report.settlingMillis));
        if (report.chosen != null) {
            text.append("选用方案：").append(CoreXYWizard.layerNames(report.chosen.getLayers())).append('\n');
            appendMetrics(text, "补偿前独立路径", report.baselineFinal);
            appendMetrics(text, "补偿后独立路径", report.correctedFinal);
            text.append("目标 P95 ").append(report.request.target).append(" mm · ").append(report.targetReached ? "已达到" : "尚未达到").append('\n');
        }
        for (String note : report.notes) {
            text.append("\n• ").append(note);
        }
        text.append("\n\n校准、补偿方案与原始 CSV：").append(reportDirectory);
        if (report.enabled) {
            text.append("\n以后启动软件，补偿会在 Home 前预载；Home 的视觉步骤修正第 1 点复位偏移。");
            if (report.request.fixedMarkers.verifyOnStartup) {
                text.append("本次开启了每次启动的 5 点自动校验。");
            }
        }
        results.setText(text.toString());
        results.setCaretPosition(0);
        tabs.setSelectedIndex(1);
        state.setText(report.outcome);
    }

    private static void appendMetrics(StringBuilder text, String label, PrecisionMetrics metrics) {
        if (metrics != null) {
            text.append(String.format(Locale.ROOT, "%s：RMS %.4f · P95 %.4f · 最大 %.4f mm\n", label, metrics.rms, metrics.p95, metrics.maximum));
        }
    }

    private static FixedMarkerSetup demonstrationSettings() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        double[][] points = {{100, 100}, {140, 100}, {140, 140}, {100, 140}, {120, 120}};
        for (int i = 0; i < points.length; i++) {
            setup.markers.add(new FixedMarkerSetup.Marker(i == 0 ? "home" : "marker-" + (i + 1),
                    i == 0 ? "Home 视觉基准" : "覆盖点 " + (i + 1), points[i][0], points[i][1]));
        }
        return setup;
    }

    private static JSpinner number(double initial, double low, double high, double step) {
        return new JSpinner(new SpinnerNumberModel(initial, low, high, step));
    }

    private static double value(JSpinner spinner) {
        try {
            spinner.commitEdit();
        }
        catch (java.text.ParseException failure) {
            throw new IllegalArgumentException("请输入有效数字。", failure);
        }
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static void field(JPanel panel, String label, java.awt.Component input) {
        panel.add(new JLabel(label));
        panel.add(input);
    }
}
