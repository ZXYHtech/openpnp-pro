/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import org.openpnp.events.JobPlacementProcessingEvent;
import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.components.reticle.Reticle;
import org.openpnp.gui.components.reticle.TrialPlacementReticle;
import org.openpnp.gui.support.BottomVisionTrialCorrection;
import org.openpnp.machine.reference.vision.AbstractPartAlignment;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.XmlSerialize;

import com.google.common.eventbus.Subscribe;

/** Two-stage operator workflow for measuring and correcting a real trial placement. */
@SuppressWarnings("serial")
public class TrialPlacementCorrectionDialog extends JDialog {
    private static final String PREPARE_CARD = "prepare";
    private static final String CORRECT_CARD = "correct";
    private static final String RETICLE_KEY = TrialPlacementCorrectionDialog.class.getName();
    private static final double WARN_CORRECTION_MM = 2.0;
    private static final double MAX_CORRECTION_MM = 10.0;

    private final JobPanel jobPanel;
    private final BoardLocation boardLocation;
    private final Placement placement;
    private final Runnable nextRequested;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JLabel statusLabel = new JLabel();
    private final JTextArea correctionSummary = new JTextArea();

    private boolean registered;
    private boolean trialRunning;
    private boolean measurementReady;
    private boolean correctionWritten;
    private Camera topCamera;
    private CameraView topCameraView;
    private Reticle previousDefaultReticle;
    private Object previousLightValue;
    private Location nominalLocation;
    private JogControlsPanel mirroredJogControls;
    private Timer topCameraGuard;

    public TrialPlacementCorrectionDialog(MainFrame owner,
            JobPlacementsPanel placementsPanel, BoardLocation boardLocation,
            Placement placement, Runnable nextRequested) {
        super(owner, "试贴纠偏 · " + placement.getDisplayId(), false);
        this.jobPanel = placementsPanel.getJobPanel();
        this.boardLocation = boardLocation;
        this.placement = placement;
        this.nextRequested = nextRequested;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose();
            }
        });
        buildUi();
        Configuration.get().getBus().register(this);
        registered = true;
        refreshReadiness();
        pack();
        setMinimumSize(new Dimension(720, 500));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        cardPanel.add(createPreparePage(), PREPARE_CARD);
        cardPanel.add(createCorrectionPage(), CORRECT_CARD);
        setContentPane(cardPanel);
        cards.show(cardPanel, PREPARE_CARD);
    }

    private JPanel createPreparePage() {
        JPanel page = new JPanel(new BorderLayout(12, 12));
        page.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        JLabel title = new JLabel("准备试贴第一个器件");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        page.add(title, BorderLayout.NORTH);

        JTextArea explanation = new JTextArea(
                "即将只执行当前选中的一个器件，不会继续贴装任务中的其他行。\n\n"
                + "器件：" + placement.getDisplayId() + "\n"
                + "Part：" + (placement.getPart() == null ? "未配置"
                        : placement.getPart().getId()) + "\n"
                + "单板：" + boardLocation.getUniqueId() + "\n\n"
                + "开始后将沿用原生流程：飞达取料 → 底部视觉 → 放置 → 安全 Z。"
                + "请确认板、飞达、吸嘴、灯光和工作区域已经准备好。");
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setFont(explanation.getFont().deriveFont(14f));
        page.add(explanation, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 8));
        statusLabel.setOpaque(true);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        south.add(statusLabel, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton inspect = new JButton("先巡检基准点");
        inspect.addActionListener(event -> {
            jobPanel.fiducialCheckAction.actionPerformed(event);
            statusLabel.setText("已提交基准点巡检；完成后请再次确认状态，再点击开始。");
        });
        JButton start = new JButton("开始");
        start.addActionListener(event -> startTrialPlacement());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> requestClose());
        buttons.add(inspect);
        buttons.add(start);
        buttons.add(close);
        south.add(buttons, BorderLayout.SOUTH);
        page.add(south, BorderLayout.SOUTH);
        return page;
    }

    private JPanel createCorrectionPage() {
        JPanel page = new JPanel(new BorderLayout(10, 10));
        page.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JLabel title = new JLabel("实际贴片位置确认");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        page.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        JTextArea guide = new JTextArea(
                "顶部相机已经移到理论贴装中心。本页会临时关闭相机默认视觉投射和整板投射，"
                + "仅保留淡化的封装轮廓与固定中心十字。请使用下方原生控制面板移动顶部相机，"
                + "使固定十字精确对准实贴器件中心。"
                + "只调整 X/Y；请勿用 Z/C 代替平面误差。确认时系统读取相机当前位置，"
                + "按贴装角度转换到器件局部坐标并反补 Bottom Vision XY 偏移。");
        guide.setEditable(false);
        guide.setLineWrap(true);
        guide.setWrapStyleWord(true);
        guide.setRows(4);
        guide.setBackground(new Color(245, 249, 255));
        guide.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(100, 145, 205)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        center.add(guide, BorderLayout.NORTH);

        mirroredJogControls = new JogControlsPanel(Configuration.get(),
                MainFrame.get().getMachineControls());
        center.add(new JScrollPane(mirroredJogControls), BorderLayout.CENTER);

        correctionSummary.setEditable(false);
        correctionSummary.setRows(4);
        correctionSummary.setLineWrap(true);
        correctionSummary.setWrapStyleWord(true);
        correctionSummary.setText("等待试贴完成……");
        center.add(correctionSummary, BorderLayout.SOUTH);
        page.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton retry = new JButton("确认偏移并再贴一件纠偏");
        retry.addActionListener(event -> confirmCorrection(true));
        JButton finish = new JButton("确认并退出");
        finish.addActionListener(event -> confirmCorrection(false));
        JButton cancel = new JButton("取消（不写入偏移）");
        cancel.addActionListener(event -> requestClose());
        buttons.add(cancel);
        buttons.add(retry);
        buttons.add(finish);
        page.add(buttons, BorderLayout.SOUTH);
        return page;
    }

    private void refreshReadiness() {
        Machine machine = Configuration.get().getMachine();
        String readiness = "机器：" + (machine.isEnabled() ? "已启动" : "未启动")
                + "　Home：" + (machine.isHomed() ? "已完成" : "未完成")
                + "　当前器件：" + placement.getDisplayId();
        statusLabel.setText(readiness);
        boolean ready = machine.isEnabled() && machine.isHomed();
        statusLabel.setBackground(ready ? new Color(221, 248, 228)
                : new Color(255, 235, 218));
    }

    private void startTrialPlacement() {
        try {
            trialRunning = true;
            statusLabel.setText("正在试贴 " + placement.getDisplayId()
                    + "，请勿关闭窗口或手动移动机器……");
            jobPanel.startTrialPlacement(boardLocation, placement);
        }
        catch (Exception exception) {
            trialRunning = false;
            refreshReadiness();
            UiUtils.showError(this, "无法开始试贴纠偏", exception);
        }
    }

    @Subscribe
    public void placementProcessing(JobPlacementProcessingEvent event) {
        if (event.stage != JobPlacementProcessingEvent.Stage.Complete
                || event.boardLocation != boardLocation || event.placement != placement) {
            return;
        }
        trialRunning = false;
        SwingUtilities.invokeLater(this::prepareMeasurement);
    }

    private void prepareMeasurement() {
        statusLabel.setText("试贴完成，正在切换到顶部相机并移动到理论中心……");
        UiUtils.submitUiMachineTask(() -> {
            Head head = Configuration.get().getMachine().getDefaultHead();
            if (head == null || head.getDefaultCamera() == null
                    || head.getDefaultCamera().getLooking() != Camera.Looking.Down) {
                throw new Exception("没有可用的向下顶部相机。");
            }
            topCamera = head.getDefaultCamera();
            nominalLocation = Utils2D.calculateBoardPlacementLocation(boardLocation,
                    placement.getLocation());
            MovableUtils.moveToLocationAtSafeZ(topCamera, nominalLocation);
            Actuator light = topCamera.getLightActuator();
            previousLightValue = light == null ? null : light.getLastActuationValue();
            topCamera.actuateLightBeforeCapture();
            MovableUtils.fireTargetedUserAction(topCamera);
            return topCamera;
        }, camera -> {
            jobPanel.disableBoardPlacementProjection();
            topCameraView = activateTopCameraView();
            if (topCameraView == null) {
                UiUtils.showError(this, "试贴纠偏",
                        new Exception("无法显示顶部相机画面。"));
                return;
            }
            previousDefaultReticle = topCameraView.suspendDefaultReticle();
            topCameraView.setReticle(RETICLE_KEY,
                    new TrialPlacementReticle(camera, placement, nominalLocation,
                            boardLocation.getGlobalSide()));
            topCameraView.repaint();
            measurementReady = true;
            startTopCameraGuard();
            correctionSummary.setText("顶部相机已锁定为活动画面；默认视觉投射已临时隐藏。"
                    + "请移动顶部相机对准实贴器件中心，然后选择一种确认方式。");
            cards.show(cardPanel, CORRECT_CARD);
            toFront();
        }, failure -> UiUtils.showError(this, "准备顶部视觉纠偏失败", failure));
    }

    private void confirmCorrection(boolean placeAnother) {
        if (correctionWritten) {
            JOptionPane.showMessageDialog(this,
                    "本次纠偏已经写入配置，不会再次叠加。请关闭窗口后重新开始一次试贴纠偏。",
                    "试贴纠偏已写入", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!measurementReady || topCamera == null || nominalLocation == null) {
            UiUtils.showError(this, "试贴纠偏", new Exception("顶部相机纠偏画面尚未准备完成。"));
            return;
        }
        try {
            if (activateTopCameraView() == null
                    || MainFrame.get().getCameraViews().getSelectedCamera() != topCamera) {
                throw new Exception("顶部相机没有成为当前活动画面，系统已停止读取偏移。请重试。");
            }
            Part part = placement.getPart();
            BottomVisionSettings inherited = AbstractPartAlignment
                    .getInheritedVisionSettings(part, true);
            if (inherited == null) {
                throw new Exception("当前 Part 没有可用的 Bottom Vision 配置。");
            }
            Location measured = topCamera.getLocation();
            BottomVisionTrialCorrection.Result correction =
                    BottomVisionTrialCorrection.calculate(inherited.getVisionOffset(),
                            nominalLocation, measured);
            correctionSummary.setText(formatCorrection(correction, inherited));
            if (correction.getMagnitudeMm() > MAX_CORRECTION_MM) {
                throw new Exception(String.format(Locale.ROOT,
                        "测得偏差 %.3f mm，超过 %.1f mm 安全上限。请检查板定位、相机选择和单位，系统未写入任何偏移。",
                        correction.getMagnitudeMm(), MAX_CORRECTION_MM));
            }
            if (correction.getMagnitudeMm() > WARN_CORRECTION_MM) {
                int answer = JOptionPane.showConfirmDialog(this,
                        formatCorrection(correction, inherited)
                                + "\n\n偏差较大，仍要写入吗？",
                        "确认较大纠偏量", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            BottomVisionSettings previousDirect = part.getBottomVisionSettings();
            BottomVisionSettings target = specializeForPartIfNeeded(part, inherited);
            boolean specialized = target != inherited;
            Location previousOffset = target.getVisionOffset();
            boolean previousAsymmetric = target.isAsymmetric();
            target.setAsymmetric(true);
            target.setVisionOffset(correction.newOffset);
            Configuration.get().fireVisionSettingsChanged();
            if (!MainFrame.get().saveConfig()) {
                if (specialized) {
                    part.setBottomVisionSettings(previousDirect);
                    Configuration.get().removeVisionSettings(target);
                }
                else {
                    target.setVisionOffset(previousOffset);
                    target.setAsymmetric(previousAsymmetric);
                    Configuration.get().fireVisionSettingsChanged();
                }
                throw new Exception("偏移已写入内存，但配置保存失败；请查看保存错误后重试。 ");
            }
            // saveConfig() has completed. Never apply this measured error twice, even if an
            // environmental problem prevents the following independent disk read-back check.
            correctionWritten = true;
            verifySavedCorrection(part, target, correction.newOffset);
            String savedMessage = formatCorrection(correction, target)
                    + "\n\n已保存并从配置文件回读验证通过。"
                    + "\n设置 ID：" + target.getId()
                    + "\n配置目录：" + Configuration.get().getConfigurationDirectory()
                    + "\n说明：横放、竖放及任意角度都会把该器件局部偏移按实际贴装角度旋转后应用。";
            correctionSummary.setText(savedMessage);
            JOptionPane.showMessageDialog(this, savedMessage,
                    "试贴纠偏已保存", JOptionPane.INFORMATION_MESSAGE);
            disposeWorkflow();
            if (placeAnother && nextRequested != null) {
                SwingUtilities.invokeLater(nextRequested);
            }
        }
        catch (Exception exception) {
            UiUtils.showError(this, "无法确认试贴纠偏", exception);
        }
    }

    private BottomVisionSettings specializeForPartIfNeeded(Part part,
            BottomVisionSettings inherited) {
        boolean shared = inherited.getUsedBottomVisionIn().size() > 1;
        if (part.getBottomVisionSettings() == inherited && !shared
                && !inherited.isStockSetting()) {
            return inherited;
        }
        BottomVisionSettings specialized = new BottomVisionSettings();
        specialized.setValues(inherited);
        specialized.setName(part.getId() + " · 试贴纠偏");
        part.setBottomVisionSettings(specialized);
        Configuration.get().addVisionSettings(specialized);
        return specialized;
    }

    private String formatCorrection(BottomVisionTrialCorrection.Result value,
            BottomVisionSettings settings) {
        return String.format(Locale.ROOT,
                "实贴全局误差：X %+.3f mm，Y %+.3f mm（合计 %.3f mm）\n"
                + "贴装角度：%.2f°；换算到器件局部：X %+.3f，Y %+.3f mm\n"
                + "Bottom Vision [%s]：旧 X %+.3f / Y %+.3f → 新 X %+.3f / Y %+.3f mm",
                value.globalError.getX(), value.globalError.getY(), value.getMagnitudeMm(),
                value.placementRotation, value.localError.getX(), value.localError.getY(),
                settings.getName(), value.oldOffset.getX(), value.oldOffset.getY(),
                value.newOffset.getX(), value.newOffset.getY());
    }

    private CameraView activateTopCameraView() {
        if (topCamera == null) {
            return null;
        }
        MachineControlsPanel controls = MainFrame.get().getMachineControls();
        if (controls.getSelectedTool() != topCamera) {
            controls.setSelectedTool(topCamera);
        }
        return MainFrame.get().getCameraViews().setSelectedCamera(topCamera);
    }

    private void startTopCameraGuard() {
        if (topCameraGuard == null) {
            topCameraGuard = new Timer(350, event -> {
                if (measurementReady) {
                    activateTopCameraView();
                }
            });
        }
        topCameraGuard.start();
    }

    private void verifySavedCorrection(Part part, BottomVisionSettings target,
            Location expectedOffset) throws Exception {
        Configuration configuration = Configuration.get();
        if (part.getBottomVisionSettings() != target) {
            throw new Exception("配置已保存，但当前 Part 没有指向刚写入的 Bottom Vision 设置。");
        }
        if (configuration.getVisionSettings(target.getId()) != target) {
            throw new Exception("配置已保存，但内存设置注册表中找不到 " + target.getId());
        }
        File visionSettingsFile = new File(configuration.getConfigurationDirectory(),
                "vision-settings.xml");
        BottomVisionTrialCorrection.verifySavedOffset(visionSettingsFile,
                target.getId(), expectedOffset);
        File partsFile = new File(configuration.getConfigurationDirectory(), "parts.xml");
        String partsXml = new String(Files.readAllBytes(partsFile.toPath()),
                StandardCharsets.UTF_8);
        String reference = "bottom-vision-id=\""
                + XmlSerialize.escapeXml(target.getId()) + "\"";
        if (!partsXml.contains(reference)) {
            throw new Exception("配置已保存，但 parts.xml 中没有写入设置引用 " + target.getId());
        }
    }

    private void requestClose() {
        if (trialRunning && jobPanel.isJobInMotion()) {
            JOptionPane.showMessageDialog(this,
                    "试贴动作仍在运行。若要中止，请先使用任务栏的停止按钮；"
                    + "为避免失去状态提示，本窗口暂不关闭。",
                    "试贴正在运行", JOptionPane.WARNING_MESSAGE);
            return;
        }
        trialRunning = false;
        disposeWorkflow();
    }

    private void disposeWorkflow() {
        measurementReady = false;
        if (topCameraGuard != null) {
            topCameraGuard.stop();
        }
        if (registered) {
            Configuration.get().getBus().unregister(this);
            registered = false;
        }
        if (topCameraView != null) {
            topCameraView.removeReticle(RETICLE_KEY);
            topCameraView.restoreDefaultReticle(previousDefaultReticle);
            topCameraView.repaint();
            topCameraView = null;
            previousDefaultReticle = null;
        }
        if (mirroredJogControls != null) {
            mirroredJogControls.disposePanel();
            mirroredJogControls = null;
        }
        restoreLight();
        dispose();
    }

    private void restoreLight() {
        Camera camera = topCamera;
        Object lightValue = previousLightValue;
        if (camera == null || camera.getLightActuator() == null) {
            return;
        }
        UiUtils.submitUiMachineTask(() -> {
            Actuator actuator = camera.getLightActuator();
            actuator.actuate(lightValue == null ? actuator.getDefaultOffValue() : lightValue);
        });
    }
}
