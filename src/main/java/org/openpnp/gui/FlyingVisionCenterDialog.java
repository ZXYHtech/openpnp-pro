/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;

import org.openpnp.machine.reference.vision.FlyingBottomVisionService;
import org.openpnp.machine.reference.vision.FlyingVisionFrameEvaluator;
import org.openpnp.machine.reference.vision.FlyingVisionSettings;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.util.VisionUtils;

/**
 * Human-guided, guarded flying-bottom-vision laboratory.
 */
@SuppressWarnings("serial")
public class FlyingVisionCenterDialog extends JDialog {
    private final MainFrame frame;
    private final String[] steps = {
            "① 原理与安全", "② 参数和白名单", "③ 静止连拍基线",
            "④ 低速经过相机", "⑤ 对比与正式接入"
    };
    private final JList<String> stepList = new JList<>(steps);
    private final JTextArea explanation = new JTextArea();
    private final JTextArea results = new JTextArea();
    private final FlightAnimation animation = new FlightAnimation();
    private final JCheckBox enabled = new JCheckBox("启用飞拍实验");
    private final JCheckBox production = new JCheckBox("允许白名单元件进入正式任务");
    private final JCheckBox fallback = new JCheckBox("失败自动回退普通停拍");
    private final JCheckBox saveImages = new JCheckBox("保存调试图片");
    private final JCheckBox timestamp = new JCheckBox("启用相机延迟选帧补偿");
    private final JCheckBox safety =
            new JCheckBox("我已确认：吸嘴有测试元件、路径无障碍、急停可用");
    private final JSpinner speed = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getSpeed(), 0.02, 0.35, 0.01));
    private final JSpinner approach = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getApproachDistanceMm(), 3.0, 40.0, 1.0));
    private final JSpinner exit = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getExitDistanceMm(), 3.0, 40.0, 1.0));
    private final JSpinner frames = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getFrameCount(), 3, 30, 1));
    private final JSpinner repeats = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
    private final JSpinner sharpness = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getMinimumSharpness(), 0.0, 100.0, 0.5));
    private final JSpinner maxOffset = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getMaximumOffsetMm(), 0.05, 5.0, 0.05));
    private final JSpinner maxAngle = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getMaximumAngleDeg(), 0.5, 45.0, 0.5));
    private final JSpinner latency = new JSpinner(new SpinnerNumberModel(
            FlyingVisionSettings.getCameraLatencyMs(), -500.0, 500.0, 1.0));
    private final JTextArea partIds = new JTextArea(
            FlyingVisionSettings.getEnabledPartIdsText(), 3, 30);
    private final JButton staticTest = new JButton("静止连拍基线");
    private final JButton flightTest = new JButton("低速飞拍测试");
    private PartAlignmentOffset lastStaticOffset;

    public FlyingVisionCenterDialog(MainFrame owner) {
        super(owner, "实验模式 · 飞拍视觉中心", false);
        this.frame = owner;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(980, 700));
        setSize(1120, 780);
        setLocationRelativeTo(owner);
        buildUi();
        loadValues();
        showStep(0);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        stepList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stepList.setFixedCellHeight(46);
        stepList.setPreferredSize(new Dimension(190, 250));
        stepList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showStep(Math.max(0, stepList.getSelectedIndex()));
            }
        });
        root.add(new JScrollPane(stepList), BorderLayout.WEST);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        animation.setPreferredSize(new Dimension(700, 190));
        center.add(animation);
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        explanation.setRows(4);
        explanation.setFont(explanation.getFont().deriveFont(Font.PLAIN, 14f));
        center.add(explanation);
        center.add(buildSettings());
        results.setEditable(false);
        results.setLineWrap(true);
        results.setWrapStyleWord(true);
        results.setRows(8);
        results.setText("先执行静止连拍建立基线，再执行低速飞拍测试。");
        center.add(new JScrollPane(results));
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton autoGuide = new JButton("▶ 自动演示流程");
        autoGuide.addActionListener(event -> playGuide());
        JButton save = new JButton("保存实验设置");
        save.addActionListener(event -> saveValues());
        JButton export = new JButton("导出 CSV");
        export.addActionListener(event -> exportReport());
        staticTest.addActionListener(event -> runStaticTest());
        flightTest.addActionListener(event -> runFlightTest());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        buttons.add(autoGuide);
        buttons.add(save);
        buttons.add(export);
        buttons.add(staticTest);
        buttons.add(flightTest);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
    }

    private JPanel buildSettings() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("安全参数（速度为机器最大速度比例）"));
        panel.add(row(enabled, production, fallback, saveImages));
        panel.add(row(label("速度", speed), label("进入距离 mm", approach),
                label("离开距离 mm", exit), label("采集帧数", frames),
                label("重复次数", repeats)));
        panel.add(row(label("最低清晰度", sharpness), label("最大偏移 mm", maxOffset),
                label("最大角度 °", maxAngle), timestamp, label("相机延迟 ms", latency)));
        JPanel whitelist = new JPanel(new BorderLayout(6, 4));
        whitelist.add(new JLabel("正式任务白名单 Part ID（逗号或换行分隔；空白名单绝不接入）："),
                BorderLayout.NORTH);
        whitelist.add(new JScrollPane(partIds), BorderLayout.CENTER);
        panel.add(whitelist);
        panel.add(safety);
        return panel;
    }

    private static JPanel row(java.awt.Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        for (java.awt.Component component : components) {
            panel.add(component);
        }
        return panel;
    }

    private static JPanel label(String name, java.awt.Component value) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.add(new JLabel(name));
        panel.add(value);
        return panel;
    }

    private void loadValues() {
        enabled.setSelected(FlyingVisionSettings.isEnabled());
        production.setSelected(FlyingVisionSettings.isProductionEnabled());
        fallback.setSelected(FlyingVisionSettings.isFallbackEnabled());
        saveImages.setSelected(FlyingVisionSettings.isSaveDebugImages());
        timestamp.setSelected(FlyingVisionSettings.isTimestampCompensationEnabled());
    }

    private void saveValues() {
        if (production.isSelected()
                && (!enabled.isSelected() || partIds.getText().trim().isEmpty())) {
            JOptionPane.showMessageDialog(this,
                    "正式接入必须同时启用飞拍实验并填写 Part ID 白名单。",
                    "白名单不能为空", JOptionPane.WARNING_MESSAGE);
            production.setSelected(false);
        }
        FlyingVisionSettings.setEnabled(enabled.isSelected());
        FlyingVisionSettings.setProductionEnabled(production.isSelected());
        FlyingVisionSettings.setFallbackEnabled(fallback.isSelected());
        FlyingVisionSettings.setSaveDebugImages(saveImages.isSelected());
        FlyingVisionSettings.setTimestampCompensationEnabled(timestamp.isSelected());
        FlyingVisionSettings.setSpeed(number(speed));
        FlyingVisionSettings.setApproachDistanceMm(number(approach));
        FlyingVisionSettings.setExitDistanceMm(number(exit));
        FlyingVisionSettings.setFrameCount((int) number(frames));
        FlyingVisionSettings.setMinimumSharpness(number(sharpness));
        FlyingVisionSettings.setMaximumOffsetMm(number(maxOffset));
        FlyingVisionSettings.setMaximumAngleDeg(number(maxAngle));
        FlyingVisionSettings.setCameraLatencyMs(number(latency));
        FlyingVisionSettings.setEnabledPartIds(partIds.getText());
        results.setText("设置已保存。正式任务仍需同时满足：实验模式、飞拍开关、"
                + "正式接入开关和 Part ID 白名单。");
    }

    private void runStaticTest() {
        if (!preparePhysicalTest("静止连拍")) {
            return;
        }
        saveValues();
        setTestButtons(false);
        results.setText("正在移动到普通底部相机位置并建立静止连拍基线…");
        UiUtils.submitUiMachineTask(() -> {
            Nozzle nozzle = requireNozzle();
            Part part = nozzle.getPart();
            ReferenceBottomVision alignment = ReferenceBottomVision.getDefault();
            Camera camera = VisionUtils.getBottomVisionCamera();
            Location center = alignment.getCameraLocationAtPartHeight(part, camera, nozzle, 0.);
            MovableUtils.moveToLocationAtSafeZ(nozzle, center, Math.min(number(speed), 0.20));
            nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            List<String> lines = new ArrayList<>();
            PartAlignmentOffset best = null;
            double bestSharpness = -1;
            int valid = 0;
            for (int index = 0; index < (int) number(frames); index++) {
                long started = System.nanoTime();
                BufferedImage image = camera.lightSettleAndCapture();
                double captureMs = (System.nanoTime() - started) / 1_000_000.0;
                double focus = FlyingVisionFrameEvaluator.sharpness(image);
                try {
                    PartAlignmentOffset offset = alignment.analyzeExperimentalFrame(
                            part, nozzle, camera, image);
                    valid++;
                    if (focus > bestSharpness) {
                        bestSharpness = focus;
                        best = offset;
                    }
                    lines.add(String.format(Locale.ROOT,
                            "帧 %02d  清晰度 %.2f  抓图 %.1f ms  偏移 %s",
                            index + 1, focus, captureMs, offset.getLocation()));
                }
                catch (Exception exception) {
                    lines.add(String.format(Locale.ROOT,
                            "帧 %02d  清晰度 %.2f  抓图 %.1f ms  识别失败：%s",
                            index + 1, focus, captureMs, exception.getMessage()));
                }
            }
            if (best == null) {
                throw new Exception("静止连拍没有任何有效识别帧。");
            }
            return new StaticResult(best, valid, String.join("\n", lines));
        }, result -> {
            lastStaticOffset = result.offsets;
            setTestButtons(true);
            results.setText("静止基线完成：有效 " + result.valid + "/"
                    + (int) number(frames) + " 帧\n" + result.details);
            stepList.setSelectedIndex(3);
        }, throwable -> {
            setTestButtons(true);
            UiUtils.showError(throwable);
            results.setText("静止基线失败：" + throwable.getMessage());
        });
    }

    private void runFlightTest() {
        if (!preparePhysicalTest("低速飞拍")) {
            return;
        }
        saveValues();
        setTestButtons(false);
        results.setText("正在执行低速经过相机测试；出现异常会停止并保留普通停拍回退…");
        int repeatCount = (int) number(repeats);
        UiUtils.submitUiMachineTask(() -> {
            List<FlyingBottomVisionService.Result> batch = new ArrayList<>();
            Nozzle nozzle = requireNozzle();
            for (int index = 0; index < repeatCount; index++) {
                batch.add(FlyingBottomVisionService.execute(
                        ReferenceBottomVision.getDefault(), nozzle.getPart(), nozzle));
            }
            return batch;
        }, batch -> {
            setTestButtons(true);
            FlyingBottomVisionService.Result result = batch.get(batch.size() - 1);
            String comparison = summarizeBatch(batch, lastStaticOffset);
            results.setText(String.format(Locale.ROOT,
                    "低速飞拍完成：重复 %d 次；末次采集 %d 帧，Pipeline 有效 %d 帧，"
                            + "最佳清晰度 %.2f，末次耗时 %.1f ms\n末次偏移：%s%s",
                    batch.size(), result.getCapturedFrames(), result.getSuccessfulFrames(),
                    result.getSharpness(), result.getElapsedMs(),
                    result.getOffsets().getLocation(), comparison));
            stepList.setSelectedIndex(4);
        }, throwable -> {
            setTestButtons(true);
            UiUtils.showError(throwable);
            results.setText("低速飞拍失败：" + throwable.getMessage()
                    + "\n正式任务启用自动回退后会改用原有停拍 Bottom Vision。");
        });
    }

    private boolean preparePhysicalTest(String operation) {
        if (!safety.isSelected()) {
            JOptionPane.showMessageDialog(this, "请先完成页面底部的安全确认。",
                    "需要安全确认", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (!Configuration.get().getMachine().isEnabled()
                || !Configuration.get().getMachine().isHomed()) {
            JOptionPane.showMessageDialog(this, "机器必须已启用并完成回零。",
                    "机器未就绪", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        Nozzle nozzle = frame.getMachineControls().getSelectedNozzle();
        if (nozzle == null || nozzle.getPart() == null) {
            JOptionPane.showMessageDialog(this, "请选择一个吸有测试元件的吸嘴。",
                    "没有测试元件", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return JOptionPane.showConfirmDialog(this,
                operation + "将移动吸嘴到下视相机附近。\n"
                        + "速度上限已限制为 35%，是否继续？",
                "最后安全确认", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private Nozzle requireNozzle() throws Exception {
        Nozzle nozzle = frame.getMachineControls().getSelectedNozzle();
        if (nozzle == null || nozzle.getPart() == null) {
            throw new Exception("No selected nozzle with a part.");
        }
        return nozzle;
    }

    private void setTestButtons(boolean value) {
        staticTest.setEnabled(value);
        flightTest.setEnabled(value);
    }

    private String summarizeBatch(List<FlyingBottomVisionService.Result> batch,
            PartAlignmentOffset baseline) {
        double sumX = 0;
        double sumY = 0;
        double sumAngle = 0;
        double sumTime = 0;
        double maxDistance = 0;
        List<Location> locations = new ArrayList<>();
        for (FlyingBottomVisionService.Result result : batch) {
            Location location = result.getOffsets().getLocation()
                    .convertToUnits(LengthUnit.Millimeters);
            locations.add(location);
            sumX += location.getX();
            sumY += location.getY();
            sumAngle += location.getRotation();
            sumTime += result.getElapsedMs();
            maxDistance = Math.max(maxDistance, Math.hypot(location.getX(), location.getY()));
        }
        double count = locations.size();
        double meanX = sumX / count;
        double meanY = sumY / count;
        double meanAngle = sumAngle / count;
        double variance = 0;
        for (Location location : locations) {
            variance += Math.pow(location.getX() - meanX, 2)
                    + Math.pow(location.getY() - meanY, 2);
        }
        String baselineText = "";
        if (baseline != null) {
            Location normal = baseline.getLocation().convertToUnits(LengthUnit.Millimeters);
            baselineText = String.format(Locale.ROOT,
                    "\n相对静止基线：X %.3f mm，Y %.3f mm，角度 %.3f°",
                    meanX - normal.getX(), meanY - normal.getY(),
                    meanAngle - normal.getRotation());
        }
        return String.format(Locale.ROOT,
                "\n批次统计：平均 X %.3f mm，Y %.3f mm，角度 %.3f°；"
                        + "XY 标准差 %.3f mm，最大偏移 %.3f mm，平均耗时 %.1f ms%s",
                meanX, meanY, meanAngle, Math.sqrt(variance / count),
                maxDistance, sumTime / count, baselineText);
    }

    private void exportReport() {
        try {
            File directory = new File(Configuration.get().getConfigurationDirectory(),
                    "pro-flying-vision");
            if (!directory.mkdirs() && !directory.isDirectory()) {
                throw new Exception("无法创建飞拍报告目录。");
            }
            File report = new File(directory,
                    "report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                            + ".csv");
            String value = results.getText().replace("\"", "\"\"");
            String csv = "time,experimental_mode,enabled,production_enabled,"
                    + "speed,approach_mm,exit_mm,frames,repeats,part_ids,result\n"
                    + "\"" + new Date() + "\",true," + enabled.isSelected() + ","
                    + production.isSelected() + "," + number(speed) + ","
                    + number(approach) + "," + number(exit) + ","
                    + (int) number(frames) + "," + (int) number(repeats) + ",\""
                    + partIds.getText().replace("\"", "\"\"") + "\",\""
                    + value + "\"\n";
            Files.write(report.toPath(), csv.getBytes(StandardCharsets.UTF_8));
            results.append("\n报告已导出：" + report.getAbsolutePath());
        }
        catch (Exception exception) {
            UiUtils.showError(exception);
        }
    }
    private void showStep(int index) {
        if (stepList.getSelectedIndex() != index) {
            stepList.setSelectedIndex(index);
        }
        String[] text = {
                "飞拍不是简单地取消等待，而是在吸嘴低速经过底部相机时连续取帧，"
                        + "再用原有 Pipeline 识别。所有功能默认关闭。",
                "先保持 10% 速度、12 mm 进入/离开距离和 8 帧。正式任务必须填写"
                        + " Part ID 白名单，未在白名单中的元件永远使用普通停拍。",
                "吸嘴停在相机中心连续识别，建立清晰度、耗时和偏移基线。"
                        + "静止基线不通过时不要继续飞拍。",
                "吸嘴从相机左侧低速通过到右侧。系统按时间接近中心、清晰度和"
                        + " Pipeline 成功情况选择最佳帧，不合格立即失败。",
                "先比较飞拍与静止偏差。只有多次重复稳定后再打开正式接入；"
                        + "任何无帧、模糊、识别失败或超限都自动回退普通停拍。"
        };
        explanation.setText(text[index]);
        animation.setStage(index);
    }

    private void playGuide() {
        final int[] step = {0};
        Timer timer = new Timer(1800, null);
        timer.addActionListener(event -> {
            stepList.setSelectedIndex(step[0]++);
            if (step[0] >= steps.length) {
                ((Timer) event.getSource()).stop();
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static final class StaticResult {
        private final PartAlignmentOffset offsets;
        private final int valid;
        private final String details;

        StaticResult(PartAlignmentOffset offsets, int valid, String details) {
            this.offsets = offsets;
            this.valid = valid;
            this.details = details;
        }
    }

    private static final class FlightAnimation extends JPanel {
        private int stage;
        private double phase;
        private final Timer timer = new Timer(35, event -> {
            phase += 0.025;
            repaint();
        });

        FlightAnimation() {
            setOpaque(false);
            timer.start();
        }

        void setStage(int stage) {
            this.stage = stage;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int y = getHeight() / 2;
            g.setColor(new Color(90, 100, 112));
            g.setStroke(new BasicStroke(3f));
            g.drawLine(60, y, getWidth() - 60, y);
            int cameraX = getWidth() / 2;
            g.setColor(new Color(76, 132, 164));
            g.fillRoundRect(cameraX - 45, y + 22, 90, 48, 16, 16);
            g.setColor(Color.WHITE);
            g.fillOval(cameraX - 13, y + 32, 26, 26);
            double travel = (Math.sin(phase * Math.PI * 2) + 1) / 2;
            int nozzleX = 90 + (int) ((getWidth() - 180) * travel);
            g.setColor(stage >= 3 ? new Color(77, 166, 116) : new Color(140, 145, 151));
            g.fillRoundRect(nozzleX - 18, y - 64, 36, 54, 10, 10);
            g.fillOval(nozzleX - 11, y - 18, 22, 14);
            if (stage >= 2) {
                g.setColor(new Color(77, 166, 116, 85));
                int pulse = 25 + (int) (10 * Math.sin(phase * Math.PI * 4));
                g.drawOval(cameraX - pulse, y - pulse, pulse * 2, pulse * 2);
            }
            g.dispose();
        }
    }
}
