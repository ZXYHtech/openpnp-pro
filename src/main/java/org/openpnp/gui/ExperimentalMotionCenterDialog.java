/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.io.File;
import java.nio.file.Files;
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
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

import org.openpnp.gui.support.ModernTheme;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.GcodeAsyncDriver;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.solutions.GcodeDriverSolutions;
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
            "① 实验模式说明", "② 安全与配置预检", "③ GcodeAsyncDriver",
            "④ 建立运动基线", "⑤ 共振与降噪", "⑥ 回差与精度",
            "⑦ 分级运动配置", "⑧ 结果与应用"
    };
    private final JList<String> stepList = new JList<>(steps);
    private final JEditorPane explanation = new JEditorPane();
    private final MotionAnimation animation = new MotionAnimation();
    private final AxisTableModel axisModel = new AxisTableModel();
    private final JTable axisTable = new JTable(axisModel);
    private final JComboBox<Driver> driverCombo = new JComboBox<>();
    private final JLabel driverState = statusLabel();
    private final JLabel machineState = statusLabel();
    private final JLabel safetyState = statusLabel();
    private final JLabel resultLabel = new JLabel("尚未运行测试");
    private final JSpinner spanSpinner = new JSpinner(new SpinnerNumberModel(10.0, 2.0, 50.0, 1.0));
    private final JSpinner speedSpinner = new JSpinner(new SpinnerNumberModel(0.25, 0.05, 0.60, 0.05));
    private final JCheckBox emptyNozzle = new JCheckBox("确认吸嘴无料，运动范围内没有障碍物");
    private final JButton convertButton = new JButton();
    private final JButton motionTestButton = new JButton("运行安全空载往返测试");
    private final JButton previousButton = new JButton("上一步");
    private final JButton nextButton = new JButton("下一步");
    private final JButton autoGuideButton = new JButton("一键可视化预检");
    private int stepIndex;
    private Timer autoGuideTimer;

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
        super(frame, "实验模式 · 运动性能与补偿中心", false);
        this.frame = frame;
        this.configuration = configuration;
        this.machine = configuration.getMachine();
        buildUi();
        refreshAnalysis();
        showStep(0);
        setMinimumSize(new Dimension(1040, 700));
        setSize(new Dimension(1180, 760));
        setLocationRelativeTo(frame);
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
        JLabel title = new JLabel("运动性能与补偿中心");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("先备份、再预检、后运动；任何物理测试都需要单独确认。");
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

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.add(animation, BorderLayout.NORTH);
        explanation.setContentType("text/html");
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        explanation.setFont(explanation.getFont().deriveFont(14f));
        JScrollPane explanationScroll = new JScrollPane(explanation);
        explanationScroll.setBorder(BorderFactory.createTitledBorder("当前步骤"));
        explanationScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        center.add(explanationScroll, BorderLayout.CENTER);

        JPanel workingArea = new JPanel(new BorderLayout(8, 8));
        workingArea.setBorder(BorderFactory.createTitledBorder("实时分析与操作"));
        workingArea.add(createDriverPanel(), BorderLayout.NORTH);
        axisTable.setAutoCreateRowSorter(true);
        axisTable.setFillsViewportHeight(true);
        axisTable.setRowHeight(27);
        workingArea.add(new JScrollPane(axisTable), BorderLayout.CENTER);
        workingArea.add(createMotionTestPanel(), BorderLayout.SOUTH);
        center.add(workingArea, BorderLayout.SOUTH);

        JPanel main = new JPanel(new BorderLayout(12, 0));
        main.add(new JScrollPane(stepList), BorderLayout.WEST);
        main.add(center, BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JLabel warning = new JLabel("实验功能默认关闭；参数建议不会自动写入轴配置。");
        warning.setForeground(new Color(0xC47A18));
        footer.add(warning, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        previousButton.addActionListener(event -> showStep(stepIndex - 1));
        nextButton.addActionListener(event -> showStep(stepIndex + 1));
        autoGuideButton.addActionListener(event -> runAutoGuide());
        JButton refresh = new JButton("重新分析");
        refresh.addActionListener(event -> refreshAnalysis());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        actions.add(autoGuideButton);
        actions.add(refresh);
        actions.add(previousButton);
        actions.add(nextButton);
        actions.add(close);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
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
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        settings.add(new JLabel("往返范围"));
        settings.add(spanSpinner);
        settings.add(new JLabel("mm"));
        settings.add(new JLabel("速度比例"));
        settings.add(speedSpinner);
        settings.add(emptyNozzle);
        motionTestButton.addActionListener(event -> runSafeMotionTest());
        settings.add(motionTestButton);
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
        animation.setMode(stepIndex);
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
                return "<h2>实验模式不会偷偷改变机器</h2>"
                        + "<p>该中心用于提前体验和验证下一阶段的运动优化。默认关闭，所有建议先显示、"
                        + "再由你决定是否应用。物理运动测试必须确认吸嘴为空、区域无障碍，并且"
                        + "始终在当前坐标附近的小范围内进行。</p>"
                        + "<p><b>目标：</b>减少串口等待、降低方向切换冲击、建立速度基线，"
                        + "为后续相机稳定时间、振动和二维误差补偿留下统一入口。</p>";
            case 1:
                return "<h2>先判断是否具备安全测试条件</h2>"
                        + "<p>系统检查机器连接、回零状态、驱动类型、XY轴运动限制以及配置备份。"
                        + "未通过时只生成建议，不会启动运动。</p>"
                        + "<p>红色项目必须先处理；黄色项目可以继续查看方案，但不能运行空载测试。</p>";
            case 2:
                return "<h2>用异步驱动减少不必要的等待</h2>"
                        + "<p>选择普通 GcodeDriver 后点击“升级为异步驱动”。转换会完整保留驱动ID、"
                        + "G-code、串口、轴和执行器关联，并把超时调整为适合异步序列的范围。</p>"
                        + "<p>转换前强制备份 machine.xml。转换后仍可在这里一键恢复普通驱动。"
                        + "启用机器前建议运行“问题与解决方案”检查位置回报与确认命令。</p>";
            case 3:
                return "<h2>以当前位置建立空载运动基线</h2>"
                        + "<p>安全测试先升到 Safe Z，再围绕当前工具位置完成X、Y小范围往返，"
                        + "最后回到起点。记录总耗时、平均单程时间和是否能可靠等待到位。</p>"
                        + "<p>这一步不会自动提高速度，它只建立可重复比较的基准。</p>";
            case 4:
                return "<h2>把“快”与“到位后稳定”分开测量</h2>"
                        + "<p>当前版本先生成振动扫描路线和参数建议。后续接入加速度计后，"
                        + "同一页面将显示X/Y频谱、主共振和整形前后曲线；没有传感器时，"
                        + "使用下视相机测量Mark点在运动后的稳定时间。</p>"
                        + "<p>高速段优先动态扭矩，视觉对位段优先低冲击和短稳定时间。</p>";
            case 5:
                return "<h2>方向误差与吸嘴偏心分别处理</h2>"
                        + "<p>轴回差来自皮带、同步轮和导轨；吸嘴偏心来自吸嘴与旋转轴。"
                        + "中心会分别引用轴回差测试和吸嘴校准结果，避免用一种补偿掩盖另一种问题。</p>"
                        + "<p>表格中的回差方式和偏移仅用于诊断显示，不会自动覆盖现有参数。</p>";
            case 6:
                return "<h2>一台机器不应只有一套速度</h2>"
                        + "<p>建议生成长距离空载、普通器件、微小器件、底部视觉、贴装接近和"
                        + "相机拍照六种运动配置。远距离追求效率，接近飞达和PCB时降低冲击，"
                        + "避免高速到位后反而等待更久。</p>";
            default:
                return "<h2>先比较，再决定是否应用</h2>"
                        + "<p>最终页汇总驱动状态、各轴速度/加速度/加加速度、空载基线、"
                        + "回差配置和风险项。建议值必须通过多轮空载、带料和贴装验证后才进入正式配置。</p>"
                        + "<p><b>回退原则：</b>任何丢步、撞限位、吸料不稳或视觉等待增加，"
                        + "立即恢复备份并降低参数。</p>";
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
        boolean async = drivers.stream().anyMatch(driver -> driver instanceof GcodeAsyncDriver);
        setStatus(driverState, async ? "驱动：异步" : "驱动：待升级", async);
        boolean xy = axisModel.hasLinearXy();
        setStatus(safetyState, xy ? "轴配置：已识别XY" : "轴配置：需检查", xy);
        updateDriverButton();
    }

    private void updateDriverButton() {
        Driver driver = (Driver) driverCombo.getSelectedItem();
        if (driver instanceof GcodeAsyncDriver) {
            convertButton.setText("恢复普通驱动");
            convertButton.setEnabled(true);
        }
        else if (driver != null && driver.getClass() == GcodeDriver.class) {
            convertButton.setText("升级为异步驱动");
            convertButton.setEnabled(true);
        }
        else {
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
        double speed = ((Number) speedSpinner.getValue()).doubleValue();
        int answer = JOptionPane.showConfirmDialog(this,
                "工具将先移动到 Safe Z，再以当前位置为中心进行 ±" + span
                        + " mm 的X/Y空载往返。\n速度比例：" + speed
                        + "\n\n确认急停可用、吸嘴无料、范围内没有障碍物。",
                "最后安全确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        motionTestButton.setEnabled(false);
        resultLabel.setText("正在运行空载测试，请观察机器并保持急停可用…");
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
            tool.moveToSafeZ(Math.min(speed, 0.35));
            long started = System.nanoTime();
            for (int cycle = 0; cycle < 2; cycle++) {
                for (Location target : targets) {
                    tool.moveTo(target.derive(null, null, Double.NaN, null), speed);
                }
            }
            tool.waitForCompletion(CompletionType.WaitForStillstand);
            long elapsed = System.nanoTime() - started;
            return elapsed / 1_000_000.0;
        }, elapsedMs -> {
            motionTestButton.setEnabled(true);
            double oneWay = elapsedMs / 12.0;
            resultLabel.setText(String.format(Locale.ROOT,
                    "空载基线完成：总耗时 %.0f ms，平均每段 %.1f ms；已返回起点。"
                            + " 建议保存该结果后再逐级提高速度。", elapsedMs, oneWay));
            showStep(4);
        }, throwable -> {
            motionTestButton.setEnabled(true);
            resultLabel.setText("测试未完成，参数没有改变。");
            UiUtils.showError(this, "空载运动测试失败", throwable);
        });
    }

    private void runAutoGuide() {
        refreshAnalysis();
        if (autoGuideTimer != null && autoGuideTimer.isRunning()) {
            autoGuideTimer.stop();
            autoGuideButton.setText("一键可视化预检");
            return;
        }
        autoGuideButton.setText("停止自动演示");
        showStep(0);
        autoGuideTimer = new Timer(1450, event -> {
            if (stepIndex >= steps.length - 1) {
                ((Timer) event.getSource()).stop();
                autoGuideButton.setText("重新播放可视化预检");
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
        animation.stop();
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

    /** Animated, vector-only calibration illustration with no external assets. */
    private static class MotionAnimation extends JPanel {
        private final Timer timer = new Timer(35, event -> advance());
        private float phase;
        private int mode;

        MotionAnimation() {
            setPreferredSize(new Dimension(720, 175));
            setBorder(BorderFactory.createLineBorder(ModernTheme.border()));
            timer.start();
        }

        void setMode(int mode) {
            this.mode = mode;
            phase = 0f;
            repaint();
        }

        void stop() {
            timer.stop();
        }

        private void advance() {
            phase = (phase + 0.012f) % 1f;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ModernTheme.isDark() ? new Color(0x15202B) : new Color(0xEEF4F7));
            g.fillRect(0, 0, getWidth(), getHeight());
            paintMachine(g);
            paintGraph(g);
            paintCaption(g);
            g.dispose();
        }

        private void paintMachine(Graphics2D g) {
            int left = 34;
            int top = 29;
            int width = Math.max(260, getWidth() / 2 - 55);
            int height = 112;
            g.setColor(new Color(0x6F808B));
            g.setStroke(new BasicStroke(5f));
            g.drawRoundRect(left, top, width, height, 14, 14);
            g.drawLine(left + 12, top + 28, left + width - 12, top + 28);
            double travel = 0.5 - 0.5 * Math.cos(phase * Math.PI * 2);
            int headX = left + 25 + (int) ((width - 70) * travel);
            int vibration = mode == 4
                    ? Math.round((float) (Math.sin(phase * Math.PI * 18)
                            * 9 * (1.0 - phase)))
                    : 0;
            g.setColor(mode == 2 ? new Color(0xD18A22) : ModernTheme.ACCENT);
            g.fillRoundRect(headX + vibration, top + 15, 46, 48, 10, 10);
            g.setColor(new Color(0xD7E2E8));
            g.fillRect(headX + 19 + vibration, top + 58, 8, 35);
            g.setColor(ModernTheme.SUCCESS);
            g.fillOval(headX + 13 + vibration, top + 91, 20, 9);
            if (mode == 5) {
                g.setColor(new Color(0xD64550));
                g.drawLine(headX - 5, top + 104, headX + 10, top + 104);
                g.drawLine(headX + 35, top + 114, headX + 50, top + 114);
            }
        }

        private void paintGraph(Graphics2D g) {
            int x = getWidth() / 2 + 15;
            int y = 35;
            int width = Math.max(260, getWidth() / 2 - 45);
            int height = 100;
            g.setColor(ModernTheme.border());
            g.drawRoundRect(x, y, width, height, 12, 12);
            g.setColor(ModernTheme.mutedForeground());
            g.drawLine(x + 15, y + height / 2, x + width - 12, y + height / 2);
            g.setColor(mode == 5 ? new Color(0xD18A22) : ModernTheme.ACCENT);
            g.setStroke(new BasicStroke(2.3f));
            int lastX = x + 15;
            int lastY = y + height / 2;
            for (int pixel = 1; pixel < width - 28; pixel++) {
                double t = pixel / (double) (width - 28);
                double signal;
                if (mode == 4) {
                    signal = Math.sin(t * Math.PI * 15) * Math.exp(-t * 3.2);
                }
                else if (mode == 5) {
                    signal = t < 0.5 ? -0.35 : 0.35;
                }
                else {
                    signal = Math.sin(t * Math.PI) * (mode == 3 ? 0.75 : 0.45);
                }
                int nextX = x + 15 + pixel;
                int nextY = y + height / 2 - (int) (signal * 42);
                g.drawLine(lastX, lastY, nextX, nextY);
                lastX = nextX;
                lastY = nextY;
            }
            int cursor = x + 15 + (int) ((width - 28) * phase);
            g.setColor(new Color(0x19A974));
            g.fillOval(cursor - 4, lastY - 4, 8, 8);
        }

        private void paintCaption(Graphics2D g) {
            String[] captions = {
                    "实验功能隔离运行", "安全预检与软限位", "异步发送 · 按需等待",
                    "XY空载往返基线", "振动衰减与稳定时间", "方向回差分离测量",
                    "长移 / 接近 / 视觉分级", "对比结果后再应用"
            };
            g.setColor(ModernTheme.mutedForeground());
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.drawString(captions[Math.max(0, Math.min(captions.length - 1, mode))],
                    getWidth() / 2 - 80, getHeight() - 13);
        }
    }
}
