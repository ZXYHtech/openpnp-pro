package org.openpnp.corexy.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.precision.CalibrationRequest;
import org.openpnp.corexy.precision.OpenPnpCalibrationMachine;
import org.openpnp.corexy.precision.PlateSelfCalibration;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.UiUtils;

/** Manual plate placement is isolated from automatic measurement; machine queue stays free between views. */
public final class PlateCalibrationDialog extends JDialog {
    private final MarkerGuidePanel guide = new MarkerGuidePanel();
    private final JLabel instruction = new JLabel();
    private final JButton capture = new JButton("已放好并对齐 0 号点，采集这个姿态");
    private final JCheckBox certified = new JCheckBox("板上 0 号点到第一行末点的标准距离与方向已确认");
    private final List<PlateSelfCalibration.Observation> observations = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Configuration configuration;
    private final CalibrationRequest request;
    private final BiConsumer<Map<String, double[]>, CalibrationRequest> onComplete;
    private CalibrationRequest lastPose;
    private int pose;
    private boolean busy, ownsWorkflow;

    public PlateCalibrationDialog(JDialog owner, Configuration configuration, CalibrationRequest request,
            BiConsumer<Map<String, double[]>, CalibrationRequest> onComplete) {
        super(owner, "标定板误差分离 · 四姿态引导", false);
        this.configuration = configuration;
        this.request = request;
        this.onComplete = onComplete;
        setSize(new Dimension(730, 540));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        content.add(guide, BorderLayout.CENTER);
        content.add(instruction, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout());
        buttons.add(certified);
        buttons.add(capture);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        updateGuide();
        capture.addActionListener(event -> collect());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (busy) {
                    cancelled.set(true);
                    instruction.setText("正在停止采集并恢复配置。");
                }
                else {
                    dispose();
                }
            }
        });
    }

    private void collect() {
        if (busy) {
            return;
        }
        if (!certified.isSelected()) {
            instruction.setText("请先确认标准距离；打印机缩放误差不能靠四姿态确定绝对比例。");
            return;
        }
        if (!ownsWorkflow) {
            if (!CoreXYIntegration.acquireWorkflow()) {
                instruction.setText("另一个 CoreXY 校准窗口正在工作，请等待它完成。");
                return;
            }
            ownsWorkflow = true;
        }
        busy = true;
        cancelled.set(false);
        capture.setEnabled(false);
        guide.setGuide(2, request.gridSize, pose);
        final int view = pose;
        UiUtils.submitUiMachineTask(() -> {
            Location origin = configuration.getMachine().getDefaultHead().getDefaultCamera()
                    .getLocation().convertToUnits(LengthUnit.Millimeters);
            CalibrationRequest settings = new CalibrationRequest();
            settings.originX = origin.getX();
            settings.originY = origin.getY();
            settings.planeZ = origin.getZ();
            settings.pitch = request.pitch;
            settings.markerDiameter = request.markerDiameter;
            settings.gridSize = request.gridSize;
            settings.plateAngle = request.plateAngle + view * 90;
            settings.conditioningTravel = request.conditioningTravel;
            settings.maximumOffset = request.maximumOffset;
            settings.target = request.target;
            lastPose = settings;
            return new OpenPnpCalibrationMachine(configuration).collectPlateView(settings, view, cancelled::get);
        }, data -> {
            busy = false;
            observations.addAll(data);
            pose++;
            if (pose == 4) {
                try {
                    PlateSelfCalibration.Result result = new PlateSelfCalibration()
                            .solve(observations, request.gridSize, true, request.maximumOffset);
                    if (result.residualRms > request.target / 3) {
                        throw new IllegalStateException("姿态分离残差过大，请改善视觉测量或标定板。");
                    }
                    // Preserve each raw view for later inspection and reproducibility.
                    org.openpnp.corexy.precision.PrecisionStore.atomicWrite(
                            CoreXYIntegration.store(configuration).getRoot().resolve("plate-self-calibration.json"),
                            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(observations));
                    lastPose.plateAngle += result.viewAngleCorrections[3];
                    org.openpnp.corexy.precision.PrecisionStore.atomicWrite(
                            CoreXYIntegration.store(configuration).getRoot().resolve("plate-self-calibration-result.json"),
                            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                    onComplete.accept(result.markerOffsets, lastPose);
                    dispose();
                }
                catch (Exception failure) {
                    pose = 0;
                    observations.clear();
                    UiUtils.showError(this, "误差分离未通过", failure);
                    updateGuide();
                }
            }
            else {
                updateGuide();
            }
            capture.setEnabled(true);
        }, failure -> {
            busy = false;
            if (cancelled.get()) {
                dispose();
                return;
            }
            capture.setEnabled(true);
            UiUtils.showError(this, "该姿态采集未完成", failure);
            updateGuide();
        });
    }

    private void updateGuide() {
        guide.setGuide(0, request.gridSize, pose);
        instruction.setText("<html>第 " + (pose + 1) + "/4 次：旋转到 " + pose * 90
                + "°，平移约半个点间距，固定后把相机移到 0 号点。<br>每次改变平移量；避免四次只绕同一中心旋转。</html>");
    }

    @Override
    public void dispose() {
        if (ownsWorkflow) {
            CoreXYIntegration.releaseWorkflow();
            ownsWorkflow = false;
        }
        super.dispose();
    }
}
