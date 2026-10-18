/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

import org.openpnp.gui.support.ExperimentalGuide;
import org.openpnp.gui.support.ExperimentalGuide.Step;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.MotionPerformanceReport;
import org.openpnp.gui.support.MotionPerformanceReport.Mode;
import org.openpnp.gui.support.MotionPerformanceReport.Result;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.GcodeAsyncDriver;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.solutions.GcodeDriverSolutions;
import org.openpnp.gui.support.DynamicSafeMotionPanel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Driver;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.UiUtils;

/**
 * Experimental, guarded entry point for asynchronous motion and performance
 * calibration. Physical moves are never started by the animated walkthrough;
 * they require a separate explicit empty-nozzle and clear-area confirmation.
 */
@SuppressWarnings("serial")
public class ExperimentalMotionCenterDialog extends JDialog {
    private static final String PREF_EXPERIMENTAL_MODE = "OpenPnPPro.experimentalMode.v1";
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MainFrame frame;
    private final Configuration configuration;
    private final Machine machine;
    private final String[] steps = {
            "① 要提升什么", "② 安全与轴能力", "③ 异步驱动开销",
            "④ 建立低速基线", "⑤ 分级提高速度", "⑥ 连续与逐段等待",
            "⑦ 结论与候选速度", "⑧ 导出与复测"
    };
    private final JList<String> stepList = new JList<>(steps);
    private final JEditorPane explanation = new JEditorPane();
    private final AxisTableModel axisModel = new AxisTableModel();
    private final JTable axisTable = new JTable(axisModel);
    private final MotionResultTableModel resultModel = new MotionResultTableModel();
    private final JTable resultTable = new JTable(resultModel);
    private final JTextArea recommendations = new JTextArea();
    private final JTabbedPane details = new JTabbedPane();
    private final JComboBox<Driver> driverCombo = new JComboBox<>();
    private final JLabel driverState = statusLabel();
    private final JLabel machineState = statusLabel();
    private final JLabel safetyState = statusLabel();
    private final JLabel resultLabel = new JLabel("尚未运行测试");
    private final JSpinner spanSpinner = new JSpinner(new SpinnerNumberModel(10.0, 2.0, 50.0, 1.0));
    private final JSpinner speedSpinner = new JSpinner(new SpinnerNumberModel(0.60, 0.10, 1.00, 0.05));
    private final JSpinner cyclesSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 5, 1));
    private final JCheckBox emptyNozzle = new JCheckBox("确认吸嘴无料，运动范围内没有障碍物");
    private DynamicSafeMotionPanel dynamicSafeMotion;
    private final JButton convertButton = new JButton();
    private final JButton motionTestButton = new JButton("运行分级速度对比");
    private final JButton exportButton = new JButton("导出测速结果");
    private final JButton previousButton = new JButton("上一步");
    private final JButton nextButton = new JButton("下一步");
    private final JButton autoGuideButton = new JButton("自动浏览验证说明");
    private int stepIndex;
    private Timer autoGuideTimer;
    private ExperimentalGuide guide;
    private final List<Result> motionResults = new ArrayList<>();
    private Path lastExportDirectory;

    public static boolean isExperimentalModeEnabled() {
        return preferences().getBoolean(PREF_EXPERIMENTAL_MODE, false);
    }

    public static void setExperimentalModeEnabled(boolean enabled) {
        preferences().putBoolean(PREF_EXPERIMENTAL_MODE, enabled);
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(ExperimentalMotionCenterDialog.class);
    }

    public ExperimentalMotionCenterDialog(MainFrame frame, Configuration configuration) {
        super(frame, "实验模式 · 运动提速验证中心", false);
        this.frame = frame;
        this.configuration = configuration;
        this.machine = configuration.getMachine();
        buildUi();
        refreshAnalysis();
        showStep(0);
        guide = createGuide();
        setMinimumSize(new Dimension(1040, 700));
        setSize(new Dimension(1180, 760));
        setLocationRelativeTo(frame);
        guide.startOnFirstOpen();
    }

    private void buildUi() {
        setLayout(new BorderLayout(12, 12));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout(12, 6));
        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel badge = new JLabel("OPENPNP PRO · EXPERIMENTAL");
        badge.setForeground(new Color(0xD18A22));
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 12f));
        JLabel title = new JLabel("运动提速验证中心");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("用同一路线的分级测速，找出有实际收益的候选速度和等待瓶颈。");
        subtitle.setForeground(ModernTheme.mutedForeground());
        heading.add(badge);
        heading.add(Box.createVerticalStrut(3));
        heading.add(title);
        heading.add(Box.createVerticalStrut(3));
        heading.add(subtitle);
        header.add(heading, BorderLayout.WEST);

        JPanel states = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 4));
        states.add(machineState);
        states.add(driverState);
        states.add(safetyState);
        header.add(states, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        stepList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stepList.setFixedCellHeight(42);
        stepList.setBorder(BorderFactory.createTitledBorder("校准流程"));
        stepList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && stepList.getSelectedIndex() >= 0) {
                showStep(stepList.getSelectedIndex());
            }
        });

        explanation.setContentType("text/html");
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        explanation.setFont(explanation.getFont().deriveFont(14f));
        JScrollPane explanationScroll = new JScrollPane(explanation);
        explanationScroll.setBorder(BorderFactory.createEmptyBorder());
        explanationScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel workingArea = new JPanel(new BorderLayout(8, 8));
        workingArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        workingArea.add(createDriverPanel(), BorderLayout.NORTH);
        axisTable.setAutoCreateRowSorter(true);
        axisTable.setFillsViewportHeight(true);
        axisTable.setRowHeight(27);
        workingArea.add(new JScrollPane(axisTable), BorderLayout.CENTER);
        workingArea.add(createMotionTestPanel(), BorderLayout.SOUTH);

        details.addTab("速度对比结果", createResultPanel());
        details.addTab("测试设置与轴能力", workingArea);
        details.addTab("验证说明", explanationScroll);

        JScrollPane stepsScroll = new JScrollPane(stepList);
        stepsScroll.setPreferredSize(new Dimension(215, 500));
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stepsScroll, details);
        main.setResizeWeight(0.0);
        main.setDividerLocation(215);
        main.setContinuousLayout(true);
        add(main, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JLabel warning = new JLabel("测速不会自动写入轴参数；候选速度仍需真机稳定性复核。");
        warning.setForeground(new Color(0xC47A18));
        footer.add(warning, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        previousButton.addActionListener(event -> showStep(stepIndex - 1));
        nextButton.addActionListener(event -> showStep(stepIndex + 1));
        autoGuideButton.addActionListener(event -> runAutoGuide());
        JButton followGuide = new JButton("跟着做");
        followGuide.addActionListener(event -> guide.start());
        JButton refresh = new JButton("重新分析");
        refresh.addActionListener(event -> refreshAnalysis());
        exportButton.addActionListener(event -> exportResults());
        exportButton.setEnabled(false);
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        actions.add(autoGuideButton);
        actions.add(followGuide);
        actions.add(refresh);
        actions.add(exportButton);
        actions.add(previousButton);
        actions.add(nextButton);
        actions.add(close);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel purpose = new JLabel("测量目标：速度阶梯收益、命令提交开销、到位等待开销和理论效率；不把动画当成验证结果。");
        purpose.setForeground(ModernTheme.mutedForeground());
        panel.add(purpose, BorderLayout.NORTH);

        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(27);
        int[] resultWidths = {55, 90, 145, 65, 80, 95, 90, 85, 70, 110, 110, 220};
        for (int index = 0; index < resultWidths.length; index++) {
            resultTable.getColumnModel().getColumn(index).setPreferredWidth(resultWidths[index]);
        }
        JScrollPane resultsScroll = new JScrollPane(resultTable);
        resultsScroll.setBorder(BorderFactory.createTitledBorder("真实测速结果"));

        recommendations.setEditable(false);
        recommendations.setLineWrap(true);
        recommendations.setWrapStyleWord(true);
        recommendations.setText("尚未运行真实测速。先在“测试设置与轴能力”确认范围、最高速度和安全条件。\n\n"
                + "离线状态仍可查看轴能力，但不会生成伪造的速度、共振或回差结果。");
        JScrollPane recommendationScroll = new JScrollPane(recommendations);
        recommendationScroll.setBorder(BorderFactory.createTitledBorder("速度提升结论与验证边界"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                resultsScroll, recommendationScroll);
        split.setResizeWeight(0.58);
        split.setDividerLocation(285);
        split.setContinuousLayout(true);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private ExperimentalGuide createGuide() {
        return new ExperimentalGuide(this, "motion-center", "运动提速验证",
                new Step("理解真正测量目标",
                        "这里测速度阶梯收益、命令提交和到位等待，不再用动态示意图冒充运动结果。",
                        () -> recommendations, () -> stepIndex >= 0,
                        "显示测量目标", () -> showStep(0)),
                new Step("检查机器和轴能力",
                        "轴表显示最大速度、加速度和加加速度，理论时间会由这些真实配置计算。",
                        () -> machineState, () -> axisModel.hasLinearXy(),
                        "进入安全预检", () -> showStep(1)),
                new Step("识别异步驱动",
                        "结果会分别显示命令提交和到位等待，直接看异步队列是否减少串行等待。",
                        () -> driverCombo, () -> driverCombo.getSelectedItem() != null,
                        "查看异步驱动步骤", () -> showStep(2)),
                new Step("设置安全测试矩阵",
                        "选择中心±范围、最高速度和往返轮数。程序自动生成50%、75%、100%三档及逐段等待对照。",
                        () -> spanSpinner,
                        () -> ((Number) spanSpinner.getValue()).doubleValue() <= 15.0,
                        "显示运动基线", () -> showStep(3)),
                new Step("确认现场安全",
                        "仅当现场已清空、吸嘴无料、急停可用时勾选；离线时保持未勾选。",
                        () -> emptyNozzle, emptyNozzle::isSelected),
                new Step("运行分级速度对比",
                        "按钮会产生真实XY运动。每一档返回起点，最后比较连续队列与逐段等待。",
                        () -> motionTestButton,
                        () -> resultModel.getRowCount() > 0),
                new Step("查看速度提升结论",
                        "重点看吞吐提升、最后一档边际收益、理论效率和逐段等待损失。",
                        () -> resultTable, () -> resultModel.getRowCount() > 0,
                        "显示结果页", () -> details.setSelectedIndex(0)),
                new Step("导出并继续验证",
                        "CSV保留每档原始数据，TXT保存中文结论。没有传感器时不能把这些数据说成共振或回差测量。",
                        () -> exportButton, () -> lastExportDirectory != null,
                        "导出测速结果", () -> exportResults()));
    }
    private JPanel createDriverPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel("运动驱动："));
        driverCombo.setPreferredSize(new Dimension(245, 30));
        driverCombo.setRenderer(new DriverRenderer());
        driverCombo.addActionListener(event -> updateDriverButton());
        panel.add(driverCombo);
        convertButton.addActionListener(event -> convertSelectedDriver());
        panel.add(convertButton);
        JLabel help = new JLabel("转换前自动备份 machine.xml；机器必须处于断开状态。");
        help.setForeground(ModernTheme.mutedForeground());
        panel.add(help);
        return panel;
    }

    private JPanel createMotionTestPanel() {
        JPanel container = new JPanel(new BorderLayout(8, 6));
        JPanel settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        JPanel parameters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        parameters.add(new JLabel("中心±范围"));
        parameters.add(spanSpinner);
        parameters.add(new JLabel("mm"));
        parameters.add(new JLabel("最高速度比例"));
        parameters.add(speedSpinner);
        parameters.add(new JLabel("往返轮数"));
        parameters.add(cyclesSpinner);
        settings.add(parameters);
        dynamicSafeMotion = new DynamicSafeMotionPanel(SafetyWorkbenchStore.get(), machine,
                text -> recommendations.setText(text));
        settings.add(dynamicSafeMotion);
        JPanel execution = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        execution.add(emptyNozzle);
        motionTestButton.addActionListener(event -> runSafeMotionTest());
        execution.add(motionTestButton);
        JButton clear = new JButton("清空结果");
        clear.addActionListener(event -> clearResults());
        execution.add(clear);
        settings.add(execution);
        container.add(settings, BorderLayout.NORTH);
        resultLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 4));
        resultLabel.setForeground(ModernTheme.mutedForeground());
        container.add(resultLabel, BorderLayout.SOUTH);
        return container;
    }

    private void showStep(int requested) {
        stepIndex = Math.max(0, Math.min(steps.length - 1, requested));
        if (stepList.getSelectedIndex() != stepIndex) {
            stepList.setSelectedIndex(stepIndex);
        }
        previousButton.setEnabled(stepIndex > 0);
        nextButton.setEnabled(stepIndex < steps.length - 1);
        String foreground = ModernTheme.isDark() ? "#E7EDF4" : "#26323A";
        explanation.setText("<html><body style='font-family:sans-serif;font-size:14px;"
                + "line-height:1.5;color:" + foreground + ";margin:6px'>"
                + stepHtml(stepIndex) + "</body></html>");
        explanation.setCaretPosition(0);
    }

    private String stepHtml(int step) {
        switch (step) {
            case 0:
                return "<h2>目标是缩短真实路线耗时</h2>"
                        + "<p>中心只验证四件可以量化的内容：提高速度比例带来的实际吞吐收益、"
                        + "软件提交运动命令的耗时、控制器到位等待耗时、以及实测时间相对轴速度/加速度理论值的效率。</p>"
                        + "<p><b>不再伪装测量：</b>没有编码器、相机靶标或加速度计时，不显示虚构的共振、回差、丢步和物理稳定时间。</p>";
            case 1:
                return "<h2>理论时间来自真实轴能力</h2>"
                        + "<p>读取X/Y轴最大速度、加速度和加加速度，并在每次测试前检查所有目标是否可达。"
                        + "未连接、未回零、未选择工具或目标超限时不会启动运动。</p>"
                        + "<p>测试范围始终围绕当前位置，每档完成后回到起点。</p>";
            case 2:
                return "<h2>异步效果必须体现在数据里</h2>"
                        + "<p>连续队列测试只在整条路线末尾等待到位；逐段等待测试在每个目标后执行 WaitForStillstand。"
                        + "表格分别记录命令提交和到位等待，两种模式的差值就是流程中等待策略的实际代价。</p>"
                        + "<p>驱动类型会直接显示在结果表和 CSV 中。转换前后保持范围、速度和轮数一致再复测，"
                        + "才能看到异步驱动是否真正减少总耗时或提交/等待开销。</p>";
            case 3:
                return "<h2>第一档建立可重复低速基线</h2>"
                        + "<p>程序先移动到 Safe Z，以最高速度的50%运行同一XY往返路线，记录路线距离、总耗时、"
                        + "平均速度、命令提交和到位等待。该结果是后续档位的比较基准。</p>";
            case 4:
                return "<h2>分级提高速度，寻找收益平台</h2>"
                        + "<p>自动测试最高速度的50%、75%、100%三档。若最后一档比中间档改善不足5%，"
                        + "说明短距离路线已经受加速度、加加速度、控制器规划或协议开销限制，继续抬高最高速度意义很小。</p>";
            case 5:
                return "<h2>用逐段等待对照定位流程损失</h2>"
                        + "<p>最高档会再执行一次逐段到位路线。若它明显慢于连续队列，"
                        + "正式任务应减少无必要的同步等待，让运动规划器和异步驱动连续工作。</p>"
                        + "<p>这项对比测的是软件/控制器等待，不等同于物理振动稳定时间。</p>";
            case 6:
                return "<h2>候选速度由边际收益决定</h2>"
                        + "<p>中心根据低/中/高三档的耗时变化给出候选档，并显示实测/理论效率。"
                        + "效率过低时优先排查等待、驱动、控制器上限、加速度和加加速度，而不是盲目提高最高速度。</p>"
                        + "<p>候选值不会自动写入轴配置。</p>";
            default:
                return "<h2>导出原始结果再做复测</h2>"
                        + "<p>CSV保存每一档速度、距离、总耗时、提交耗时、等待耗时、理论时间和效率；"
                        + "TXT保存中文分析与验证边界。改变加速度、驱动或任务等待策略后运行相同矩阵即可直接比较。</p>"
                        + "<p><b>正式提速前：</b>必须继续检查声音、丢步、吸料、相机画面稳定和贴装精度。</p>";
        }
    }

    private void refreshAnalysis() {
        List<Driver> drivers = new ArrayList<>(machine.getDrivers());
        Driver selected = (Driver) driverCombo.getSelectedItem();
        driverCombo.setModel(new DefaultComboBoxModel<>(drivers.toArray(new Driver[0])));
        if (selected != null) {
            for (int index = 0; index < driverCombo.getItemCount(); index++) {
                if (driverCombo.getItemAt(index).getId().equals(selected.getId())) {
                    driverCombo.setSelectedIndex(index);
                    break;
                }
            }
        }
        axisModel.reload(machine.getAxes());
        setStatus(machineState, machine.isEnabled()
                ? (machine.isHomed() ? "机器：已连接/已回零" : "机器：已连接/未回零")
                : "机器：未连接", machine.isEnabled() && machine.isHomed());
        boolean xy = axisModel.hasLinearXy();
        setStatus(safetyState, xy ? "轴配置：已识别XY" : "轴配置：需检查", xy);
        if (dynamicSafeMotion != null) {
            dynamicSafeMotion.refresh();
        }
        updateDriverButton();
    }

    private void updateDriverButton() {
        Driver driver = (Driver) driverCombo.getSelectedItem();
        if (driver instanceof GcodeAsyncDriver) {
            setStatus(driverState, "所选驱动：异步", true);
            convertButton.setText("恢复普通驱动");
            convertButton.setEnabled(true);
        }
        else if (driver != null && driver.getClass() == GcodeDriver.class) {
            setStatus(driverState, "所选驱动：普通", false);
            convertButton.setText("升级为异步驱动");
            convertButton.setEnabled(true);
        }
        else {
            setStatus(driverState, driver == null ? "驱动：未选择" : "所选驱动：其他", false);
            convertButton.setText("此驱动不支持转换");
            convertButton.setEnabled(false);
        }
    }

    private void convertSelectedDriver() {
        Driver selected = (Driver) driverCombo.getSelectedItem();
        if (!(selected instanceof GcodeDriver)) {
            return;
        }
        if (machine.isEnabled()) {
            JOptionPane.showMessageDialog(this,
                    "请先停止任务并断开机器电源连接，再转换驱动。\n"
                            + "实验中心不会在机器已启用时替换运动驱动。",
                    "需要先断开机器", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean toAsync = !(selected instanceof GcodeAsyncDriver);
        int answer = JOptionPane.showConfirmDialog(this,
                (toAsync ? "将当前 GcodeDriver 升级为 GcodeAsyncDriver？"
                        : "将当前异步驱动恢复为普通 GcodeDriver？")
                        + "\n\n转换前会自动备份 machine.xml，并保留轴、执行器和驱动ID关联。",
                "确认驱动转换", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            File backup = backupMachineConfiguration();
            if (toAsync) {
                GcodeDriverSolutions.convertToAsync((GcodeDriver) selected);
            }
            else {
                GcodeDriverSolutions.convertToPlain((GcodeAsyncDriver) selected);
            }
            configuration.save();
            refreshAnalysis();
            JOptionPane.showMessageDialog(this,
                    (toAsync ? "异步驱动升级完成。" : "普通驱动恢复完成。")
                            + "\n备份位置：" + backup.getAbsolutePath()
                            + "\n\n连接机器前请运行“问题与解决方案”检查。",
                    "转换完成", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception exception) {
            UiUtils.showError(this, "驱动转换失败", exception);
            refreshAnalysis();
        }
    }

    private File backupMachineConfiguration() throws Exception {
        File source = new File(configuration.getConfigurationDirectory(), "machine.xml");
        if (!source.isFile()) {
            configuration.save();
        }
        File directory = new File(configuration.getConfigurationDirectory(),
                "pro-experimental-backups");
        Files.createDirectories(directory.toPath());
        File target = new File(directory,
                "machine-before-driver-conversion-" + BACKUP_TIME.format(LocalDateTime.now()) + ".xml");
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private void runSafeMotionTest() {
        if (!machine.isEnabled() || !machine.isHomed()) {
            JOptionPane.showMessageDialog(this, "机器必须已连接并完成回零。",
                    "不能开始运动测试", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!emptyNozzle.isSelected()) {
            JOptionPane.showMessageDialog(this, "请先确认吸嘴无料且测试范围没有障碍物。",
                    "需要安全确认", JOptionPane.WARNING_MESSAGE);
            return;
        }
        HeadMountable tool = frame.getMachineControls().getSelectedTool();
        if (tool == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个工具头或下视相机。",
                    "未选择工具", JOptionPane.WARNING_MESSAGE);
            return;
        }
        double span = ((Number) spanSpinner.getValue()).doubleValue();
        double maximumSpeed = ((Number) speedSpinner.getValue()).doubleValue();
        int cycles = ((Number) cyclesSpinner.getValue()).intValue();
        List<Double> speeds = MotionPerformanceReport.speedPlan(maximumSpeed);
        int routeCount = speeds.size() + 1;
        double totalDistance = routeCount * 8 * span * cycles;
        int answer = JOptionPane.showConfirmDialog(this,
                "工具将先移动到 Safe Z，再以当前位置为中心进行 ±" + span
                        + " mm 的X/Y空载往返。\n速度档位：" + describeSpeeds(speeds)
                        + "\n每档轮数：" + cycles + "，总计划距离："
                        + String.format(Locale.ROOT, "%.0f", totalDistance) + " mm"
                        + "\n最高档将增加一次逐段到位等待对照。"
                        + "\n\n确认急停可用、吸嘴无料、范围内没有障碍物。",
                "最后安全确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        motionTestButton.setEnabled(false);
        exportButton.setEnabled(false);
        resultLabel.setText("正在运行分级速度对比，请观察机器并保持急停可用…");
        details.setSelectedIndex(0);
        List<Result> batchResults = new ArrayList<>();
        Driver selectedDriver = (Driver) driverCombo.getSelectedItem();
        String driverType = selectedDriver == null ? "未选择"
                : selectedDriver.getClass().getSimpleName();
        int firstRun = motionResults.size() + 1;
        double xFeedrate = axisModel.feedrate(Axis.Type.X);
        double xAcceleration = axisModel.acceleration(Axis.Type.X);
        double yFeedrate = axisModel.feedrate(Axis.Type.Y);
        double yAcceleration = axisModel.acceleration(Axis.Type.Y);
        UiUtils.submitUiMachineTask(() -> {
            Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location xPlus = start.derive(start.getX() + span, null, null, null);
            Location xMinus = start.derive(start.getX() - span, null, null, null);
            Location yPlus = start.derive(null, start.getY() + span, null, null);
            Location yMinus = start.derive(null, start.getY() - span, null, null);
            Location[] targets = {xPlus, xMinus, start, yPlus, yMinus, start};
            for (Location target : targets) {
                if (!tool.isReachable(target)) {
                    throw new Exception("目标超出软限位或工具可达范围：" + target);
                }
            }
            tool.moveToSafeZ(Math.min(speeds.get(0), 0.35));
            tool.waitForCompletion(CompletionType.WaitForStillstand);
            int run = firstRun;
            for (double speed : speeds) {
                batchResults.add(measureRoute(tool, targets, span, cycles, speed,
                        Mode.CONTINUOUS_QUEUE, run++, driverType,
                        xFeedrate, xAcceleration, yFeedrate, yAcceleration));
            }
            batchResults.add(measureRoute(tool, targets, span, cycles,
                    speeds.get(speeds.size() - 1), Mode.WAIT_EACH_SEGMENT, run, driverType,
                    xFeedrate, xAcceleration, yFeedrate, yAcceleration));
            return batchResults;
        }, results -> {
            motionTestButton.setEnabled(true);
            appendResults(results);
            autoSaveResults();
            resultLabel.setText("分级测速完成，共 " + results.size()
                    + " 组；已返回起点。请查看速度收益和逐段等待损失。");
            showStep(6);
        }, throwable -> {
            motionTestButton.setEnabled(true);
            Result failed = new Result();
            failed.time = BACKUP_TIME.format(LocalDateTime.now());
            failed.run = firstRun + batchResults.size();
            failed.label = "测试中断";
            failed.driver = driverType;
            failed.tool = tool.getName();
            failed.spanMm = span;
            failed.cycles = cycles;
            failed.speedFraction = maximumSpeed;
            String message = throwable.getMessage();
            failed.error = message == null || message.trim().isEmpty()
                    ? throwable.getClass().getSimpleName() : message;
            batchResults.add(failed);
            if (!batchResults.isEmpty()) {
                appendResults(batchResults);
                autoSaveResults();
            }
            exportButton.setEnabled(!motionResults.isEmpty());
            resultLabel.setText("测速未全部完成；已保留完成的数据，参数没有改变。");
            UiUtils.showError(this, "分级运动测速失败", throwable);
        });
    }

    private Result measureRoute(HeadMountable tool, Location[] targets, double span,
            int cycles, double speed, Mode mode, int run, String driverType,
            double xFeedrate, double xAcceleration,
            double yFeedrate, double yAcceleration) throws Exception {
        Result result = new Result();
        result.time = BACKUP_TIME.format(LocalDateTime.now());
        result.run = run;
        result.mode = mode;
        result.label = mode == Mode.CONTINUOUS_QUEUE
                ? "连续队列 " + String.format(Locale.ROOT, "%.0f%%", speed * 100)
                : "逐段等待 " + String.format(Locale.ROOT, "%.0f%%", speed * 100);
        result.driver = driverType;
        result.tool = tool.getName();
        result.spanMm = span;
        result.cycles = cycles;
        result.speedFraction = speed;
        result.routeDistanceMm = 8 * span * cycles;
        result.predictedMs = MotionPerformanceReport.predictedRouteMillis(span, cycles, speed,
                xFeedrate, xAcceleration, yFeedrate, yAcceleration);

        long started = System.nanoTime();
        long commandNanos = 0;
        long waitNanos = 0;
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (Location target : targets) {
                long commandStarted = System.nanoTime();
                tool.moveTo(target.derive(null, null, Double.NaN, null), speed);
                commandNanos += System.nanoTime() - commandStarted;
                if (mode == Mode.WAIT_EACH_SEGMENT) {
                    long waitStarted = System.nanoTime();
                    tool.waitForCompletion(CompletionType.WaitForStillstand);
                    waitNanos += System.nanoTime() - waitStarted;
                }
            }
        }
        if (mode == Mode.CONTINUOUS_QUEUE) {
            long waitStarted = System.nanoTime();
            tool.waitForCompletion(CompletionType.WaitForStillstand);
            waitNanos += System.nanoTime() - waitStarted;
        }
        result.elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        result.commandSubmitMs = commandNanos / 1_000_000.0;
        result.completionWaitMs = waitNanos / 1_000_000.0;
        return result;
    }

    private void appendResults(List<Result> results) {
        motionResults.addAll(results);
        resultModel.setResults(motionResults);
        recommendations.setText(MotionPerformanceReport.analyze(motionResults));
        recommendations.setCaretPosition(0);
        exportButton.setEnabled(!motionResults.isEmpty());
        details.setSelectedIndex(0);
    }

    private void clearResults() {
        motionResults.clear();
        resultModel.setResults(motionResults);
        recommendations.setText("结果已清空。重新运行相同路线可建立新的速度基线。");
        resultLabel.setText("尚未运行测试");
        exportButton.setEnabled(false);
        lastExportDirectory = null;
    }

    private void autoSaveResults() {
        if (motionResults.isEmpty()) {
            return;
        }
        try {
            Path directory = configuration.getConfigurationDirectory().toPath()
                    .resolve("motion-diagnostics")
                    .resolve(BACKUP_TIME.format(LocalDateTime.now()));
            Files.createDirectories(directory);
            writeReports(directory.resolve("motion-performance.csv"));
            lastExportDirectory = directory;
        }
        catch (Exception error) {
            resultLabel.setText("测速完成，但自动保存失败：" + error.getMessage());
        }
    }

    private void exportResults() {
        if (motionResults.isEmpty()) {
            JOptionPane.showMessageDialog(this, "还没有可导出的真实测速结果。",
                    "运动测速", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File file = PathSelectionDialog.chooseSave(this, "motion-performance-export",
                "导出运动测速结果",
                new File("motion-performance-" + BACKUP_TIME.format(LocalDateTime.now()) + ".csv"),
                configuration.getConfigurationDirectory(),
                new javax.swing.filechooser.FileNameExtensionFilter("CSV 数据 (*.csv)", "csv"));
        if (file == null) {
            return;
        }
        try {
            Path csv = file.toPath();
            writeReports(csv);
            lastExportDirectory = csv.toAbsolutePath().getParent();
            resultLabel.setText("已导出CSV和中文分析：" + csv.toAbsolutePath());
        }
        catch (Exception error) {
            UiUtils.showError(this, "导出运动测速失败", error);
        }
    }

    private void writeReports(Path csv) throws Exception {
        Path parent = csv.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(csv, ("\ufeff" + MotionPerformanceReport.csv(motionResults))
                .getBytes(StandardCharsets.UTF_8));
        String name = csv.getFileName().toString();
        int dot = name.toLowerCase(Locale.ROOT).endsWith(".csv") ? name.length() - 4
                : name.length();
        Path analysis = csv.resolveSibling(name.substring(0, dot) + "-analysis.txt");
        Files.write(analysis, MotionPerformanceReport.analyze(motionResults)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String describeSpeeds(List<Double> speeds) {
        StringBuilder text = new StringBuilder();
        for (double speed : speeds) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(String.format(Locale.ROOT, "%.0f%%", speed * 100));
        }
        return text.toString();
    }

    private void runAutoGuide() {
        refreshAnalysis();
        if (autoGuideTimer != null && autoGuideTimer.isRunning()) {
            autoGuideTimer.stop();
            autoGuideButton.setText("自动浏览验证说明");
            return;
        }
        autoGuideButton.setText("停止自动浏览");
        showStep(0);
        autoGuideTimer = new Timer(1450, event -> {
            if (stepIndex >= steps.length - 1) {
                ((Timer) event.getSource()).stop();
                autoGuideButton.setText("重新浏览验证说明");
                return;
            }
            showStep(stepIndex + 1);
        });
        autoGuideTimer.start();
    }

    private static JLabel statusLabel() {
        JLabel label = new JLabel();
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return label;
    }

    private static void setStatus(JLabel label, String text, boolean good) {
        label.setText(text);
        label.setForeground(good ? new Color(0x155F45) : new Color(0x7A4C12));
        label.setBackground(good ? new Color(0xCDEEDD) : new Color(0xF5DFC0));
    }

    @Override
    public void dispose() {
        if (autoGuideTimer != null) {
            autoGuideTimer.stop();
        }
        super.dispose();
    }

    private static class DriverRenderer extends JLabel implements ListCellRenderer<Driver> {
        DriverRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Driver> list, Driver value,
                int index, boolean isSelected, boolean cellHasFocus) {
            setText(value == null ? "未选择"
                    : value.getName() + " · " + value.getClass().getSimpleName());
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    private static class AxisTableModel extends AbstractTableModel {
        private final String[] columns = {
                "轴", "类型", "速度 mm/s", "加速度 mm/s²", "加加速度 mm/s³", "回差方式", "回差 mm"
        };
        private final List<ReferenceControllerAxis> axes = new ArrayList<>();

        void reload(List<Axis> machineAxes) {
            axes.clear();
            for (Axis axis : machineAxes) {
                if (axis instanceof ReferenceControllerAxis) {
                    axes.add((ReferenceControllerAxis) axis);
                }
            }
            fireTableDataChanged();
        }

        boolean hasLinearXy() {
            boolean x = false;
            boolean y = false;
            for (ReferenceControllerAxis axis : axes) {
                x |= axis.getType() == Axis.Type.X;
                y |= axis.getType() == Axis.Type.Y;
            }
            return x && y;
        }

        double feedrate(Axis.Type type) {
            ReferenceControllerAxis axis = find(type);
            return axis == null ? Double.NaN
                    : axis.getFeedratePerSecond().convertToUnits(LengthUnit.Millimeters)
                            .getValue();
        }

        double acceleration(Axis.Type type) {
            ReferenceControllerAxis axis = find(type);
            return axis == null ? Double.NaN
                    : axis.getAccelerationPerSecond2().convertToUnits(LengthUnit.Millimeters)
                            .getValue();
        }

        private ReferenceControllerAxis find(Axis.Type type) {
            for (ReferenceControllerAxis axis : axes) {
                if (axis.getType() == type) {
                    return axis;
                }
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return axes.size();
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
            ReferenceControllerAxis axis = axes.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return axis.getName();
                case 1:
                    return axis.getType();
                case 2:
                    return format(axis.getFeedratePerSecond().convertToUnits(LengthUnit.Millimeters).getValue());
                case 3:
                    return format(axis.getAccelerationPerSecond2().convertToUnits(LengthUnit.Millimeters).getValue());
                case 4:
                    return format(axis.getJerkPerSecond3().convertToUnits(LengthUnit.Millimeters).getValue());
                case 5:
                    return axis.getBacklashCompensationMethod();
                default:
                    return format(axis.getBacklashOffset().convertToUnits(LengthUnit.Millimeters).getValue());
            }
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
    }

    private static class MotionResultTableModel extends AbstractTableModel {
        private final String[] columns = {"序号", "模式", "驱动", "速度", "路线 mm", "总耗时 ms",
                "平均 mm/s", "理论 ms", "效率", "命令提交 ms", "到位等待 ms", "状态"};
        private final List<Result> results = new ArrayList<>();

        void setResults(List<Result> values) {
            results.clear();
            results.addAll(values);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return results.size();
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
            Result result = results.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return result.run;
                case 1:
                    return result.mode == Mode.CONTINUOUS_QUEUE ? "连续队列" : "逐段等待";
                case 2:
                    return result.driver;
                case 3:
                    return String.format(Locale.ROOT, "%.0f%%", result.speedFraction * 100);
                case 4:
                    return format(result.routeDistanceMm);
                case 5:
                    return format(result.elapsedMs);
                case 6:
                    return format(result.averageSpeedMmPerSecond());
                case 7:
                    return format(result.predictedMs);
                case 8:
                    return format(result.efficiencyPercent()) + "%";
                case 9:
                    return format(result.commandSubmitMs);
                case 10:
                    return format(result.completionWaitMs);
                default:
                    return result.error == null || result.error.isEmpty() ? "完成" : result.error;
            }
        }

        private static String format(double value) {
            return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f", value) : "—";
        }
    }
}
