package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.precision.CalibrationController;
import org.openpnp.corexy.precision.CalibrationRequest;
import org.openpnp.corexy.precision.DigitalTwinMachine;
import org.openpnp.corexy.precision.OpenPnpCalibrationMachine;
import org.openpnp.corexy.precision.PrecisionFitter;
import org.openpnp.corexy.precision.PrecisionMetrics;
import org.openpnp.corexy.precision.PrecisionRuntime;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.UiUtils;

/** Four actions: place marker, record its position, start, view verified results. */
public final class CoreXYWizard extends JDialog {
    private final Configuration configuration;
    private final CalibrationController controller = new CalibrationController();
    private final MarkerGuidePanel guide = new MarkerGuidePanel();
    private final JComboBox<String> plate = new JComboBox<>(new String[] {"5 × 5 标定板（推荐）", "单 marker（快速）", "9 × 9 标定板（精细）"});
    private final JSpinner diameter = number(1, .1, 10, .1);
    private final JSpinner pitch = number(10, 2, 100, 1);
    private final JSpinner target = number(.05, .001, 1, .005);
    private final JSpinner conditioning = number(48, 3, 128, 1);
    private final JSpinner angle = number(0, -180, 180, .1);
    private final JSpinner toolHeight = number(.5, .2, 10, .1);
    private final JTextField temperature = new JTextField();
    private final JCheckBox shaping = new JCheckBox("自动检查并校准控制器输入整形", true);
    private final JCheckBox tools = new JCheckBox("自动校准已配置的吸嘴与底部相机", true);
    private final JLabel position = new JLabel("尚未记录 marker 位置");
    private final JLabel state = new JLabel(CoreXYIntegration.getStartupStatus());
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea results = new JTextArea();
    private final JButton start = new JButton("开始自动校准");
    private final JButton simulation = new JButton("离线演练");
    private final JButton record = new JButton("使用当前相机位置");
    private final JButton stop = new JButton("取消并回滚");
    private final JTabbedPane tabs = new JTabbedPane();
    private Location origin;
    private boolean running;
    private Path reportDirectory;
    private java.util.Map<String, double[]> artifactOffsets = new java.util.LinkedHashMap<>();

    public CoreXYWizard(Frame owner, Configuration configuration) {
        super(owner, "CoreXY 自动精度校准", false);
        this.configuration = configuration;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 640));
        setSize(1010, 750);
        setLocationRelativeTo(owner);
        JPanel setup = new JPanel(new BorderLayout(18, 18));
        setup.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        setup.add(guide, BorderLayout.CENTER);
        JPanel inputs = new JPanel(new GridLayout(0, 2, 10, 12));
        JPanel inputArea = new JPanel(new BorderLayout(0, 16));
        inputArea.setPreferredSize(new Dimension(400, 320));
        field(inputs, "校准标记", plate);
        field(inputs, "圆形 marker 直径（mm）", diameter);
        field(inputs, "标定板点间距（mm）", pitch);
        field(inputs, "目标 P95 偏差（mm）", target);
        field(inputs, "已放置 marker", record);
        field(inputs, "当前位置", position);
        JPanel automatic = new JPanel(new GridLayout(0, 1, 0, 8));
        JButton fixedMarkers = new JButton("5 个固定 marker · Home 补偿与启动校验");
        automatic.add(fixedMarkers);
        automatic.add(shaping);
        automatic.add(tools);
        inputArea.add(inputs, BorderLayout.CENTER);
        inputArea.add(automatic, BorderLayout.SOUTH);
        setup.add(inputArea, BorderLayout.EAST);
        JLabel help = new JLabel("<html>固定 marker → 对齐顶部相机并记录位置 → 开始。软件会自动选方案，并在新路径上实测通过后启用。"
                + "<br>单 marker 适合换向校准；标定板覆盖的位置才能评估空间误差。离线演练不会修改生产补偿。</html>");
        setup.add(help, BorderLayout.SOUTH);
        tabs.addTab("1  准备与开始", setup);
        results.setEditable(false);
        results.setLineWrap(true);
        results.setWrapStyleWord(true);
        results.setMargin(new java.awt.Insets(14, 14, 14, 14));
        tabs.addTab("2  评估结果", new JScrollPane(results));
        JPanel advanced = new JPanel(new BorderLayout(12, 12));
        advanced.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JPanel options = new JPanel(new GridLayout(0, 2, 10, 16));
        field(options, "换向预行程（mm）", conditioning);
        field(options, "标定板相对机器 X 轴角度（°）", angle);
        field(options, "温度传感器 Actuator ID（可留空）", temperature);
        field(options, "吸嘴多高度校准的 Z 跨度（mm）", toolHeight);
        JButton selfCalibration = new JButton("标定板误差分离（四姿态引导）");
        JButton original = new JButton("原插件完整工具箱");
        JButton disable = new JButton("停用补偿并恢复轴设置");
        JButton reports = new JButton("打开本次报告目录");
        JButton board = new JButton("生成可打印的 marker 板");
        field(options, "有标准节距的标定板", selfCalibration);
        field(options, "高级调参与全部原功能", original);
        field(options, "参数回退", disable);
        field(options, "报告与原始数据", reports);
        field(options, "普通打印板用于相对精度评估", board);
        advanced.add(options, BorderLayout.NORTH);
        advanced.add(new JLabel("<html>温漂需要真实温度数据和足够温差。输入整形需要控制器及加速度计支持。"
                + "<br>缺少这些条件时会说明原因，并继续比较可用方案。吸嘴校准沿用已配置的原生视觉管线。"
                + "<br>打印板不提供绝对长度精度；绝对空间精度需要经过标定的节距基准。</html>"), BorderLayout.SOUTH);
        tabs.addTab("可选设置与工具", advanced);
        add(tabs, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(12, 18, 16, 18));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(simulation);
        buttons.add(stop);
        buttons.add(start);
        progress.setStringPainted(true);
        footer.add(state, BorderLayout.NORTH);
        footer.add(progress, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        start.setEnabled(false);
        stop.setEnabled(false);
        record.addActionListener(event -> recordOrigin());
        start.addActionListener(event -> run(false));
        simulation.addActionListener(event -> run(true));
        stop.addActionListener(event -> {
            controller.cancel();
            state.setText("正在停止采集并恢复配置，请等待完成。");
        });
        plate.addActionListener(event -> guide.setGuide(0, plate.getSelectedIndex() == 1 ? 1 : plate.getSelectedIndex() == 2 ? 9 : 5, 0));
        plate.addActionListener(event -> artifactOffsets.clear());
        pitch.addChangeListener(event -> artifactOffsets.clear());
        diameter.addChangeListener(event -> artifactOffsets.clear());
        reports.addActionListener(event -> UiUtils.messageBoxOnException(() -> {
            if (reportDirectory != null) {
                java.awt.Desktop.getDesktop().open(reportDirectory.toFile());
            }
        }));
        original.addActionListener(event -> {
            if (!running) {
                UiUtils.messageBoxOnException(() -> CoreXYIntegration.launchLegacy(configuration));
            }
        });
        disable.addActionListener(event -> {
            if (!running) {
                UiUtils.submitUiMachineTask(() -> {
                    CoreXYIntegration.disable(configuration);
                    return "补偿已停用，原轴设置已恢复。";
                }, message -> state.setText(message), failure -> UiUtils.showError(this, "恢复失败", failure));
            }
        });
        selfCalibration.addActionListener(event -> {
            if (!running) {
                UiUtils.messageBoxOnException(() -> new PlateCalibrationDialog(this, configuration, request(false), (offsets, pose) -> {
                    artifactOffsets = offsets;
                    origin = new Location(LengthUnit.Millimeters, pose.originX, pose.originY, pose.planeZ, 0);
                    angle.setValue(pose.plateAngle > 180 ? pose.plateAngle - 360 : pose.plateAngle);
                    position.setText(String.format(Locale.ROOT, "X %.3f · Y %.3f · Z %.3f", origin.getX(), origin.getY(), origin.getZ()));
                    start.setEnabled(true);
                    state.setText("标定板误差已分离，下次校准将使用校正后的 marker 坐标。");
                }).setVisible(true));
            }
        });
        board.addActionListener(event -> UiUtils.messageBoxOnException(() -> {
            Path file = CoreXYIntegration.store(configuration).getRoot().resolve("marker-board.svg");
            MarkerBoard.write(file, plate.getSelectedIndex() == 2 ? 9 : 5, value(pitch), value(diameter));
            java.awt.Desktop.getDesktop().browse(file.toUri());
        }));
        fixedMarkers.addActionListener(event -> {
            if (!running) {
                UiUtils.messageBoxOnException(() -> new FixedMarkerDialog(this, configuration).setVisible(true));
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (running) {
                    controller.cancel();
                    state.setText("取消请求已发送；回滚完成后可关闭窗口。");
                }
                else {
                    dispose();
                }
            }
        });
    }

    private void recordOrigin() {
        UiUtils.messageBoxOnException(() -> {
            origin = configuration.getMachine().getDefaultHead().getDefaultCamera().getLocation()
                    .convertToUnits(LengthUnit.Millimeters);
            position.setText(String.format(Locale.ROOT, "X %.3f · Y %.3f · Z %.3f", origin.getX(), origin.getY(), origin.getZ()));
            start.setEnabled(true);
            guide.setGuide(1, plate.getSelectedIndex() == 1 ? 1 : plate.getSelectedIndex() == 2 ? 9 : 5, 0);
        });
    }

    private CalibrationRequest request(boolean simulated) {
        CalibrationRequest request = new CalibrationRequest();
        if (!simulated && origin != null) {
            request.originX = origin.getX();
            request.originY = origin.getY();
            request.planeZ = origin.getZ();
        }
        request.spatial = plate.getSelectedIndex() != 1;
        request.gridSize = plate.getSelectedIndex() == 2 ? 9 : 5;
        request.markerDiameter = value(diameter);
        request.pitch = value(pitch);
        request.target = value(target);
        request.conditioningTravel = value(conditioning);
        request.plateAngle = value(angle);
        request.temperatureActuator = temperature.getText().trim();
        request.optimizeShaping = shaping.isSelected();
        request.calibrateTools = tools.isSelected();
        request.toolHeightSpan = value(toolHeight);
        request.artifactOffsets = simulated ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(artifactOffsets);
        request.validate();
        return request;
    }

    private void run(boolean simulated) {
        if (running) {
            return;
        }
        final CalibrationRequest request;
        try {
            request = request(simulated);
        }
        catch (Exception failure) {
            UiUtils.showError(this, "设置无效", failure);
            return;
        }
        if (!CoreXYIntegration.acquireWorkflow()) {
            state.setText("另一项 CoreXY 校准正在进行，请等待完成。");
            return;
        }
        setRunning(true);
        controller.prepareRun();
        guide.setGuide(2, request.spatial ? request.gridSize : 1, 0);
        CalibrationController.Progress listener = (percent, message) -> SwingUtilities.invokeLater(() -> {
            progress.setValue(percent);
            state.setText((simulated ? "离线演练 · " : "") + message);
        });
        if (simulated) {
            new SwingWorker<CalibrationController.Report, Void>() {
                @Override
                protected CalibrationController.Report doInBackground() throws Exception {
                    CalibrationController.Report report = controller.run(new DigitalTwinMachine(20260910), request, listener);
                    reportDirectory = CoreXYIntegration.store(configuration).saveReport(report);
                    return report;
                }
                @Override
                protected void done() {
                    try {
                        showReport(get());
                    }
                    catch (Exception failure) {
                        showFailure(failure.getCause() == null ? failure : failure.getCause());
                    }
                    finally {
                        setRunning(false);
                    }
                }
            }.execute();
        }
        else {
            UiUtils.submitUiMachineTask(() -> {
                OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
                try {
                    CalibrationController.Report report = controller.run(backend, request, listener);
                    reportDirectory = CoreXYIntegration.persistResult(configuration, backend, report);
                    return report;
                }
                catch (Exception failure) {
                    if (controller.getLastReport() != null) {
                        reportDirectory = CoreXYIntegration.store(configuration).saveReport(controller.getLastReport());
                    }
                    throw failure;
                }
            }, report -> {
                showReport(report);
                setRunning(false);
            }, failure -> {
                showFailure(failure);
                setRunning(false);
            });
        }
    }

    private void setRunning(boolean busy) {
        running = busy;
        if (!busy) {
            CoreXYIntegration.releaseWorkflow();
        }
        start.setEnabled(!busy && origin != null);
        simulation.setEnabled(!busy);
        record.setEnabled(!busy);
        stop.setEnabled(busy);
        tabs.setEnabledAt(2, !busy);
        plate.setEnabled(!busy);
        for (java.awt.Component option : new java.awt.Component[] {diameter, pitch, target, shaping, tools}) {
            option.setEnabled(!busy);
        }
    }

    private void showReport(CalibrationController.Report report) {
        StringBuilder text = new StringBuilder(report.simulation ? "离线演练结果（不能代表实机精度）\n\n" : "校准评估结果\n\n");
        text.append(report.outcome).append("\n");
        text.append(String.format(Locale.ROOT, "测量噪声：%.4f mm；等待时间：%d ms\n", report.noise, report.settlingMillis));
        if (report.previousMetrics != null) {
            text.append(metrics("此前已启用的方案", report.previousMetrics));
        }
        if (report.chosen != null) {
            text.append("选用模型：").append(layerNames(report.chosen.getLayers())).append("\n");
            text.append(metrics("补偿前第三组路径", report.baselineFinal));
            text.append(metrics("补偿后第三组路径", report.correctedFinal));
            text.append("目标 P95：").append(report.request.target).append(" mm · ")
                    .append(report.targetReached ? "已达到" : "尚未达到").append("\n");
        }
        if (report.evaluation != null) {
            text.append("\n候选模型（独立路径评估）\n");
            for (PrecisionFitter.Candidate candidate : report.evaluation.candidates) {
                text.append(candidate.acceptable ? "可用 · " : "拒绝 · ").append(layerNames(candidate.model.getLayers()))
                        .append(String.format(Locale.ROOT, " · P95 %.4f mm · 最大 %.4f mm\n",
                                candidate.validation.p95, candidate.validation.maximum)).append("  ").append(candidate.reason).append('\n');
            }
        }
        text.append("\n评估说明\n");
        for (String note : report.notes) {
            text.append("• ").append(note).append('\n');
        }
        if (reportDirectory != null) {
            text.append("\n报告和原始 CSV：").append(reportDirectory);
        }
        results.setText(text.toString());
        results.setCaretPosition(0);
        tabs.setSelectedIndex(1);
    }

    private void showFailure(Throwable failure) {
        CalibrationController.Report report = controller.getLastReport();
        if (report != null) {
            showReport(report);
        }
        state.setText("校准未完成：" + failure.getMessage());
        if (!(failure instanceof java.util.concurrent.CancellationException)) {
            UiUtils.showError(this, "校准未完成", failure);
        }
    }

    private static String metrics(String label, PrecisionMetrics value) {
        return String.format(Locale.ROOT, "%s：RMS %.4f · P95 %.4f · 最大 %.4f mm\n",
                label, value.rms, value.p95, value.maximum);
    }

    private static JSpinner number(double initial, double low, double high, double step) {
        return new JSpinner(new SpinnerNumberModel(initial, low, high, step));
    }

    private static double value(JSpinner spinner) {
        try {
            spinner.commitEdit();
        }
        catch (java.text.ParseException invalid) {
            throw new IllegalArgumentException("数字格式无效，请检查输入。", invalid);
        }
        return ((Number) spinner.getValue()).doubleValue();
    }

    static String layerNames(java.util.List<org.openpnp.corexy.precision.PrecisionModel.Layer> layers) {
        String[] names = {"公共偏置", "固定换向", "连续换向记忆", "A/B 联合状态", "几何误差", "空间网格",
                "速度与加速度", "温漂", "工具点", "换向状态空间网格"};
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (org.openpnp.corexy.precision.PrecisionModel.Layer layer : layers) {
            selected.add(names[layer.ordinal()]);
        }
        return String.join(" + ", selected);
    }

    private static void field(JPanel panel, String name, java.awt.Component component) {
        panel.add(new JLabel(name));
        panel.add(component);
    }
}
