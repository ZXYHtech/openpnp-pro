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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;

import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.Category;
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
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.UiUtils;

/**
 * Operator-defined 3D digital twin and guarded motion laboratory.
 */
@SuppressWarnings("serial")
public class SafetyWorkbenchDialog extends JDialog {
    private final MainFrame frame;
    private final SafetyWorkbenchModel model;
    private final DefaultListModel<SafetyObject> objectListModel = new DefaultListModel<>();
    private final JList<SafetyObject> objectList = new JList<>(objectListModel);
    private final WorkbenchCanvas canvas = new WorkbenchCanvas();
    private final JLabel certification = new JLabel();
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
    private final JToggleButton motionArmed = new JToggleButton("启用窗口内低速点动");
    private final JLabel coordinateStatus = new JLabel("尚未读取坐标");
    private final JLabel vertexStatus = new JLabel("请在 3D 视图点击物体顶点");
    private final JToggleButton liveMode = new JToggleButton("实时运行画面");
    private final JCheckBox followSelectedTool = new JCheckBox("跟随控制吸嘴", true);
    private final JCheckBox keepOnTop = new JCheckBox("窗口置顶");
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
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1220, 760));
        setSize(new Dimension(1440, 860));
        setLocationRelativeTo(frame);
        buildUi();
        reloadObjects();
        updateCertification();
        selectFirstObject();
        startLiveView();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (liveTimer != null) {
                    liveTimer.stop();
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
            canvas.repaint();
        });
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
        liveTimer = new Timer(33, event -> updateLiveScene());
        liveTimer.setCoalesce(true);
        liveTimer.start();
    }

    private void updateLiveScene() {
        long now = System.currentTimeMillis();
        if (now - lastLiveSnapshot >= 45) {
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
                        }
                        state.setTarget(tool);
                    }
                    liveTools.keySet().removeIf(key -> !seen.containsKey(key));
                    updateLiveTrail(selected);
                }
            }
            catch (Exception ignored) {
                // Live visualization is diagnostic and must never disturb a running job.
            }
        }
        for (LiveToolState state : liveTools.values()) {
            state.animate();
        }
        if (liveMode.isSelected() && followSelectedTool.isSelected()) {
            HeadMountable selected = selectedWorkbenchTool();
            if (selected != null && selected.getHead() != null) {
                LiveToolState state = liveTools.get(selected.getHead().getId()
                        + "/" + selected.getId());
                if (state != null) {
                    canvas.follow(state.x, state.y);
                }
            }
        }
        canvas.repaint();
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
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildPalette(), BorderLayout.WEST);
        root.add(canvas, BorderLayout.CENTER);
        root.add(buildProperties(), BorderLayout.EAST);
        root.add(buildMotionPanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        JLabel title = new JLabel("3D 安全工作台 / 数字孪生");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel explanation = new JLabel(
                "拖入物体 → 工具头采点精确标定 → 扫掠碰撞预演 → 认证后解锁受控 XYZ 联动");
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(title);
        text.add(explanation);
        header.add(text, BorderLayout.WEST);
        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        certification.setOpaque(true);
        certification.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        JButton importConfiguration = new JButton("从 OpenPnP 导入…");
        importConfiguration.addActionListener(event -> importOpenPnpConfiguration());
        JButton settings = new JButton("工作台与工具包络…");
        settings.addActionListener(event -> editWorkbench());
        JButton save = new JButton("保存模型");
        save.addActionListener(event -> saveModel());
        JButton certify = new JButton("检查并认证");
        certify.addActionListener(event -> certifyModel());
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
        status.add(liveMode);
        status.add(followSelectedTool);
        status.add(keepOnTop);
        status.add(driver);
        status.add(certification);
        status.add(importConfiguration);
        status.add(settings);
        status.add(save);
        status.add(certify);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    private Component buildPalette() {
        JPanel panel = new JPanel(new BorderLayout(5, 8));
        panel.setPreferredSize(new Dimension(220, 500));
        panel.setBorder(BorderFactory.createTitledBorder("可添加清单（拖入中间）"));
        DefaultListModel<String> paletteModel = new DefaultListModel<>();
        for (ObjectType value : ObjectType.values()) {
            paletteModel.addElement(value.getDisplayName());
        }
        JList<String> palette = new JList<>(paletteModel);
        palette.setFixedCellHeight(34);
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
        duplicate.addActionListener(event -> duplicateSelected());
        JButton remove = new JButton("删除");
        remove.addActionListener(event -> removeSelected());
        buttons.add(duplicate);
        buttons.add(remove);
        placed.add(buttons, BorderLayout.SOUTH);
        panel.add(placed, BorderLayout.CENTER);
        JLabel hint = new JLabel("<html>右键拖动旋转 · 滚轮缩放<br>左键拖动物体 · Shift+左键调整高度</html>");
        hint.setBorder(new EmptyBorder(4, 4, 4, 4));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildProperties() {
        JPanel outer = new JPanel(new BorderLayout(4, 4));
        outer.setPreferredSize(new Dimension(310, 500));
        outer.setBorder(BorderFactory.createTitledBorder("物体属性与实机采点"));
        JPanel fields = new JPanel(new GridLayout(0, 2, 5, 5));
        addField(fields, "名称", name);
        addField(fields, "类别", type);
        addField(fields, "形状", shape);
        addField(fields, "原点 X (mm)", x);
        addField(fields, "原点 Y (mm)", y);
        addField(fields, "原点 Z (mm)", z);
        addField(fields, "宽 X (mm)", width);
        addField(fields, "深 Y (mm)", depth);
        addField(fields, "高 Z (mm)", height);
        addField(fields, "安全间隙 (mm)", clearance);
        addField(fields, "中央通道宽", apertureWidth);
        addField(fields, "中央通道深", apertureDepth);
        fields.add(obstacle);
        fields.add(locked);
        JButton apply = new JButton("应用属性（修改后需重新认证）");
        apply.addActionListener(event -> applyObject());
        JPanel propertyBody = new JPanel(new BorderLayout(4, 5));
        propertyBody.add(fields, BorderLayout.CENTER);
        propertyBody.add(apply, BorderLayout.SOUTH);
        outer.add(propertyBody, BorderLayout.NORTH);

        JPanel capture = new JPanel(new GridLayout(0, 1, 4, 4));
        capture.setBorder(BorderFactory.createTitledBorder("移动工具头后读取当前位置"));
        JButton origin = new JButton("① 记录物体原点");
        origin.addActionListener(event -> captureOrigin());
        JButton minimum = new JButton("② 记录最小角点");
        minimum.addActionListener(event -> captureMinimum());
        JButton maximum = new JButton("③ 记录最大角点并计算尺寸");
        maximum.addActionListener(event -> captureMaximum());
        JButton polygonPoint = new JButton("追加自定义轮廓点");
        polygonPoint.addActionListener(event -> capturePolygonPoint());
        JButton clearPolygon = new JButton("清空自定义轮廓");
        clearPolygon.addActionListener(event -> clearPolygon());
        JButton moveVertex = new JButton("移动到 3D 视图所选顶点");
        moveVertex.addActionListener(event -> moveToSelectedVertex());
        JButton confirmVertex = new JButton("用当前吸嘴位置确认该顶点");
        confirmVertex.addActionListener(event -> confirmSelectedVertex());
        capture.add(origin);
        capture.add(minimum);
        capture.add(maximum);
        capture.add(polygonPoint);
        capture.add(clearPolygon);
        capture.add(moveVertex);
        capture.add(confirmVertex);
        JTextArea captureHint = new JTextArea(
                "采点只读取工具头当前位置，不会自动移动机器。请使用原有点动控制慢速靠近物体边界，"
                        + "也可转到“移动/坐标”在本窗口低速点动；请把吸嘴半径、相机外壳和线缆活动范围计入安全间隙。");
        captureHint.setEditable(false);
        captureHint.setLineWrap(true);
        captureHint.setWrapStyleWord(true);
        captureHint.setOpaque(false);
        captureHint.setRows(4);
        capture.add(captureHint);
        outer.add(capture, BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setPreferredSize(new Dimension(330, 500));
        tabs.addTab("物体/采点", outer);
        tabs.addTab("贴装头/吸嘴", buildToolProfilesPanel());
        tabs.addTab("移动/坐标", buildCoordinatePanel());
        return tabs;
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

        JPanel fields = new JPanel(new GridLayout(0, 2, 5, 5));
        addField(fields, "显示名称", profileName);
        addField(fields, "机头半宽 X", profileBodyRadiusX);
        addField(fields, "机头半深 Y", profileBodyRadiusY);
        addField(fields, "机头最低 Z 偏移", profileBodyMinZ);
        addField(fields, "机头最高 Z 偏移", profileBodyMaxZ);
        addField(fields, "吸嘴杆半径", profileShaftRadius);
        addField(fields, "额外安全间隙", profileClearance);
        addField(fields, "收起标定 Z", profileRetractedZ);
        addField(fields, "伸出标定 Z", profileExtendedZ);
        addField(fields, "收起时吸嘴长度", profileRetractedLength);
        addField(fields, "伸出时吸嘴长度", profileExtendedLength);
        fields.add(profileEnabled);
        fields.add(profileBodyFollowsZ);
        fields.add(new JLabel("Z 动态规则"));
        fields.add(new JLabel("吸嘴长度按两标定点插值"));

        JPanel actions = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton captureRetracted = new JButton("读取当前 N1 位置作为收起 Z");
        captureRetracted.addActionListener(event -> captureProfileZ(true));
        JButton captureExtended = new JButton("读取当前 N1 位置作为伸出 Z");
        captureExtended.addActionListener(event -> captureProfileZ(false));
        JButton apply = new JButton("应用吸嘴包络并重新认证");
        apply.addActionListener(event -> applyToolProfile());
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
        actions.add(hint);

        JPanel body = new JPanel(new BorderLayout(4, 5));
        body.add(fields, BorderLayout.NORTH);
        body.add(actions, BorderLayout.CENTER);
        panel.add(new JScrollPane(body), BorderLayout.CENTER);
        if (!toolProfileListModel.isEmpty()) {
            toolProfileList.setSelectedIndex(0);
        }
        return panel;
    }

    private Component buildCoordinatePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 6));
        JPanel selectors = new JPanel(new GridLayout(0, 2, 5, 5));
        addField(selectors, "当前控制吸嘴", workbenchNozzle);
        addField(selectors, "飞达取料坐标", workbenchFeeder);
        addField(selectors, "目标 X (mm)", coordinateX);
        addField(selectors, "目标 Y (mm)", coordinateY);
        addField(selectors, "吸嘴 Z (mm)", coordinateZ);
        addField(selectors, "旋转 (°)", coordinateRotation);
        addField(selectors, "点动步距 (mm)", jogStep);
        selectors.add(new JLabel("安全锁"));
        selectors.add(motionArmed);
        panel.add(selectors, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton readCurrent = new JButton("读取当前吸嘴 XYZ/R");
        readCurrent.addActionListener(event -> readCurrentCoordinate());
        JButton readZ = new JButton("仅读取当前吸嘴 Z");
        readZ.addActionListener(event -> readCurrentNozzleZ());
        JButton readFeeder = new JButton("读取所选飞达拾取坐标");
        readFeeder.addActionListener(event -> readSelectedFeederCoordinate());
        JButton readVertex = new JButton("使用 3D 视图所选物体/顶点坐标");
        readVertex.addActionListener(event -> useSelectedVertexCoordinate());
        actions.add(readCurrent);
        actions.add(readZ);
        actions.add(readFeeder);
        actions.add(readVertex);

        JPanel jog = new JPanel(new GridLayout(3, 3, 3, 3));
        jog.setBorder(BorderFactory.createTitledBorder("窗口内低速点动"));
        JButton xMinus = new JButton("X−");
        xMinus.addActionListener(event -> jog(-1, 0, 0));
        JButton yPlus = new JButton("Y+");
        yPlus.addActionListener(event -> jog(0, 1, 0));
        JButton zPlus = new JButton("Z+");
        zPlus.addActionListener(event -> jog(0, 0, 1));
        JButton yMinus = new JButton("Y−");
        yMinus.addActionListener(event -> jog(0, -1, 0));
        JButton xPlus = new JButton("X+");
        xPlus.addActionListener(event -> jog(1, 0, 0));
        JButton zMinus = new JButton("Z−");
        zMinus.addActionListener(event -> jog(0, 0, -1));
        jog.add(new JLabel());
        jog.add(yPlus);
        jog.add(zPlus);
        jog.add(xMinus);
        jog.add(new JLabel("步进", SwingConstants.CENTER));
        jog.add(xPlus);
        jog.add(new JLabel());
        jog.add(yMinus);
        jog.add(zMinus);
        actions.add(jog);

        JButton move = new JButton("移动到坐标（安全 Z 路径）");
        move.addActionListener(event -> moveToCoordinate(false));
        JButton moveAndRelease = new JButton("移动到坐标并释放吸嘴物料");
        moveAndRelease.addActionListener(event -> moveToCoordinate(true));
        actions.add(move);
        actions.add(moveAndRelease);
        vertexStatus.setToolTipText("先在 3D 视图点击黄色顶点手柄；点击不会立即驱动机器。");
        actions.add(vertexStatus);
        actions.add(coordinateStatus);

        JTextArea hint = new JTextArea(
                "这里直接控制上方选定的吸嘴，不再依赖主界面的机器控件。"
                        + "所有运动均要求实验模式、机器启用并回零、软限位可达。"
                        + "点动限速；整段移动先抬到安全 Z，再走 XY，最后下降。"
                        + "顶点示教只在路径检查时忽略被测物体本身，其他物体仍参与碰撞检查。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setRows(5);
        actions.add(hint);
        panel.add(new JScrollPane(actions), BorderLayout.CENTER);
        return panel;
    }

    private Component buildMotionPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 5));
        panel.setBorder(BorderFactory.createTitledBorder("轨迹实验室（先预演，后真机）"));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 2));
        controls.add(new JLabel("轨迹"));
        controls.add(route);
        controls.add(new JLabel("中心/目标 X"));
        controls.add(targetX);
        controls.add(new JLabel("Y"));
        controls.add(targetY);
        controls.add(new JLabel("对焦/目标 Z"));
        controls.add(targetZ);
        controls.add(new JLabel("飞拍跨度"));
        controls.add(flightSpan);
        controls.add(new JLabel("速度"));
        controls.add(speed);
        JButton useSelected = new JButton("使用所选物体中心");
        useSelected.addActionListener(event -> useSelectedObjectCenter());
        JButton simulate = new JButton("3D 预演与碰撞检查");
        simulate.addActionListener(event -> simulate());
        JButton execute = new JButton("受控真机测试");
        execute.addActionListener(event -> executePhysicalTest());
        controls.add(useSelected);
        controls.add(simulate);
        controls.add(execute);
        panel.add(controls, BorderLayout.NORTH);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setText("真机按钮不会因“看起来没问题”而解锁：必须实验模式开启、模型已认证、"
                + "机器已回零、吸嘴无料、软限位可达，并再次通过整段扫掠检查。");
        JScrollPane resultScroll = new JScrollPane(result);
        resultScroll.setPreferredSize(new Dimension(300, 78));
        panel.add(resultScroll, BorderLayout.CENTER);
        panel.add(physicalConfirmation, BorderLayout.SOUTH);
        return panel;
    }

    private static void addField(JPanel panel, String label, Component field) {
        panel.add(new JLabel(label));
        panel.add(field);
    }

    private void importOpenPnpConfiguration() {
        JCheckBox workArea = new JCheckBox("工作范围：XYZ 轴已启用的软限位", true);
        JCheckBox homeAndPark = new JCheckBox("Home 点和各贴装头停靠点", true);
        JCheckBox changer = new JCheckBox("自动换吸嘴的起点、中间点和终点", true);
        JCheckBox discard = new JCheckBox("机器抛料点", true);
        JCheckBox feeders = new JCheckBox("全部飞达的取料点", true);
        JPanel choices = new JPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.add(new JLabel("选择要从当前 OpenPnP 机器配置同步到 3D 工作台的内容："));
        choices.add(Box.createVerticalStrut(8));
        choices.add(workArea);
        choices.add(homeAndPark);
        choices.add(changer);
        choices.add(discard);
        choices.add(feeders);
        choices.add(Box.createVerticalStrut(8));
        choices.add(new JLabel("再次导入会按来源更新已有标记，不会重复堆叠。"));
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
        SafetyWorkbenchImportService.Summary summary =
                SafetyWorkbenchImportService.importConfiguration(model,
                        Configuration.get().getMachine(), selectedWorkbenchNozzle(), categories);
        reloadObjects();
        selectFirstObject();
        updateCertification();
        canvas.repaint();
        result.setText(summary.toDisplayText()
                + "\n导入后请在 3D 视图核对标记和范围，再重新检查并认证模型。");
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
                height, clearance, apertureWidth, apertureDepth, obstacle, locked)) {
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
        clearance.setText(format(object.clearance));
        apertureWidth.setText(format(object.apertureWidth));
        apertureDepth.setText(format(object.apertureDepth));
        obstacle.setSelected(object.obstacle);
        locked.setSelected(object.locked);
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
        SafetyWorkbenchVertexEditor.apply(vertex, location);
        model.touch();
        showObject(vertex.object);
        objectList.repaint();
        canvas.repaint();
        updateCertification();
        setCoordinate(location);
        result.setText("已用当前吸嘴坐标确认“" + vertex.getLabel()
                + "”。物体几何已更新，需要重新检查并认证。"
                + (vertex.object.sourceKey == null || vertex.object.sourceKey.isEmpty()
                        ? "" : "该点来自 OpenPnP；再次导入会恢复为机器配置中的坐标。"));
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
                    "请先在“移动/坐标”页启用窗口内低速点动安全锁。",
                    "移动未解锁", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return nozzle;
    }

    private Location targetCoordinate() {
        return new Location(LengthUnit.Millimeters, number(coordinateX), number(coordinateY),
                number(coordinateZ), number(coordinateRotation));
    }

    private boolean checkReachable(Nozzle nozzle, Location target) {
        try {
            if (nozzle.isReachable(target)) {
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
                            + "请在实验功能的“运动性能与补偿中心”完成驱动升级和基础测试。",
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
                        + "工具头/吸嘴包络和安全余量。\n"
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
            result.setText("安全工作台已保存到：" + SafetyWorkbenchStore.getFile().getAbsolutePath());
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "保存失败：" + exception.getMessage(),
                    "保存模型", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateCertification() {
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

        void setTarget(DynamicTool tool) {
            this.tool = tool;
            targetX = tool.reference.x;
            targetY = tool.reference.y;
            targetZ = tool.reference.z;
        }

        void animate() {
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

    private static class ObjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                    selected, focused);
            if (value instanceof SafetyObject) {
                SafetyObject object = (SafetyObject) value;
                label.setText(typeMark(object.type) + " "
                        + (object.locked ? "[锁定] " : "") + object.name);
                label.setToolTipText(object.type.getDisplayName() + " · "
                        + format(object.width) + " × " + format(object.depth) + " × "
                        + format(object.height) + " mm");
            }
            return label;
        }

        private static String typeMark(ObjectType type) {
            switch (type) {
                case FEEDER:
                    return "▥";
                case PLACEMENT_HEAD:
                    return "⌁";
                case BOTTOM_CAMERA:
                    return "◉";
                case LOOSE_PARTS_FEEDER:
                    return "▦";
                case BOARD:
                    return "▤";
                case KEEP_OUT:
                    return "⚠";
                case HOME_POINT:
                    return "⌂";
                case PARK_POINT:
                    return "P";
                case NOZZLE_CHANGER_POINT:
                    return "↻";
                case DISCARD_POINT:
                    return "⌫";
                case FEEDER_PICK_POINT:
                    return "◎";
                default:
                    return "◆";
            }
        }
    }

    private class WorkbenchCanvas extends JPanel {
        private double yaw = Math.toRadians(-35);
        private double pitch = Math.toRadians(38);
        private double zoom = 1.25;
        private double panX;
        private double panY = 35;
        private double viewCenterX = Double.NaN;
        private double viewCenterY = Double.NaN;
        private SafetyObject selected;
        private Vertex selectedVertex;
        private Point mouseStart;
        private double dragStartX;
        private double dragStartY;
        private double dragStartZ;
        private double startYaw;
        private double startPitch;
        private boolean objectDragged;
        private List<Waypoint> path = new ArrayList<>();
        private boolean safePath;
        private long lastPaintNanos;
        private double smoothedFps;

        WorkbenchCanvas() {
            setDoubleBuffered(true);
            setOpaque(true);
            setBackground(new Color(38, 43, 48));
            setBorder(BorderFactory.createLineBorder(new Color(88, 96, 104)));
            setTransferHandler(new CanvasTransferHandler());
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    requestFocusInWindow();
                    mouseStart = event.getPoint();
                    startYaw = yaw;
                    startPitch = pitch;
                    Vertex vertex = hitVertex(event.getPoint());
                    selected = vertex == null ? hitObject(event.getPoint()) : vertex.object;
                    objectList.setSelectedValue(selected, true);
                    selectedVertex = vertex;
                    onVertexSelected(selectedVertex);
                    if (selected != null) {
                        dragStartX = selected.x;
                        dragStartY = selected.y;
                        dragStartZ = selected.z;
                    }
                    objectDragged = false;
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (mouseStart == null) {
                        return;
                    }
                    int dx = event.getX() - mouseStart.x;
                    int dy = event.getY() - mouseStart.y;
                    if ((event.getModifiersEx() & InputEvent.BUTTON3_DOWN_MASK) != 0) {
                        followSelectedTool.setSelected(false);
                        yaw = startYaw + dx * 0.008;
                        pitch = Math.max(Math.toRadians(15),
                                Math.min(Math.toRadians(75), startPitch - dy * 0.006));
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
                    if (objectDragged) {
                        model.touch();
                        updateCertification();
                    }
                    mouseStart = null;
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

        void setSelected(SafetyObject selected) {
            if (this.selected != selected) {
                selectedVertex = null;
                onVertexSelected(null);
            }
            this.selected = selected;
            repaint();
        }

        Vertex getSelectedVertex() {
            return selectedVertex;
        }

        void setPath(List<Waypoint> path, boolean safe) {
            this.path = new ArrayList<>(path);
            this.safePath = safe;
            repaint();
        }
        void follow(double worldX, double worldY) {
            if (Double.isNaN(viewCenterX)) {
                viewCenterX = worldX;
                viewCenterY = worldY;
            }
            else {
                viewCenterX += (worldX - viewCenterX) * 0.16;
                viewCenterY += (worldY - viewCenterY) * 0.16;
            }
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
            drawLiveTrail(g);
            drawPath(g);
            drawLiveTools(g);
            drawOverlay(g);
            g.dispose();
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
                int radius = active ? 7 : 5;
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
            for (double gx = Math.ceil(model.getMinX() / interval) * interval;
                    gx <= model.getMaxX(); gx += interval) {
                Point a = project(gx, model.getMinY(), model.getMinZ());
                Point b = project(gx, model.getMaxY(), model.getMinZ());
                g.setColor(new Color(72, 79, 85));
                g.drawLine(a.x, a.y, b.x, b.y);
            }
            for (double gy = Math.ceil(model.getMinY() / interval) * interval;
                    gy <= model.getMaxY(); gy += interval) {
                Point a = project(model.getMinX(), gy, model.getMinZ());
                Point b = project(model.getMaxX(), gy, model.getMinZ());
                g.setColor(new Color(72, 79, 85));
                g.drawLine(a.x, a.y, b.x, b.y);
            }
            drawAxis(g, model.getMinX(), model.getMinY(), model.getMinZ(),
                    model.getMaxX(), model.getMinY(), model.getMinZ(),
                    new Color(177, 94, 94), "X");
            drawAxis(g, model.getMinX(), model.getMinY(), model.getMinZ(),
                    model.getMinX(), model.getMaxY(), model.getMinZ(),
                    new Color(91, 155, 107), "Y");
            drawAxis(g, model.getMinX(), model.getMinY(), model.getMinZ(),
                    model.getMinX(), model.getMinY(), model.getMaxZ(),
                    new Color(88, 133, 184), "Z");
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
            g.setColor(new Color(231, 234, 236));
            g.drawString(object.name, top.x + 7, top.y - 6);
            g.setFont(originalFont.deriveFont(9f));
            g.setColor(new Color(171, 183, 191));
            g.drawString(String.format(Locale.ROOT, "X %.2f  Y %.2f  Z %.2f",
                    object.x, object.y, object.z), top.x + 7, top.y + 7);
            g.setFont(originalFont);
        }

        private void drawFeederDetails(Graphics2D g, SafetyObject object) {
            double top = object.z + object.height + 0.3;
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
                    object.y + object.depth * 0.84, top + object.height * 0.32);
            int reelRadius = (int) Math.max(5, object.width * scale() * 0.38);
            g.setColor(new Color(111, 122, 113));
            g.fillOval(reel.x - reelRadius, reel.y - reelRadius,
                    reelRadius * 2, reelRadius * 2);
            g.setColor(new Color(205, 211, 205));
            g.drawOval(reel.x - reelRadius, reel.y - reelRadius,
                    reelRadius * 2, reelRadius * 2);
            g.fillOval(reel.x - 2, reel.y - 2, 4, 4);
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
            if (object.type == ObjectType.BOTTOM_CAMERA && object.apertureWidth > 0
                    && object.apertureDepth > 0) {
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
                    : "编辑模式：拖动物体 · Shift 升降 · 右键旋转 · 滚轮缩放",
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
            double nearestDistance = 13;
            for (Vertex vertex : SafetyWorkbenchVertexEditor.vertices(selected)) {
                Location location = vertex.getLocation();
                Point point = project(location.getX(), location.getY(), location.getZ());
                double distance = point.distance(mouse);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = vertex;
                }
            }
            return nearest;
        }

        private SafetyObject hitObject(Point mouse) {
            SafetyObject best = null;
            double bestDistance = 34;
            for (SafetyObject object : model.getObjects()) {
                if (object == null || !object.enabled) {
                    continue;
                }
                Point center = object.type.isReferencePoint()
                        ? project(object.x, object.y, object.z)
                        : project(object.x + object.width / 2.0,
                                object.y + object.depth / 2.0,
                                object.z + object.height / 2.0);
                double distance = center.distance(mouse);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = object;
                }
            }
            return best;
        }

        private Point project(double worldX, double worldY, double worldZ) {
            double centerX = Double.isNaN(viewCenterX)
                    ? (model.getMinX() + model.getMaxX()) / 2.0 : viewCenterX;
            double centerY = Double.isNaN(viewCenterY)
                    ? (model.getMinY() + model.getMaxY()) / 2.0 : viewCenterY;
            double dx = worldX - centerX;
            double dy = worldY - centerY;
            double rx = Math.cos(yaw) * dx - Math.sin(yaw) * dy;
            double ry = Math.sin(yaw) * dx + Math.cos(yaw) * dy;
            double sx = getWidth() / 2.0 + panX + rx * scale();
            double sy = getHeight() / 2.0 + panY
                    + (ry * Math.sin(pitch) - worldZ * Math.cos(pitch)) * scale();
            return new Point((int) Math.round(sx), (int) Math.round(sy));
        }

        double[] groundAt(double screenX, double screenY) {
            double rx = (screenX - getWidth() / 2.0 - panX) / scale();
            double ry = (screenY - getHeight() / 2.0 - panY)
                    / (scale() * Math.sin(pitch));
            double dx = Math.cos(yaw) * rx + Math.sin(yaw) * ry;
            double dy = -Math.sin(yaw) * rx + Math.cos(yaw) * ry;
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
