/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;

import org.openpnp.gui.support.AdaptiveUiRefresh;
import org.openpnp.gui.support.ExperimentalGuide;
import org.openpnp.gui.support.DynamicSafeMotionPanel;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.SafetyWorkbenchViewTransform;
import org.openpnp.gui.support.WrapLayout;
import org.openpnp.gui.support.ExperimentalGuide.Step;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.Plan;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchGeometry;
import org.openpnp.machine.reference.safety.SafetyWorkbenchHistory;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.Category;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.FeederType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVertexEditor;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVertexEditor.Vertex;
import org.openpnp.machine.reference.safety.SafetyModelValidator;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.machine.reference.vision.BottomVisionTiming;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;

/**
 * Operator-defined 3D digital twin and guarded motion laboratory.
 */
@SuppressWarnings("serial")
public class SafetyWorkbenchDialog extends JDialog {
    private final MainFrame frame;
    private final SafetyWorkbenchModel model;
    private final SafetyWorkbenchHistory history;
    private final DefaultListModel<SafetyObject> objectListModel = new DefaultListModel<>();
    private final JList<SafetyObject> objectList = new JList<>(objectListModel);
    private final WorkbenchCanvas canvas = new WorkbenchCanvas();
    private final JLabel certification = new JLabel();
    private DynamicSafeMotionPanel coordinatedMotion;
    private JDialog visionTimingDialog;
    private final JButton importConfiguration = new JButton("从 OpenPnP 导入…");
    private final JButton certifyButton = new JButton("检查并认证");
    private final JButton simulateButton = new JButton("3D 预演与碰撞检查");
    private final JButton undoButton = new JButton("← 后退");
    private final JButton redoButton = new JButton("前进 →");
    private final JTabbedPane propertyTabs = new JTabbedPane();
    private ExperimentalGuide guide;
    private final JTextArea result = new JTextArea(5, 30);

    private final JTextField name = new JTextField();
    private final JComboBox<ObjectType> type = new JComboBox<>(ObjectType.values());
    private final JComboBox<Shape> shape = new JComboBox<>(Shape.values());
    private final JTextField x = new JTextField();
    private final JTextField y = new JTextField();
    private final JTextField z = new JTextField();
    private final JTextField width = new JTextField();
    private final JTextField depth = new JTextField();
    private final JTextField height = new JTextField();
    private final JTextField surfaceZ = new JTextField();
    private final JButton applySurfaceZ = new JButton("按实测顶面 Z 计算高度");
    private final JTextField clearance = new JTextField();
    private final JTextField apertureWidth = new JTextField();
    private final JTextField apertureDepth = new JTextField();
    private final JCheckBox obstacle = new JCheckBox("参与碰撞检查");
    private final JCheckBox locked = new JCheckBox("锁定，避免误拖");

    private final JComboBox<String> route = new JComboBox<>(new String[] {
            "安全 Z 分段移动", "XYZ 联动直线", "飞拍：联合下降—定高通过—联合抬升"
    });
    private final JSpinner targetX = new JSpinner(new SpinnerNumberModel(200.0, -5000, 5000, 1));
    private final JSpinner targetY = new JSpinner(new SpinnerNumberModel(150.0, -5000, 5000, 1));
    private final JSpinner targetZ = new JSpinner(new SpinnerNumberModel(10.0, -5000, 5000, 0.5));
    private final JSpinner flightSpan = new JSpinner(new SpinnerNumberModel(40.0, 2, 300, 1));
    private final JSpinner speed = new JSpinner(new SpinnerNumberModel(0.12, 0.02, 0.35, 0.01));
    private final JCheckBox physicalConfirmation =
            new JCheckBox("空吸嘴、现场清空、急停可用，且我已核对预演轨迹");
    private Location captureMinimum;
    private List<Waypoint> previewPath = new ArrayList<>();
    private ValidationResult previewResult;

    private final DefaultListModel<ToolProfile> toolProfileListModel = new DefaultListModel<>();
    private final JList<ToolProfile> toolProfileList = new JList<>(toolProfileListModel);
    private final JTextField profileName = new JTextField();
    private final JTextField profileBodyRadiusX = new JTextField();
    private final JTextField profileBodyRadiusY = new JTextField();
    private final JTextField profileBodyMinZ = new JTextField();
    private final JTextField profileBodyMaxZ = new JTextField();
    private final JTextField profileShaftRadius = new JTextField();
    private final JTextField profileClearance = new JTextField();
    private final JTextField profileRetractedZ = new JTextField();
    private final JTextField profileExtendedZ = new JTextField();
    private final JTextField profileRetractedLength = new JTextField();
    private final JTextField profileExtendedLength = new JTextField();
    private final JCheckBox profileEnabled = new JCheckBox("启用此吸嘴安全包络");
    private final JCheckBox profileBodyFollowsZ =
            new JCheckBox("机头本体随吸嘴 Z 一起升降");
    private final JComboBox<Nozzle> workbenchNozzle = new JComboBox<>();
    private final JComboBox<Feeder> workbenchFeeder = new JComboBox<>();
    private final JSpinner coordinateX = new JSpinner(
            new SpinnerNumberModel(0.0, -5000, 5000, 0.1));
    private final JSpinner coordinateY = new JSpinner(
            new SpinnerNumberModel(0.0, -5000, 5000, 0.1));
    private final JSpinner coordinateZ = new JSpinner(
            new SpinnerNumberModel(0.0, -5000, 5000, 0.1));
    private final JSpinner coordinateRotation = new JSpinner(
            new SpinnerNumberModel(0.0, -3600, 3600, 1));
    private final JComboBox<Double> jogStep = new JComboBox<>(
            new Double[] {0.01, 0.1, 1.0, 10.0});
    private final JToggleButton motionArmed = new JToggleButton("启用窗口内运动（含主控制动作）");
    private final JLabel coordinateStatus = new JLabel("尚未读取坐标");
    private final JLabel vertexStatus = new JLabel("请在 3D 视图点击物体顶点");
    private final JToggleButton liveMode = new JToggleButton("实时运行画面");
    private final JCheckBox followSelectedTool = new JCheckBox("跟随控制吸嘴", true);
    private final JCheckBox keepOnTop = new JCheckBox("窗口置顶");
    private final JCheckBox machineFrontView =
            new JCheckBox("机台前视：X 方向匹配实机", true);
    private final JCheckBox showReferenceLabels =
            new JCheckBox("显示全部坐标标签", false);
    private final JCheckBox visionHeightCompensation =
            new JCheckBox("按 3D 高度修正顶部相机定位");
    private final JLabel visionHeightStatus = new JLabel();
    private PropertyChangeListener selectedToolListener;
    private final Map<String, LiveToolState> liveTools = new LinkedHashMap<>();
    private final List<Waypoint> liveTrail = new ArrayList<>();
    private Timer liveTimer;
    private long lastLiveSnapshot;

    public SafetyWorkbenchDialog(MainFrame frame) {
        super(frame, "OpenPnP Pro · 3D 安全工作台（实验）", false);
        this.frame = frame;
        this.model = SafetyWorkbenchStore.get();
        synchronizeToolProfiles();
        loadWorkbenchControls();
        this.history = new SafetyWorkbenchHistory(model);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(new Dimension(Math.min(1540, Math.max(1024, screen.width - 80)),
                Math.min(920, Math.max(680, screen.height - 100))));
        setLocationRelativeTo(frame);
        buildUi();
        installHistoryShortcuts();
        guide = createGuide();
        reloadObjects();
        updateCertification();
        selectFirstObject();
        startLiveView();
        guide.startOnFirstOpen();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (liveTimer != null) {
                    liveTimer.stop();
                }
                if (selectedToolListener != null) {
                    frame.getMachineControls().removePropertyChangeListener(
                            "selectedTool", selectedToolListener);
                }
            }
        });
    }

    private void synchronizeToolProfiles() {
        for (Head head : Configuration.get().getMachine().getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                model.ensureToolProfile(head.getId(), nozzle.getId(),
                        head.getName() + " · " + nozzle.getName());
            }
        }
        reloadToolProfiles();
    }

    private void reloadToolProfiles() {
        toolProfileListModel.clear();
        for (ToolProfile profile : model.getToolProfiles()) {
            if (profile != null) {
                toolProfileListModel.addElement(profile);
            }
        }
    }

    private void loadWorkbenchControls() {
        HeadMountable mainSelection = frame.getMachineControls().getSelectedTool();
        for (Head head : Configuration.get().getMachine().getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                workbenchNozzle.addItem(nozzle);
                if (nozzle == mainSelection) {
                    workbenchNozzle.setSelectedItem(nozzle);
                }
            }
        }
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            workbenchFeeder.addItem(feeder);
        }
        workbenchNozzle.addActionListener(event -> {
            Nozzle nozzle = selectedWorkbenchNozzle();
            if (nozzle == null || nozzle.getHead() == null) {
                return;
            }
            for (int index = 0; index < toolProfileListModel.size(); index++) {
                ToolProfile profile = toolProfileListModel.get(index);
                if (profile.headId.equals(nozzle.getHead().getId())
                        && profile.toolId.equals(nozzle.getId())) {
                    toolProfileList.setSelectedIndex(index);
                    break;
                }
            }
            if (frame.getMachineControls().getSelectedTool() != nozzle) {
                frame.getMachineControls().setSelectedTool(nozzle);
            }
            canvas.repaint();
        });
        selectedToolListener = event -> {
            Object selected = event.getNewValue();
            if (selected instanceof Nozzle && workbenchNozzle.getSelectedItem() != selected) {
                workbenchNozzle.setSelectedItem(selected);
            }
        };
        frame.getMachineControls().addPropertyChangeListener(
                "selectedTool", selectedToolListener);
        Nozzle initialNozzle = selectedWorkbenchNozzle();
        if (initialNozzle != null
                && frame.getMachineControls().getSelectedTool() != initialNozzle) {
            frame.getMachineControls().setSelectedTool(initialNozzle);
        }
    }

    private Nozzle selectedWorkbenchNozzle() {
        return (Nozzle) workbenchNozzle.getSelectedItem();
    }

    private HeadMountable selectedWorkbenchTool() {
        Nozzle nozzle = selectedWorkbenchNozzle();
        return nozzle == null ? firstNozzle() : nozzle;
    }

    private void showToolProfile(ToolProfile profile) {
        boolean enabled = profile != null;
        for (Component component : Arrays.asList(profileName, profileBodyRadiusX,
                profileBodyRadiusY, profileBodyMinZ, profileBodyMaxZ,
                profileShaftRadius, profileClearance, profileRetractedZ,
                profileExtendedZ, profileRetractedLength, profileExtendedLength,
                profileEnabled, profileBodyFollowsZ)) {
            component.setEnabled(enabled);
        }
        if (!enabled) {
            return;
        }
        profileName.setText(profile.name);
        profileBodyRadiusX.setText(format(profile.bodyRadiusX));
        profileBodyRadiusY.setText(format(profile.bodyRadiusY));
        profileBodyMinZ.setText(format(profile.bodyMinZOffset));
        profileBodyMaxZ.setText(format(profile.bodyMaxZOffset));
        profileShaftRadius.setText(format(profile.shaftRadius));
        profileClearance.setText(format(profile.clearance));
        profileRetractedZ.setText(format(profile.retractedZ));
        profileExtendedZ.setText(format(profile.extendedZ));
        profileRetractedLength.setText(format(profile.retractedTipLength));
        profileExtendedLength.setText(format(profile.extendedTipLength));
        profileEnabled.setSelected(profile.enabled);
        profileBodyFollowsZ.setSelected(profile.bodyFollowsNozzleZ);
    }

    private void applyToolProfile() {
        ToolProfile profile = toolProfileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        try {
            String parsedName = profileName.getText().trim();
            double parsedRadiusX = positive(profileBodyRadiusX, "机头半宽");
            double parsedRadiusY = positive(profileBodyRadiusY, "机头半深");
            double parsedBodyMinZ = parse(profileBodyMinZ);
            double parsedBodyMaxZ = parse(profileBodyMaxZ);
            double parsedShaftRadius = positive(profileShaftRadius, "吸嘴杆半径");
            double parsedClearance = nonNegative(profileClearance, "额外安全间隙");
            double parsedRetractedZ = parse(profileRetractedZ);
            double parsedExtendedZ = parse(profileExtendedZ);
            double parsedRetractedLength = positive(profileRetractedLength, "收起吸嘴长度");
            double parsedExtendedLength = positive(profileExtendedLength, "伸出吸嘴长度");
            if (parsedBodyMinZ >= parsedBodyMaxZ) {
                throw new IllegalArgumentException("机头最低 Z 偏移必须小于最高 Z 偏移。");
            }
            profile.name = parsedName.isEmpty() ? profile.toolId : parsedName;
            profile.bodyRadiusX = parsedRadiusX;
            profile.bodyRadiusY = parsedRadiusY;
            profile.bodyMinZOffset = parsedBodyMinZ;
            profile.bodyMaxZOffset = parsedBodyMaxZ;
            profile.shaftRadius = parsedShaftRadius;
            profile.clearance = parsedClearance;
            profile.retractedZ = parsedRetractedZ;
            profile.extendedZ = parsedExtendedZ;
            profile.retractedTipLength = parsedRetractedLength;
            profile.extendedTipLength = parsedExtendedLength;
            profile.enabled = profileEnabled.isSelected();
            profile.bodyFollowsNozzleZ = profileBodyFollowsZ.isSelected();
            model.touch();
            toolProfileList.repaint();
            updateCertification();
            canvas.repaint();
            result.setText("已更新“" + profile.name + "”的随 Z 变化安全包络。"
                    + "模型需要重新检查和认证。");
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "吸嘴包络无效",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void captureProfileZ(boolean retracted) {
        ToolProfile profile = toolProfileList.getSelectedValue();
        HeadMountable selected = selectedWorkbenchTool();
        if (profile == null || !(selected instanceof Nozzle)
                || selected.getHead() == null
                || !profile.headId.equals(selected.getHead().getId())
                || !profile.toolId.equals(selected.getId())) {
            JOptionPane.showMessageDialog(this,
                    "请先在主界面选择与当前配置行对应的吸嘴，再缓慢点动到标定位置。",
                    "吸嘴不匹配", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Location location = selected.getLocation().convertToUnits(LengthUnit.Millimeters);
        if (retracted) {
            profileRetractedZ.setText(format(location.getZ()));
            result.setText("已读取收起位置 Z=" + format(location.getZ())
                    + " mm；请填写这个位置下的实际吸嘴外露长度。");
        }
        else {
            profileExtendedZ.setText(format(location.getZ()));
            result.setText("已读取伸出位置 Z=" + format(location.getZ())
                    + " mm；请填写这个位置下的实际吸嘴外露长度。");
        }
    }
    private void startLiveView() {
        liveTimer = new Timer(AdaptiveUiRefresh.ACTIVE_DELAY_MS,
                event -> updateLiveScene());
        liveTimer.setCoalesce(true);
        liveTimer.start();
    }

    private void updateLiveScene() {
        boolean showing = isShowing();
        if (!showing) {
            liveTimer.setDelay(AdaptiveUiRefresh.timerDelay(false, false));
            return;
        }
        boolean machineActive = Configuration.get().getMachine().isBusy();
        if (coordinatedMotion != null) {
            coordinatedMotion.refresh();
        }
        boolean sceneChanged = false;
        long now = System.currentTimeMillis();
        if (AdaptiveUiRefresh.shouldTakeSnapshot(now, lastLiveSnapshot, machineActive,
                hasPendingUserInput())) {
            lastLiveSnapshot = now;
            try {
                HeadMountable selected = selectedWorkbenchTool();
                if (selected == null || selected.getHead() == null) {
                    selected = firstNozzle();
                }
                if (selected != null) {
                    List<DynamicTool> snapshot = DynamicSafetyCollisionEngine.snapshot(
                            model, Configuration.get().getMachine(), selected);
                    Map<String, Boolean> seen = new LinkedHashMap<>();
                    for (DynamicTool tool : snapshot) {
                        String key = tool.headId + "/" + tool.toolId;
                        seen.put(key, Boolean.TRUE);
                        LiveToolState state = liveTools.get(key);
                        if (state == null) {
                            state = new LiveToolState(tool);
                            liveTools.put(key, state);
                            sceneChanged = true;
                        }
                        else {
                            sceneChanged |= state.setTarget(tool);
                        }
                    }
                    int previousCount = liveTools.size();
                    liveTools.keySet().removeIf(key -> !seen.containsKey(key));
                    sceneChanged |= previousCount != liveTools.size();
                    updateLiveTrail(selected);
                }
            }
            catch (Exception ignored) {
                // Live visualization is diagnostic and must never disturb a running job.
            }
        }
        boolean animating = false;
        for (LiveToolState state : liveTools.values()) {
            animating |= state.animate();
        }
        boolean followingView = false;
        boolean following = liveMode.isSelected() && followSelectedTool.isSelected();
        if (following) {
            HeadMountable selected = selectedWorkbenchTool();
            if (selected != null && selected.getHead() != null) {
                LiveToolState state = liveTools.get(selected.getHead().getId()
                        + "/" + selected.getId());
                if (state != null) {
                    followingView = canvas.follow(state.x, state.y);
                }
            }
        }
        boolean active = machineActive || animating || followingView;
        liveTimer.setDelay(AdaptiveUiRefresh.timerDelay(true, active));
        if (sceneChanged || active) {
            canvas.repaint();
        }
    }

    private static boolean hasPendingUserInput() {
        EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        return queue.peekEvent(MouseEvent.MOUSE_PRESSED) != null
                || queue.peekEvent(MouseEvent.MOUSE_RELEASED) != null
                || queue.peekEvent(MouseWheelEvent.MOUSE_WHEEL) != null
                || queue.peekEvent(KeyEvent.KEY_PRESSED) != null
                || queue.peekEvent(KeyEvent.KEY_RELEASED) != null;
    }

    private HeadMountable firstNozzle() {
        for (Head head : Configuration.get().getMachine().getHeads()) {
            if (!head.getNozzles().isEmpty()) {
                return head.getNozzles().get(0);
            }
        }
        return null;
    }

    private void updateLiveTrail(HeadMountable selected) {
        if (!liveMode.isSelected() || selected == null || selected.getHead() == null) {
            return;
        }
        LiveToolState state = liveTools.get(selected.getHead().getId()
                + "/" + selected.getId());
        if (state == null) {
            return;
        }
        Waypoint point = new Waypoint(state.targetX, state.targetY, state.targetZ);
        if (liveTrail.isEmpty() || distance(liveTrail.get(liveTrail.size() - 1), point) > 0.15) {
            liveTrail.add(point);
            while (liveTrail.size() > 180) {
                liveTrail.remove(0);
            }
        }
    }

    private static double distance(Waypoint a, Waypoint b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(buildHeader(), BorderLayout.NORTH);

        Component viewport = buildViewport();
        Component details = buildProperties();
        viewport.setMinimumSize(new Dimension(390, 320));
        details.setMinimumSize(new Dimension(300, 300));
        JSplitPane viewportAndDetails = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                viewport, details);
        viewportAndDetails.setBorder(null);
        viewportAndDetails.setContinuousLayout(true);
        viewportAndDetails.setResizeWeight(1.0);
        viewportAndDetails.setDividerLocation(820);

        Component palette = buildPalette();
        palette.setMinimumSize(new Dimension(165, 300));
        JSplitPane workspace = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                palette, viewportAndDetails);
        workspace.setBorder(null);
        workspace.setContinuousLayout(true);
        workspace.setResizeWeight(0.0);
        workspace.setDividerLocation(215);
        root.add(workspace, BorderLayout.CENTER);
        setContentPane(root);
    }

    private Component buildViewport() {
        JPanel viewport = new JPanel(new BorderLayout(4, 4));
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
        JButton reset = new JButton("适合全部");
        reset.addActionListener(event -> canvas.resetView());
        JButton isometric = new JButton("等轴视图");
        isometric.addActionListener(event -> canvas.setIsometricView());
        JButton top = new JButton("俯视");
        top.addActionListener(event -> canvas.setTopView());
        machineFrontView.setToolTipText(
                "默认镜像屏幕横向投影，使机头左右移动与从机器正面观察的方向一致。");
        machineFrontView.addActionListener(
                event -> canvas.setMirrorX(machineFrontView.isSelected()));
        showReferenceLabels.setToolTipText(
                "默认只显示所选物体或参考点名称，避免大量飞达、换嘴坞和停靠点标签遮挡实体。");
        showReferenceLabels.addActionListener(event -> canvas.repaint());
        controls.add(new JLabel("视图："));
        controls.add(reset);
        controls.add(isometric);
        controls.add(top);
        controls.add(machineFrontView);
        controls.add(showReferenceLabels);
        controls.add(new JLabel("空白处左拖 / 中键拖：平移；右拖：旋转；滚轮：缩放"));
        viewport.add(controls, BorderLayout.NORTH);
        viewport.add(canvas, BorderLayout.CENTER);
        return viewport;
    }

    private Component buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(83, 89, 94)),
                new EmptyBorder(2, 4, 7, 4)));

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        JLabel title = new JLabel("3D 安全工作台 / 数字孪生");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel explanation = new JLabel(
                "建模与校准  →  轨迹预演  →  检查认证  →  受控运动");
        explanation.setForeground(new Color(170, 181, 188));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(title);
        text.add(Box.createVerticalStrut(2));
        text.add(explanation);
        heading.add(text, BorderLayout.WEST);
        certification.setOpaque(true);
        certification.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        heading.add(certification, BorderLayout.EAST);
        header.add(heading);
        header.add(Box.createVerticalStrut(6));

        importConfiguration.addActionListener(event -> importOpenPnpConfiguration());
        JButton settings = new JButton("工作台与工具包络…");
        settings.addActionListener(event -> editWorkbench());
        JButton save = new JButton("保存模型");
        save.addActionListener(event -> saveModel());
        save.setToolTipText("保存在用户配置目录，不在安装目录中；重装软件不会覆盖。每次保存还会保留一个 .bak 备份。");
        undoButton.setToolTipText("撤销上一步模型编辑（Ctrl+Z）");
        redoButton.setToolTipText("重做刚撤销的模型编辑（Ctrl+Y）");
        undoButton.addActionListener(event -> navigateHistory(false));
        redoButton.addActionListener(event -> navigateHistory(true));
        certifyButton.addActionListener(event -> certifyModel());
        JButton followGuide = new JButton("跟着做");
        followGuide.addActionListener(event -> guide.start());

        liveMode.setToolTipText("编辑模式允许拖放；实时模式显示 OpenPnP 内部运动位置，不等同于独立编码器实测位置。");
        liveMode.addActionListener(event -> {
            liveMode.setText(liveMode.isSelected() ? "● 实时运行" : "编辑模式");
            canvas.repaint();
        });
        followSelectedTool.setToolTipText("实时模式下让视图中心跟随本窗口选择的控制吸嘴。");
        keepOnTop.setToolTipText("把数字孪生保持在主界面上方，适合贴装时持续观察。");
        keepOnTop.addActionListener(event -> setAlwaysOnTop(keepOnTop.isSelected()));
        JLabel driver = new JLabel(isAsyncDriverActive()
                ? "异步驱动：已启用" : "异步驱动：未启用");
        driver.setToolTipText("联合飞拍轨迹真机测试需要 GcodeAsyncDriver；可在运动性能中心升级。");
        visionHeightCompensation.setSelected(model.isVisionHeightCompensationEnabled());
        visionHeightCompensation.setToolTipText(
                "按鼠标下方 3D 实体的顶面 Z 调用相机双高度标定比例；只影响顶部相机的毫米/像素换算、标尺和拖拽定位，不缩放原始视频帧。");
        visionHeightCompensation.addActionListener(event -> {
            model.setVisionHeightCompensationEnabled(visionHeightCompensation.isSelected());
            updateCertification();
            result.setText(visionHeightCompensation.isSelected()
                    ? "已请求启用 3D 高度定位。请精确填写物体底部 Z 和高度，完成顶部相机双高度标定，再检查、认证并保存模型。"
                    : "已关闭 3D 高度定位；顶部相机恢复使用默认观察平面。请保存模型。");
        });
        updateVisionHeightStatus();

        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 7, 3));
        toolbar.add(liveMode);
        toolbar.add(followSelectedTool);
        toolbar.add(keepOnTop);
        toolbar.add(driver);
        toolbar.add(visionHeightCompensation);
        toolbar.add(visionHeightStatus);
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(undoButton);
        toolbar.add(redoButton);
        toolbar.add(followGuide);
        toolbar.add(importConfiguration);
        toolbar.add(settings);
        toolbar.add(save);
        toolbar.add(certifyButton);
        header.add(toolbar);
        coordinatedMotion = new DynamicSafeMotionPanel(model, Configuration.get().getMachine(), result::setText);
        JButton previewCoordinated = new JButton("预览联动路径与耗时");
        previewCoordinated.setName("preview-xyz-overlap");
        previewCoordinated.setToolTipText("使用运动/预演页的目标 XYZ，只计算路径，不移动机器。");
        previewCoordinated.addActionListener(event -> previewCoordinatedMotion());
        coordinatedMotion.add(previewCoordinated);
        JButton previewVision = new JButton("预览视觉直达");
        previewVision.setName("preview-bottom-vision-transit");
        previewVision.setToolTipText("按运动/预演页的拍摄 XYZ 检查非接触路径；Z 可在 XY 行程中达到拍摄高度，不移动机器。");
        previewVision.addActionListener(event -> previewCoordinatedMotion(true));
        coordinatedMotion.add(previewVision);
        JButton visionTiming = new JButton("底部视觉耗时");
        visionTiming.setName("bottom-vision-timing");
        visionTiming.setToolTipText("查看真实静拍调用的移动下发、采图/稳定、流水线耗时和复拍轮数，不额外拍照。");
        visionTiming.addActionListener(event -> showBottomVisionTiming());
        coordinatedMotion.add(visionTiming);
        JButton motionDiagnostics = new JButton("联动运行诊断");
        motionDiagnostics.setName("xyz-runtime-diagnostics");
        motionDiagnostics.addActionListener(event -> {
            JTextArea diagnosticText = new JTextArea(org.openpnp.machine.reference.safety.DynamicMotionDiagnostics.summary(), 18, 70);
            diagnosticText.setEditable(false);
            diagnosticText.setLineWrap(true);
            diagnosticText.setWrapStyleWord(true);
            Object[] options = { "关闭", "复制 CSV" };
            if (JOptionPane.showOptionDialog(this, new JScrollPane(diagnosticText), "XY/Z 联动运行诊断 · 本次启动",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]) == 1) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                        org.openpnp.machine.reference.safety.DynamicMotionDiagnostics.csv()), null);
            }
        });
        coordinatedMotion.add(motionDiagnostics);
        header.add(coordinatedMotion);
        return header;
    }

    private void installHistoryShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo-model");
        getRootPane().getActionMap().put("undo-model", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                navigateHistory(false);
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo-model");
        getRootPane().getActionMap().put("redo-model", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                navigateHistory(true);
            }
        });
    }

    private void navigateHistory(boolean forward) {
        String selectedId = objectList.getSelectedValue() == null
                ? null : objectList.getSelectedValue().id;
        boolean changed = forward ? history.redo(model) : history.undo(model);
        if (!changed) {
            return;
        }
        reloadObjects();
        reloadToolProfiles();
        visionHeightCompensation.setSelected(model.isVisionHeightCompensationEnabled());
        selectObjectById(selectedId);
        updateCertification();
        canvas.clearVertexSelection();
        canvas.repaint();
        result.setText((forward ? "已前进一步。" : "已后退一步。")
                + "为保证运动安全，恢复后的模型需要重新检查并认证。");
    }

    private ExperimentalGuide createGuide() {
        return new ExperimentalGuide(this, "safety-workbench", "3D 安全工作台",
                new Step("导入现有机器坐标",
                        "先同步软限位、Safe Z、停靠点、换嘴路径，以及飞达和底部相机实体。",
                        () -> importConfiguration, () -> objectListModel.size() > 0),
                new Step("核对 3D 场景",
                        "先用空白处左拖或中键平移，再旋转和缩放；核对 X 方向、实体位置和真实机器一致。",
                        () -> canvas, () -> objectListModel.size() > 0),
                new Step("校准障碍物尺寸",
                        "选择物体并检查原点、宽深高和安全间隙。用于视觉高度修正时，顶面 Z = 原点 Z + 高度。任何修改都会使旧认证失效。",
                        () -> objectList, () -> objectList.getSelectedValue() != null,
                        "打开物体/采点", () -> propertyTabs.setSelectedIndex(0)),
                new Step("启用顶部视觉高度修正",
                        "先给顶部相机做两个已知高度的像素比例标定，再勾选高度修正；视频帧不会反复缩放，只修正毫米/像素、标尺和拖拽定位。",
                        () -> visionHeightCompensation,
                        () -> visionHeightCompensation.isSelected()
                                && hasCalibratedTopCamera()),
                new Step("配置每支吸嘴的动态包络",
                        "分别填写机头本体、吸嘴杆、收起和伸出标定值；不能只沿用单一工具方盒。",
                        () -> toolProfileList, () -> toolProfileList.getSelectedValue() != null,
                        "打开吸嘴包络", () -> propertyTabs.setSelectedIndex(1)),
                new Step("读取坐标与低速示教",
                        "先选择真实吸嘴。读取坐标不会运动；点动和移动必须由操作者主动解锁并确认。",
                        () -> workbenchNozzle, () -> workbenchNozzle.getSelectedItem() != null,
                        "打开移动/坐标", () -> propertyTabs.setSelectedIndex(2)),
                new Step("先做整段碰撞预演",
                        "设置轨迹和目标后执行预演。系统检查整段扫掠，不只是起点和终点。",
                        () -> simulateButton, () -> result.getText().contains("预演通过")),
                new Step("检查并认证模型",
                        "只有逐项核对模型与实机一致后才认证；随后任何几何修改都会立即撤销认证。",
                        () -> certifyButton, model::isCertified),
                new Step("观察实时数字孪生",
                        "实时画面读取 OpenPnP 内部位置，不会发送额外 G-code，也不等同独立编码器实测。",
                        () -> liveMode, liveMode::isSelected,
                        "切换实时画面", () -> liveMode.doClick()),
                new Step("真机测试仍需最终确认",
                        "受控真机按钮需要实验模式、回零、空吸嘴、认证模型、软限位和碰撞检查全部通过。引导绝不自动执行。",
                        () -> physicalConfirmation, physicalConfirmation::isSelected));
    }
    private Component buildPalette() {
        JPanel panel = new JPanel(new BorderLayout(5, 8));
        panel.setPreferredSize(new Dimension(225, 500));
        panel.setBorder(BorderFactory.createTitledBorder("可添加清单（拖入中间）"));
        DefaultListModel<String> paletteModel = new DefaultListModel<>();
        for (ObjectType value : ObjectType.values()) {
            paletteModel.addElement(value.getDisplayName());
        }
        JList<String> palette = new JList<>(paletteModel);
        palette.setFixedCellHeight(38);
        palette.setCellRenderer(new PaletteRenderer());
        palette.setDragEnabled(true);
        palette.setTransferHandler(new PaletteTransferHandler());
        panel.add(new JScrollPane(palette), BorderLayout.NORTH);

        objectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        objectList.setCellRenderer(new ObjectRenderer());
        objectList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                canvas.setSelected(objectList.getSelectedValue());
                showObject(objectList.getSelectedValue());
            }
        });
        JPanel placed = new JPanel(new BorderLayout(4, 4));
        placed.setBorder(BorderFactory.createTitledBorder("已放置物体"));
        placed.add(new JScrollPane(objectList), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton duplicate = new JButton("复制");
        duplicate.setIcon(new WorkbenchGlyphIcon(Glyph.Copy, new Color(92, 151, 198)));
        styleActionButton(duplicate, new Color(56, 91, 119));
        duplicate.addActionListener(event -> duplicateSelected());
        JButton remove = new JButton("删除");
        remove.setIcon(new WorkbenchGlyphIcon(Glyph.Delete, new Color(225, 112, 104)));
        styleActionButton(remove, new Color(117, 61, 59));
        remove.addActionListener(event -> removeSelected());
        buttons.add(duplicate);
        buttons.add(remove);
        placed.add(buttons, BorderLayout.SOUTH);
        panel.add(placed, BorderLayout.CENTER);
        JLabel hint = new JLabel("<html>物体左拖移动 · 顶点左拖改尺寸<br>Shift+拖动调 Z · 顶点右键快捷示教</html>");
        hint.setBorder(new EmptyBorder(4, 4, 4, 4));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildProperties() {
        WidthTrackingPanel content = new WidthTrackingPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(5, 5, 8, 5));

        JPanel identity = compactGrid();
        addCompactWideField(identity, 0, "名称", name);
        addCompactField(identity, 1, 0, "类别", type);
        addCompactField(identity, 1, 1, "形状", shape);
        content.add(section("① 基本信息", identity));
        content.add(Box.createVerticalStrut(5));

        JPanel coordinates = compactGrid();
        addCompactField(coordinates, 0, 0, "X", x);
        addCompactField(coordinates, 0, 1, "Y", y);
        addCompactField(coordinates, 1, 0, "底面 Z", z);
        addCompactField(coordinates, 1, 1, "顶面 Z", surfaceZ);
        content.add(section("② 原点坐标（mm）", coordinates));
        content.add(Box.createVerticalStrut(5));

        JPanel dimensions = compactGrid();
        addCompactField(dimensions, 0, 0, "宽 X", width);
        addCompactField(dimensions, 0, 1, "深 Y", depth);
        addCompactField(dimensions, 1, 0, "高 Z", height);
        addCompactField(dimensions, 1, 1, "间隙", clearance);
        addCompactField(dimensions, 2, 0, "通道宽", apertureWidth);
        addCompactField(dimensions, 2, 1, "通道深", apertureDepth);
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        flags.add(obstacle);
        flags.add(locked);
        GridBagConstraints flagConstraints = new GridBagConstraints();
        flagConstraints.gridx = 0;
        flagConstraints.gridy = 3;
        flagConstraints.gridwidth = 4;
        flagConstraints.anchor = GridBagConstraints.WEST;
        flagConstraints.fill = GridBagConstraints.HORIZONTAL;
        dimensions.add(flags, flagConstraints);
        content.add(section("③ 外形尺寸（mm）", dimensions));

        applySurfaceZ.setText("按顶面 Z 计算高度");
        applySurfaceZ.setIcon(new WorkbenchGlyphIcon(Glyph.Measure,
                new Color(109, 184, 213)));
        applySurfaceZ.setToolTipText("把高度改为“实测顶面 Z - 底面 Z”，随后仍需应用属性。");
        applySurfaceZ.addActionListener(event -> applyMeasuredSurfaceZ());
        styleActionButton(applySurfaceZ, new Color(52, 93, 113));
        JButton apply = new JButton("✓ 应用物体属性");
        apply.setIcon(new WorkbenchGlyphIcon(Glyph.Apply, new Color(111, 212, 145)));
        apply.setToolTipText("保存右侧字段到当前物体；安全认证将失效，需要重新检查。");
        styleActionButton(apply, new Color(48, 103, 72));
        apply.addActionListener(event -> applyObject());
        JPanel propertyActions = new JPanel(new GridLayout(1, 2, 5, 0));
        propertyActions.add(applySurfaceZ);
        propertyActions.add(apply);
        propertyActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        content.add(Box.createVerticalStrut(5));
        content.add(propertyActions);
        content.add(Box.createVerticalStrut(7));

        JPanel capture = new JPanel(new BorderLayout(4, 6));
        capture.setBorder(BorderFactory.createTitledBorder("④ 实机采点与顶点示教"));
        JButton origin = new JButton("记录原点");
        origin.setIcon(new WorkbenchGlyphIcon(Glyph.Capture, new Color(116, 184, 224)));
        origin.addActionListener(event -> captureOrigin());
        JButton minimum = new JButton("记录最小角");
        minimum.setIcon(new WorkbenchGlyphIcon(Glyph.Corner, new Color(116, 184, 224)));
        minimum.addActionListener(event -> captureMinimum());
        JButton maximum = new JButton("最大角并算尺寸");
        maximum.setIcon(new WorkbenchGlyphIcon(Glyph.Measure, new Color(116, 184, 224)));
        maximum.addActionListener(event -> captureMaximum());
        JButton polygonPoint = new JButton("追加轮廓点");
        polygonPoint.setIcon(new WorkbenchGlyphIcon(Glyph.Corner, new Color(187, 150, 222)));
        polygonPoint.addActionListener(event -> capturePolygonPoint());
        JButton clearPolygon = new JButton("清空轮廓");
        clearPolygon.setIcon(new WorkbenchGlyphIcon(Glyph.Delete, new Color(226, 126, 116)));
        clearPolygon.addActionListener(event -> clearPolygon());
        JButton moveVertex = new JButton("吸嘴到顶点", Icons.centerTool);
        moveVertex.addActionListener(event -> moveToSelectedVertex());
        JButton moveCameraVertex = new JButton("相机到顶点", Icons.centerCamera);
        moveCameraVertex.addActionListener(event -> moveCameraToSelectedVertex());
        JButton confirmVertex = new JButton("吸嘴定义顶点", Icons.captureTool);
        confirmVertex.addActionListener(event -> confirmSelectedVertex());
        JButton confirmVertexByCamera =
                new JButton("相机定义顶点", Icons.captureCamera);
        confirmVertexByCamera.addActionListener(event -> confirmSelectedVertexByCamera());
        for (JButton button : Arrays.asList(origin, minimum, maximum, polygonPoint,
                moveVertex, moveCameraVertex, confirmVertex, confirmVertexByCamera)) {
            styleActionButton(button, new Color(55, 81, 103));
        }
        styleActionButton(clearPolygon, new Color(103, 58, 58));
        JPanel captureActions = new JPanel(new GridLayout(0, 2, 5, 5));
        captureActions.add(origin);
        captureActions.add(minimum);
        captureActions.add(maximum);
        captureActions.add(polygonPoint);
        captureActions.add(clearPolygon);
        captureActions.add(moveVertex);
        captureActions.add(moveCameraVertex);
        captureActions.add(confirmVertex);
        captureActions.add(confirmVertexByCamera);
        capture.add(captureActions, BorderLayout.CENTER);
        JTextArea captureHint = new JTextArea(
                "蓝色按钮读取或移动，紫色按钮编辑轮廓，红色按钮清除。顶点右键也可直接输入或读取吸嘴坐标。");
        captureHint.setEditable(false);
        captureHint.setLineWrap(true);
        captureHint.setWrapStyleWord(true);
        captureHint.setOpaque(false);
        captureHint.setRows(2);
        capture.add(captureHint, BorderLayout.SOUTH);
        capture.setMaximumSize(new Dimension(Integer.MAX_VALUE, 255));
        content.add(capture);

        propertyTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        propertyTabs.setPreferredSize(new Dimension(430, 540));
        propertyTabs.setMinimumSize(new Dimension(310, 300));
        JScrollPane objectScroll = new JScrollPane(content);
        objectScroll.setBorder(BorderFactory.createEmptyBorder());
        objectScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        objectScroll.getVerticalScrollBar().setUnitIncrement(18);
        propertyTabs.addTab("物体/采点", objectScroll);
        propertyTabs.addTab("贴装头/吸嘴", buildToolProfilesPanel());
        propertyTabs.addTab("移动/坐标", buildCoordinatePanel());
        JScrollPane motionScroll = new JScrollPane(buildMotionPanel());
        motionScroll.setBorder(BorderFactory.createEmptyBorder());
        motionScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        propertyTabs.addTab("轨迹/预演", motionScroll);
        return propertyTabs;
    }

    private Component buildToolProfilesPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 6));
        toolProfileList.setVisibleRowCount(5);
        toolProfileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolProfileList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showToolProfile(toolProfileList.getSelectedValue());
            }
        });
        panel.add(new JScrollPane(toolProfileList), BorderLayout.NORTH);

        JPanel fields = compactGrid();
        fields.setBorder(BorderFactory.createTitledBorder("贴装头与吸嘴安全包络（mm）"));
        addCompactWideField(fields, 0, "名称", profileName);
        addCompactField(fields, 1, 0, "机头半宽 X", profileBodyRadiusX);
        addCompactField(fields, 1, 1, "机头半深 Y", profileBodyRadiusY);
        addCompactField(fields, 2, 0, "最低 Z 偏移", profileBodyMinZ);
        addCompactField(fields, 2, 1, "最高 Z 偏移", profileBodyMaxZ);
        addCompactField(fields, 3, 0, "吸嘴杆半径", profileShaftRadius);
        addCompactField(fields, 3, 1, "安全间隙", profileClearance);
        addCompactField(fields, 4, 0, "收起标定 Z", profileRetractedZ);
        addCompactField(fields, 4, 1, "伸出标定 Z", profileExtendedZ);
        addCompactField(fields, 5, 0, "收起长度", profileRetractedLength);
        addCompactField(fields, 5, 1, "伸出长度", profileExtendedLength);
        JPanel profileFlags = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        profileFlags.add(profileEnabled);
        profileFlags.add(profileBodyFollowsZ);
        profileFlags.add(new JLabel("长度按两标定点插值"));
        GridBagConstraints flagConstraints = new GridBagConstraints();
        flagConstraints.gridx = 0;
        flagConstraints.gridy = 6;
        flagConstraints.gridwidth = 4;
        flagConstraints.fill = GridBagConstraints.HORIZONTAL;
        fields.add(profileFlags, flagConstraints);

        JPanel actions = new JPanel(new GridLayout(0, 2, 5, 5));
        JButton captureRetracted = new JButton("读取收起 Z");
        captureRetracted.setIcon(new WorkbenchGlyphIcon(Glyph.Capture,
                new Color(121, 195, 224)));
        captureRetracted.addActionListener(event -> captureProfileZ(true));
        JButton captureExtended = new JButton("读取伸出 Z");
        captureExtended.setIcon(new WorkbenchGlyphIcon(Glyph.Capture,
                new Color(121, 195, 224)));
        captureExtended.addActionListener(event -> captureProfileZ(false));
        JButton apply = new JButton("应用吸嘴包络");
        apply.setIcon(new WorkbenchGlyphIcon(Glyph.Apply, new Color(119, 218, 153)));
        apply.addActionListener(event -> applyToolProfile());
        styleActionButton(captureRetracted, new Color(52, 87, 110));
        styleActionButton(captureExtended, new Color(52, 87, 110));
        styleActionButton(apply, new Color(47, 102, 70));
        actions.add(captureRetracted);
        actions.add(captureExtended);
        actions.add(apply);
        JTextArea hint = new JTextArea(
                "每支吸嘴独立建模。当前贴装头移动 XY 时，同头其他吸嘴同步平移；"
                        + "只有实际使用该 Z 轴的吸嘴伸缩。携带元件后，元件外形和高度会自动扩大包络。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setRows(4);
        JPanel hintPanel = new JPanel(new BorderLayout());
        hintPanel.add(hint, BorderLayout.CENTER);
        actions.add(hintPanel);

        JPanel body = new JPanel(new BorderLayout(4, 5));
        body.add(fields, BorderLayout.NORTH);
        body.add(actions, BorderLayout.CENTER);
        JScrollPane profileScroll = new JScrollPane(body);
        profileScroll.setBorder(BorderFactory.createEmptyBorder());
        profileScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        profileScroll.getVerticalScrollBar().setUnitIncrement(18);
        panel.add(profileScroll, BorderLayout.CENTER);
        if (!toolProfileListModel.isEmpty()) {
            toolProfileList.setSelectedIndex(0);
        }
        return panel;
    }

    private Component buildCoordinatePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel body = new WidthTrackingPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(6, 6, 8, 6));

        JPanel selectors = compactGrid();
        selectors.setBorder(BorderFactory.createTitledBorder("目标与示教坐标"));
        addCompactWideField(selectors, 0, "控制吸嘴", workbenchNozzle);
        addCompactWideField(selectors, 1, "飞达取料", workbenchFeeder);
        addCompactField(selectors, 2, 0, "X", coordinateX);
        addCompactField(selectors, 2, 1, "Y", coordinateY);
        addCompactField(selectors, 3, 0, "Z", coordinateZ);
        addCompactField(selectors, 3, 1, "R", coordinateRotation);
        addCompactField(selectors, 4, 0, "点动步距", jogStep);
        motionArmed.setText("启用 3D 自定义运动");
        GridBagConstraints armConstraints = new GridBagConstraints();
        armConstraints.gridx = 2;
        armConstraints.gridy = 4;
        armConstraints.gridwidth = 2;
        armConstraints.anchor = GridBagConstraints.WEST;
        selectors.add(motionArmed, armConstraints);
        body.add(selectors);
        body.add(Box.createVerticalStrut(7));
        body.add(buildNativeMachineControls());
        body.add(Box.createVerticalStrut(7));

        JPanel reads = new JPanel(new GridLayout(0, 2, 5, 5));
        reads.setBorder(BorderFactory.createTitledBorder("读取坐标（不会移动机器）"));
        JButton readCurrent = new JButton("读取当前吸嘴 XYZ/R");
        readCurrent.addActionListener(event -> readCurrentCoordinate());
        JButton readZ = new JButton("仅读取当前吸嘴 Z");
        readZ.addActionListener(event -> readCurrentNozzleZ());
        JButton readFeeder = new JButton("读取所选飞达拾取坐标");
        readFeeder.addActionListener(event -> readSelectedFeederCoordinate());
        JButton readVertex = new JButton("使用 3D 所选端点坐标");
        readVertex.addActionListener(event -> useSelectedVertexCoordinate());
        reads.add(readCurrent);
        reads.add(readZ);
        reads.add(readFeeder);
        reads.add(readVertex);
        for (JButton button : Arrays.asList(readCurrent, readZ, readFeeder, readVertex)) {
            button.setIcon(new WorkbenchGlyphIcon(Glyph.Capture,
                    new Color(121, 195, 224)));
            styleActionButton(button, new Color(52, 87, 110));
        }
        body.add(reads);
        body.add(Box.createVerticalStrut(7));

        JPanel moves = new JPanel(new GridLayout(0, 2, 5, 5));
        moves.setBorder(BorderFactory.createTitledBorder("3D 工作台专用受控动作"));
        JButton move = new JButton("安全 Z 路径移动到坐标");
        move.addActionListener(event -> moveToCoordinate(false));
        JButton moveAndRelease = new JButton("移动到坐标并释放物料");
        moveAndRelease.addActionListener(event -> moveToCoordinate(true));
        move.setIcon(new WorkbenchGlyphIcon(Glyph.Corner, new Color(238, 185, 92)));
        moveAndRelease.setIcon(new WorkbenchGlyphIcon(Glyph.Corner,
                new Color(239, 145, 104)));
        styleActionButton(move, new Color(112, 82, 43));
        styleActionButton(moveAndRelease, new Color(116, 64, 48));
        moves.add(move);
        moves.add(moveAndRelease);
        body.add(moves);
        body.add(Box.createVerticalStrut(7));

        vertexStatus.setToolTipText("先在 3D 视图点击黄色顶点手柄；点击不会立即驱动机器。");
        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.setBorder(BorderFactory.createTitledBorder("状态"));
        status.add(vertexStatus);
        status.add(Box.createVerticalStrut(4));
        status.add(coordinateStatus);
        body.add(status);

        JTextArea hint = new JTextArea(
                "上方控制区直接复用原生控制面板的动作、图标、步距和速度。"
                        + "这些原生按钮遵循 OpenPnP 自身的启用、回零和安全检查；"
                        + "安全锁只约束本页的 3D 专用移动、端点示教和释放动作。"
                        + "整段移动始终先预演并优先使用安全 Z 路径。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setRows(5);
        hint.setBorder(new EmptyBorder(7, 4, 4, 4));
        body.add(hint);

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private Component buildNativeMachineControls() {
        MachineControlsPanel controls = frame.getMachineControls();
        JogControlsPanel jogControls = controls.getJogControlsPanel();
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                "原生控制面板（同步动作、速度与步距）"));

        JPanel system = new JPanel(new GridLayout(1, 4, 5, 5));
        system.add(nativeActionButton(controls.startStopMachineAction, "机器启动 / 停止"));
        system.add(nativeActionButton(controls.homeAction, "机器回零"));
        JButton copy = new JButton(controls.copyDroAction);
        copy.setToolTipText("复制原生控制面板当前坐标");
        system.add(copy);
        panel.add(system, BorderLayout.NORTH);

        JPanel axes = new JPanel(new GridLayout(1, 3, 7, 0));
        JPanel xy = new JPanel(new GridLayout(3, 3, 3, 3));
        xy.setBorder(BorderFactory.createTitledBorder("X / Y"));
        xy.add(new JLabel());
        xy.add(nativeActionButton(jogControls.yPlusAction, "Y+"));
        xy.add(new JLabel());
        xy.add(nativeActionButton(jogControls.xMinusAction, "X−"));
        xy.add(nativeActionButton(jogControls.xyParkAction, "XY 停靠"));
        xy.add(nativeActionButton(jogControls.xPlusAction, "X+"));
        xy.add(new JLabel());
        xy.add(nativeActionButton(jogControls.yMinusAction, "Y−"));
        xy.add(new JLabel());

        JPanel zAxis = new JPanel(new GridLayout(3, 1, 3, 3));
        zAxis.setBorder(BorderFactory.createTitledBorder("Z"));
        zAxis.add(nativeActionButton(jogControls.zPlusAction, "Z+"));
        zAxis.add(nativeActionButton(jogControls.zParkAction, "Z 停靠"));
        zAxis.add(nativeActionButton(jogControls.zMinusAction, "Z−"));

        JPanel cAxis = new JPanel(new GridLayout(3, 1, 3, 3));
        cAxis.setBorder(BorderFactory.createTitledBorder("C"));
        cAxis.add(nativeActionButton(jogControls.cPlusAction, "C+"));
        cAxis.add(nativeActionButton(jogControls.cParkAction, "C 轴归位"));
        cAxis.add(nativeActionButton(jogControls.cMinusAction, "C−"));
        axes.add(xy);
        axes.add(zAxis);
        axes.add(cAxis);
        panel.add(axes, BorderLayout.CENTER);

        JPanel alignment = new JPanel(new GridLayout(1, 3, 5, 5));
        alignment.add(nativeActionButton(controls.targetToolAction, "吸嘴对准相机"));
        alignment.add(nativeActionButton(controls.targetCameraAction, "相机对准吸嘴"));
        JButton safeZ = new JButton(jogControls.safezAction);
        safeZ.setHideActionText(true);
        safeZ.setToolTipText("全部贴装头抬到安全 Z");
        alignment.add(safeZ);
        panel.add(alignment, BorderLayout.SOUTH);
        return panel;
    }

    private JButton nativeActionButton(Action action, String tooltip) {
        JButton button = new JButton(action);
        button.setHideActionText(true);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(44, 36));
        return button;
    }

    private Component buildMotionPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 7));
        panel.setBorder(new EmptyBorder(6, 6, 8, 6));

        JPanel upper = new JPanel();
        upper.setLayout(new BoxLayout(upper, BoxLayout.Y_AXIS));
        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.setBorder(BorderFactory.createTitledBorder("轨迹参数"));
        addField(fields, "轨迹类型", route);
        addField(fields, "中心 / 目标 X", targetX);
        addField(fields, "中心 / 目标 Y", targetY);
        addField(fields, "对焦 / 目标 Z", targetZ);
        addField(fields, "飞拍跨度", flightSpan);
        addField(fields, "速度倍率", speed);
        upper.add(fields);
        upper.add(Box.createVerticalStrut(7));

        JPanel actions = new JPanel(new GridLayout(0, 1, 5, 5));
        actions.setBorder(BorderFactory.createTitledBorder("执行顺序"));
        JButton useSelected = new JButton("① 使用所选物体中心作为目标");
        useSelected.addActionListener(event -> useSelectedObjectCenter());
        simulateButton.setText("② 3D 预演与整段碰撞检查");
        simulateButton.addActionListener(event -> simulate());
        JButton execute = new JButton("③ 受控真机测试");
        execute.addActionListener(event -> executePhysicalTest());
        actions.add(useSelected);
        actions.add(simulateButton);
        actions.add(execute);
        upper.add(actions);
        panel.add(upper, BorderLayout.NORTH);

        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setRows(10);
        result.setText("真机按钮不会因“看起来没问题”而解锁：必须实验模式开启、模型已认证、"
                + "机器已回零、吸嘴无料、软限位可达，并再次通过整段扫掠检查。");
        JScrollPane resultScroll = new JScrollPane(result);
        resultScroll.setBorder(BorderFactory.createTitledBorder("预演与检查结果"));
        panel.add(resultScroll, BorderLayout.CENTER);
        physicalConfirmation.setBorder(new EmptyBorder(7, 3, 3, 3));
        panel.add(physicalConfirmation, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel compactGrid() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(2, 3, 3, 3));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        return panel;
    }

    private static JPanel section(String title, Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        return panel;
    }

    private static void addCompactField(JPanel panel, int row, int column,
            String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = column * 2;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.EAST;
        labelConstraints.insets = new Insets(3, column == 0 ? 1 : 7, 3, 3);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = column * 2 + 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(2, 0, 2, 2);
        panel.add(field, fieldConstraints);
    }

    private static void addCompactWideField(JPanel panel, int row,
            String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.EAST;
        labelConstraints.insets = new Insets(3, 1, 3, 3);
        panel.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.gridwidth = 3;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(2, 0, 2, 2);
        panel.add(field, fieldConstraints);
    }

    private static void styleActionButton(JButton button, Color background) {
        button.setBackground(background);
        button.setForeground(new Color(239, 244, 247));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setIconTextGap(5);
        button.putClientProperty("JButton.buttonType", "roundRect");
    }

    private static void addField(JPanel panel, String label, Component field) {
        panel.add(new JLabel(label));
        panel.add(field);
    }

    private void importOpenPnpConfiguration() {
        org.openpnp.spi.Machine machine = Configuration.get().getMachine();
        JCheckBox workArea = new JCheckBox("工作范围：XYZ 轴已启用的软限位", true);
        JCheckBox homeAndPark = new JCheckBox("Home 点和各贴装头停靠点", true);
        JCheckBox changer = new JCheckBox("自动换嘴实体坞（按左右配置自动对称放置）", true);
        JCheckBox discard = new JCheckBox("机器抛料点", true);
        JCheckBox bottomCameras = new JCheckBox("向上看的底部相机实体", true);

        List<FeederType> feederTypes = SafetyWorkbenchImportService.feederTypes(machine);
        JCheckBox feeders = new JCheckBox("导入飞达实体（按类型选择，共 "
                + machine.getFeeders().size() + " 个）", !feederTypes.isEmpty());
        feeders.setEnabled(!feederTypes.isEmpty());
        Map<String, JCheckBox> feederTypeChecks = new LinkedHashMap<>();
        JPanel feederTypeRows = new JPanel();
        feederTypeRows.setLayout(new BoxLayout(feederTypeRows, BoxLayout.Y_AXIS));
        feederTypeRows.setBorder(new EmptyBorder(4, 18, 5, 4));
        for (FeederType feederType : feederTypes) {
            String count = feederType.getEnabledCount() == feederType.getCount()
                    ? "共 " + feederType.getCount() + " 个"
                    : "共 " + feederType.getCount() + " 个，已启用 "
                            + feederType.getEnabledCount() + " 个";
            JCheckBox typeChoice = new JCheckBox(feederType.getLabel() + " — " + count, true);
            typeChoice.setToolTipText("仅同步这一类飞达；不会删除其他类型已导入的实体");
            feederTypeChecks.put(feederType.getKey(), typeChoice);
            feederTypeRows.add(typeChoice);
        }

        JButton selectAllFeederTypes = new JButton("全选类型");
        JButton clearAllFeederTypes = new JButton("全不选");
        JPanel feederTypeActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        feederTypeActions.add(selectAllFeederTypes);
        feederTypeActions.add(clearAllFeederTypes);
        feederTypeRows.add(Box.createVerticalStrut(4));
        feederTypeRows.add(feederTypeActions);
        selectAllFeederTypes.addActionListener(event -> feederTypeChecks.values()
                .forEach(choice -> choice.setSelected(true)));
        clearAllFeederTypes.addActionListener(event -> feederTypeChecks.values()
                .forEach(choice -> choice.setSelected(false)));

        JScrollPane feederTypeScroll = new JScrollPane(feederTypeRows);
        feederTypeScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Color.GRAY));
        feederTypeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        feederTypeScroll.setPreferredSize(new Dimension(560,
                Math.min(190, 38 + feederTypes.size() * 26)));
        feederTypeScroll.setVisible(false);
        JToggleButton feederDisclosure = new JToggleButton(
                "▶ 展开飞达类型（" + feederTypes.size() + " 类）", false);
        feederDisclosure.setEnabled(!feederTypes.isEmpty());
        JPanel feederHeader = new JPanel(new BorderLayout(8, 0));
        feederHeader.add(feeders, BorderLayout.CENTER);
        feederHeader.add(feederDisclosure, BorderLayout.EAST);
        JPanel feederGroup = new JPanel(new BorderLayout());
        feederGroup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                new EmptyBorder(4, 5, 4, 5)));
        feederGroup.add(feederHeader, BorderLayout.NORTH);
        feederGroup.add(feederTypeScroll, BorderLayout.CENTER);
        feederDisclosure.addActionListener(event -> {
            boolean expanded = feederDisclosure.isSelected();
            feederDisclosure.setText(expanded
                    ? "▼ 收起飞达类型" : "▶ 展开飞达类型（" + feederTypes.size() + " 类）");
            feederTypeScroll.setVisible(expanded);
            feederGroup.revalidate();
            Window window = SwingUtilities.getWindowAncestor(feederGroup);
            if (window != null) {
                window.pack();
                window.setLocationRelativeTo(this);
            }
        });
        Runnable updateFeederChoices = () -> {
            boolean enabled = feeders.isSelected() && feeders.isEnabled();
            feederTypeChecks.values().forEach(choice -> choice.setEnabled(enabled));
            selectAllFeederTypes.setEnabled(enabled);
            clearAllFeederTypes.setEnabled(enabled);
        };
        feeders.addActionListener(event -> updateFeederChoices.run());
        updateFeederChoices.run();

        JPanel choices = new JPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.add(new JLabel("选择要从当前 OpenPnP 机器配置同步到 3D 工作台的内容："));
        choices.add(Box.createVerticalStrut(8));
        choices.add(workArea);
        choices.add(homeAndPark);
        choices.add(changer);
        choices.add(discard);
        choices.add(Box.createVerticalStrut(4));
        choices.add(feederGroup);
        choices.add(Box.createVerticalStrut(4));
        choices.add(bottomCameras);
        choices.add(Box.createVerticalStrut(8));
        choices.add(new JLabel("再次导入会更新实体并迁移旧版点标记；实体顶面与机器配置 Z 对齐。"));
        if (JOptionPane.showConfirmDialog(this, choices, "从 OpenPnP 导入安全区与坐标",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        EnumSet<Category> categories = EnumSet.noneOf(Category.class);
        if (workArea.isSelected()) {
            categories.add(Category.WORK_AREA);
        }
        if (homeAndPark.isSelected()) {
            categories.add(Category.HOME_AND_PARK);
        }
        if (changer.isSelected()) {
            categories.add(Category.NOZZLE_CHANGER);
        }
        if (discard.isSelected()) {
            categories.add(Category.DISCARD);
        }
        if (feeders.isSelected()) {
            categories.add(Category.FEEDER_PICK_POINTS);
        }
        if (bottomCameras.isSelected()) {
            categories.add(Category.BOTTOM_CAMERAS);
        }
        Set<String> selectedFeederTypeKeys = new LinkedHashSet<>();
        for (Map.Entry<String, JCheckBox> entry : feederTypeChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                selectedFeederTypeKeys.add(entry.getKey());
            }
        }
        SafetyWorkbenchImportService.Summary summary =
                SafetyWorkbenchImportService.importConfiguration(model,
                        machine, selectedWorkbenchNozzle(), categories, selectedFeederTypeKeys);
        reloadObjects();
        selectFirstObject();
        updateCertification();
        canvas.repaint();
        result.setText(summary.toDisplayText()
                + "\n飞达、底部相机和换嘴坞按真实坐标生成可碰撞实体；"
                + "顶面 Z 对齐机器配置坐标，3D 网格显示自动识别的工作面 Z。"
                + "请核对外形尺寸、方向和范围，再重新认证。");
        JOptionPane.showMessageDialog(this, summary.toDisplayText(), "导入完成",
                summary.getWarnings().isEmpty()
                        ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private void reloadObjects() {
        objectListModel.clear();
        for (SafetyObject object : model.getObjects()) {
            objectListModel.addElement(object);
        }
        canvas.repaint();
    }

    private void selectFirstObject() {
        if (!objectListModel.isEmpty()) {
            objectList.setSelectedIndex(0);
        }
    }

    private void selectObjectById(String id) {
        if (id != null) {
            for (int index = 0; index < objectListModel.size(); index++) {
                SafetyObject object = objectListModel.get(index);
                if (id.equals(object.id)) {
                    objectList.setSelectedIndex(index);
                    return;
                }
            }
        }
        selectFirstObject();
    }

    private void addObject(ObjectType objectType, double worldX, double worldY) {
        SafetyObject object = model.createObject(objectType);
        object.x = objectType.isReferencePoint()
                ? worldX : worldX - object.width / 2.0;
        object.y = objectType.isReferencePoint()
                ? worldY : worldY - object.depth / 2.0;
        object.z = Math.max(model.getMinZ(), 0);
        model.addObject(object);
        objectListModel.addElement(object);
        objectList.setSelectedValue(object, true);
        updateCertification();
        canvas.repaint();
    }

    private void duplicateSelected() {
        SafetyObject selected = objectList.getSelectedValue();
        if (selected == null) {
            return;
        }
        SafetyObject copy = selected.copy();
        model.addObject(copy);
        objectListModel.addElement(copy);
        objectList.setSelectedValue(copy, true);
        updateCertification();
    }

    private void removeSelected() {
        SafetyObject selected = objectList.getSelectedValue();
        if (selected == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "删除“" + selected.name + "”？",
                "删除物体", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        int index = objectList.getSelectedIndex();
        model.removeObject(selected);
        objectListModel.removeElement(selected);
        if (!objectListModel.isEmpty()) {
            objectList.setSelectedIndex(Math.min(index, objectListModel.size() - 1));
        }
        updateCertification();
        canvas.repaint();
    }

    private void showObject(SafetyObject object) {
        boolean enabled = object != null;
        for (Component component : Arrays.asList(name, type, shape, x, y, z, width, depth,
                height, surfaceZ, applySurfaceZ, clearance, apertureWidth, apertureDepth,
                obstacle, locked)) {
            component.setEnabled(enabled);
        }
        if (!enabled) {
            return;
        }
        name.setText(object.name);
        type.setSelectedItem(object.type);
        shape.setSelectedItem(object.shape);
        x.setText(format(object.x));
        y.setText(format(object.y));
        z.setText(format(object.z));
        width.setText(format(object.width));
        depth.setText(format(object.depth));
        height.setText(format(object.height));
        surfaceZ.setText(format(object.z + object.height));
        clearance.setText(format(object.clearance));
        apertureWidth.setText(format(object.apertureWidth));
        apertureDepth.setText(format(object.apertureDepth));
        obstacle.setSelected(object.obstacle);
        locked.setSelected(object.locked);
    }

    private void applyMeasuredSurfaceZ() {
        try {
            double baseZ = parse(z);
            double measuredSurfaceZ = parse(surfaceZ);
            double measuredHeight = measuredSurfaceZ - baseZ;
            if (!Double.isFinite(measuredHeight) || measuredHeight < 0) {
                throw new IllegalArgumentException("实测顶面 Z 不能低于底面 Z。");
            }
            height.setText(format(measuredHeight));
            result.setText(String.format(Locale.ROOT,
                    "已根据底面 Z %.3f mm 和实测顶面 Z %.3f mm 计算高度 %.3f mm。"
                            + "请点击“应用属性”，检查后重新认证模型。",
                    baseZ, measuredSurfaceZ, measuredHeight));
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "顶面高度无效",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void applyObject() {
        SafetyObject object = objectList.getSelectedValue();
        if (object == null) {
            return;
        }
        try {
            object.name = name.getText().trim().isEmpty() ? "未命名物体" : name.getText().trim();
            object.type = (ObjectType) type.getSelectedItem();
            object.shape = (Shape) shape.getSelectedItem();
            object.x = parse(x);
            object.y = parse(y);
            object.z = parse(z);
            object.width = positive(width, "宽度");
            object.depth = positive(depth, "深度");
            object.height = nonNegative(height, "高度");
            object.clearance = nonNegative(clearance, "安全间隙");
            object.apertureWidth = nonNegative(apertureWidth, "中央通道宽度");
            object.apertureDepth = nonNegative(apertureDepth, "中央通道深度");
            object.obstacle = obstacle.isSelected();
            object.locked = locked.isSelected();
            surfaceZ.setText(format(object.z + object.height));
            model.touch();
            objectList.repaint();
            canvas.repaint();
            updateCertification();
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "属性无效",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void captureOrigin() {
        SafetyObject object = requireSelected();
        Location location = currentToolLocation();
        if (object == null || location == null) {
            return;
        }
        object.x = location.getX();
        object.y = location.getY();
        object.z = location.getZ();
        model.touch();
        showObject(object);
        canvas.repaint();
        updateCertification();
        result.setText("已把工具头当前位置记录为“" + object.name + "”原点。");
    }

    private void captureMinimum() {
        if (requireSelected() == null) {
            return;
        }
        captureMinimum = currentToolLocation();
        if (captureMinimum != null) {
            result.setText(String.format(Locale.ROOT,
                    "已记录最小角点 X %.3f / Y %.3f / Z %.3f mm。请点动到对角的最大角点。",
                    captureMinimum.getX(), captureMinimum.getY(), captureMinimum.getZ()));
        }
    }

    private void captureMaximum() {
        SafetyObject object = requireSelected();
        if (object == null) {
            return;
        }
        if (captureMinimum == null) {
            JOptionPane.showMessageDialog(this, "请先记录最小角点。", "缺少第一个点",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        Location maximum = currentToolLocation();
        if (maximum == null) {
            return;
        }
        object.x = Math.min(captureMinimum.getX(), maximum.getX());
        object.y = Math.min(captureMinimum.getY(), maximum.getY());
        object.z = Math.min(captureMinimum.getZ(), maximum.getZ());
        object.width = Math.max(0.01, Math.abs(maximum.getX() - captureMinimum.getX()));
        object.depth = Math.max(0.01, Math.abs(maximum.getY() - captureMinimum.getY()));
        object.height = Math.max(0.01, Math.abs(maximum.getZ() - captureMinimum.getZ()));
        captureMinimum = null;
        model.touch();
        showObject(object);
        canvas.repaint();
        updateCertification();
        result.setText("已由两个实机角点计算物体原点和三轴尺寸。请再增加安全间隙，"
                + "不要把测得外形直接当作零余量安全边界。");
    }

    private void capturePolygonPoint() {
        SafetyObject object = requireSelected();
        Location location = currentToolLocation();
        if (object == null || location == null) {
            return;
        }
        object.shape = Shape.POLYGON;
        object.polygon.add(new Point2(location.getX() - object.x, location.getY() - object.y));
        model.touch();
        showObject(object);
        canvas.repaint();
        updateCertification();
        result.setText("已追加轮廓点 " + object.polygon.size()
                + "。请按顺时针或逆时针沿边界继续采点，至少需要 3 点。");
    }

    private void clearPolygon() {
        SafetyObject object = requireSelected();
        if (object == null) {
            return;
        }
        object.polygon.clear();
        model.touch();
        canvas.repaint();
        updateCertification();
    }

    private SafetyObject requireSelected() {
        SafetyObject object = objectList.getSelectedValue();
        if (object == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个已放置物体。", "未选择物体",
                    JOptionPane.WARNING_MESSAGE);
        }
        return object;
    }

    private Location currentToolLocation() {
        HeadMountable tool = selectedWorkbenchTool();
        if (tool == null) {
            JOptionPane.showMessageDialog(this, "请先在主界面选择要用于标定的工具头/吸嘴。",
                    "未选择工具头", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            return tool.getLocation().convertToUnits(LengthUnit.Millimeters);
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "无法读取工具头位置：" + exception.getMessage(),
                    "读取失败", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void readCurrentCoordinate() {
        Location location = currentToolLocation();
        if (location != null) {
            setCoordinate(location);
        }
    }

    private void readCurrentNozzleZ() {
        Location location = currentToolLocation();
        if (location != null) {
            coordinateZ.setValue(location.getZ());
            coordinateStatus.setText("已读取当前吸嘴 Z=" + format(location.getZ()) + " mm");
        }
    }

    private void readSelectedFeederCoordinate() {
        Feeder feeder = (Feeder) workbenchFeeder.getSelectedItem();
        if (feeder == null) {
            JOptionPane.showMessageDialog(this, "当前机器没有可选飞达。", "未选择飞达",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Location location = feeder.getPickLocation();
            if (location == null) {
                throw new Exception("飞达没有返回取料坐标");
            }
            setCoordinate(location);
            coordinateStatus.setText("已读取飞达“" + feeder.getName() + "”的取料坐标");
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "无法读取飞达取料坐标：" + exception.getMessage(),
                    "读取失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void useSelectedVertexCoordinate() {
        Vertex vertex = canvas.getSelectedVertex();
        if (vertex != null) {
            setCoordinate(vertex.getLocation());
            vertexStatus.setText("已选择：" + vertex.getLabel());
            return;
        }
        SafetyObject object = requireSelected();
        if (object == null) {
            return;
        }
        double px = object.type.isReferencePoint() ? object.x : object.x + object.width / 2.0;
        double py = object.type.isReferencePoint() ? object.y : object.y + object.depth / 2.0;
        double pz = object.type.isReferencePoint() ? object.z
                : object.z + object.height + object.clearance;
        setCoordinate(new Location(LengthUnit.Millimeters, px, py, pz, 0));
        vertexStatus.setText("未选顶点，已使用“" + object.name + "”参考位置");
    }

    private void setCoordinate(Location source) {
        Location location = source.convertToUnits(LengthUnit.Millimeters);
        coordinateX.setValue(location.getX());
        coordinateY.setValue(location.getY());
        coordinateZ.setValue(location.getZ());
        coordinateRotation.setValue(location.getRotation());
        coordinateStatus.setText(String.format(Locale.ROOT,
                "目标 X %.3f / Y %.3f / Z %.3f / R %.2f",
                location.getX(), location.getY(), location.getZ(), location.getRotation()));
    }

    private void onVertexSelected(Vertex vertex) {
        if (vertex == null) {
            vertexStatus.setText("请在 3D 视图点击物体顶点");
            return;
        }
        setCoordinate(vertex.getLocation());
        vertexStatus.setText("已选择：" + vertex.getLabel());
    }

    private void moveToSelectedVertex() {
        Vertex vertex = canvas.getSelectedVertex();
        if (vertex == null) {
            JOptionPane.showMessageDialog(this,
                    "请先在 3D 视图点击所选物体上显示的黄色顶点手柄。",
                    "未选择顶点", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setCoordinate(vertex.getLocation());
        moveToCoordinate(false, true);
    }

    private Camera selectedDefaultCamera(Nozzle nozzle) {
        if (nozzle == null || nozzle.getHead() == null) {
            JOptionPane.showMessageDialog(this, "请先选择带有头部相机的吸嘴。",
                    "相机不可用", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            Camera camera = nozzle.getHead().getDefaultCamera();
            if (camera == null) {
                throw new Exception("贴装头没有默认相机");
            }
            return camera;
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this,
                    "无法取得所选吸嘴的头部相机：" + exception.getMessage(),
                    "相机不可用", JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private void moveCameraToSelectedVertex() {
        Vertex vertex = canvas.getSelectedVertex();
        if (vertex == null) {
            JOptionPane.showMessageDialog(this,
                    "请先在 3D 视图点击所选物体上显示的黄色端点手柄。",
                    "未选择端点", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Nozzle nozzle = requireMotionReady();
        Camera camera = selectedDefaultCamera(nozzle);
        if (camera == null) {
            return;
        }
        Location target = vertex.getLocation();
        if (!checkReachable(camera, target)) {
            return;
        }
        setCoordinate(target);
        UiUtils.submitUiMachineTask(() -> {
            frame.getMachineControls().checkJogMotionSafety(camera, target);
            MovableUtils.moveToLocationAtSafeZ(camera, target);
            MovableUtils.fireTargetedUserAction(camera);
        });
    }

    private void confirmSelectedVertex() {
        Vertex vertex = canvas.getSelectedVertex();
        if (vertex == null) {
            JOptionPane.showMessageDialog(this, "请先在 3D 视图选择一个顶点。",
                    "未选择顶点", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Location location = currentToolLocation();
        if (location == null) {
            return;
        }
        applySelectedVertexLocation(vertex, location, "吸嘴");
    }

    private void confirmSelectedVertexByCamera() {
        Vertex vertex = canvas.getSelectedVertex();
        Nozzle nozzle = selectedWorkbenchNozzle();
        if (vertex == null) {
            JOptionPane.showMessageDialog(this, "请先在 3D 视图选择一个端点。",
                    "未选择端点", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Camera camera = selectedDefaultCamera(nozzle);
        if (camera == null) {
            return;
        }
        try {
            Location location = camera.getLocation()
                    .convertToUnits(LengthUnit.Millimeters);
            applySelectedVertexLocation(vertex, location, "相机");
        }
        catch (Exception exception) {
            UiUtils.showError(this, "读取相机位置失败", exception);
        }
    }

    private void applySelectedVertexLocation(Vertex vertex, Location location, String source) {
        SafetyWorkbenchVertexEditor.apply(vertex, location);
        model.touch();
        showObject(vertex.object);
        objectList.repaint();
        canvas.repaint();
        updateCertification();
        setCoordinate(location);
        result.setText("已用当前" + source + "坐标定义“" + vertex.getLabel()
                + "”。物体几何已更新，需要重新检查并认证。"
                + (vertex.object.sourceKey == null || vertex.object.sourceKey.isEmpty()
                        ? "" : "该实体来自 OpenPnP；再次导入会恢复机器配置坐标。"));
    }

    private void showVertexContextMenu(Component invoker, int menuX, int menuY,
            Vertex vertex) {
        if (vertex == null) {
            return;
        }
        canvas.selectVertex(vertex);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem title = new JMenuItem(vertex.getLabel());
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();

        JMenuItem setLocation = new JMenuItem("设定顶点坐标…");
        setLocation.addActionListener(event -> editVertexCoordinate(vertex));
        menu.add(setLocation);

        JMenuItem readNozzle = new JMenuItem("读取当前吸嘴坐标并设定顶点");
        readNozzle.addActionListener(event -> {
            Location location = currentToolLocation();
            if (location != null) {
                applySelectedVertexLocation(vertex, location, "吸嘴");
            }
        });
        menu.add(readNozzle);

        JMenuItem useCoordinatePanel = new JMenuItem("使用右侧坐标栏设定顶点");
        useCoordinatePanel.addActionListener(event ->
                applySelectedVertexLocation(vertex, targetCoordinate(), "坐标栏"));
        menu.add(useCoordinatePanel);
        menu.addSeparator();

        JMenuItem moveNozzle = new JMenuItem("吸嘴移动到该顶点…");
        moveNozzle.addActionListener(event -> moveToSelectedVertex());
        menu.add(moveNozzle);
        JMenuItem moveCamera = new JMenuItem("顶部相机移动到该顶点…");
        moveCamera.addActionListener(event -> moveCameraToSelectedVertex());
        menu.add(moveCamera);

        JMenuItem lockObject = new JMenuItem(vertex.object.locked ? "解除物体锁定" : "锁定物体");
        lockObject.addActionListener(event -> {
            vertex.object.locked = !vertex.object.locked;
            model.touch();
            showObject(vertex.object);
            updateCertification();
            canvas.repaint();
        });
        menu.addSeparator();
        menu.add(lockObject);
        menu.show(invoker, menuX, menuY);
    }

    private void editVertexCoordinate(Vertex vertex) {
        Location location = vertex.getLocation().convertToUnits(LengthUnit.Millimeters);
        JTextField vertexX = new JTextField(format(location.getX()));
        JTextField vertexY = new JTextField(format(location.getY()));
        JTextField vertexZ = new JTextField(format(location.getZ()));
        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        addField(fields, "X (mm)", vertexX);
        addField(fields, "Y (mm)", vertexY);
        addField(fields, "Z (mm)", vertexZ);
        if (JOptionPane.showConfirmDialog(this, fields,
                "设定顶点坐标 · " + vertex.getLabel(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Location target = new Location(LengthUnit.Millimeters,
                    parse(vertexX), parse(vertexY), parse(vertexZ), 0);
            applySelectedVertexLocation(vertex, target, "输入的");
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "顶点坐标无效",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void moveToCoordinate(boolean release) {
        moveToCoordinate(release, false);
    }

    private void moveToCoordinate(boolean release, boolean teachingVertex) {
        Nozzle nozzle = requireMotionReady();
        if (nozzle == null) {
            return;
        }
        Location target = targetCoordinate();
        if (!checkReachable(nozzle, target)) {
            return;
        }
        if (release && nozzle.getPart() == null) {
            JOptionPane.showMessageDialog(this,
                    "当前吸嘴没有记录到物料，不会执行释放动作。",
                    "吸嘴无料", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Waypoint> path;
        ValidationResult validation;
        try {
            path = safeCoordinatePath(nozzle, target);
            validation = validateManualPath(nozzle, path, teachingVertex);
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "无法完成移动前安全检查：" + exception.getMessage(),
                    "安全检查失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        canvas.setPath(path, validation.isSafe());
        if (!validation.isSafe()) {
            result.setText("坐标移动被 3D 安全工作台拒绝：\n• "
                    + String.join("\n• ", validation.getIssues()));
            JOptionPane.showMessageDialog(this, result.getText(), "路径存在碰撞风险",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (release && JOptionPane.showConfirmDialog(this,
                "吸嘴将移动到指定坐标并执行释放，然后抬升到安全 Z。\n"
                        + "请确认目标位置允许放下当前物料。",
                "确认释放物料", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        result.setText(release ? "正在移动并释放物料…" : "正在按安全 Z 路径移动…");
        List<Waypoint> executedPath = new ArrayList<>(path);
        UiUtils.submitUiMachineTask(() -> {
            double currentRotation = nozzle.getLocation().getRotation();
            for (int index = 1; index < executedPath.size(); index++) {
                Waypoint point = executedPath.get(index);
                double rotation = index >= 2 ? target.getRotation() : currentRotation;
                nozzle.moveTo(new Location(LengthUnit.Millimeters,
                        point.x, point.y, point.z, rotation),
                        Math.min(number(speed), 0.20));
            }
            nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            if (release) {
                nozzle.place();
                Waypoint safe = executedPath.get(Math.max(0, executedPath.size() - 2));
                nozzle.moveTo(new Location(LengthUnit.Millimeters,
                        safe.x, safe.y, safe.z, target.getRotation()), 0.15);
                nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            }
            return nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        }, actual -> {
            setCoordinate(actual);
            result.setText(release
                    ? "物料已释放，吸嘴已回到安全 Z。"
                    : "坐标移动完成，控制器已确认静止。");
        }, throwable -> UiUtils.showError(this, "3D 安全工作台移动失败", throwable));
    }

    private void jog(double dx, double dy, double dz) {
        Nozzle nozzle = requireMotionReady();
        if (nozzle == null) {
            return;
        }
        try {
            Location current = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            double step = ((Number) jogStep.getSelectedItem()).doubleValue();
            Location target = new Location(LengthUnit.Millimeters,
                    current.getX() + dx * step, current.getY() + dy * step,
                    current.getZ() + dz * step, current.getRotation());
            if (!checkReachable(nozzle, target)) {
                return;
            }
            List<Waypoint> path = Arrays.asList(
                    new Waypoint(current.getX(), current.getY(), current.getZ()),
                    new Waypoint(target.getX(), target.getY(), target.getZ()));
            ValidationResult validation = validateManualPath(nozzle, path,
                    canvas.getSelectedVertex() != null);
            canvas.setPath(path, validation.isSafe());
            if (!validation.isSafe()) {
                result.setText("低速点动被碰撞检查拒绝：\n• "
                        + String.join("\n• ", validation.getIssues()));
                return;
            }
            UiUtils.submitUiMachineTask(() -> {
                nozzle.moveTo(target, Math.min(number(speed), 0.12));
                nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                return nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            }, this::setCoordinate,
                    throwable -> UiUtils.showError(this, "低速点动失败", throwable));
        }
        catch (Exception exception) {
            UiUtils.showError(this, "低速点动安全检查失败", exception);
        }
    }

    private Nozzle requireMotionReady() {
        Nozzle nozzle = selectedWorkbenchNozzle();
        if (nozzle == null) {
            JOptionPane.showMessageDialog(this, "请先在本窗口选择要控制的吸嘴。",
                    "未选择吸嘴", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (!ExperimentalMotionCenterDialog.isExperimentalModeEnabled()) {
            JOptionPane.showMessageDialog(this, "请先从“窗口 → 实验模式”开启实验功能。",
                    "实验模式未开启", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (!Configuration.get().getMachine().isEnabled()
                || !Configuration.get().getMachine().isHomed()) {
            JOptionPane.showMessageDialog(this, "机器必须已连接、启用并完成回零。",
                    "机器未就绪", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (Configuration.get().getMachine().isBusy()) {
            JOptionPane.showMessageDialog(this, "机器正在执行其他任务，不能开始手动移动。",
                    "机器忙", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (!motionArmed.isSelected()) {
            JOptionPane.showMessageDialog(this,
                    "请先在“移动/坐标”页启用窗口内运动安全锁。",
                    "移动未解锁", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return nozzle;
    }

    private Location targetCoordinate() {
        return new Location(LengthUnit.Millimeters, number(coordinateX), number(coordinateY),
                number(coordinateZ), number(coordinateRotation));
    }

    private boolean checkReachable(HeadMountable tool, Location target) {
        try {
            if (tool.isReachable(target)) {
                return true;
            }
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "软限位检查失败：" + exception.getMessage(),
                    "位置不可达", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        JOptionPane.showMessageDialog(this, String.format(Locale.ROOT,
                "软限位拒绝 X %.3f / Y %.3f / Z %.3f。",
                target.getX(), target.getY(), target.getZ()),
                "位置不可达", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private List<Waypoint> safeCoordinatePath(Nozzle nozzle, Location target) throws Exception {
        Location start = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        Length effectiveSafeZ = nozzle.getEffectiveSafeZ();
        double safeZ = effectiveSafeZ == null
                ? model.getMaxZ() - model.getToolMaxZOffset()
                : effectiveSafeZ.convertToUnits(LengthUnit.Millimeters).getValue();
        return Arrays.asList(new Waypoint(start.getX(), start.getY(), start.getZ()),
                new Waypoint(start.getX(), start.getY(), safeZ),
                new Waypoint(target.getX(), target.getY(), safeZ),
                new Waypoint(target.getX(), target.getY(), target.getZ()));
    }

    private ValidationResult validateManualPath(Nozzle nozzle, List<Waypoint> path,
            boolean teachingVertex) throws Exception {
        Vertex vertex = teachingVertex ? canvas.getSelectedVertex() : null;
        SafetyObject target = vertex == null ? null : vertex.object;
        boolean obstacleState = target != null && target.obstacle;
        if (target != null) {
            target.obstacle = false;
        }
        try {
            return DynamicSafetyCollisionEngine.validate(model, path,
                    DynamicSafetyCollisionEngine.snapshot(model,
                            Configuration.get().getMachine(), nozzle));
        }
        finally {
            if (target != null) {
                target.obstacle = obstacleState;
            }
        }
    }

    private void useSelectedObjectCenter() {
        SafetyObject object = requireSelected();
        if (object == null) {
            return;
        }
        targetX.setValue(object.type.isReferencePoint()
                ? object.x : object.x + object.width / 2.0);
        targetY.setValue(object.type.isReferencePoint()
                ? object.y : object.y + object.depth / 2.0);
        targetZ.setValue(object.type.isReferencePoint()
                ? object.z : object.z + object.height + object.clearance);
    }

    private void showBottomVisionTiming() {
        if (visionTimingDialog != null && visionTimingDialog.isDisplayable()) {
            visionTimingDialog.toFront();
            return;
        }
        JDialog dialog = new JDialog(this, "底部视觉静拍耗时 · 本次启动", false);
        visionTimingDialog = dialog;
        JTextArea text = new JTextArea(18, 65);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(new EmptyBorder(12, 12, 12, 12));
        text.setText(BottomVisionTiming.summary());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("刷新");
        refresh.addActionListener(event -> text.setText(BottomVisionTiming.summary()));
        JButton copy = new JButton("复制 CSV");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(BottomVisionTiming.csv()), null));
        JButton clear = new JButton("清空本次统计");
        clear.addActionListener(event -> {
            BottomVisionTiming.clear();
            text.setText(BottomVisionTiming.summary());
        });
        actions.add(clear);
        actions.add(copy);
        actions.add(refresh);
        dialog.add(new JScrollPane(text), BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void previewCoordinatedMotion() {
        previewCoordinatedMotion(false);
    }

    private void previewCoordinatedMotion(boolean imaging) {
        for (int index = 0; index < propertyTabs.getTabCount(); index++) {
            if (propertyTabs.getTitleAt(index).contains("预演")) {
                propertyTabs.setSelectedIndex(index);
                break;
            }
        }
        Nozzle tool = selectedWorkbenchNozzle();
        if (tool == null) {
            result.setText("请先选择吸嘴。预览不需要连接机器。");
            return;
        }
        Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location target = new Location(LengthUnit.Millimeters, number(targetX),
                number(targetY), number(targetZ), start.getRotation());
        Plan plan = imaging ? DynamicSafeMotionPlanner.previewBottomVision(tool, target, number(speed))
                : DynamicSafeMotionPlanner.preview(tool, target, number(speed));
        if (!plan.isOptimized()) {
            canvas.setPath(new ArrayList<>(), false);
            result.setText("此段保持原安全 Z 路径：" + plan.getReason()
                    + "\n目标坐标取自“运动/预演”页。预览只计算，不启用开关，不移动机器。");
            return;
        }
        List<Waypoint> points = new ArrayList<>();
        points.add(new Waypoint(start.getX(), start.getY(), start.getZ()));
        for (Location location : plan.getLocations()) {
            points.add(new Waypoint(location.getX(), location.getY(), location.getZ()));
        }
        canvas.setPath(points, true);
        result.setText(String.format(Locale.ROOT,
                "联动路径通过：原分段 %.3f 秒 → 联动 %.3f 秒，预计减少 %.1f%%。\n"
                        + "按当前轴限速/加速度计算，属于软件估算；实机执行还受控制器、真空与视觉耗时影响。\n"
                        + (imaging ? "视觉直达：在 XY 行进中达到拍摄 Z，无额外末段垂直下降。"
                                : "先到局部安全高度再边走 XY 边调整 Z；接触前保留必要的竖直段。")
                        + "预览没有移动机器。",
                plan.getOriginalSeconds(), plan.getCoordinatedSeconds(),
                100 * (1 - plan.getCoordinatedSeconds() / plan.getOriginalSeconds())));
    }

    private void simulate() {
        HeadMountable tool = selectedWorkbenchTool();
        if (tool == null) {
            JOptionPane.showMessageDialog(this, "请先选择工具头，预演需要读取真实起点。",
                    "未选择工具头", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!(tool instanceof Nozzle)) {
            JOptionPane.showMessageDialog(this,
                    "多头动态碰撞预演必须选择一支真实吸嘴（例如 N1），不能选择相机或执行器。",
                    "请选择吸嘴", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Location start = tool.getLocation().convertToUnits(LengthUnit.Millimeters);
            previewPath = buildPath(tool, start);
            List<DynamicTool> toolEnvelopes = DynamicSafetyCollisionEngine.snapshot(
                    model, Configuration.get().getMachine(), tool);
            previewResult = DynamicSafetyCollisionEngine.validate(
                    model, previewPath, toolEnvelopes);
            canvas.setPath(previewPath, previewResult.isSafe());
            if (previewResult.isSafe()) {
                result.setText("预演通过：当前贴装头、同头联动吸嘴和其他贴装头的动态包络均未发生碰撞。"
                        + "本结果仍依赖模型尺寸、工具头包络、线缆余量和 Z 方向定义是否真实。");
            }
            else {
                result.setText("预演未通过：\n• "
                        + String.join("\n• ", previewResult.getIssues()));
            }
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "无法生成轨迹：" + exception.getMessage(),
                    "预演失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<Waypoint> buildPath(HeadMountable tool, Location start) throws Exception {
        double tx = number(targetX);
        double ty = number(targetY);
        double tz = number(targetZ);
        List<Waypoint> path = new ArrayList<>();
        path.add(new Waypoint(start.getX(), start.getY(), start.getZ()));
        int routeIndex = route.getSelectedIndex();
        if (routeIndex == 0) {
            Length effectiveSafeZ = tool.getEffectiveSafeZ();
            double safeZ = effectiveSafeZ == null
                    ? model.getMaxZ() - model.getToolMaxZOffset()
                    : effectiveSafeZ.convertToUnits(LengthUnit.Millimeters).getValue();
            path.add(new Waypoint(start.getX(), start.getY(), safeZ));
            path.add(new Waypoint(tx, ty, safeZ));
            path.add(new Waypoint(tx, ty, tz));
        }
        else if (routeIndex == 1) {
            path.add(new Waypoint(tx, ty, tz));
        }
        else {
            double span = number(flightSpan);
            Length effectiveSafeZ = tool.getEffectiveSafeZ();
            double safeZ = effectiveSafeZ == null
                    ? model.getMaxZ() - model.getToolMaxZOffset()
                    : effectiveSafeZ.convertToUnits(LengthUnit.Millimeters).getValue();
            path.add(new Waypoint(tx - span, ty, safeZ));
            path.add(new Waypoint(tx - span / 2.0, ty, tz));
            path.add(new Waypoint(tx + span / 2.0, ty, tz));
            path.add(new Waypoint(tx + span, ty, safeZ));
        }
        return path;
    }

    private void executePhysicalTest() {
        simulate();
        if (previewResult == null || !previewResult.isSafe()) {
            JOptionPane.showMessageDialog(this, "轨迹未通过碰撞预演，不能下发真机。",
                    "安全锁定", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!ExperimentalMotionCenterDialog.isExperimentalModeEnabled()) {
            JOptionPane.showMessageDialog(this, "请先从“窗口 → 实验模式”开启实验功能。",
                    "实验模式未开启", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!model.isCertified()) {
            JOptionPane.showMessageDialog(this,
                    "模型没有认证或认证后又被修改，真机测试已锁定。", "模型未认证",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (route.getSelectedIndex() == 2 && !isAsyncDriverActive()) {
            JOptionPane.showMessageDialog(this,
                    "联合下降—定高通过—联合抬升需要先启用 GcodeAsyncDriver。\n"
                            + "请在实验功能的“运动提速验证中心”完成驱动升级和基础测试。",
                    "异步驱动未启用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Configuration.get().getMachine().isEnabled()
                || !Configuration.get().getMachine().isHomed()) {
            JOptionPane.showMessageDialog(this, "机器必须已连接、启用并完成回零。",
                    "机器未就绪", JOptionPane.WARNING_MESSAGE);
            return;
        }
        HeadMountable tool = selectedWorkbenchTool();
        if (tool == null) {
            return;
        }
        if (tool instanceof Nozzle && ((Nozzle) tool).getPart() != null) {
            JOptionPane.showMessageDialog(this, "当前吸嘴检测到有物料，请先停靠或抛料。",
                    "吸嘴不为空", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!physicalConfirmation.isSelected()) {
            JOptionPane.showMessageDialog(this, "请先完成真机安全确认。",
                    "需要确认", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            for (Waypoint point : previewPath) {
                Location location = new Location(LengthUnit.Millimeters,
                        point.x, point.y, point.z, tool.getLocation().getRotation());
                if (!tool.isReachable(location)) {
                    JOptionPane.showMessageDialog(this,
                            String.format(Locale.ROOT,
                                    "软限位拒绝位置 X %.3f / Y %.3f / Z %.3f。",
                                    point.x, point.y, point.z),
                            "位置不可达", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "软限位检查失败：" + exception.getMessage(),
                    "不能开始", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "即将执行实验性联合运动。\n"
                        + "请把手移出工作区并准备急停。运动完成后会等待控制器确认并抬升到安全 Z。\n\n继续吗？",
                "最后一次真机确认", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        double testSpeed = number(speed);
        List<Waypoint> path = new ArrayList<>(previewPath);
        result.setText("真机测试进行中：轨迹已通过认证模型和软限位检查…");
        UiUtils.submitUiMachineTask(() -> {
            double rotation = tool.getLocation().getRotation();
            for (int index = 1; index < path.size(); index++) {
                Waypoint point = path.get(index);
                tool.moveTo(new Location(LengthUnit.Millimeters,
                        point.x, point.y, point.z, rotation), testSpeed);
            }
            tool.waitForCompletion(CompletionType.WaitForStillstand);
            tool.moveToSafeZ(Math.min(testSpeed, 0.25));
            tool.waitForCompletion(CompletionType.WaitForStillstand);
            return null;
        }, ignored -> result.setText("真机测试完成：控制器已确认全部运动完成，工具头已抬升到安全 Z。"),
                throwable -> {
                    result.setText("真机测试中止：" + throwable.getMessage()
                            + "\n请先检查现场和机器状态，不要盲目重试。");
                    UiUtils.showError(SafetyWorkbenchDialog.this, "3D 安全工作台运动异常",
                            throwable);
                });
    }

    private boolean isAsyncDriverActive() {
        return Configuration.get().getMachine().getDrivers().stream()
                .anyMatch(driver -> driver instanceof
                        org.openpnp.machine.reference.driver.GcodeAsyncDriver);
    }
    private void editWorkbench() {
        JTextField minX = valueField(model.getMinX());
        JTextField maxX = valueField(model.getMaxX());
        JTextField minY = valueField(model.getMinY());
        JTextField maxY = valueField(model.getMaxY());
        JTextField minZ = valueField(model.getMinZ());
        JTextField maxZ = valueField(model.getMaxZ());
        JTextField radiusX = valueField(model.getToolRadiusX());
        JTextField radiusY = valueField(model.getToolRadiusY());
        JTextField toolMinZ = valueField(model.getToolMinZOffset());
        JTextField toolMaxZ = valueField(model.getToolMaxZOffset());
        JTextField sample = valueField(model.getSampleStep());
        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        addField(fields, "工作台最小 X", minX);
        addField(fields, "工作台最大 X", maxX);
        addField(fields, "工作台最小 Y", minY);
        addField(fields, "工作台最大 Y", maxY);
        addField(fields, "工作台最小 Z", minZ);
        addField(fields, "工作台最大 Z", maxZ);
        addField(fields, "工具头半宽 X", radiusX);
        addField(fields, "工具头半深 Y", radiusY);
        addField(fields, "工具头最低 Z 偏移", toolMinZ);
        addField(fields, "工具头最高 Z 偏移", toolMaxZ);
        addField(fields, "轨迹采样间距", sample);
        if (JOptionPane.showConfirmDialog(this, fields,
                "工作台范围与工具头安全包络（毫米）",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            double parsedMinX = parse(minX);
            double parsedMaxX = parse(maxX);
            double parsedMinY = parse(minY);
            double parsedMaxY = parse(maxY);
            double parsedMinZ = parse(minZ);
            double parsedMaxZ = parse(maxZ);
            double parsedRadiusX = positive(radiusX, "工具头半宽");
            double parsedRadiusY = positive(radiusY, "工具头半深");
            double parsedToolMinZ = parse(toolMinZ);
            double parsedToolMaxZ = parse(toolMaxZ);
            double parsedSample = positive(sample, "采样间距");
            model.setMinX(parsedMinX);
            model.setMaxX(parsedMaxX);
            model.setMinY(parsedMinY);
            model.setMaxY(parsedMaxY);
            model.setMinZ(parsedMinZ);
            model.setMaxZ(parsedMaxZ);
            model.setToolRadiusX(parsedRadiusX);
            model.setToolRadiusY(parsedRadiusY);
            model.setToolMinZOffset(parsedToolMinZ);
            model.setToolMaxZOffset(parsedToolMaxZ);
            model.setSampleStep(parsedSample);
            updateCertification();
            canvas.repaint();
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "参数无效",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void certifyModel() {
        List<String> issues = SafetyModelValidator.validate(model);
        if (!issues.isEmpty()) {
            JOptionPane.showMessageDialog(this, "模型不能认证：\n• "
                    + String.join("\n• ", issues), "检查未通过",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "认证表示你已按实机核对工作台范围、所有障碍物、底部相机中央通道、"
                        + "工具头/吸嘴包络、安全余量，以及用于视觉修正的各实体顶面高度。\n"
                        + "软件的几何模型不能发现未建模的线缆、临时工具或松动物体。\n\n"
                        + "确认把当前修订标记为“已认证”吗？",
                "认证安全模型", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        model.certify("操作员于 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date()) + " 确认");
        saveModel();
        updateCertification();
    }

    private void saveModel() {
        try {
            SafetyWorkbenchStore.save(model);
            result.setText("安全工作台已保存到用户配置目录："
                    + SafetyWorkbenchStore.getFile().getAbsolutePath()
                    + "\n安装、升级或重装 OpenPnP Pro 不会覆盖该文件；旧版本备份位于："
                    + SafetyWorkbenchStore.getBackupFile().getAbsolutePath());
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "保存失败：" + exception.getMessage(),
                    "保存模型", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateCertification() {
        history.capture(model);
        undoButton.setEnabled(history.canUndo());
        redoButton.setEnabled(history.canRedo());
        if (model.isCertified()) {
            certification.setText("● 已认证 · 修订 " + model.getRevision());
            certification.setForeground(new Color(30, 83, 57));
            certification.setBackground(new Color(213, 234, 220));
        }
        else {
            certification.setText("● 未认证/已修改 · 修订 " + model.getRevision());
            certification.setForeground(new Color(112, 69, 25));
            certification.setBackground(new Color(239, 224, 199));
        }
        updateVisionHeightStatus();
    }

    private void updateVisionHeightStatus() {
        if (coordinatedMotion != null) {
            coordinatedMotion.refresh();
        }
        if (!model.isVisionHeightCompensationEnabled()) {
            visionHeightStatus.setText("高度定位：关闭");
            visionHeightStatus.setForeground(new Color(145, 151, 156));
        }
        else if (!hasCalibratedTopCamera()) {
            visionHeightStatus.setText("高度定位：需相机双高度标定");
            visionHeightStatus.setForeground(new Color(204, 132, 54));
        }
        else if (!model.isCertified()) {
            visionHeightStatus.setText("高度定位：需认证模型");
            visionHeightStatus.setForeground(new Color(204, 132, 54));
        }
        else {
            visionHeightStatus.setText("高度定位：已就绪");
            visionHeightStatus.setForeground(new Color(72, 174, 112));
        }
    }

    private boolean hasCalibratedTopCamera() {
        for (Camera camera : Configuration.get().getMachine().getAllCameras()) {
            if (camera.getLooking() == Camera.Looking.Down
                    && camera.isUnitsPerPixelAtZCalibrated()) {
                return true;
            }
        }
        return false;
    }

    private static JTextField valueField(double value) {
        return new JTextField(format(value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double parse(JTextField field) {
        return Double.parseDouble(field.getText().trim().replace(',', '.'));
    }

    private static double positive(JTextField field, String label) {
        double value = parse(field);
        if (value <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0。");
        }
        return value;
    }

    private static double nonNegative(JTextField field, String label) {
        double value = parse(field);
        if (value < 0) {
            throw new IllegalArgumentException(label + "不能为负数。");
        }
        return value;
    }

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static final class LiveToolState {
        private DynamicTool tool;
        private double x;
        private double y;
        private double z;
        private double targetX;
        private double targetY;
        private double targetZ;

        LiveToolState(DynamicTool tool) {
            this.tool = tool;
            x = targetX = tool.reference.x;
            y = targetY = tool.reference.y;
            z = targetZ = tool.reference.z;
        }

        boolean setTarget(DynamicTool tool) {
            boolean changed = !sameVisual(this.tool, tool)
                    || targetX != tool.reference.x
                    || targetY != tool.reference.y
                    || targetZ != tool.reference.z;
            this.tool = tool;
            targetX = tool.reference.x;
            targetY = tool.reference.y;
            targetZ = tool.reference.z;
            return changed;
        }

        boolean animate() {
            boolean moving = x != targetX || y != targetY || z != targetZ;
            double smoothing = 0.34;
            x += (targetX - x) * smoothing;
            y += (targetY - y) * smoothing;
            z += (targetZ - z) * smoothing;
            if (Math.abs(targetX - x) < 0.001) {
                x = targetX;
            }
            if (Math.abs(targetY - y) < 0.001) {
                y = targetY;
            }
            if (Math.abs(targetZ - z) < 0.001) {
                z = targetZ;
            }
            return moving;
        }

        private static boolean sameVisual(DynamicTool first, DynamicTool second) {
            return first != null && second != null
                    && first.radiusX == second.radiusX
                    && first.radiusY == second.radiusY
                    && first.minZOffset == second.minZOffset
                    && first.maxZOffset == second.maxZOffset
                    && first.shaftRadius == second.shaftRadius
                    && first.tipLength == second.tipLength
                    && first.colorRgb == second.colorRgb
                    && first.carryingPart == second.carryingPart
                    && first.bodyFollowsNozzleZ == second.bodyFollowsNozzleZ
                    && first.bodyMinZOffset == second.bodyMinZOffset
                    && first.bodyMaxZOffset == second.bodyMaxZOffset
                    && first.carriedPartHeight == second.carriedPartHeight;
        }
    }
    private class PaletteTransferHandler extends TransferHandler {
        @Override
        protected Transferable createTransferable(javax.swing.JComponent component) {
            @SuppressWarnings("unchecked")
            JList<String> list = (JList<String>) component;
            return new StringSelection(list.getSelectedValue());
        }

        @Override
        public int getSourceActions(javax.swing.JComponent component) {
            return COPY;
        }
    }

    private class CanvasTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return !liveMode.isSelected()
                    && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                String label = (String) support.getTransferable()
                        .getTransferData(DataFlavor.stringFlavor);
                Point point = support.getDropLocation().getDropPoint();
                double[] world = canvas.groundAt(point.x, point.y);
                for (ObjectType objectType : ObjectType.values()) {
                    if (objectType.getDisplayName().equals(label)) {
                        addObject(objectType, world[0], world[1]);
                        return true;
                    }
                }
            }
            catch (Exception exception) {
                return false;
            }
            return false;
        }
    }

    private static final class WidthTrackingPanel extends JPanel
            implements javax.swing.Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect,
                int orientation, int direction) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect,
                int orientation, int direction) {
            int extent = orientation == SwingConstants.VERTICAL
                    ? visibleRect.height : visibleRect.width;
            return Math.max(20, extent - 20);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
    private enum Glyph {
        Apply, Capture, Corner, Measure, Copy, Delete, Edit
    }

    private static final class WorkbenchGlyphIcon implements Icon {
        private final Glyph glyph;
        private final Color color;

        WorkbenchGlyphIcon(Glyph glyph, Color color) {
            this.glyph = glyph;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            switch (glyph) {
                case Apply:
                    g.drawLine(3, 8, 7, 12);
                    g.drawLine(7, 12, 14, 4);
                    break;
                case Capture:
                    g.drawOval(3, 3, 10, 10);
                    g.drawLine(8, 1, 8, 5);
                    g.drawLine(8, 11, 8, 15);
                    g.drawLine(1, 8, 5, 8);
                    g.drawLine(11, 8, 15, 8);
                    break;
                case Corner:
                    g.drawLine(3, 3, 3, 13);
                    g.drawLine(3, 13, 13, 13);
                    g.fillOval(1, 11, 5, 5);
                    break;
                case Measure:
                    g.drawLine(2, 12, 13, 3);
                    g.drawLine(3, 10, 6, 13);
                    g.drawLine(6, 7, 8, 9);
                    g.drawLine(10, 4, 12, 6);
                    break;
                case Copy:
                    g.drawRect(5, 3, 8, 9);
                    g.drawRect(2, 6, 8, 8);
                    break;
                case Delete:
                    g.drawLine(4, 5, 12, 5);
                    g.drawLine(6, 2, 10, 2);
                    g.drawRect(5, 5, 6, 9);
                    break;
                case Edit:
                    g.drawLine(3, 13, 12, 4);
                    g.drawLine(11, 3, 13, 5);
                    g.drawLine(3, 13, 6, 12);
                    break;
                default:
                    break;
            }
            g.dispose();
        }
    }

    private static final class ObjectTypeIcon implements Icon {
        private final ObjectType type;

        ObjectTypeIcon(ObjectType type) {
            this.type = type;
        }

        @Override
        public int getIconWidth() {
            return 25;
        }

        @Override
        public int getIconHeight() {
            return 25;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = typeColor(type);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
            g.fillRoundRect(1, 1, 23, 23, 7, 7);
            g.setColor(color);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            if (type.isReferencePoint()) {
                g.drawOval(7, 7, 11, 11);
                g.drawLine(3, 12, 22, 12);
                g.drawLine(12, 3, 12, 22);
            }
            else if (type == ObjectType.BOTTOM_CAMERA) {
                g.drawOval(5, 7, 15, 11);
                g.fillOval(10, 10, 5, 5);
            }
            else if (type == ObjectType.PLACEMENT_HEAD) {
                g.drawRoundRect(5, 4, 15, 10, 3, 3);
                g.drawLine(12, 14, 12, 21);
                g.drawLine(9, 21, 15, 21);
            }
            else if (type == ObjectType.NOZZLE_CHANGER) {
                g.drawRoundRect(3, 6, 19, 13, 4, 4);
                g.drawArc(6, 9, 5, 6, 80, 200);
                g.drawArc(14, 9, 5, 6, -100, 200);
            }
            else if (type == ObjectType.KEEP_OUT) {
                g.drawPolygon(new int[] {12, 21, 3}, new int[] {3, 21, 21}, 3);
                g.drawLine(12, 8, 12, 15);
                g.fillOval(11, 18, 3, 3);
            }
            else if (type == ObjectType.FEEDER
                    || type == ObjectType.LOOSE_PARTS_FEEDER) {
                g.drawRoundRect(4, 4, 17, 17, 3, 3);
                g.drawLine(8, 6, 8, 19);
                g.drawLine(13, 6, 13, 19);
                g.drawLine(18, 6, 18, 19);
            }
            else {
                g.drawRect(4, 6, 17, 14);
                g.drawLine(4, 10, 21, 10);
                g.drawLine(9, 10, 9, 20);
                g.drawLine(15, 10, 15, 20);
            }
            g.dispose();
        }

        private static Color typeColor(ObjectType type) {
            switch (type) {
                case FEEDER:
                    return new Color(102, 177, 126);
                case PLACEMENT_HEAD:
                    return new Color(105, 155, 213);
                case BOTTOM_CAMERA:
                    return new Color(91, 187, 205);
                case LOOSE_PARTS_FEEDER:
                    return new Color(157, 184, 108);
                case BOARD:
                    return new Color(82, 174, 119);
                case KEEP_OUT:
                    return new Color(225, 103, 96);
                case NOZZLE_CHANGER:
                    return new Color(172, 133, 199);
                case HOME_POINT:
                case PARK_POINT:
                    return new Color(101, 166, 218);
                case NOZZLE_CHANGER_POINT:
                    return new Color(181, 137, 207);
                case DISCARD_POINT:
                    return new Color(217, 126, 92);
                case FEEDER_PICK_POINT:
                    return new Color(225, 174, 76);
                default:
                    return new Color(151, 161, 168);
            }
        }
    }

    private static final class PaletteRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                    selected, focused);
            for (ObjectType type : ObjectType.values()) {
                if (type.getDisplayName().equals(value)) {
                    label.setIcon(new ObjectTypeIcon(type));
                    label.setToolTipText("拖到中间 3D 视图添加“" + value + "”");
                    break;
                }
            }
            label.setBorder(new EmptyBorder(3, 5, 3, 5));
            label.setIconTextGap(7);
            return label;
        }
    }

    private static class ObjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                    selected, focused);
            if (value instanceof SafetyObject) {
                SafetyObject object = (SafetyObject) value;
                label.setIcon(new ObjectTypeIcon(object.type));
                label.setIconTextGap(6);
                label.setText((object.locked ? "[锁定] " : "") + object.name);
                label.setToolTipText(object.type.getDisplayName() + " · "
                        + format(object.width) + " × " + format(object.depth) + " × "
                        + format(object.height) + " mm");
            }
            return label;
        }
    }

    private class WorkbenchCanvas extends JPanel {
        private double yaw = Math.toRadians(-35);
        private double pitch = Math.toRadians(38);
        private double zoom = 1.25;
        private double panX;
        private double panY = 35;
        private double dragStartPanX;
        private double dragStartPanY;
        private boolean panning;
        private boolean mirrorX = true;
        private double viewCenterX = Double.NaN;
        private double viewCenterY = Double.NaN;
        private SafetyObject selected;
        private Vertex selectedVertex;
        private Point mouseStart;
        private double dragStartX;
        private double dragStartY;
        private double dragStartZ;
        private Location dragStartVertexLocation;
        private Vertex vertexPopupCandidate;
        private double startYaw;
        private double startPitch;
        private boolean objectDragged;
        private List<Waypoint> path = new ArrayList<>();
        private boolean safePath;
        private long lastPaintNanos;
        private double smoothedFps;
        private double visualOriginZ;
        private final DimensionEditor dimensionEditor = new DimensionEditor();

        WorkbenchCanvas() {
            setLayout(null);
            setDoubleBuffered(true);
            setOpaque(true);
            setBackground(new Color(38, 43, 48));
            setBorder(BorderFactory.createLineBorder(new Color(88, 96, 104)));
            setTransferHandler(new CanvasTransferHandler());
            add(dimensionEditor);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    requestFocusInWindow();
                    mouseStart = event.getPoint();
                    startYaw = yaw;
                    startPitch = pitch;
                    dragStartPanX = panX;
                    dragStartPanY = panY;
                    Vertex vertex = hitVertex(event.getPoint());
                    selected = vertex == null ? hitObject(event.getPoint()) : vertex.object;
                    objectList.setSelectedValue(selected, true);
                    selectedVertex = vertex;
                    onVertexSelected(selectedVertex);
                    updateDimensionEditor(true);
                    dragStartVertexLocation = vertex == null ? null : vertex.getLocation();
                    vertexPopupCandidate = event.getButton() == MouseEvent.BUTTON3
                            ? vertex : null;
                    if (selected != null) {
                        dragStartX = selected.x;
                        dragStartY = selected.y;
                        dragStartZ = selected.z;
                    }
                    panning = event.getButton() == MouseEvent.BUTTON2
                            || event.isAltDown()
                            || (event.getButton() == MouseEvent.BUTTON1 && selected == null);
                    objectDragged = false;
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (mouseStart == null) {
                        return;
                    }
                    int dx = event.getX() - mouseStart.x;
                    int dy = event.getY() - mouseStart.y;
                    if (vertexPopupCandidate != null && mouseStart.distance(event.getPoint()) > 5) {
                        vertexPopupCandidate = null;
                    }
                    if (panning) {
                        followSelectedTool.setSelected(false);
                        panX = dragStartPanX + dx;
                        panY = dragStartPanY + dy;
                    }
                    else if ((event.getModifiersEx() & InputEvent.BUTTON3_DOWN_MASK) != 0
                            && vertexPopupCandidate == null) {
                        followSelectedTool.setSelected(false);
                        yaw = startYaw + dx * 0.008;
                        pitch = Math.max(Math.toRadians(15),
                                Math.min(Math.toRadians(89), startPitch - dy * 0.006));
                    }
                    else if (!liveMode.isSelected() && selectedVertex != null
                            && dragStartVertexLocation != null && !selectedVertex.object.locked
                            && (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
                        Location target;
                        if ((event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                            target = dragStartVertexLocation.derive(null, null,
                                    dragStartVertexLocation.getZ() - dy / scale(), null);
                        }
                        else {
                            double[] initial = groundAt(mouseStart.x, mouseStart.y);
                            double[] current = groundAt(event.getX(), event.getY());
                            target = dragStartVertexLocation.add(new Location(
                                    LengthUnit.Millimeters,
                                    current[0] - initial[0], current[1] - initial[1], 0, 0));
                        }
                        SafetyWorkbenchVertexEditor.apply(selectedVertex, target);
                        selected = selectedVertex.object;
                        objectDragged = true;
                        showObject(selected);
                        onVertexSelected(selectedVertex);
                        updateDimensionEditor(true);
                    }
                    else if (!liveMode.isSelected()
                            && selected != null && selectedVertex == null
                            && !selected.locked) {
                        if ((event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                            selected.z = dragStartZ - dy / scale();
                        }
                        else {
                            double[] initial = groundAt(mouseStart.x, mouseStart.y);
                            double[] current = groundAt(event.getX(), event.getY());
                            selected.x = dragStartX + current[0] - initial[0];
                            selected.y = dragStartY + current[1] - initial[1];
                        }
                        objectDragged = true;
                        showObject(selected);
                    }
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (vertexPopupCandidate != null
                            && (event.isPopupTrigger()
                                    || event.getButton() == MouseEvent.BUTTON3)) {
                        showVertexContextMenu(WorkbenchCanvas.this, event.getX(), event.getY(),
                                vertexPopupCandidate);
                    }
                    if (objectDragged) {
                        model.touch();
                        updateCertification();
                    }
                    mouseStart = null;
                    panning = false;
                    dragStartVertexLocation = null;
                    vertexPopupCandidate = null;
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    zoom = Math.max(0.35, Math.min(5.0,
                            zoom * Math.pow(1.12, -event.getPreciseWheelRotation())));
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void resetView() {
            yaw = Math.toRadians(-35);
            pitch = Math.toRadians(38);
            zoom = 1.25;
            panX = 0;
            panY = 35;
            viewCenterX = Double.NaN;
            viewCenterY = Double.NaN;
            followSelectedTool.setSelected(false);
            repaint();
        }

        void setIsometricView() {
            yaw = Math.toRadians(-35);
            pitch = Math.toRadians(38);
            followSelectedTool.setSelected(false);
            repaint();
        }

        void setTopView() {
            yaw = 0;
            pitch = Math.toRadians(89);
            followSelectedTool.setSelected(false);
            repaint();
        }

        void setMirrorX(boolean mirrorX) {
            this.mirrorX = mirrorX;
            repaint();
        }

        void setSelected(SafetyObject selected) {
            if (this.selected != selected) {
                selectedVertex = null;
                onVertexSelected(null);
                updateDimensionEditor(true);
            }
            this.selected = selected;
            repaint();
        }

        Vertex getSelectedVertex() {
            return selectedVertex;
        }

        void selectVertex(Vertex vertex) {
            selectedVertex = vertex;
            selected = vertex == null ? selected : vertex.object;
            if (vertex != null) {
                objectList.setSelectedValue(vertex.object, true);
            }
            onVertexSelected(vertex);
            updateDimensionEditor(true);
            repaint();
        }

        void clearVertexSelection() {
            selectedVertex = null;
            onVertexSelected(null);
            updateDimensionEditor(true);
        }

        void setPath(List<Waypoint> path, boolean safe) {
            this.path = new ArrayList<>(path);
            this.safePath = safe;
            repaint();
        }
        boolean follow(double worldX, double worldY) {
            if (Double.isNaN(viewCenterX)) {
                viewCenterX = worldX;
                viewCenterY = worldY;
                return true;
            }
            double deltaX = worldX - viewCenterX;
            double deltaY = worldY - viewCenterY;
            if (Math.abs(deltaX) < 0.001 && Math.abs(deltaY) < 0.001) {
                viewCenterX = worldX;
                viewCenterY = worldY;
                return false;
            }
            viewCenterX += deltaX * 0.16;
            viewCenterY += deltaY * 0.16;
            return true;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            long paintNow = System.nanoTime();
            if (lastPaintNanos != 0) {
                double fps = 1_000_000_000.0 / Math.max(1, paintNow - lastPaintNanos);
                smoothedFps = smoothedFps == 0 ? fps : smoothedFps * 0.88 + fps * 0.12;
            }
            lastPaintNanos = paintNow;
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            visualOriginZ = SafetyWorkbenchGeometry.workSurfaceZ(model);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            drawGrid(g);
            drawSafeZZone(g);
            for (SafetyObject object : model.getObjects()) {
                if (object != null && object.enabled) {
                    drawObject(g, object);
                }
            }
            drawVertices(g);
            drawSelectedDimensions(g);
            drawLiveTrail(g);
            drawPath(g);
            drawLiveTools(g);
            drawOverlay(g);
            g.dispose();
            updateDimensionEditor(false);
        }

        private void updateDimensionEditor(boolean refreshValues) {
            boolean visible = selectedVertex != null && selectedVertex.object != null
                    && !selectedVertex.object.type.isReferencePoint()
                    && !liveMode.isSelected();
            dimensionEditor.setVisible(visible);
            if (!visible) {
                return;
            }
            if (refreshValues) {
                dimensionEditor.showObject(selectedVertex.object);
            }
            Location location = selectedVertex.getLocation();
            Point anchor = project(location.getX(), location.getY(), location.getZ());
            Dimension preferred = dimensionEditor.getPreferredSize();
            int editorX = Math.max(8, Math.min(getWidth() - preferred.width - 8,
                    anchor.x + 16));
            int editorY = Math.max(92, Math.min(getHeight() - preferred.height - 8,
                    anchor.y - preferred.height / 2));
            dimensionEditor.setBounds(editorX, editorY, preferred.width, preferred.height);
        }

        private void drawSelectedDimensions(Graphics2D g) {
            if (selected == null || selected.type.isReferencePoint()
                    || liveMode.isSelected()) {
                return;
            }
            double top = selected.z + selected.height;
            drawDimension(g, project(selected.x, selected.y, top),
                    project(selected.x + selected.width, selected.y, top),
                    "X " + format(selected.width) + " mm", new Color(111, 191, 224), -10);
            drawDimension(g, project(selected.x, selected.y, top),
                    project(selected.x, selected.y + selected.depth, top),
                    "Y " + format(selected.depth) + " mm", new Color(111, 203, 143), 14);
            drawDimension(g, project(selected.x + selected.width,
                            selected.y + selected.depth, selected.z),
                    project(selected.x + selected.width,
                            selected.y + selected.depth, top),
                    "Z " + format(selected.height) + " mm", new Color(235, 179, 79), 14);
        }

        private void drawDimension(Graphics2D g, Point start, Point end, String text,
                Color color, int offset) {
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 205));
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(start.x, start.y, end.x, end.y);
            int dx = end.x - start.x;
            int dy = end.y - start.y;
            double length = Math.max(1, Math.sqrt(dx * dx + dy * dy));
            int nx = (int) Math.round(-dy / length * 4);
            int ny = (int) Math.round(dx / length * 4);
            g.drawLine(start.x - nx, start.y - ny, start.x + nx, start.y + ny);
            g.drawLine(end.x - nx, end.y - ny, end.x + nx, end.y + ny);
            int textWidth = g.getFontMetrics().stringWidth(text);
            int textX = (start.x + end.x - textWidth) / 2;
            int textY = (start.y + end.y) / 2 + offset;
            g.setColor(new Color(28, 33, 37, 218));
            g.fillRoundRect(textX - 4, textY - 13, textWidth + 8, 17, 6, 6);
            g.setColor(color);
            g.drawString(text, textX, textY);
        }

        private final class DimensionEditor extends JPanel {
            private final JTextField editorWidth = dimensionField("X");
            private final JTextField editorDepth = dimensionField("Y");
            private final JTextField editorHeight = dimensionField("Z");
            private SafetyObject editing;

            DimensionEditor() {
                super(new FlowLayout(FlowLayout.LEFT, 4, 4));
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(99, 174, 205), 2),
                        new EmptyBorder(1, 3, 1, 3)));
                setBackground(new Color(43, 50, 56));
                add(dimensionLabel("尺寸 X", new Color(111, 191, 224)));
                add(editorWidth);
                add(dimensionLabel("Y", new Color(111, 203, 143)));
                add(editorDepth);
                add(dimensionLabel("Z", new Color(235, 179, 79)));
                add(editorHeight);
                JButton apply = new JButton(new WorkbenchGlyphIcon(Glyph.Apply,
                        new Color(124, 225, 159)));
                apply.setToolTipText("应用尺寸（Enter）");
                apply.setPreferredSize(new Dimension(30, 26));
                styleActionButton(apply, new Color(45, 104, 70));
                apply.addActionListener(event -> applyDimensions());
                add(apply);
                for (JTextField field : Arrays.asList(editorWidth, editorDepth,
                        editorHeight)) {
                    field.addActionListener(event -> applyDimensions());
                }
                setVisible(false);
            }

            private JTextField dimensionField(String axis) {
                JTextField field = new JTextField(5);
                field.setToolTipText(axis + " 尺寸，单位 mm；输入后按 Enter");
                field.setHorizontalAlignment(SwingConstants.RIGHT);
                return field;
            }

            private JLabel dimensionLabel(String text, Color color) {
                JLabel label = new JLabel(text);
                label.setForeground(color);
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                return label;
            }

            void showObject(SafetyObject object) {
                editing = object;
                editorWidth.setText(format(object.width));
                editorDepth.setText(format(object.depth));
                editorHeight.setText(format(object.height));
                boolean planarEditable = object.shape != Shape.POLYGON;
                editorWidth.setEnabled(planarEditable && !object.locked);
                editorDepth.setEnabled(planarEditable && !object.locked);
                editorHeight.setEnabled(!object.locked);
                setToolTipText(object.shape == Shape.POLYGON
                        ? "自定义轮廓的 X/Y 请拖动轮廓顶点；此处可直接修改高度 Z。"
                        : "像 CAD 一样直接输入物体 X/Y/Z 尺寸。");
            }

            private void applyDimensions() {
                if (editing == null || editing.locked) {
                    return;
                }
                try {
                    if (editing.shape != Shape.POLYGON) {
                        editing.width = positive(editorWidth, "宽度 X");
                        editing.depth = positive(editorDepth, "深度 Y");
                    }
                    editing.height = positive(editorHeight, "高度 Z");
                    model.touch();
                    showObject(editing);
                    objectList.repaint();
                    updateCertification();
                    repaint();
                    result.setText("已在 3D 模型中更新“" + editing.name
                            + "”尺寸：X " + format(editing.width) + " / Y "
                            + format(editing.depth) + " / Z " + format(editing.height)
                            + " mm。请重新检查并认证模型。");
                }
                catch (Exception exception) {
                    JOptionPane.showMessageDialog(SafetyWorkbenchDialog.this,
                            exception.getMessage(), "尺寸无效", JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        private void drawSafeZZone(Graphics2D g) {
            if (!model.hasImportedSafeZ()) {
                return;
            }
            double low = model.getImportedSafeZLow();
            double high = model.getImportedSafeZHigh();
            g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, new float[] {6f, 5f}, 0));
            drawSafeZPlane(g, low, new Color(100, 160, 184));
            drawSafeZPlane(g, high, new Color(123, 186, 158));
            g.setComposite(AlphaComposite.SrcOver);
            Point label = project(model.getMinX(), model.getMaxY(), high);
            g.setColor(new Color(151, 202, 183));
            g.drawString(String.format(Locale.ROOT, "导入安全 Z %.2f～%.2f mm", low, high),
                    label.x + 5, label.y - 5);
        }

        private void drawSafeZPlane(Graphics2D g, double z, Color color) {
            Point a = project(model.getMinX(), model.getMinY(), z);
            Point b = project(model.getMaxX(), model.getMinY(), z);
            Point c = project(model.getMaxX(), model.getMaxY(), z);
            Point d = project(model.getMinX(), model.getMaxY(), z);
            g.setColor(color);
            g.drawPolygon(polygon(a, b, c, d));
        }

        private void drawVertices(Graphics2D g) {
            if (selected == null || liveMode.isSelected()) {
                return;
            }
            for (Vertex vertex : SafetyWorkbenchVertexEditor.vertices(selected)) {
                Location location = vertex.getLocation();
                Point point = project(location.getX(), location.getY(), location.getZ());
                boolean active = sameVertex(vertex, selectedVertex);
                int radius = active ? 11 : 8;
                g.setColor(new Color(0, 0, 0, 110));
                g.fillOval(point.x - radius - 3, point.y - radius - 3,
                        radius * 2 + 6, radius * 2 + 6);
                g.setColor(active ? new Color(106, 205, 231) : new Color(242, 190, 83));
                g.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
                g.setColor(new Color(35, 39, 43));
                g.drawOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
            }
        }

        private boolean sameVertex(Vertex first, Vertex second) {
            return first != null && second != null && first.object == second.object
                    && first.polygonIndex == second.polygonIndex
                    && first.maximumX == second.maximumX
                    && first.maximumY == second.maximumY
                    && first.maximumZ == second.maximumZ;
        }

        private void drawGrid(Graphics2D g) {
            g.setStroke(new BasicStroke(1f));
            double interval = chooseGridInterval();
            double workSurfaceZ = visualOriginZ;
            for (double gx = Math.ceil(model.getMinX() / interval) * interval;
                    gx <= model.getMaxX(); gx += interval) {
                Point a = project(gx, model.getMinY(), workSurfaceZ);
                Point b = project(gx, model.getMaxY(), workSurfaceZ);
                g.setColor(new Color(72, 79, 85));
                g.drawLine(a.x, a.y, b.x, b.y);
            }
            for (double gy = Math.ceil(model.getMinY() / interval) * interval;
                    gy <= model.getMaxY(); gy += interval) {
                Point a = project(model.getMinX(), gy, workSurfaceZ);
                Point b = project(model.getMaxX(), gy, workSurfaceZ);
                g.setColor(new Color(72, 79, 85));
                g.drawLine(a.x, a.y, b.x, b.y);
            }
            drawAxis(g, model.getMinX(), model.getMinY(), workSurfaceZ,
                    model.getMaxX(), model.getMinY(), workSurfaceZ,
                    new Color(177, 94, 94), "X");
            drawAxis(g, model.getMinX(), model.getMinY(), workSurfaceZ,
                    model.getMinX(), model.getMaxY(), workSurfaceZ,
                    new Color(91, 155, 107), "Y");
            drawAxis(g, model.getMinX(), model.getMinY(), model.getMinZ(),
                    model.getMinX(), model.getMinY(), model.getMaxZ(),
                    new Color(88, 133, 184), "Z");
            Point surfaceLabel = project(model.getMinX(), model.getMaxY(), workSurfaceZ);
            g.setColor(new Color(137, 151, 160));
            g.drawString(String.format(Locale.ROOT, "工作面 Z %.2f mm", workSurfaceZ),
                    surfaceLabel.x + 6, surfaceLabel.y + 16);
        }

        private void drawAxis(Graphics2D g, double ax, double ay, double az, double bx,
                double by, double bz, Color color, String label) {
            Point a = project(ax, ay, az);
            Point b = project(bx, by, bz);
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(a.x, a.y, b.x, b.y);
            g.drawString(label, b.x + 4, b.y - 4);
        }

        private void drawObject(Graphics2D g, SafetyObject object) {
            if (isPhysicalReferenceEntity(object.type)) {
                drawReferenceEntity(g, object);
                return;
            }
            if (object.type.isReferencePoint()) {
                drawReferencePoint(g, object);
                return;
            }
            if (object.shape == Shape.CYLINDER) {
                drawCylinder(g, object);
                return;
            }
            if (object.shape == Shape.POLYGON && object.polygon.size() >= 3) {
                drawExtrudedPolygon(g, object);
                return;
            }
            if (object.type == ObjectType.KEEP_OUT) {
                g.setComposite(AlphaComposite.SrcOver.derive(0.28f));
                drawBox(g, object);
                g.setComposite(AlphaComposite.SrcOver);
                return;
            }
            drawBox(g, object);
            switch (object.type) {
                case FEEDER:
                    drawFeederDetails(g, object);
                    break;
                case BOTTOM_CAMERA:
                    drawCameraDetails(g, object);
                    break;
                case NOZZLE_CHANGER:
                    drawNozzleChangerDetails(g, object);
                    break;
                case BOARD:
                    drawBoardDetails(g, object);
                    break;
                case LOOSE_PARTS_FEEDER:
                    drawTrayDetails(g, object);
                    break;
                case PLACEMENT_HEAD:
                    drawPlacementHeadDetails(g, object);
                    break;
                default:
                    break;
            }
        }

        private boolean isPhysicalReferenceEntity(ObjectType objectType) {
            return objectType == ObjectType.HOME_POINT
                    || objectType == ObjectType.PARK_POINT
                    || objectType == ObjectType.DISCARD_POINT;
        }

        private void drawReferenceEntity(Graphics2D g, SafetyObject object) {
            double bodyX = object.x - object.width / 2.0;
            double bodyY = object.y - object.depth / 2.0;
            double bodyZ = object.z - object.height;
            Color base = new Color(object.colorRgb);
            drawCuboid(g, bodyX, bodyY, bodyZ, object.width, object.depth,
                    object.height, base, object == selected
                            ? new Color(242, 190, 83) : shade(base, 1.35));
            Point center = project(object.x, object.y, object.z + 0.2);
            int marker = (int) Math.max(7, Math.min(15,
                    Math.min(object.width, object.depth) * scale() * 0.20));
            if (object.type == ObjectType.DISCARD_POINT) {
                g.setColor(new Color(29, 34, 37, 220));
                g.fillOval(center.x - marker, center.y - Math.max(4, marker / 2),
                        marker * 2, Math.max(8, marker));
                g.setColor(new Color(229, 161, 103));
                g.drawOval(center.x - marker, center.y - Math.max(4, marker / 2),
                        marker * 2, Math.max(8, marker));
            }
            else {
                g.setColor(new Color(226, 234, 238, 205));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(center.x - marker / 2, center.y - marker / 2,
                        marker, marker);
                g.drawLine(center.x - marker, center.y, center.x + marker, center.y);
                g.drawLine(center.x, center.y - marker, center.x, center.y + marker);
            }
            if (object == selected || showReferenceLabels.isSelected()) {
                g.setColor(new Color(234, 238, 240));
                g.drawString(object.name, center.x + marker + 5, center.y - 6);
                g.setColor(new Color(171, 183, 191));
                g.drawString(String.format(Locale.ROOT, "X %.2f  Y %.2f  Z %.2f",
                        object.x, object.y, object.z), center.x + marker + 5, center.y + 8);
            }
        }

        private void drawReferencePoint(Graphics2D g, SafetyObject object) {
            Font originalFont = g.getFont();
            Point point = project(object.x, object.y, object.z);
            Point top = project(object.x, object.y, object.z + Math.max(4, object.height));
            Color base = new Color(object.colorRgb);
            int radius = (int) Math.max(6, Math.min(13, object.width * scale() * 0.35));
            g.setColor(shade(base, 0.75));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.drawLine(point.x, point.y, top.x, top.y);
            g.setColor(new Color(30, 35, 39, 210));
            g.fillOval(point.x - radius, point.y - radius / 2,
                    radius * 2, Math.max(5, radius));
            g.setColor(object == selected ? new Color(242, 190, 83) : shade(base, 1.35));
            g.drawOval(point.x - radius, point.y - radius / 2,
                    radius * 2, Math.max(5, radius));
            g.drawLine(point.x - radius - 4, point.y, point.x + radius + 4, point.y);
            g.drawLine(point.x, point.y - radius, point.x, point.y + radius);
            if (object == selected || showReferenceLabels.isSelected()) {
                g.setColor(new Color(231, 234, 236));
                g.drawString(object.name, top.x + 7, top.y - 6);
                if (object == selected) {
                    g.setFont(originalFont.deriveFont(9f));
                    g.setColor(new Color(171, 183, 191));
                    g.drawString(String.format(Locale.ROOT, "X %.2f  Y %.2f  Z %.2f",
                            object.x, object.y, object.z), top.x + 7, top.y + 7);
                }
            }
            g.setFont(originalFont);
        }

        private void drawFeederDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.3;
            double pickFraction = object.rearFacing ? 0.90 : 0.10;
            double reelFraction = object.rearFacing ? 0.16 : 0.84;
            g.setColor(new Color(199, 207, 194));
            for (int lane = 0; lane < 2; lane++) {
                double laneX = object.x + object.width * (0.35 + lane * 0.3);
                Point start = project(laneX, object.y + 4, top);
                Point end = project(laneX, object.y + object.depth - 4, top);
                g.setStroke(new BasicStroke(1.4f));
                g.drawLine(start.x, start.y, end.x, end.y);
            }
            for (int pocket = 0; pocket < 10; pocket++) {
                double py = object.y + 8 + pocket * (object.depth - 16) / 9.0;
                Point point = project(object.x + object.width / 2.0, py, top);
                int radius = (int) Math.max(2, Math.min(5, object.width * scale() * 0.08));
                g.setColor(new Color(46, 56, 52));
                g.fillOval(point.x - radius, point.y - radius / 2,
                        radius * 2, Math.max(2, radius));
            }
            Point reel = project(object.x + object.width / 2.0,
                    object.y + object.depth * reelFraction, top + object.height * 0.32);
            int reelRadius = (int) Math.max(5, object.width * scale() * 0.38);
            g.setColor(new Color(111, 122, 113));
            g.fillOval(reel.x - reelRadius, reel.y - reelRadius,
                    reelRadius * 2, reelRadius * 2);
            g.setColor(new Color(205, 211, 205));
            g.drawOval(reel.x - reelRadius, reel.y - reelRadius,
                    reelRadius * 2, reelRadius * 2);
            g.fillOval(reel.x - 2, reel.y - 2, 4, 4);
            Point pick = project(object.x + object.width / 2.0,
                    object.y + object.depth * pickFraction, top);
            g.setColor(new Color(242, 190, 83));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(pick.x - 5, pick.y - 5, 10, 10);
            g.drawLine(pick.x - 8, pick.y, pick.x + 8, pick.y);
            g.drawLine(pick.x, pick.y - 8, pick.x, pick.y + 8);
        }

        private void drawNozzleChangerDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.4;
            boolean alongX = object.apertureWidth > object.apertureDepth;
            double centerX = object.x + object.width / 2.0;
            double centerY = object.y + object.depth / 2.0;
            double halfSlot = (alongX ? object.apertureDepth : object.apertureWidth) / 2.0;
            g.setColor(new Color(188, 193, 196));
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            if (alongX) {
                Point firstA = project(object.x + 3, centerY - halfSlot, top);
                Point firstB = project(object.x + object.width - 3, centerY - halfSlot, top);
                Point secondA = project(object.x + 3, centerY + halfSlot, top);
                Point secondB = project(object.x + object.width - 3, centerY + halfSlot, top);
                g.drawLine(firstA.x, firstA.y, firstB.x, firstB.y);
                g.drawLine(secondA.x, secondA.y, secondB.x, secondB.y);
            }
            else {
                Point firstA = project(centerX - halfSlot, object.y + 3, top);
                Point firstB = project(centerX - halfSlot, object.y + object.depth - 3, top);
                Point secondA = project(centerX + halfSlot, object.y + 3, top);
                Point secondB = project(centerX + halfSlot, object.y + object.depth - 3, top);
                g.drawLine(firstA.x, firstA.y, firstB.x, firstB.y);
                g.drawLine(secondA.x, secondA.y, secondB.x, secondB.y);
            }
            Point docking = project(centerX, centerY, top + 2.5);
            int radius = (int) Math.max(4, Math.min(8, 4 * scale()));
            g.setColor(new Color(208, 211, 212));
            g.fillOval(docking.x - radius, docking.y - radius / 2,
                    radius * 2, Math.max(4, radius));
            g.setColor(new Color(79, 85, 89));
            g.fillOval(docking.x - Math.max(2, radius / 3),
                    docking.y - Math.max(1, radius / 6),
                    Math.max(3, radius * 2 / 3), Math.max(2, radius / 3));
        }

        private void drawCameraDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.5;
            Point lens = project(object.x + object.width / 2.0,
                    object.y + object.depth / 2.0, top);
            int outer = (int) Math.max(7,
                    Math.min(object.width, object.depth) * scale() * 0.28);
            g.setColor(new Color(27, 34, 39));
            g.fillOval(lens.x - outer, lens.y - outer / 2,
                    outer * 2, outer);
            g.setColor(new Color(154, 174, 188));
            g.drawOval(lens.x - outer, lens.y - outer / 2,
                    outer * 2, outer);
            int inner = Math.max(3, outer / 2);
            g.setColor(new Color(47, 89, 111));
            g.fillOval(lens.x - inner, lens.y - inner / 2,
                    inner * 2, inner);
            g.setColor(new Color(151, 204, 222));
            g.fillOval(lens.x - Math.max(1, inner / 4),
                    lens.y - Math.max(1, inner / 4),
                    Math.max(2, inner / 2), Math.max(2, inner / 2));
        }

        private void drawBoardDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.25;
            g.setColor(new Color(206, 181, 103));
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 7; column++) {
                    double cx = object.x + object.width * (0.12 + column * 0.125);
                    double cy = object.y + object.depth * (0.17 + row * 0.21);
                    double pw = Math.max(1.2, object.width * 0.035);
                    double pd = Math.max(1.0, object.depth * 0.045);
                    Point a = project(cx - pw, cy - pd, top);
                    Point b = project(cx + pw, cy - pd, top);
                    Point c = project(cx + pw, cy + pd, top);
                    Point d = project(cx - pw, cy + pd, top);
                    g.fillPolygon(polygon(a, b, c, d));
                }
            }
            g.setColor(new Color(111, 176, 126));
            g.setStroke(new BasicStroke(1f));
            for (int trace = 0; trace < 4; trace++) {
                Point a = project(object.x + object.width * 0.08,
                        object.y + object.depth * (0.2 + trace * 0.18), top);
                Point b = project(object.x + object.width * 0.92,
                        object.y + object.depth * (0.28 + trace * 0.16), top);
                g.drawLine(a.x, a.y, b.x, b.y);
            }
        }

        private void drawTrayDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.3;
            g.setColor(new Color(54, 59, 62));
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 6; column++) {
                    double cx = object.x + object.width * (0.1 + column * 0.16);
                    double cy = object.y + object.depth * (0.14 + row * 0.23);
                    Point point = project(cx, cy, top);
                    int size = (int) Math.max(2, Math.min(7,
                            Math.min(object.width, object.depth) * scale() * 0.055));
                    g.fillOval(point.x - size, point.y - size / 2,
                            size * 2, Math.max(2, size));
                }
            }
        }

        private void drawPlacementHeadDetails(Graphics2D g, SafetyObject object) {
            double cx = object.x + object.width / 2.0;
            double cy = object.y + object.depth / 2.0;
            double bottom = object.z - Math.max(8, object.height * 0.25);
            Point a = project(cx, cy, object.z + object.height * 0.25);
            Point b = project(cx, cy, bottom);
            g.setColor(new Color(199, 207, 213));
            g.setStroke(new BasicStroke((float) Math.max(2, 3 * scale()),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(a.x, a.y, b.x, b.y);
            Point tip = project(cx, cy, bottom - 3);
            g.setColor(new Color(227, 231, 233));
            g.fillOval(tip.x - 3, tip.y - 2, 6, 4);
        }
        private void drawBox(Graphics2D g, SafetyObject object) {
            Point p000 = project(object.x, object.y, object.z);
            Point p100 = project(object.x + object.width, object.y, object.z);
            Point p010 = project(object.x, object.y + object.depth, object.z);
            Point p110 = project(object.x + object.width, object.y + object.depth, object.z);
            Point p001 = project(object.x, object.y, object.z + object.height);
            Point p101 = project(object.x + object.width, object.y, object.z + object.height);
            Point p011 = project(object.x, object.y + object.depth, object.z + object.height);
            Point p111 = project(object.x + object.width, object.y + object.depth,
                    object.z + object.height);
            Color base = new Color(object.colorRgb);
            fillFace(g, shade(base, 0.62), p000, p100, p101, p001);
            fillFace(g, shade(base, 0.78), p100, p110, p111, p101);
            fillFace(g, shade(base, 1.08), p001, p101, p111, p011);
            drawEdges(g, object == selected ? new Color(240, 190, 91) : shade(base, 1.35),
                    p000, p100, p110, p010, p001, p101, p111, p011);
            if (SafetyWorkbenchGeometry.hasCentralOpening(object)) {
                double ax = object.x + (object.width - object.apertureWidth) / 2.0;
                double ay = object.y + (object.depth - object.apertureDepth) / 2.0;
                Point a = project(ax, ay, object.z + object.height + 0.1);
                Point b = project(ax + object.apertureWidth, ay, object.z + object.height + 0.1);
                Point c = project(ax + object.apertureWidth, ay + object.apertureDepth,
                        object.z + object.height + 0.1);
                Point d = project(ax, ay + object.apertureDepth,
                        object.z + object.height + 0.1);
                fillFace(g, getBackground(), a, b, c, d);
                g.setColor(new Color(154, 174, 188));
                g.drawPolygon(polygon(a, b, c, d));
            }
            drawLabel(g, object);
        }

        private void drawCylinder(Graphics2D g, SafetyObject object) {
            int segments = 24;
            Polygon bottom = new Polygon();
            Polygon top = new Polygon();
            for (int i = 0; i < segments; i++) {
                double angle = 2 * Math.PI * i / segments;
                double px = object.x + object.width / 2.0
                        + Math.cos(angle) * object.width / 2.0;
                double py = object.y + object.depth / 2.0
                        + Math.sin(angle) * object.depth / 2.0;
                Point b = project(px, py, object.z);
                Point t = project(px, py, object.z + object.height);
                bottom.addPoint(b.x, b.y);
                top.addPoint(t.x, t.y);
            }
            Color base = new Color(object.colorRgb);
            g.setColor(shade(base, 0.68));
            g.fillPolygon(bottom);
            g.setColor(shade(base, 1.08));
            g.fillPolygon(top);
            g.setColor(object == selected ? new Color(240, 190, 91) : shade(base, 1.35));
            g.drawPolygon(bottom);
            g.drawPolygon(top);
            drawLabel(g, object);
        }

        private void drawExtrudedPolygon(Graphics2D g, SafetyObject object) {
            Polygon bottom = new Polygon();
            Polygon top = new Polygon();
            for (Point2 point : object.polygon) {
                Point b = project(object.x + point.x, object.y + point.y, object.z);
                Point t = project(object.x + point.x, object.y + point.y,
                        object.z + object.height);
                bottom.addPoint(b.x, b.y);
                top.addPoint(t.x, t.y);
            }
            Color base = new Color(object.colorRgb);
            g.setColor(shade(base, 0.68));
            g.fillPolygon(bottom);
            g.setColor(shade(base, 1.08));
            g.fillPolygon(top);
            g.setColor(object == selected ? new Color(240, 190, 91) : shade(base, 1.35));
            g.drawPolygon(bottom);
            g.drawPolygon(top);
            drawLabel(g, object);
        }

        private void drawLabel(Graphics2D g, SafetyObject object) {
            if (object != selected && !showReferenceLabels.isSelected()) {
                return;
            }
            Point center = project(object.x + object.width / 2.0,
                    object.y + object.depth / 2.0, object.z + object.height);
            g.setColor(new Color(230, 233, 235));
            g.drawString(object.name, center.x + 5, center.y - 6);
        }

        private void drawLiveTrail(Graphics2D g) {
            if (!liveMode.isSelected() || liveTrail.size() < 2) {
                return;
            }
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            for (int index = 1; index < liveTrail.size(); index++) {
                float alpha = Math.max(0.08f, index / (float) liveTrail.size() * 0.52f);
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                Waypoint a = liveTrail.get(index - 1);
                Waypoint b = liveTrail.get(index);
                Point pa = project(a.x, a.y, a.z);
                Point pb = project(b.x, b.y, b.z);
                g.setColor(new Color(91, 182, 218));
                g.drawLine(pa.x, pa.y, pb.x, pb.y);
            }
            g.setComposite(AlphaComposite.SrcOver);
        }

        private void drawLiveTools(Graphics2D g) {
            for (LiveToolState state : liveTools.values()) {
                drawLiveTool(g, state);
            }
        }

        private void drawLiveTool(Graphics2D g, LiveToolState state) {
            DynamicTool tool = state.tool;
            Color base = new Color(tool.colorRgb);
            double bodyBottom = tool.bodyMinimumZAt(state.z);
            double bodyTop = tool.bodyMaximumZAt(state.z);
            double dynamicTipLength = tool.tipLengthAt(state.z);
            double bodyWidth = tool.radiusX * 2;
            double bodyDepth = tool.radiusY * 2;
            drawCuboid(g, state.x - tool.radiusX, state.y - tool.radiusY,
                    bodyBottom, bodyWidth, bodyDepth,
                    Math.max(4, bodyTop - bodyBottom), base,
                    new Color(208, 221, 229));

            Point shaftTop = project(state.x, state.y, bodyBottom + 2);
            Point shaftBottom = project(state.x, state.y, state.z - dynamicTipLength);
            g.setColor(new Color(184, 194, 202));
            g.setStroke(new BasicStroke((float) Math.max(2,
                    tool.shaftRadius * scale() * 1.6), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.drawLine(shaftTop.x, shaftTop.y, shaftBottom.x, shaftBottom.y);

            double coneRadius = Math.max(1.6, tool.shaftRadius * 0.9);
            Point coneLeft = project(state.x - coneRadius, state.y,
                    state.z - dynamicTipLength + 4);
            Point coneRight = project(state.x + coneRadius, state.y,
                    state.z - dynamicTipLength + 4);
            Point coneTip = project(state.x, state.y,
                    state.z - dynamicTipLength - 1.5);
            g.setColor(new Color(215, 220, 223));
            g.fillPolygon(polygon(coneLeft, coneRight, coneTip));
            g.setColor(new Color(103, 114, 121));
            g.drawPolygon(polygon(coneLeft, coneRight, coneTip));

            if (tool.carryingPart) {
                double partWidth = Math.max(4, tool.radiusX * 1.45);
                double partDepth = Math.max(3, tool.radiusY * 1.3);
                drawCuboid(g, state.x - partWidth / 2.0,
                        state.y - partDepth / 2.0,
                        state.z - dynamicTipLength - 3.2,
                        partWidth, partDepth, 2.2,
                        new Color(84, 112, 88), new Color(174, 201, 159));
            }

            Point label = project(state.x, state.y, bodyTop);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            g.setColor(new Color(236, 239, 241));
            g.drawString(tool.name + String.format(Locale.ROOT, "  Z %.2f", state.z),
                    label.x + 7, label.y - 8);
        }

        private void drawCuboid(Graphics2D g, double x, double y, double z,
                double width, double depth, double height, Color base, Color outline) {
            Point p000 = project(x, y, z);
            Point p100 = project(x + width, y, z);
            Point p010 = project(x, y + depth, z);
            Point p110 = project(x + width, y + depth, z);
            Point p001 = project(x, y, z + height);
            Point p101 = project(x + width, y, z + height);
            Point p011 = project(x, y + depth, z + height);
            Point p111 = project(x + width, y + depth, z + height);
            fillFace(g, shade(base, 0.62), p000, p100, p101, p001);
            fillFace(g, shade(base, 0.78), p100, p110, p111, p101);
            fillFace(g, shade(base, 1.08), p001, p101, p111, p011);
            drawEdges(g, outline, p000, p100, p110, p010,
                    p001, p101, p111, p011);
        }
        private void drawPath(Graphics2D g) {
            if (path.size() < 2) {
                return;
            }
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.setColor(safePath ? new Color(93, 198, 132) : new Color(226, 94, 91));
            for (int index = 1; index < path.size(); index++) {
                Waypoint a = path.get(index - 1);
                Waypoint b = path.get(index);
                Point pa = project(a.x, a.y, a.z);
                Point pb = project(b.x, b.y, b.z);
                g.drawLine(pa.x, pa.y, pb.x, pb.y);
                g.fillOval(pb.x - 4, pb.y - 4, 8, 8);
            }
        }

        private void drawOverlay(Graphics2D g) {
            g.setColor(new Color(0, 0, 0, 145));
            g.fillRoundRect(12, 12, 390, 74, 10, 10);
            g.setColor(new Color(226, 229, 231));
            g.drawString(liveMode.isSelected()
                    ? "实时运行：显示全部贴装头、动态 N1 吸嘴、携带元件与运动轨迹"
                    : "编辑：整面可选物体 · 顶点拖动改尺寸 · 顶点右键示教 · 空白拖动平移",
                    24, 34);
            g.setColor(model.isCertified()
                    ? new Color(113, 212, 148) : new Color(228, 186, 106));
            g.drawString(model.isCertified()
                    ? "当前几何模型已认证" : "当前几何模型未认证，真机联合运动锁定",
                    24, 54);
            g.setColor(new Color(146, 174, 192));
            g.drawString("实时吸嘴 " + liveTools.size() + " · 轨迹缓存 "
                    + liveTrail.size() + " 点 · "
                    + String.format(Locale.ROOT, "%.0f FPS", smoothedFps), 24, 74);
        }

        private Vertex hitVertex(Point mouse) {
            Vertex nearest = null;
            double nearestDistance = 22;
            for (Vertex vertex : SafetyWorkbenchVertexEditor.vertices(selected)) {
                Location location = vertex.getLocation();
                Point point = project(location.getX(), location.getY(), location.getZ());
                double distance = point.distance(mouse);
                if (distance < nearestDistance - 0.5
                        || (Math.abs(distance - nearestDistance) <= 0.5
                                && vertex.maximumZ
                                && (nearest == null || !nearest.maximumZ))) {
                    nearestDistance = distance;
                    nearest = vertex;
                }
            }
            return nearest;
        }

        private SafetyObject hitObject(Point mouse) {
            SafetyObject best = null;
            double bestTop = -Double.MAX_VALUE;
            double bestArea = Double.MAX_VALUE;
            List<SafetyObject> objects = model.getObjects();
            for (int index = objects.size() - 1; index >= 0; index--) {
                SafetyObject object = objects.get(index);
                if (object == null || !object.enabled) {
                    continue;
                }
                java.awt.Rectangle bounds = projectedBounds(object);
                bounds.grow(10, 10);
                if (!bounds.contains(mouse)) {
                    continue;
                }
                double top = isPhysicalReferenceEntity(object.type)
                        ? object.z : object.z + object.height;
                double area = Math.max(1.0, bounds.getWidth() * bounds.getHeight());
                if (top > bestTop + 0.001
                        || (Math.abs(top - bestTop) <= 0.001 && area < bestArea)) {
                    bestTop = top;
                    bestArea = area;
                    best = object;
                }
            }
            return best;
        }

        private java.awt.Rectangle projectedBounds(SafetyObject object) {
            if (isPhysicalReferenceEntity(object.type)) {
                java.awt.Rectangle bounds = new java.awt.Rectangle(project(
                        object.x - object.width / 2.0,
                        object.y - object.depth / 2.0, object.z - object.height));
                for (int ix = 0; ix < 2; ix++) {
                    for (int iy = 0; iy < 2; iy++) {
                        for (int iz = 0; iz < 2; iz++) {
                            bounds.add(project(object.x + (ix - 0.5) * object.width,
                                    object.y + (iy - 0.5) * object.depth,
                                    object.z - object.height + iz * object.height));
                        }
                    }
                }
                return bounds;
            }
            if (object.type.isReferencePoint()) {
                Point center = project(object.x, object.y, object.z);
                return new java.awt.Rectangle(center.x - 12, center.y - 12, 24, 24);
            }
            if (object.shape == Shape.POLYGON && object.polygon != null
                    && !object.polygon.isEmpty()) {
                Point2 firstVertex = object.polygon.get(0);
                java.awt.Rectangle bounds = new java.awt.Rectangle(project(
                        object.x + firstVertex.x, object.y + firstVertex.y, object.z));
                for (Point2 vertex : object.polygon) {
                    bounds.add(project(object.x + vertex.x, object.y + vertex.y, object.z));
                    bounds.add(project(object.x + vertex.x, object.y + vertex.y,
                            object.z + object.height));
                }
                return bounds;
            }
            java.awt.Rectangle bounds = new java.awt.Rectangle(
                    project(object.x, object.y, object.z));
            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        bounds.add(project(object.x + ix * object.width,
                                object.y + iy * object.depth,
                                object.z + iz * object.height));
                    }
                }
            }
            return bounds;
        }

        private Point project(double worldX, double worldY, double worldZ) {
            double centerX = Double.isNaN(viewCenterX)
                    ? (model.getMinX() + model.getMaxX()) / 2.0 : viewCenterX;
            double centerY = Double.isNaN(viewCenterY)
                    ? (model.getMinY() + model.getMaxY()) / 2.0 : viewCenterY;
            double dx = worldX - centerX;
            double dy = worldY - centerY;
            double rx = SafetyWorkbenchViewTransform.horizontal(
                    dx, dy, yaw, mirrorX);
            double ry = SafetyWorkbenchViewTransform.depth(dx, dy, yaw);
            double sx = getWidth() / 2.0 + panX + rx * scale();
            double relativeZ = worldZ - visualOriginZ;
            double sy = getHeight() / 2.0 + panY
                    + (ry * Math.sin(pitch) - relativeZ * Math.cos(pitch)) * scale();
            return new Point((int) Math.round(sx), (int) Math.round(sy));
        }

        double[] groundAt(double screenX, double screenY) {
            double rx = (screenX - getWidth() / 2.0 - panX) / scale();
            double ry = (screenY - getHeight() / 2.0 - panY)
                    / (scale() * Math.sin(pitch));
            double[] delta = SafetyWorkbenchViewTransform.worldDelta(
                    rx, ry, yaw, mirrorX);
            double dx = delta[0];
            double dy = delta[1];
            return new double[] {
                    (Double.isNaN(viewCenterX)
                            ? (model.getMinX() + model.getMaxX()) / 2.0 : viewCenterX) + dx,
                    (Double.isNaN(viewCenterY)
                            ? (model.getMinY() + model.getMaxY()) / 2.0 : viewCenterY) + dy
            };
        }

        private double scale() {
            double extent = Math.max(model.getMaxX() - model.getMinX(),
                    model.getMaxY() - model.getMinY());
            double fitting = Math.min(Math.max(1, getWidth() - 80),
                    Math.max(1, getHeight() - 100)) / Math.max(100, extent);
            return fitting * zoom;
        }

        private double chooseGridInterval() {
            double pixelsPerMillimeter = scale();
            if (pixelsPerMillimeter * 10 >= 34) {
                return 10;
            }
            if (pixelsPerMillimeter * 25 >= 34) {
                return 25;
            }
            if (pixelsPerMillimeter * 50 >= 34) {
                return 50;
            }
            return 100;
        }

        private void fillFace(Graphics2D g, Color color, Point... points) {
            g.setColor(color);
            g.fillPolygon(polygon(points));
        }

        private void drawEdges(Graphics2D g, Color color, Point p000, Point p100,
                Point p110, Point p010, Point p001, Point p101, Point p111, Point p011) {
            g.setColor(color);
            g.setStroke(new BasicStroke(selected == null ? 1f : 1.2f));
            Point[][] lines = {
                    { p000, p100 }, { p100, p110 }, { p110, p010 }, { p010, p000 },
                    { p001, p101 }, { p101, p111 }, { p111, p011 }, { p011, p001 },
                    { p000, p001 }, { p100, p101 }, { p110, p111 }, { p010, p011 }
            };
            for (Point[] line : lines) {
                g.drawLine(line[0].x, line[0].y, line[1].x, line[1].y);
            }
        }

        private Polygon polygon(Point... points) {
            Polygon polygon = new Polygon();
            for (Point point : points) {
                polygon.addPoint(point.x, point.y);
            }
            return polygon;
        }

        private Color shade(Color color, double factor) {
            return new Color(
                    Math.min(255, (int) (color.getRed() * factor)),
                    Math.min(255, (int) (color.getGreen() * factor)),
                    Math.min(255, (int) (color.getBlue() * factor)));
        }
    }
}
