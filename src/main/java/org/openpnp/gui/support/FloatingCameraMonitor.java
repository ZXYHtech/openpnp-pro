/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Window;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.CameraView;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;

/** Always-on-top, edge-dockable active camera monitor with configurable quick commands. */
public class FloatingCameraMonitor {
    private static final String PREF_ENABLED = "floatingCamera.enabled";
    private static final String PREF_X = "floatingCamera.x";
    private static final String PREF_Y = "floatingCamera.y";
    private static final String PREF_WIDTH = "floatingCamera.width";
    private static final String PREF_HEIGHT = "floatingCamera.height";
    private static final String PREF_COMMAND = "floatingCamera.command.";
    private static final String PREF_COMMAND_COUNT = "floatingCamera.commandCount";
    private static final String PREF_CAMERA_SOURCE = "floatingCamera.cameraSource";
    private static final int HEADER_HEIGHT = 38;
    private static final int EDGE_VISIBLE = 22;

    public enum Command {
        START_JOB("任务", "启动/暂停", "启动、暂停或继续贴片任务"),
        STEP_JOB("任务", "单步执行", "单步执行贴片任务"),
        STOP_JOB("任务", "停止任务", "停止当前贴片任务"),
        PAUSE_NOZZLES_EMPTY("任务", "吸嘴贴完暂停", "完成所有吸嘴上的物料贴装，确认吸嘴全部为空后暂停"),
        PICK("任务", "取料", "从当前选择的飞达安全取料"),
        DEFER_ERRORS("任务", "延后错误", "切换任务错误立即提示或延后处理"),
        RESET_PLACED("任务", "重置贴片", "把任务器件重置为未贴片"),
        SAVE_JOB("任务", "保存任务", "保存当前任务"),
        SAVE_JOB_AS("任务", "任务另存", "将当前任务另存为"),
        OPEN_JOB("任务", "打开任务", "打开贴片任务"),
        NEW_JOB("任务", "新建任务", "新建贴片任务"),
        PACKAGE_ALIGNMENT("对位", "整板投射", "切换顶部相机整板封装实时投射"),
        FIDUCIAL_CHECK("对位", "Mark对位", "执行单板基准点对位"),
        TWO_POINT_LOCATE("对位", "两点定位", "使用两点重新定位单板"),
        VIEW_JOB("对位", "任务预览", "打开全任务器件查看器"),
        CAPTURE_CAMERA_BOARD("对位", "相机设原点", "用相机当前位置设置单板位置"),
        CAPTURE_TOOL_BOARD("对位", "吸嘴设原点", "用工具当前位置设置单板位置"),
        MOVE_CAMERA_BOARD("对位", "相机到原点", "移动相机到当前单板位置"),
        MOVE_CAMERA_NEXT("对位", "相机下一板", "移动相机到下一块单板"),
        MOVE_TOOL_BOARD("对位", "吸嘴到原点", "移动工具到当前单板位置"),
        HOME("机器", "回零", "执行机器回零"),
        MACHINE_POWER("机器", "机器电源", "启动或关闭机器"),
        TARGET_TOOL("机器", "定位吸嘴", "让相机移动到当前工具位置"),
        TARGET_CAMERA("机器", "定位相机", "让工具移动到当前相机位置"),
        PARK_XY("机器", "停靠XY", "将 XY 轴移动到停靠位置"),
        PARK_Z("机器", "停靠Z", "将 Z 轴移动到停靠位置"),
        PARK_C("机器", "停靠旋转", "将旋转轴移动到停靠位置"),
        SAFE_Z("机器", "安全高度", "所有工具移动到安全 Z 高度"),
        DISCARD("机器", "抛料", "将吸嘴物料移动到抛料位置"),
        RECYCLE("机器", "回收物料", "将吸嘴物料送回回收位置"),
        JOG_X_PLUS("点动", "X+", "X 轴正向点动"),
        JOG_X_MINUS("点动", "X-", "X 轴反向点动"),
        JOG_Y_PLUS("点动", "Y+", "Y 轴正向点动"),
        JOG_Y_MINUS("点动", "Y-", "Y 轴反向点动"),
        JOG_Z_PLUS("点动", "Z+", "Z 轴正向点动"),
        JOG_Z_MINUS("点动", "Z-", "Z 轴反向点动"),
        JOG_C_PLUS("点动", "C+", "旋转轴正向点动"),
        JOG_C_MINUS("点动", "C-", "旋转轴反向点动"),
        JOG_INCREMENT_UP("点动", "增大步距", "增大点动步距"),
        JOG_INCREMENT_DOWN("点动", "减小步距", "减小点动步距"),
        SHOW_JOB("页面", "任务页面", "切换到贴片任务页面"),
        SHOW_DASHBOARD("页面", "生产看板", "切换到生产看板"),
        SHOW_PARTS("页面", "元件库", "切换到元件库"),
        SHOW_PACKAGES("页面", "封装库", "切换到封装库"),
        SHOW_FEEDERS("页面", "飞达", "切换到飞达页面"),
        SHOW_BOARDS("页面", "单板库", "切换到单板库"),
        SHOW_PANELS("页面", "拼版库", "切换到拼版库"),
        SHOW_VISION("页面", "视觉设置", "切换到视觉设置"),
        SHOW_MACHINE_SETUP("页面", "机器设置", "切换到机器设置"),
        SHOW_ISSUES("页面", "问题诊断", "切换到问题与解决方案"),
        UNDO("编辑", "撤销", "撤销上一次修改"),
        REDO("编辑", "重做", "恢复上一次撤销"),
        DOCK("窗口", "停靠悬浮窗", "贴靠到最近的屏幕边缘"),
        HIDE("窗口", "隐藏悬浮窗", "关闭悬浮监控窗");

        private final String category;
        private final String label;
        private final String description;

        Command(String category, String label, String description) {
            this.category = category;
            this.label = label;
            this.description = description;
        }

        @Override
        public String toString() {
            return category + " · " + label;
        }
    }

    public enum CameraSourceMode {
        FOLLOW_ACTIVE("跟随活动画面", "等待活动相机"),
        TOP_CAMERA("固定顶部相机", "未找到顶部相机（向下看）"),
        BOTTOM_CAMERA("固定底部相机", "未找到底部相机（向上看）");

        private final String label;
        private final String unavailableLabel;

        CameraSourceMode(String label, String unavailableLabel) {
            this.label = label;
            this.unavailableLabel = unavailableLabel;
        }

        @Override
        public String toString() {
            return label;
        }
    }
    private final MainFrame owner;
    private final Preferences prefs = Preferences.userNodeForPackage(FloatingCameraMonitor.class);
    private final JFrame window;
    private final CameraView cameraView = new CameraView();
    private final CameraLayerPane cameraContainer = new CameraLayerPane();
    private final JPanel commandPanel = new JPanel(new java.awt.GridLayout(1, 4, 8, 0));
    private final JPanel footer = new JPanel(new BorderLayout(7, 0));
    private final JPanel warningPanel = new JPanel(new BorderLayout(8, 8));
    private final JPanel warningActions = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.CENTER, 8, 0));
    private final JLabel warningLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel cameraNameLabel = new JLabel("等待摄像头");
    private final Timer cameraTimer;
    private final Timer presentationTimer;
    private final Timer redockTimer;
    private final MessageBoxes.ErrorListener errorListener = new MessageBoxes.ErrorListener() {
        @Override
        public void errorShown(String title, String message) {
            showError(title, message);
        }

        @Override
        public void errorDialogShown(String title, String message, JDialog dialog) {
            mirrorDialog(dialog, findOptionPane(dialog));
        }
    };
    private final AWTEventListener dialogErrorListener = this::inspectOpenedDialog;
    private final Thread.UncaughtExceptionHandler previousExceptionHandler =
            Thread.getDefaultUncaughtExceptionHandler();
    private final Thread.UncaughtExceptionHandler exceptionHandler = (thread, cause) -> {
        showError("未处理异常", cause.getMessage() == null ? cause.toString() : cause.getMessage());
        if (previousExceptionHandler != null) {
            previousExceptionHandler.uncaughtException(thread, cause);
        }
        else {
            cause.printStackTrace();
        }
    };

    private Camera activeCamera;
    private CameraView activePresentationSource;
    private Rectangle expandedBounds;
    private Point dragScreenOrigin;
    private Point dragWindowOrigin;
    private Dimension resizeOrigin;
    private boolean docked;
    private boolean dockedRight = true;
    private boolean minimized;
    private boolean autoCompact;
    private volatile JDialog activeDialog;
    private volatile JOptionPane activeOptionPane;

    public FloatingCameraMonitor(MainFrame owner) {
        this.owner = owner;
        window = new JFrame("OpenPnP Pro · 实时监控");
        buildWindow();
        cameraTimer = new Timer(350, event -> {
            updateActiveCamera();
            updateCompactMode();
            ensureInteractiveWindow();
        });
        presentationTimer = new Timer(50, event -> mirrorCameraPresentation());
        redockTimer = new Timer(700, event -> collapseToEdgeIfPointerOutside());
        redockTimer.setRepeats(false);
        MessageBoxes.addErrorListener(errorListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(dialogErrorListener,
                AWTEvent.WINDOW_EVENT_MASK);
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler);
        if (isEnabled()) {
            show();
        }
    }

    private void buildWindow() {
        window.setUndecorated(true);
        window.setType(Window.Type.UTILITY);
        window.setAlwaysOnTop(true);
        try {
            window.setModalExclusionType(Dialog.ModalExclusionType.TOOLKIT_EXCLUDE);
        }
        catch (SecurityException e) {
            window.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        }
        window.setFocusable(true);
        window.setFocusableWindowState(true);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.getRootPane().setBorder(BorderFactory.createLineBorder(new Color(0x42566F), 1));
        window.getContentPane().setLayout(new BorderLayout());
        window.getContentPane().setBackground(new Color(0x101722));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 4));
        header.setBackground(new Color(0x182433));
        JLabel title = new JLabel("OPENPNP Pro · 实时监控");
        title.setForeground(new Color(0xE6EDF7));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        header.add(title, BorderLayout.WEST);
        cameraNameLabel.setForeground(new Color(0x8EA4BD));
        cameraNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(cameraNameLabel, BorderLayout.CENTER);

        JPanel headerButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 3, 0));
        headerButtons.setOpaque(false);
        JButton settings = headerButton("设置");
        settings.setToolTipText("悬浮监控设置");
        settings.addActionListener(event -> showSettingsDialog());
        JButton minimize = headerButton("—");
        minimize.setToolTipText("最小化或恢复");
        minimize.addActionListener(event -> toggleMinimized());
        JButton close = headerButton("×");
        close.setToolTipText("关闭悬浮监控（可在设置中重新打开）");
        close.addActionListener(event -> setEnabled(false));
        headerButtons.add(settings);
        headerButtons.add(minimize);
        headerButtons.add(close);
        header.add(headerButtons, BorderLayout.EAST);
        installDragHandler(header);
        installDragHandler(title);
        installDragHandler(cameraNameLabel);
        window.add(header, BorderLayout.NORTH);

        cameraContainer.setOpaque(true);
        cameraContainer.setBackground(Color.BLACK);
        cameraContainer.add(cameraView, Integer.valueOf(JLayeredPane.DEFAULT_LAYER));
        cameraContainer.add(warningPanel, Integer.valueOf(JLayeredPane.MODAL_LAYER));
        buildWarningPanel();
        window.add(cameraContainer, BorderLayout.CENTER);

        footer.setLayout(new BorderLayout(7, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 6));
        footer.setBackground(new Color(0x111A25));
        commandPanel.setOpaque(false);
        footer.add(commandPanel, BorderLayout.CENTER);
        JLabel resizeGrip = new JLabel("◢", SwingConstants.RIGHT);
        resizeGrip.setForeground(new Color(0x6E8197));
        resizeGrip.setPreferredSize(new Dimension(16, 22));
        installResizeHandler(resizeGrip);
        footer.add(resizeGrip, BorderLayout.EAST);
        window.add(footer, BorderLayout.SOUTH);
        rebuildCommands();

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (docked) {
                    expandFromEdge();
                }
                redockTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (docked) {
                    redockTimer.restart();
                }
            }
        };
        installHoverHandler(window.getContentPane(), hover);
        restoreBounds();
    }

    private JButton headerButton(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 7, 1, 7));
        return button;
    }

    private void buildWarningPanel() {
        warningPanel.setOpaque(true);
        warningPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        warningPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        warningLabel.setForeground(Color.WHITE);
        warningLabel.setFont(warningLabel.getFont().deriveFont(Font.BOLD, 15f));
        warningPanel.add(warningLabel, BorderLayout.CENTER);
        warningActions.setOpaque(false);
        warningPanel.add(warningActions, BorderLayout.SOUTH);
        setWarningStyle(JOptionPane.ERROR_MESSAGE);
        installDragHandler(warningLabel);
        warningPanel.setVisible(false);
    }

    private void setWarningStyle(int messageType) {
        Color background = messageType == JOptionPane.ERROR_MESSAGE
                ? new Color(116, 26, 31, 238)
                : messageType == JOptionPane.WARNING_MESSAGE
                        ? new Color(102, 72, 19, 238) : new Color(24, 67, 104, 238);
        Color border = messageType == JOptionPane.ERROR_MESSAGE
                ? new Color(0xFF5964) : messageType == JOptionPane.WARNING_MESSAGE
                        ? new Color(0xE6A23C) : new Color(0x4C92E8);
        warningPanel.setBackground(background);
        warningPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 2),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
    }

    private void rebuildCommands() {
        commandPanel.removeAll();
        int commandCount = readCommandCount();
        commandPanel.setLayout(new java.awt.GridLayout(0, Math.min(4, commandCount), 8, 7));
        Command[] defaults = defaultCommands(commandCount);
        for (int index = 0; index < commandCount; index++) {
            Command command = readCommand(index, defaults[index]);
            JButton button = new RoundedCommandButton(commandButtonText(command),
                    commandButtonColor(command));
            button.setToolTipText("<html><b>" + command + "</b><br>" + command.description
                    + "</html>");
            button.addActionListener(event -> run(command));
            commandPanel.add(button);
        }
        commandPanel.revalidate();
        commandPanel.repaint();
    }

    private int readCommandCount() {
        return Math.max(4, Math.min(8, prefs.getInt(PREF_COMMAND_COUNT, 4)));
    }

    private Command[] defaultCommands(int count) {
        Command[] preferred = {Command.START_JOB, Command.STOP_JOB, Command.PICK,
                Command.PAUSE_NOZZLES_EMPTY, Command.HOME, Command.DISCARD,
                Command.SHOW_JOB, Command.DOCK};
        return java.util.Arrays.copyOf(preferred, count);
    }

    private String commandButtonText(Command command) {
        switch (command) {
            case START_JOB: return "▶  启动";
            case STOP_JOB: return "■  停止";
            case PAUSE_NOZZLES_EMPTY: return "◫  贴完暂停";
            case PICK: return "◆  取料";
            case DOCK: return "◀  停靠";
            case HOME: return "⌂  回零";
            case MACHINE_POWER: return "◉  电源";
            case DISCARD: return "▼  抛料";
            case HIDE: return "—  隐藏";
            default:
                return command.label.length() > 5
                        ? command.label.substring(0, 5) + "…" : command.label;
        }
    }

    private Color commandButtonColor(Command command) {
        if (command == Command.STOP_JOB || command == Command.DISCARD) {
            return new Color(0xC4414B);
        }
        if (command == Command.START_JOB || command == Command.PAUSE_NOZZLES_EMPTY) {
            return new Color(0x16845B);
        }
        if ("任务".equals(command.category) || "对位".equals(command.category)) {
            return new Color(0x2878D0);
        }
        if ("机器".equals(command.category) || "点动".equals(command.category)) {
            return new Color(0x8B62C8);
        }
        if ("页面".equals(command.category)) {
            return new Color(0x397A8A);
        }
        return new Color(0x526579);
    }
    private Command readCommand(int index, Command fallback) {
        try {
            return Command.valueOf(prefs.get(PREF_COMMAND + index, fallback.name()));
        }
        catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void run(Command command) {
        switch (command) {
            case PICK:
                owner.performSafePick();
                return;
            case DOCK:
                dockToNearestEdge();
                return;
            case HIDE:
                setEnabled(false);
                return;
            case SHOW_JOB:
                showTab(owner.getJobTab());
                return;
            case SHOW_DASHBOARD:
                showTab(owner.getProductionDashboardTab());
                return;
            case SHOW_PARTS:
                showTab(owner.getPartsTab());
                return;
            case SHOW_PACKAGES:
                showTab(owner.getPackagesTab());
                return;
            case SHOW_FEEDERS:
                showTab(owner.getFeedersTab());
                return;
            case SHOW_BOARDS:
                showTab(owner.getBoardsTab());
                return;
            case SHOW_PANELS:
                showTab(owner.getPanelsTab());
                return;
            case SHOW_VISION:
                showTab(owner.getVisionSettingsTab());
                return;
            case SHOW_MACHINE_SETUP:
                showTab(owner.getMachineSetupTab());
                return;
            case SHOW_ISSUES:
                showTab(owner.getIssuesAndSolutionsTab());
                return;
            default:
                Action action = actionFor(command);
                perform(action, command.description + "当前不可用");
        }
    }

    private Action actionFor(Command command) {
        org.openpnp.gui.JobPanel job = owner.getJobTab();
        org.openpnp.gui.MachineControlsPanel machine = owner.getMachineControls();
        org.openpnp.gui.JogControlsPanel jog = machine.getJogControlsPanel();
        switch (command) {
            case START_JOB: return job.startPauseResumeJobAction;
            case STEP_JOB: return job.stepJobAction;
            case STOP_JOB: return job.stopJobAction;
            case PAUSE_NOZZLES_EMPTY: return job.pauseWhenNozzlesEmptyAction;
            case DEFER_ERRORS: return job.deferErrorsAction;
            case RESET_PLACED: return job.resetAllPlacedAction;
            case SAVE_JOB: return job.saveJobAction;
            case SAVE_JOB_AS: return job.saveJobAsAction;
            case OPEN_JOB: return job.openJobAction;
            case NEW_JOB: return job.newJobAction;
            case PACKAGE_ALIGNMENT: return job.packageAlignmentCheckAction;
            case FIDUCIAL_CHECK: return job.fiducialCheckAction;
            case TWO_POINT_LOCATE: return job.twoPointLocateBoardLocationAction;
            case VIEW_JOB: return job.viewerAction;
            case CAPTURE_CAMERA_BOARD: return job.captureCameraBoardLocationAction;
            case CAPTURE_TOOL_BOARD: return job.captureToolBoardLocationAction;
            case MOVE_CAMERA_BOARD: return job.moveCameraToBoardLocationAction;
            case MOVE_CAMERA_NEXT: return job.moveCameraToBoardLocationNextAction;
            case MOVE_TOOL_BOARD: return job.moveToolToBoardLocationAction;
            case HOME: return machine.homeAction;
            case MACHINE_POWER: return machine.startStopMachineAction;
            case TARGET_TOOL: return machine.targetToolAction;
            case TARGET_CAMERA: return machine.targetCameraAction;
            case PARK_XY: return jog.xyParkAction;
            case PARK_Z: return jog.zParkAction;
            case PARK_C: return jog.cParkAction;
            case SAFE_Z: return jog.safezAction;
            case DISCARD: return jog.discardAction;
            case RECYCLE: return jog.recycleAction;
            case JOG_X_PLUS: return jog.xPlusAction;
            case JOG_X_MINUS: return jog.xMinusAction;
            case JOG_Y_PLUS: return jog.yPlusAction;
            case JOG_Y_MINUS: return jog.yMinusAction;
            case JOG_Z_PLUS: return jog.zPlusAction;
            case JOG_Z_MINUS: return jog.zMinusAction;
            case JOG_C_PLUS: return jog.cPlusAction;
            case JOG_C_MINUS: return jog.cMinusAction;
            case JOG_INCREMENT_UP: return jog.raiseIncrementAction;
            case JOG_INCREMENT_DOWN: return jog.lowerIncrementAction;
            case UNDO: return owner.undoAction;
            case REDO: return owner.redoAction;
            default: return null;
        }
    }

    private void showTab(Component component) {
        owner.getTabs().setSelectedComponent(component);
        owner.setVisible(true);
        owner.toFront();
    }
    private void perform(Action action, String unavailableMessage) {
        if (action != null && action.isEnabled()) {
            action.actionPerformed(new ActionEvent(window, ActionEvent.ACTION_PERFORMED, "floating"));
        }
        else {
            showWarning("操作不可用", unavailableMessage);
        }
    }

    private void updateActiveCamera() {
        if (!window.isVisible() || !isEnabled()) {
            return;
        }
        Camera activeSelection = owner.getCameraViews().getSelectedCamera();
        List<Camera> cameras = new ArrayList<>();
        if (Configuration.get().getMachine() != null) {
            cameras.addAll(Configuration.get().getMachine().getAllCameras());
        }
        CameraSourceMode sourceMode = readCameraSourceMode();
        Camera selected = selectCameraForMode(sourceMode, activeSelection, cameras);
        if (selected == null) {
            if (activeCamera != null) {
                activeCamera.stopContinuousCapture(cameraView);
                activeCamera = null;
                activePresentationSource = null;
            }
            cameraView.setVisible(false);
            cameraNameLabel.setText(sourceMode.unavailableLabel);
            return;
        }
        cameraView.setVisible(true);
        CameraView source = selected == null ? null
                : owner.getCameraViews().getCameraView(selected);
        if (selected != null && (selected != activeCamera
                || source != activePresentationSource)) {
            if (activeCamera != null) {
                activeCamera.stopContinuousCapture(cameraView);
            }
            activeCamera = selected;
            activePresentationSource = source;
            cameraView.setCamera(selected);
            cameraView.setShowName(false);
            if (activePresentationSource != null) {
                // The main CameraView remains the single capture and presentation authority. This
                // prevents duplicate USB capture and mirrors pipeline frames plus dynamic reticles.
                selected.stopContinuousCapture(cameraView);
                cameraView.mirrorPresentationFrom(activePresentationSource);
            }
        }
        cameraNameLabel.setText(sourceMode == CameraSourceMode.FOLLOW_ACTIVE
                ? selected.getName() : sourceMode + " · " + selected.getName());
    }

    static Camera selectCameraForMode(CameraSourceMode mode, Camera activeSelection,
            List<Camera> cameras) {
        if (mode == CameraSourceMode.FOLLOW_ACTIVE) {
            return activeSelection != null ? activeSelection
                    : cameras.isEmpty() ? null : cameras.get(0);
        }
        Camera.Looking expected = mode == CameraSourceMode.TOP_CAMERA
                ? Camera.Looking.Down : Camera.Looking.Up;
        for (Camera camera : cameras) {
            if (camera.getLooking() == expected) {
                return camera;
            }
        }
        return null;
    }

    private CameraSourceMode readCameraSourceMode() {
        try {
            return CameraSourceMode.valueOf(prefs.get(PREF_CAMERA_SOURCE,
                    CameraSourceMode.FOLLOW_ACTIVE.name()));
        }
        catch (IllegalArgumentException e) {
            return CameraSourceMode.FOLLOW_ACTIVE;
        }
    }

    private void mirrorCameraPresentation() {
        if (window.isVisible() && activePresentationSource != null) {
            cameraView.mirrorPresentationFrom(activePresentationSource);
        }
    }

    private void ensureInteractiveWindow() {
        if (!window.isVisible()) {
            return;
        }
        if (!window.isEnabled()) {
            window.setEnabled(true);
        }
        if (!window.getFocusableWindowState()) {
            window.setFocusableWindowState(true);
        }
        if (!window.isAlwaysOnTop()) {
            window.setAlwaysOnTop(true);
        }
    }

    private void inspectOpenedDialog(AWTEvent event) {
        if (!(event instanceof WindowEvent) || !(event.getSource() instanceof JDialog)) {
            return;
        }
        JDialog dialog = (JDialog) event.getSource();

        if (event.getID() == WindowEvent.WINDOW_CLOSED) {
            if (dialog == activeDialog) {
                clearDialogMirror(dialog, true);
            }
            return;
        }
        if (event.getID() == WindowEvent.WINDOW_OPENED) {
            JOptionPane optionPane = findOptionPane(dialog);
            if (optionPane != null) {
                mirrorDialog(dialog, optionPane);
            }
        }
    }

    private JOptionPane findOptionPane(Component component) {
        if (component instanceof JOptionPane) {
            return (JOptionPane) component;
        }
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) {
                JOptionPane found = findOptionPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String plainMessage(Object message) {
        if (message == null) {
            return "";
        }
        if (message instanceof Object[]) {
            StringBuilder result = new StringBuilder();
            for (Object item : (Object[]) message) {
                appendPlainMessage(result, plainMessage(item));
            }
            return result.toString();
        }
        if (message instanceof JLabel) {
            return ((JLabel) message).getText().replaceAll("<[^>]+>", " ");
        }
        if (message instanceof JTextComponent) {
            return ((JTextComponent) message).getText();
        }
        if (message instanceof java.awt.Container) {
            StringBuilder result = new StringBuilder();
            for (Component child : ((java.awt.Container) message).getComponents()) {
                appendPlainMessage(result, plainMessage(child));
            }
            if (result.length() > 0) {
                return result.toString();
            }
        }
        return message instanceof Component ? ""
                : String.valueOf(message).replaceAll("<[^>]+>", " ");
    }

    private void appendPlainMessage(StringBuilder target, String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (!normalized.isEmpty()) {
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(normalized);
        }
    }

    private void mirrorDialog(JDialog dialog, JOptionPane optionPane) {
        if (dialog == null || optionPane == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!isEnabled()) {
                return;
            }
            if (dialog == activeDialog && optionPane == activeOptionPane) {
                revealWarningWindow();
                return;
            }
            activeDialog = dialog;
            activeOptionPane = optionPane;
            revealWarningWindow();
            showWarningContent(dialog.getTitle(), plainMessage(optionPane.getMessage()),
                    optionPane.getMessageType());
            rebuildDialogActions(dialog, optionPane);
            optionPane.addPropertyChangeListener(event -> {
                if (!JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())
                        || event.getNewValue() == null
                        || event.getNewValue() == JOptionPane.UNINITIALIZED_VALUE) {
                    return;
                }
                if (activeOptionPane == optionPane) {
                    clearDialogMirror(dialog, true);
                }
            });
        });
    }

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            if (!isEnabled()) {
                return;
            }
            revealWarningWindow();
            // MessageBoxes announces an error immediately before publishing its dialog. Do not let
            // this first notification replace the actionable mirrored dialog that follows it.
            if (activeOptionPane == null) {
                showWarning(title, message);
            }
        });
    }

    private void revealWarningWindow() {
        show();
        if (minimized) {
            toggleMinimized();
        }
        if (autoCompact) {
            setAutoCompact(false);
        }
        if (docked) {
            expandFromEdge();
        }
        ensureInteractiveWindow();
        window.toFront();
    }

    private void showWarning(String title, String message) {
        activeDialog = null;
        activeOptionPane = null;
        showWarningContent(title, message, JOptionPane.ERROR_MESSAGE);
        warningActions.removeAll();
        JButton dismiss = new JButton("知道了");
        dismiss.addActionListener(event -> hideWarning());
        warningActions.add(dismiss);
        warningActions.revalidate();
        warningActions.repaint();
    }

    private void showWarningContent(String title, String message, int messageType) {
        String safeTitle = escape(title == null || title.trim().isEmpty() ? "提示" : title);
        String safeMessage = escape(message == null || message.trim().isEmpty()
                ? "请在悬浮窗或主窗口中选择处理方式。" : message);
        if (safeMessage.length() > 420) {
            safeMessage = safeMessage.substring(0, 420) + "…";
        }
        String symbol = messageType == JOptionPane.ERROR_MESSAGE ? "⚠ "
                : messageType == JOptionPane.WARNING_MESSAGE ? "◇ " : "● ";
        warningLabel.setText("<html><div style='text-align:center;width:280px'>" + symbol
                + safeTitle + "<br><span style='font-weight:normal'>" + safeMessage
                + "</span></div></html>");
        setWarningStyle(messageType);
        warningPanel.setVisible(true);
        warningPanel.revalidate();
        warningPanel.repaint();
    }

    private void rebuildDialogActions(JDialog dialog, JOptionPane optionPane) {
        warningActions.removeAll();
        List<Object> values = optionValues(optionPane);
        for (Object value : values) {
            JButton button = new JButton(optionLabel(value));
            button.addActionListener(event -> resolveDialog(dialog, optionPane, value));
            warningActions.add(button);
        }
        warningActions.revalidate();
        warningActions.repaint();
    }

    private List<Object> optionValues(JOptionPane optionPane) {
        List<Object> values = new ArrayList<>();
        Object[] custom = optionPane.getOptions();
        if (custom != null && custom.length > 0) {
            for (Object value : custom) {
                values.add(value);
            }
            return values;
        }
        return standardOptionValues(optionPane.getOptionType());
    }

    static List<Object> standardOptionValues(int optionType) {
        List<Object> values = new ArrayList<>();
        switch (optionType) {
            case JOptionPane.YES_NO_OPTION:
                values.add(JOptionPane.YES_OPTION);
                values.add(JOptionPane.NO_OPTION);
                break;
            case JOptionPane.YES_NO_CANCEL_OPTION:
                values.add(JOptionPane.YES_OPTION);
                values.add(JOptionPane.NO_OPTION);
                values.add(JOptionPane.CANCEL_OPTION);
                break;
            case JOptionPane.OK_CANCEL_OPTION:
                values.add(JOptionPane.OK_OPTION);
                values.add(JOptionPane.CANCEL_OPTION);
                break;
            default:
                values.add(JOptionPane.OK_OPTION);
                break;
        }
        return values;
    }

    private String optionLabel(Object value) {
        if (!(value instanceof Integer)) {
            String label = plainMessage(value);
            return label.isEmpty() ? String.valueOf(value) : label;
        }
        int option = (Integer) value;
        if (option == JOptionPane.YES_OPTION) {
            return uiLabel("OptionPane.yesButtonText", "是");
        }
        if (option == JOptionPane.NO_OPTION) {
            return uiLabel("OptionPane.noButtonText", "否");
        }
        if (option == JOptionPane.CANCEL_OPTION) {
            return uiLabel("OptionPane.cancelButtonText", "取消");
        }
        return uiLabel("OptionPane.okButtonText", "确定");
    }

    private String uiLabel(String key, String fallback) {
        String value = UIManager.getString(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void resolveDialog(JDialog dialog, JOptionPane optionPane, Object value) {
        if (dialog != activeDialog || optionPane != activeOptionPane) {
            return;
        }
        optionPane.setValue(value);
        if (dialog.isVisible()) {
            dialog.setVisible(false);
        }
        clearDialogMirror(dialog, true);
    }

    private void clearDialogMirror(JDialog dialog, boolean hide) {
        if (dialog != activeDialog) {
            return;
        }
        activeDialog = null;
        activeOptionPane = null;
        if (hide) {
            hideWarning();
        }
    }

    private void hideWarning() {
        warningPanel.setVisible(false);
        warningActions.removeAll();
        updateCompactMode();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "").replace("\n", "<br>");
    }
    @SuppressWarnings("unchecked")
    public void showSettingsDialog() {
        JCheckBox enabled = new JCheckBox("启动时打开桌面悬浮监控", isEnabled());
        JComboBox<CameraSourceMode> cameraSource = new JComboBox<>(CameraSourceMode.values());
        cameraSource.setSelectedItem(readCameraSourceMode());
        cameraSource.setToolTipText("跟随活动画面会随主窗口当前相机切换；固定模式始终显示指定相机");
        int originalCount = readCommandCount();
        JSpinner commandCount = new JSpinner(new SpinnerNumberModel(originalCount, 4, 8, 1));
        JComboBox<Command>[] selectors = new JComboBox[8];
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 6, 5, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        panel.add(enabled, constraints);
        constraints.gridy++;
        constraints.gridwidth = 1;
        panel.add(new JLabel("监控画面"), constraints);
        constraints.gridx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        panel.add(cameraSource, constraints);
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        panel.add(new JLabel("错误提示固定在视频之上的独立层，不会被实时帧刷新覆盖。"),
                constraints);
        constraints.gridy++;
        panel.add(new JLabel("可配置 4–8 个按钮，功能按类别排列；悬停按钮可查看完整说明。"),
                constraints);
        constraints.gridwidth = 1;
        constraints.gridy++;
        constraints.gridx = 0;
        panel.add(new JLabel("底部按钮数量"), constraints);
        constraints.gridx = 1;
        panel.add(commandCount, constraints);

        Command[] defaults = defaultCommands(8);
        for (int index = 0; index < selectors.length; index++) {
            constraints.gridy++;
            constraints.gridx = 0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.weightx = 0;
            panel.add(new JLabel("按钮 " + (index + 1)), constraints);
            selectors[index] = new JComboBox<>(Command.values());
            selectors[index].setMaximumRowCount(18);
            selectors[index].setSelectedItem(readCommand(index, defaults[index]));
            selectors[index].setToolTipText("任务、对位、机器、点动、页面、编辑和窗口功能");
            constraints.gridx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = 1;
            panel.add(selectors[index], constraints);
        }
        Runnable updateSelectorState = () -> {
            int count = ((Number) commandCount.getValue()).intValue();
            for (int index = 0; index < selectors.length; index++) {
                selectors[index].setEnabled(index < count);
            }
        };
        commandCount.addChangeListener(event -> updateSelectorState.run());
        updateSelectorState.run();

        int result = JOptionPane.showConfirmDialog(owner, panel, "悬浮监控自定义设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            int count = ((Number) commandCount.getValue()).intValue();
            prefs.putInt(PREF_COMMAND_COUNT, count);
            prefs.put(PREF_CAMERA_SOURCE,
                    ((CameraSourceMode) cameraSource.getSelectedItem()).name());
            for (int index = 0; index < selectors.length; index++) {
                prefs.put(PREF_COMMAND + index,
                        ((Command) selectors[index].getSelectedItem()).name());
            }
            rebuildCommands();
            setEnabled(enabled.isSelected());
            updateActiveCamera();
        }
    }
    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.putBoolean(PREF_ENABLED, enabled);
        if (enabled) {
            show();
        }
        else {
            saveExpandedBounds();
            window.setVisible(false);
            stopRefreshTimers();
            if (activeCamera != null) {
                activeCamera.stopContinuousCapture(cameraView);
            }
            activePresentationSource = null;
        }
    }

    public void show() {
        startRefreshTimers();
        window.setVisible(true);
        updateActiveCamera();
        ensureInteractiveWindow();
    }

    private void startRefreshTimers() {
        if (!cameraTimer.isRunning()) {
            cameraTimer.start();
        }
        if (!presentationTimer.isRunning()) {
            presentationTimer.start();
        }
    }

    private void stopRefreshTimers() {
        cameraTimer.stop();
        presentationTimer.stop();
        redockTimer.stop();
    }

    private void updateCompactMode() {
        boolean shouldCompact = owner.isActive() && !warningPanel.isVisible()
                && !minimized && !docked;
        if (shouldCompact != autoCompact) {
            setAutoCompact(shouldCompact);
        }
    }

    private void setAutoCompact(boolean compact) {
        if (compact == autoCompact) {
            return;
        }
        if (compact) {
            expandedBounds = window.getBounds();
            cameraContainer.setVisible(false);
            footer.setVisible(true);
            window.setSize(window.getWidth(), HEADER_HEIGHT + 58);
        }
        else {
            cameraContainer.setVisible(true);
            footer.setVisible(true);
            Rectangle bounds = expandedBounds == null ? defaultBounds() : expandedBounds;
            window.setBounds(bounds);
        }
        autoCompact = compact;
    }

    private void toggleMinimized() {
        if (autoCompact) {
            setAutoCompact(false);
        }
        if (!minimized) {
            if (!docked) {
                expandedBounds = window.getBounds();
            }
            cameraContainer.setVisible(false);
            commandPanel.getParent().setVisible(false);
            window.setSize(window.getWidth(), HEADER_HEIGHT);
            minimized = true;
        }
        else {
            cameraContainer.setVisible(true);
            commandPanel.getParent().setVisible(true);
            Rectangle bounds = expandedBounds == null ? defaultBounds() : expandedBounds;
            window.setBounds(bounds);
            minimized = false;
        }
    }

    private void dockToNearestEdge() {
        if (autoCompact) {
            setAutoCompact(false);
        }
        if (minimized) {
            toggleMinimized();
        }
        expandedBounds = window.getBounds();
        Rectangle screen = usableScreenBounds(window.getGraphicsConfiguration());
        dockedRight = window.getX() + window.getWidth() / 2 > screen.getCenterX();
        docked = true;
        collapseToEdge();
    }

    private void expandFromEdge() {
        Rectangle screen = usableScreenBounds(window.getGraphicsConfiguration());
        Rectangle bounds = expandedBounds == null ? defaultBounds() : expandedBounds;
        int x = dockedRight ? screen.x + screen.width - bounds.width : screen.x;
        int y = Math.max(screen.y, Math.min(bounds.y, screen.y + screen.height - bounds.height));
        window.setBounds(x, y, bounds.width, bounds.height);
    }

    private void collapseToEdgeIfPointerOutside() {
        if (!docked || !window.isVisible()) {
            return;
        }
        Point pointer = MouseInfo.getPointerInfo() == null ? null
                : MouseInfo.getPointerInfo().getLocation();
        if (pointer == null || !window.getBounds().contains(pointer)) {
            collapseToEdge();
        }
    }

    private void collapseToEdge() {
        Rectangle screen = usableScreenBounds(window.getGraphicsConfiguration());
        Rectangle bounds = expandedBounds == null ? defaultBounds() : expandedBounds;
        int x = dockedRight ? screen.x + screen.width - EDGE_VISIBLE
                : screen.x - bounds.width + EDGE_VISIBLE;
        int y = Math.max(screen.y, Math.min(bounds.y, screen.y + screen.height - bounds.height));
        window.setBounds(x, y, bounds.width, bounds.height);
    }

    private void installDragHandler(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragScreenOrigin = event.getLocationOnScreen();
                dragWindowOrigin = window.getLocation();
                if (docked) {
                    docked = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Point current = event.getLocationOnScreen();
                window.setLocation(dragWindowOrigin.x + current.x - dragScreenOrigin.x,
                        dragWindowOrigin.y + current.y - dragScreenOrigin.y);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                saveExpandedBounds();
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    private void installResizeHandler(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            private Point start;

            @Override
            public void mousePressed(MouseEvent event) {
                start = event.getLocationOnScreen();
                resizeOrigin = window.getSize();
                docked = false;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                Point current = event.getLocationOnScreen();
                int width = Math.max(320, resizeOrigin.width + current.x - start.x);
                int height = Math.max(220, resizeOrigin.height + current.y - start.y);
                window.setSize(width, height);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                saveExpandedBounds();
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    private void installHoverHandler(Component component, MouseAdapter adapter) {
        component.addMouseListener(adapter);
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) {
                installHoverHandler(child, adapter);
            }
        }
    }

    private void restoreBounds() {
        Rectangle defaults = defaultBounds();
        int width = Math.max(320, prefs.getInt(PREF_WIDTH, defaults.width));
        int height = Math.max(220, prefs.getInt(PREF_HEIGHT, defaults.height));
        Rectangle screen = usableScreenBounds(null);
        int x = prefs.getInt(PREF_X, defaults.x);
        int y = prefs.getInt(PREF_Y, defaults.y);
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - width));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - height));
        expandedBounds = new Rectangle(x, y, width, height);
        window.setBounds(expandedBounds);
    }

    private Rectangle defaultBounds() {
        Rectangle screen = usableScreenBounds(null);
        int width = Math.max(320, screen.width / 5);
        int height = Math.max(240, width * 3 / 4);
        return new Rectangle(screen.x + screen.width - width - 24, screen.y + 72, width, height);
    }

    private Rectangle usableScreenBounds(GraphicsConfiguration configuration) {
        GraphicsConfiguration gc = configuration;
        if (gc == null) {
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            gc = device.getDefaultConfiguration();
        }
        Rectangle bounds = new Rectangle(gc.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private void saveExpandedBounds() {
        if (!window.isVisible() || docked || minimized || autoCompact) {
            return;
        }
        expandedBounds = window.getBounds();
        prefs.putInt(PREF_X, expandedBounds.x);
        prefs.putInt(PREF_Y, expandedBounds.y);
        prefs.putInt(PREF_WIDTH, expandedBounds.width);
        prefs.putInt(PREF_HEIGHT, expandedBounds.height);
    }

    @SuppressWarnings("serial")
    static class CameraLayerPane extends JLayeredPane {
        @Override
        public void doLayout() {
            for (Component component : getComponents()) {
                component.setBounds(0, 0, getWidth(), getHeight());
            }
        }

        @Override
        public boolean isOptimizedDrawingEnabled() {
            // CameraView repaints for every frame. Disabling optimized child painting is essential:
            // it makes Swing repaint the higher warning layer after any overlapping video repaint.
            return false;
        }
    }

    @SuppressWarnings("serial")
    private static class RoundedCommandButton extends JButton {
        private final Color base;
        private boolean hover;

        RoundedCommandButton(String text, Color base) {
            super(text);
            this.base = base;
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 12f));
            setFocusable(false);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
            setPreferredSize(new Dimension(88, 38));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = hover ? base.brighter() : base;
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.setColor(new Color(255, 255, 255, hover ? 90 : 45));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    public void dispose() {
        saveExpandedBounds();
        stopRefreshTimers();
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogErrorListener);
        if (Thread.getDefaultUncaughtExceptionHandler() == exceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
        }
        MessageBoxes.removeErrorListener(errorListener);
        if (activeCamera != null) {
            activeCamera.stopContinuousCapture(cameraView);
        }
        cameraView.clearTransientImageState();
        window.dispose();
    }
}
