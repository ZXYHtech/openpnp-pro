package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.components.CardinalDirectionDial;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.FeederAutoAlignConfidence;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.TaskFeederInspectionSupport;
import org.openpnp.gui.support.TaskAutoFeederVision;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.gui.support.TaskFeederAutoAlignSupport;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;

/** Modeless, task-only workflow for inspecting and correcting feeder pick XY locations. */
@SuppressWarnings("serial")
public class TaskFeederInspectionDialog extends JDialog {
    private static final String PREF_GLOBAL_CONFIDENCE = "globalAutoAlignConfidencePercent";
    private enum State {
        Pending("待检验"), Current("正在检验"), AutoAligning("自动识别中"),
        Verified("已检验"), ManualRequired("需手动"), Skipped("已跳过"), Error("检验失败");

        private final String label;

        State(String label) {
            this.label = label;
        }
    }

    private static final class Entry {
        private final Feeder feeder;
        private State state;
        private boolean skipped;
        private String pickText;

        private Entry(Feeder feeder) {
            this.feeder = feeder;
            skipped = !feeder.isEnabled() || !(feeder instanceof ReferenceFeeder);
            state = skipped ? State.Skipped : State.Pending;
            updatePickText();
        }

        private void updatePickText() {
            try {
                Location location = feeder.getPickLocation().convertToUnits(LengthUnit.Millimeters);
                pickText = String.format(Locale.ROOT, "X %.3f  Y %.3f  R %.1f°",
                        location.getX(), location.getY(), location.getRotation());
            }
            catch (Exception exception) {
                pickText = "无法读取拾取点";
            }
        }
    }

    private final MainFrame mainFrame;
    private final FeedersPanel feedersPanel;
    private final Runnable closeCallback;
    private final List<Entry> entries = new ArrayList<>();
    private final InspectionTableModel tableModel = new InspectionTableModel();
    private final JTable table = new JTable(tableModel);
    private final CardinalDirectionDial directionDial = new CardinalDirectionDial();
    private final JLabel currentLabel = new JLabel("等待开始");
    private final JTextArea statusText = new JTextArea();
    private final JButton completeNextButton = new JButton("完成并检验下一项");
    private final JButton completeExitButton = new JButton("完成退出");
    private final JButton skipButton = new JButton("跳过当前项");
    private final JButton resumeButton = new JButton("从选中项继续");
    private final JButton autoAlignButton = new JButton("自动矫正全部");
    private final Preferences prefs = Preferences.userNodeForPackage(TaskFeederInspectionDialog.class);
    private final JSpinner globalConfidence = new JSpinner(new SpinnerNumberModel(
            80.0, FeederAutoAlignConfidence.MIN_PERCENT,
            FeederAutoAlignConfidence.MAX_PERCENT, 1.0));
    private final JCheckBox feederConfidenceOverride = new JCheckBox("自定义最终阈值");
    private final JSpinner feederConfidencePercent = new JSpinner(new SpinnerNumberModel(
            80.0, FeederAutoAlignConfidence.MIN_PERCENT,
            FeederAutoAlignConfidence.MAX_PERCENT, 1.0));
    private final JLabel effectiveConfidence = new JLabel("有效阈值 80%", SwingConstants.CENTER);
    private final Timer topCameraGuard;

    private int currentIndex = -1;
    private boolean busy;
    private boolean closing;
    private boolean resumeForwardOnly;
    private int resumeStartIndex = -1;
    private boolean autoRunning;
    private boolean autoStopRequested;
    private int autoCursor = -1;
    private int autoAlignedCount;
    private int autoFailedCount;
    private int autoManualCount;
    private Camera topCamera;
    private CameraView topCameraView;
    private boolean confidenceGuard;
    private volatile double globalConfidencePercent;

    public TaskFeederInspectionDialog(MainFrame owner, FeedersPanel feedersPanel,
            List<Feeder> taskFeeders, Runnable closeCallback) {
        super(owner, "仅任务飞达快速检验", ModalityType.MODELESS);
        mainFrame = owner;
        this.feedersPanel = feedersPanel;
        this.closeCallback = closeCallback;
        globalConfidencePercent = FeederAutoAlignConfidence.resolvePercent(
                prefs.getDouble(PREF_GLOBAL_CONFIDENCE, 80.0), 0);
        globalConfidence.setValue(globalConfidencePercent);
        for (Feeder feeder : taskFeeders) {
            entries.add(new Entry(feeder));
        }
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(840, 560));
        setPreferredSize(new Dimension(980, 650));
        createUi();
        pack();
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWorkflow();
            }
        });
        topCameraGuard = new Timer(500, event -> {
            if (isVisible() && currentIndex >= 0 && !busy) {
                activateTopCameraView();
            }
        });
        topCameraGuard.start();
        SwingUtilities.invokeLater(() -> inspectIndex(nextPendingIndex(-1)));
    }

    private void createUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel header = new JPanel(new BorderLayout(10, 4));
        JLabel title = new JLabel("仅检验当前任务需要的飞达");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3f));
        header.add(title, BorderLayout.NORTH);
        JLabel introduction = new JLabel(
                "点击飞达立即前往；可从已完成项重新顺序检验。自动矫正按条带定位孔工作，只反写 XY/方向，Z 始终不变。");
        introduction.setForeground(ModernTheme.mutedForeground());
        header.add(introduction, BorderLayout.CENTER);
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        resumeButton.setToolTipText("按任务原始顺序，从表格当前选中项重新开始并继续到列表末尾");
        resumeButton.addActionListener(event -> resumeFromSelected());
        autoAlignButton.setToolTipText("条带飞达识别定位孔；自动飞达识别已出料元件中心；其他类型保留手动检验");
        autoAlignButton.addActionListener(event -> startOrStopAutoAlignment());
        headerActions.add(new JLabel("全局最低置信度："));
        headerActions.add(globalConfidence);
        headerActions.add(new JLabel("%"));
        headerActions.add(resumeButton);
        headerActions.add(autoAlignButton);
        header.add(headerActions, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        configureTable();
        JPanel directionPanel = createDirectionPanel();
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(table), directionPanel);
        splitPane.setResizeWeight(0.68);
        splitPane.setDividerLocation(650);
        splitPane.setContinuousLayout(true);
        root.add(splitPane, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 8));
        statusText.setEditable(false);
        statusText.setOpaque(false);
        statusText.setLineWrap(true);
        statusText.setWrapStyleWord(true);
        statusText.setRows(2);
        statusText.setText("准备移动到第一个待检飞达。可使用控制面板或相机画面拖动进行 XY 对位。");
        footer.add(statusText, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        skipButton.addActionListener(event -> skipCurrent());
        completeNextButton.setIcon(Icons.centerCameraMoveNext);
        completeNextButton.addActionListener(event -> completeCurrent(true));
        completeExitButton.setIcon(Icons.accept);
        completeExitButton.addActionListener(event -> completeCurrent(false));
        actions.add(skipButton);
        actions.add(completeNextButton);
        actions.add(completeExitButton);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        updateActions();
        globalConfidence.addChangeListener(event -> {
            globalConfidencePercent = ((Number) globalConfidence.getValue()).doubleValue();
            prefs.putDouble(PREF_GLOBAL_CONFIDENCE, globalConfidencePercent);
            updateConfidenceControls();
        });
        feederConfidenceOverride.addActionListener(event -> saveFeederConfidenceOverride());
        feederConfidencePercent.addChangeListener(event -> saveFeederConfidenceOverride());
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(34);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(88);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(240);
        table.getColumnModel().getColumn(4).setPreferredWidth(58);
        table.getColumnModel().getColumn(0).setCellRenderer(new StatusRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int viewRow = table.rowAtPoint(event.getPoint());
                int viewColumn = table.columnAtPoint(event.getPoint());
                if (viewRow < 0 || viewColumn < 0
                        || table.convertColumnIndexToModel(viewColumn) == 4) {
                    return;
                }
                resumeForwardOnly = false;
                resumeStartIndex = -1;
                inspectIndex(table.convertRowIndexToModel(viewRow));
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            updateActions();
            updateConfidenceControls();
        });
    }

    private JPanel createDirectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernTheme.border()),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        currentLabel.setHorizontalAlignment(SwingConstants.CENTER);
        currentLabel.setFont(currentLabel.getFont().deriveFont(Font.BOLD));
        panel.add(currentLabel, constraints);
        constraints.gridy++;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(8, 0, 8, 0);
        panel.add(directionDial, constraints);
        constraints.gridy++;
        constraints.weighty = 0;
        constraints.insets = new Insets(0, 0, 6, 0);
        JPanel confidencePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        confidencePanel.add(feederConfidenceOverride);
        confidencePanel.add(feederConfidencePercent);
        confidencePanel.add(new JLabel("%"));
        panel.add(confidencePanel, constraints);
        constraints.gridy++;
        feederConfidenceOverride.setToolTipText(
                "未勾选时跟随全局；勾选后直接填写本飞达最终阈值，不做百分比换算。");
        feederConfidencePercent.setToolTipText("允许 50%～99.5%，不会超过 100%。");
        effectiveConfidence.setToolTipText(
                "这里只使用一个最终阈值：跟随全局，或由本飞达直接覆盖。");
        panel.add(effectiveConfidence, constraints);
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 0, 0);
        JTextArea help = new JTextArea(
                "方向仪表盘\n按相机蓝线：↑ / ← / ↓ / → 对应 0° / 90° / 180° / 270°。"
                + "不点击则保留飞达现有角度。方向以原生相机蓝色方向线为参照。");
        help.setEditable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setRows(4);
        panel.add(help, constraints);
        directionDial.setDirectionListener(direction -> statusText.setText(
                "方向将覆盖为 " + direction + "°；完成检验时与 XY 一起保存，Z 不改变。"));
        return panel;
    }

    private void inspectIndex(int index) {
        if (closing || busy || index < 0 || index >= entries.size()) {
            if (index < 0) {
                statusText.setText("所有可检验飞达均已完成或跳过。可点击“完成退出”关闭窗口。");
                currentIndex = -1;
                uninstallCameraControls();
                updateActions();
            }
            return;
        }
        Entry entry = entries.get(index);
        if (entry.skipped || !(entry.feeder instanceof ReferenceFeeder)) {
            statusText.setText(entry.feeder.getName() + " 已跳过，未执行移动或修改配置。");
            return;
        }
        if (currentIndex >= 0 && currentIndex != index) {
            Entry previous = entries.get(currentIndex);
            if (previous.state == State.Current) {
                previous.state = State.Pending;
            }
        }
        currentIndex = index;
        entry.state = State.Current;
        tableModel.fireTableDataChanged();
        selectTableIndex(index);
        ReferenceFeeder feeder = (ReferenceFeeder) entry.feeder;
        directionDial.setFeederRotation(feeder.getLocation().getRotation());
        updateConfidenceControls();
        currentLabel.setText(entry.feeder.getName() + " / "
                + (entry.feeder.getPart() == null ? "未配置元件" : entry.feeder.getPart().getId()));
        busy = true;
        updateActions();
        statusText.setText("正在安全移动顶部相机到 " + entry.feeder.getName() + " 的当前拾取点……");
        UiUtils.submitUiMachineTask(() -> moveTopCameraTo(entry.feeder), camera -> {
            if (closing) {
                return;
            }
            topCamera = camera;
            topCameraView = activateTopCameraView();
            if (topCameraView == null) {
                entry.state = State.Error;
                busy = false;
                tableModel.fireTableRowsUpdated(index, index);
                updateActions();
                UiUtils.showError(this, "无法显示顶部相机",
                        new Exception("相机已经移动，但主相机区域无法切换到顶部相机。"));
                return;
            }
            installCameraControls();
            busy = false;
            updateActions();
            statusText.setText("已到达 " + entry.feeder.getName()
                    + "。请用控制面板或相机拖动对准真实拾取中心；完成按钮也位于相机画面下方中央。");
        }, failure -> {
            if (closing) {
                return;
            }
            entry.state = State.Error;
            busy = false;
            tableModel.fireTableRowsUpdated(index, index);
            updateActions();
            UiUtils.showError(this, "无法移动到飞达拾取点", failure);
        });
    }

    private Camera moveTopCameraTo(Feeder feeder) throws Exception {
        Camera camera = getSelectedTopCamera();
        Nozzle nozzle;
        try {
            nozzle = FeedersPanel.getCompatibleNozzleAndTip(feeder, false);
        }
        catch (Exception exception) {
            nozzle = mainFrame.getMachineControls().getSelectedNozzle();
        }
        Location pickLocation = feedersPanel.preliminaryPickLocation(feeder, nozzle);
        MovableUtils.moveToLocationAtSafeZ(camera, pickLocation);
        MovableUtils.fireTargetedUserAction(camera);
        return camera;
    }

    private Camera getSelectedTopCamera() throws Exception {
        if (mainFrame.getMachineControls().getSelectedTool() == null
                || mainFrame.getMachineControls().getSelectedTool().getHead() == null) {
            throw new Exception("当前没有选中带顶部相机的贴装头工具。");
        }
        Camera camera = mainFrame.getMachineControls().getSelectedTool().getHead()
                .getDefaultCamera();
        if (camera == null) {
            throw new Exception("当前贴装头没有配置默认顶部相机。");
        }
        return camera;
    }

    private CameraView activateTopCameraView() {
        if (topCamera == null) {
            return null;
        }
        if (mainFrame.getMachineControls().getSelectedTool() != topCamera) {
            mainFrame.getMachineControls().setSelectedTool(topCamera);
        }
        return mainFrame.getCameraViews().setSelectedCamera(topCamera);
    }

    private void installCameraControls() {
        if (topCameraView == null) {
            return;
        }
        topCameraView.setGuidedInspectionControlListener(
                new CameraView.GuidedInspectionControlListener() {
                    @Override
                    public void completeAndNext() {
                        completeCurrent(true);
                    }

                    @Override
                    public void completeAndExit() {
                        completeCurrent(false);
                    }
                });
    }

    private void uninstallCameraControls() {
        if (topCameraView != null) {
            topCameraView.setGuidedInspectionControlListener(null);
        }
        topCameraView = null;
    }

    private void completeCurrent(boolean inspectNext) {
        if (busy) {
            return;
        }
        if (currentIndex < 0) {
            if (inspectNext) {
                inspectIndex(nextPendingIndex(-1));
            }
            else {
                closeWorkflow();
            }
            return;
        }
        Entry entry = entries.get(currentIndex);
        if (!(entry.feeder instanceof ReferenceFeeder) || topCamera == null) {
            UiUtils.showError(this, "无法保存飞达检验",
                    new Exception("当前飞达或顶部相机不支持位置检验。"));
            return;
        }
        CameraView activeView = activateTopCameraView();
        if (activeView == null || mainFrame.getCameraViews().getSelectedCamera() != topCamera) {
            UiUtils.showError(this, "无法保存飞达检验",
                    new Exception("顶部相机没有成为当前活动画面，系统未读取偏移。"));
            return;
        }
        ReferenceFeeder feeder = (ReferenceFeeder) entry.feeder;
        TaskFeederInspectionSupport.Result result = null;
        try {
            result = TaskFeederInspectionSupport.applyXyCorrection(feeder,
                    topCamera.getLocation(), directionDial.getRotationOverride());
            if (!mainFrame.saveConfig()) {
                result.rollback(feeder);
                throw new Exception("新位置已在内存中回滚，因为机器配置文件保存失败。");
            }
        }
        catch (Exception exception) {
            if (result != null) {
                result.rollback(feeder);
            }
            entry.state = State.Error;
            tableModel.fireTableRowsUpdated(currentIndex, currentIndex);
            UiUtils.showError(this, "无法保存飞达检验", exception);
            return;
        }

        entry.state = State.Verified;
        entry.updatePickText();
        feedersPanel.refresh(entry.feeder);
        tableModel.fireTableRowsUpdated(currentIndex, currentIndex);
        Location oldBase = result.originalBase.convertToUnits(LengthUnit.Millimeters);
        Location oldPick = result.predictedPick.convertToUnits(LengthUnit.Millimeters);
        Location newPick = result.correctedPick.convertToUnits(LengthUnit.Millimeters);
        statusText.setText(String.format(Locale.ROOT,
                "%s 已保存：拾取 X %.3f → %.3f，Y %.3f → %.3f mm；Z %.3f mm 保持不变；"
                + "方向 %.1f° → %.1f°。%s",
                entry.feeder.getName(), oldPick.getX(), newPick.getX(), oldPick.getY(),
                newPick.getY(), oldBase.getZ(), oldBase.getRotation(),
                result.correctedBase.getRotation(), result.usesSprocketHoleGeometry()
                        ? " 条带飞达已整体平移首、末定位孔。" : ""));
        int completedIndex = currentIndex;
        currentIndex = -1;
        if (inspectNext) {
            int next = nextPendingIndex(completedIndex);
            if (next >= 0) {
                inspectIndex(next);
            }
            else {
                uninstallCameraControls();
                currentLabel.setText("任务飞达检验完成");
                updateActions();
            }
        }
        else {
            closeWorkflow();
        }
    }

    private void skipCurrent() {
        if (currentIndex < 0 || busy) {
            return;
        }
        skipIndex(currentIndex, true);
    }

    private void skipIndex(int index, boolean skipped) {
        if (busy || index < 0 || index >= entries.size()) {
            return;
        }
        Entry entry = entries.get(index);
        entry.skipped = skipped;
        entry.state = skipped ? State.Skipped : State.Pending;
        tableModel.fireTableRowsUpdated(index, index);
        if (skipped && currentIndex == index) {
            currentIndex = -1;
            uninstallCameraControls();
            statusText.setText(entry.feeder.getName() + " 已跳过，未修改任何坐标。");
            inspectIndex(nextPendingIndex(index));
        }
    }

    private int nextPendingIndex(int after) {
        if (resumeForwardOnly) {
            int first = Math.max(after + 1, resumeStartIndex);
            for (int index = first; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                if (!entry.skipped && entry.state != State.Verified) {
                    return index;
                }
            }
            return -1;
        }
        for (int offset = 1; offset <= entries.size(); offset++) {
            int index = (after + offset) % entries.size();
            Entry entry = entries.get(index);
            if (!entry.skipped && entry.state != State.Verified) {
                return index;
            }
        }
        return -1;
    }

    private void selectTableIndex(int modelIndex) {
        int viewIndex = table.convertRowIndexToView(modelIndex);
        if (viewIndex >= 0) {
            table.getSelectionModel().setSelectionInterval(viewIndex, viewIndex);
            table.scrollRectToVisible(table.getCellRect(viewIndex, 0, true));
        }
    }

    private void updateActions() {
        boolean canComplete = !busy && currentIndex >= 0;
        completeNextButton.setEnabled(canComplete);
        completeExitButton.setEnabled(!busy);
        skipButton.setEnabled(canComplete);
        resumeButton.setEnabled(!busy && !autoRunning && table.getSelectedRow() >= 0);
        autoAlignButton.setEnabled(autoRunning || !busy);
        autoAlignButton.setText(autoRunning ? "停止自动矫正" : "自动矫正全部");
    }

    private void resumeFromSelected() {
        if (busy || autoRunning) {
            return;
        }
        int viewIndex = table.getSelectedRow();
        if (viewIndex < 0) {
            statusText.setText("请先在列表中选择要重新开始的飞达。");
            return;
        }
        int index = table.convertRowIndexToModel(viewIndex);
        Entry selected = entries.get(index);
        if (selected.skipped) {
            statusText.setText(selected.feeder.getName() + " 已设置为跳过，请先取消“跳过”。");
            return;
        }
        if (currentIndex >= 0 && entries.get(currentIndex).state == State.Current) {
            entries.get(currentIndex).state = State.Pending;
        }
        uninstallCameraControls();
        currentIndex = -1;
        for (int item = index; item < entries.size(); item++) {
            Entry entry = entries.get(item);
            if (!entry.skipped) {
                entry.state = State.Pending;
            }
        }
        resumeForwardOnly = true;
        resumeStartIndex = index;
        tableModel.fireTableDataChanged();
        statusText.setText("将按任务原始顺序从 " + selected.feeder.getName()
                + " 重新检验到列表末尾；此前项目的完成状态保持不变。");
        inspectIndex(index);
    }

    private static final class AutoMeasurement {
        private final Camera camera;
        private final TaskFeederAutoAlignSupport.Result stripResult;
        private final TaskAutoFeederVision.Result autoFeederResult;

        private AutoMeasurement(Camera camera, TaskFeederAutoAlignSupport.Result result) {
            this.camera = camera;
            stripResult = result;
            autoFeederResult = null;
        }

        private AutoMeasurement(Camera camera, TaskAutoFeederVision.Result result) {
            this.camera = camera;
            stripResult = null;
            autoFeederResult = result;
        }
    }

    private void startOrStopAutoAlignment() {
        if (autoRunning) {
            autoStopRequested = true;
            statusText.setText("已请求停止；当前飞达完成安全识别后停止，不再移动到下一项。");
            return;
        }
        if (busy) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "自动矫正将依次检查当前任务中的条带飞达和自动飞达。\n\n"
                + "条带飞达：识别首、末 EIA-481 定位孔并验证孔距。\n"
                + "自动飞达：不执行送料，识别当前已出料元件/料槽中心；要求元件已就位，"
                + "且封装有本体宽度和高度。\n"
                + "所有模式均不修改 Z；低置信度、多目标、偏移超过 2 mm 或其他飞达类型"
                + "会保留为手动，不会猜测坐标。\n"
                + "请确认机器已启用、归零、路径无障碍，自动飞达出料位已有元件。\n\n"
                + "是否开始？",
                "自动矫正全部任务飞达", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        uninstallCameraControls();
        currentIndex = -1;
        resumeForwardOnly = false;
        resumeStartIndex = -1;
        autoRunning = true;
        autoStopRequested = false;
        autoCursor = -1;
        autoAlignedCount = 0;
        autoFailedCount = 0;
        autoManualCount = 0;
        for (Entry entry : entries) {
            if (!entry.skipped) {
                if (isAutoAlignmentSupported(entry.feeder)) {
                    entry.state = State.Pending;
                }
                else {
                    entry.state = State.ManualRequired;
                    autoManualCount++;
                }
            }
        }
        tableModel.fireTableDataChanged();
        updateActions();
        processNextAutoAlignment();
    }

    private void processNextAutoAlignment() {
        if (closing || !autoRunning) {
            return;
        }
        if (autoStopRequested) {
            finishAutoAlignment(true);
            return;
        }
        int index = -1;
        for (int item = autoCursor + 1; item < entries.size(); item++) {
            Entry entry = entries.get(item);
            if (!entry.skipped && isAutoAlignmentSupported(entry.feeder)) {
                index = item;
                break;
            }
        }
        if (index < 0) {
            finishAutoAlignment(false);
            return;
        }
        autoCursor = index;
        currentIndex = index;
        Entry entry = entries.get(index);
        Feeder feeder = entry.feeder;
        entry.state = State.AutoAligning;
        selectTableIndex(index);
        tableModel.fireTableRowsUpdated(index, index);
        boolean strip = feeder instanceof ReferenceStripFeeder;
        currentLabel.setText(feeder.getName()
                + (strip ? " / 定位孔自动矫正" : " / 自动飞达视觉纠偏"));
        statusText.setText("正在识别 " + feeder.getName()
                + (strip ? " 的首、末定位孔" : " 已出料元件/料槽的视觉中心")
                + "；不会执行送料、拾取或 Z 轴校正……");
        busy = true;
        updateActions();
        final int measuredIndex = index;
        UiUtils.submitUiMachineTask(() -> measureAutomaticFeeder(feeder), measurement -> {
            if (closing) {
                return;
            }
            topCamera = measurement.camera;
            topCameraView = activateTopCameraView();
            if (measurement.autoFeederResult != null && topCameraView != null) {
                topCameraView.showFilteredImage(measurement.autoFeederResult.preview, 700);
            }
            if (measurement.stripResult != null) {
                saveStripAutoAlignment((ReferenceStripFeeder) feeder, entry,
                        measurement.stripResult);
            }
            else {
                saveAutoFeederAlignment((ReferenceAutoFeeder) feeder, entry,
                        measurement.autoFeederResult);
            }
            busy = false;
            currentIndex = -1;
            tableModel.fireTableRowsUpdated(measuredIndex, measuredIndex);
            updateActions();
            SwingUtilities.invokeLater(this::processNextAutoAlignment);
        }, failure -> {
            if (closing) {
                return;
            }
            entry.state = State.Error;
            autoFailedCount++;
            busy = false;
            currentIndex = -1;
            tableModel.fireTableRowsUpdated(measuredIndex, measuredIndex);
            statusText.setText(feeder.getName() + " 自动识别失败：" + failure.getMessage()
                    + "；坐标未修改，已保留为手动检验项并继续下一项。");
            updateActions();
            SwingUtilities.invokeLater(this::processNextAutoAlignment);
        });
    }

    private AutoMeasurement measureAutomaticFeeder(Feeder feeder) throws Exception {
        if (feeder instanceof ReferenceStripFeeder) {
            return measureStripFeeder((ReferenceStripFeeder) feeder);
        }
        ReferenceAutoFeeder autoFeeder = (ReferenceAutoFeeder) feeder;
        Camera camera = getSelectedTopCamera();
        MovableUtils.moveToLocationAtSafeZ(camera, autoFeeder.getLocation());
        TaskAutoFeederVision.Result result = TaskAutoFeederVision.locate(camera, autoFeeder,
                effectiveThreshold(autoFeeder));
        MovableUtils.fireTargetedUserAction(camera);
        return new AutoMeasurement(camera, result);
    }

    private AutoMeasurement measureStripFeeder(ReferenceStripFeeder feeder) throws Exception {
        Camera camera = getSelectedTopCamera();
        Location originalReference = feeder.getReferenceHoleLocation();
        Location originalLast = feeder.getLastHoleLocation();
        Location detectedReference = feeder.locateSprocketHoleForCalibration(camera,
                originalReference);
        Location detectedLast = feeder.locateSprocketHoleForCalibration(camera, originalLast);
        TaskFeederAutoAlignSupport.Result result = TaskFeederAutoAlignSupport.calculate(
                originalReference, originalLast, detectedReference, detectedLast,
                feeder.getHolePitch().convertToUnits(LengthUnit.Millimeters).getValue());
        double minimumConfidence = effectiveThreshold(feeder);
        if (result.confidence < minimumConfidence) {
            throw new Exception(String.format(Locale.ROOT,
                    "定位孔几何置信度 %.0f%% 低于当前要求 %.1f%%；坐标保持不变。",
                    result.confidence * 100.0, minimumConfidence * 100.0));
        }
        MovableUtils.fireTargetedUserAction(camera);
        return new AutoMeasurement(camera, result);
    }

    private boolean isAutoAlignmentSupported(Feeder feeder) {
        return feeder instanceof ReferenceStripFeeder || feeder instanceof ReferenceAutoFeeder;
    }

    private void saveStripAutoAlignment(ReferenceStripFeeder feeder, Entry entry,
            TaskFeederAutoAlignSupport.Result result) {
        boolean changed = false;
        try {
            feeder.setReferenceHoleLocation(result.correctedReference);
            changed = true;
            feeder.setLastHoleLocation(result.correctedLast);
            if (!mainFrame.saveConfig()) {
                throw new Exception("配置文件保存失败。");
            }
            entry.state = State.Verified;
            entry.updatePickText();
            feedersPanel.refresh(feeder);
            autoAlignedCount++;
            statusText.setText(String.format(Locale.ROOT,
                    "%s 已保存：首孔偏移 %.3f mm，末孔偏移 %.3f mm，方向修正 %.2f°；"
                    + "识别跨度 %d 个孔距，几何置信度 %.0f%%（要求 %.1f%%），Z/带宽/元件节距未改变。",
                    feeder.getName(), result.referenceShiftMm, result.lastShiftMm,
                    result.angleChangeDegrees, result.holeIntervals, result.confidence * 100.0,
                    effectiveThreshold(feeder) * 100.0));
        }
        catch (Exception exception) {
            if (changed) {
                feeder.setReferenceHoleLocation(result.originalReference);
                feeder.setLastHoleLocation(result.originalLast);
            }
            entry.state = State.Error;
            autoFailedCount++;
            statusText.setText(feeder.getName() + " 保存失败：" + exception.getMessage()
                    + "；首、末定位孔已回滚，将继续下一项。");
        }
    }

    private void saveAutoFeederAlignment(ReferenceAutoFeeder feeder, Entry entry,
            TaskAutoFeederVision.Result visionResult) {
        TaskFeederInspectionSupport.Result correction = null;
        try {
            correction = TaskFeederInspectionSupport.applyXyCorrection(feeder,
                    visionResult.measuredLocation, null);
            if (!mainFrame.saveConfig()) {
                throw new Exception("配置文件保存失败。");
            }
            entry.state = State.Verified;
            entry.updatePickText();
            feedersPanel.refresh(feeder);
            autoAlignedCount++;
            Location oldPick = correction.predictedPick.convertToUnits(LengthUnit.Millimeters);
            Location newPick = correction.correctedPick.convertToUnits(LengthUnit.Millimeters);
            statusText.setText(String.format(Locale.ROOT,
                    "%s 已保存：自动飞达拾取 X %.3f → %.3f，Y %.3f → %.3f mm；"
                    + "视觉置信度 %.0f%%（要求 %.1f%%），%s约 %.2f × %.2f mm，Z/方向未改变。",
                    feeder.getName(), oldPick.getX(), newPick.getX(), oldPick.getY(),
                    newPick.getY(), visionResult.confidence * 100,
                    effectiveThreshold(feeder) * 100.0,
                    visionResult.pocketBased ? "料槽" : "元件本体",
                    visionResult.widthMm, visionResult.heightMm));
        }
        catch (Exception exception) {
            if (correction != null) {
                correction.rollback(feeder);
            }
            entry.state = State.Error;
            autoFailedCount++;
            statusText.setText(feeder.getName() + " 保存失败：" + exception.getMessage()
                    + "；自动飞达拾取点已回滚，将继续下一项。");
        }
    }

    private void finishAutoAlignment(boolean stopped) {
        autoRunning = false;
        autoStopRequested = false;
        busy = false;
        currentIndex = -1;
        currentLabel.setText(stopped ? "自动矫正已停止" : "自动矫正批次完成");
        statusText.setText(String.format(Locale.ROOT,
                "%s：自动保存 %d 项，识别/保存失败 %d 项，需手动 %d 项。"
                + "失败和不支持自动识别的飞达可直接点击列表继续手动检验。",
                stopped ? "已安全停止" : "自动矫正完成", autoAlignedCount,
                autoFailedCount, autoManualCount));
        updateActions();
    }

    private double effectiveThreshold(ReferenceFeeder feeder) {
        if (feeder == null) {
            return FeederAutoAlignConfidence.resolveOverrideRatio(globalConfidencePercent, null);
        }
        Double override = feeder.getAutoAlignConfidencePercent();
        if (override != null) {
            return FeederAutoAlignConfidence.resolveOverrideRatio(
                    globalConfidencePercent, override);
        }
        // beta36 stored a percentage-point adjustment. Preserve it until the user
        // changes this feeder, at which point it is migrated to an absolute override.
        return FeederAutoAlignConfidence.resolveRatio(globalConfidencePercent,
                feeder.getAutoAlignConfidenceAdjustment());
    }

    private ReferenceFeeder selectedConfidenceFeeder() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        Feeder feeder = entries.get(table.convertRowIndexToModel(viewRow)).feeder;
        return feeder instanceof ReferenceFeeder ? (ReferenceFeeder) feeder : null;
    }

    private void updateConfidenceControls() {
        ReferenceFeeder feeder = selectedConfidenceFeeder();
        if (feeder == null && currentIndex >= 0 && currentIndex < entries.size()
                && entries.get(currentIndex).feeder instanceof ReferenceFeeder) {
            feeder = (ReferenceFeeder) entries.get(currentIndex).feeder;
        }
        confidenceGuard = true;
        try {
            Double override = feeder == null ? null : feeder.getAutoAlignConfidencePercent();
            boolean legacyOverride = feeder != null && override == null
                    && feeder.getAutoAlignConfidenceAdjustment() != 0.0;
            double effective = feeder == null
                    ? FeederAutoAlignConfidence.resolveOverridePercent(
                            globalConfidencePercent, null)
                    : effectiveThreshold(feeder) * 100.0;
            boolean customized = override != null || legacyOverride;
            feederConfidenceOverride.setEnabled(feeder != null && !autoRunning);
            feederConfidenceOverride.setSelected(customized);
            feederConfidencePercent.setEnabled(feeder != null && customized && !autoRunning);
            feederConfidencePercent.setValue(effective);
            if (feeder == null) {
                effectiveConfidence.setText(String.format(Locale.ROOT,
                        "当前使用全局阈值 %.1f%%", effective));
            }
            else if (legacyOverride) {
                effectiveConfidence.setText(String.format(Locale.ROOT,
                        "本飞达最终阈值 %.1f%%（兼容旧设置，修改后自动升级）", effective));
            }
            else if (override != null) {
                effectiveConfidence.setText(String.format(Locale.ROOT,
                        "本飞达最终阈值 %.1f%%（覆盖全局 %.1f%%）",
                        effective, globalConfidencePercent));
            }
            else {
                effectiveConfidence.setText(String.format(Locale.ROOT,
                        "本飞达跟随全局阈值 %.1f%%", effective));
            }
        }
        finally {
            confidenceGuard = false;
        }
    }

    private void saveFeederConfidenceOverride() {
        if (confidenceGuard) {
            return;
        }
        ReferenceFeeder feeder = selectedConfidenceFeeder();
        if (feeder == null) {
            return;
        }
        Double oldOverride = feeder.getAutoAlignConfidencePercent();
        double oldAdjustment = feeder.getAutoAlignConfidenceAdjustment();
        Double override = feederConfidenceOverride.isSelected()
                ? ((Number) feederConfidencePercent.getValue()).doubleValue() : null;
        feeder.setAutoAlignConfidencePercent(override);
        feeder.setAutoAlignConfidenceAdjustment(0.0);
        if (!mainFrame.saveConfig()) {
            feeder.setAutoAlignConfidencePercent(oldOverride);
            feeder.setAutoAlignConfidenceAdjustment(oldAdjustment);
            statusText.setText("单飞达置信度保存失败，已恢复原设置；请检查配置目录权限。");
        }
        else {
            statusText.setText(override == null
                    ? String.format(Locale.ROOT, "%s 已改为跟随全局置信度 %.1f%%。",
                            feeder.getName(), globalConfidencePercent)
                    : String.format(Locale.ROOT, "%s 的最终置信度已设为 %.1f%%，不再与全局相加。",
                            feeder.getName(), override));
        }
        updateConfidenceControls();
    }

    private void closeWorkflow() {
        if (closing) {
            return;
        }
        closing = true;
        topCameraGuard.stop();
        uninstallCameraControls();
        dispose();
        if (closeCallback != null) {
            closeCallback.run();
        }
    }

    private final class InspectionTableModel extends AbstractTableModel {
        private final String[] columns = {"状态", "飞达", "元件", "当前拾取点", "跳过"};

        @Override
        public int getRowCount() {
            return entries.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 4 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            Entry entry = entries.get(rowIndex);
            return !busy && columnIndex == 4 && entry.feeder.isEnabled()
                    && entry.feeder instanceof ReferenceFeeder;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Entry entry = entries.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return entry.state.label;
                case 1:
                    return entry.feeder.getName();
                case 2:
                    return entry.feeder.getPart() == null ? "—" : entry.feeder.getPart().getId();
                case 3:
                    return entry.pickText;
                case 4:
                    return entry.skipped;
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 4) {
                skipIndex(rowIndex, Boolean.TRUE.equals(value));
            }
        }
    }

    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(source, value, selected,
                    focused, row, column);
            int modelIndex = source.convertRowIndexToModel(row);
            State state = entries.get(modelIndex).state;
            if (!selected) {
                if (state == State.Verified) {
                    label.setForeground(ModernTheme.SUCCESS);
                }
                else if (state == State.Error) {
                    label.setForeground(ModernTheme.DANGER);
                }
                else if (state == State.Current || state == State.AutoAligning) {
                    label.setForeground(ModernTheme.ACCENT);
                }
                else if (state == State.ManualRequired) {
                    label.setForeground(new Color(0xD98E04));
                }
                else {
                    Color foreground = javax.swing.UIManager.getColor("Table.foreground");
                    label.setForeground(foreground == null ? Color.DARK_GRAY : foreground);
                }
            }
            label.setHorizontalAlignment(SwingConstants.CENTER);
            return label;
        }
    }
}
