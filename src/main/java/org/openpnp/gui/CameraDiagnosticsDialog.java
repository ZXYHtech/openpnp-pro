/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import org.openpnp.CameraListener;
import org.openpnp.gui.support.CameraPreviewMetrics;
import org.openpnp.gui.support.CameraDiagnosticReport;
import org.openpnp.gui.support.CameraDiagnosticReport.Phase;
import org.openpnp.gui.support.CameraDiagnosticReport.Sample;
import org.openpnp.gui.support.ExperimentalGuide;
import org.openpnp.gui.support.ExperimentalGuide.Step;
import org.openpnp.gui.support.CameraPreviewMetrics.Snapshot;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.machine.reference.camera.AbstractBroadcastingCamera;
import org.openpnp.machine.reference.camera.OpenCvCamera;
import org.openpnp.machine.reference.camera.OpenCvCamera.OpenCvCaptureProperty;
import org.openpnp.machine.reference.camera.OpenPnpCaptureCamera;
import org.openpnp.machine.reference.camera.OpenPnpCaptureCamera.CapturePropertyHolder;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

/**
 * Experimental, non-production camera preview laboratory. It observes the existing camera
 * broadcaster instead of creating a second competing capture loop. Camera callbacks only publish
 * the newest frame; a Swing timer displays it and deliberately discards stale frames.
 */
@SuppressWarnings("serial")
public class CameraDiagnosticsDialog extends JDialog {
    private static final RateOption[] CAPTURE_RATES = {
            new RateOption("5 FPS（低负载）", 5),
            new RateOption("10 FPS", 10),
            new RateOption("15 FPS", 15),
            new RateOption("30 FPS（推荐测试）", 30),
            new RateOption("60 FPS", 60),
            new RateOption("尽可能快（上限 120）", 120)
    };
    private static final RateOption[] DISPLAY_RATES = {
            new RateOption("5 FPS", 5),
            new RateOption("10 FPS", 10),
            new RateOption("15 FPS", 15),
            new RateOption("30 FPS（推荐）", 30),
            new RateOption("60 FPS", 60),
            new RateOption("尽可能快（上限 120）", 120)
    };

    private final MainFrame owner;
    private final JComboBox<Camera> cameraChoice = new JComboBox<>();
    private final JComboBox<RateOption> captureRate = new JComboBox<>(CAPTURE_RATES);
    private final JComboBox<RateOption> displayRate = new JComboBox<>(DISPLAY_RATES);
    private final JCheckBox monitorAll = new JCheckBox("同时监测全部相机（USB 带宽对比）");
    private final JCheckBox keepLightOn = new JCheckBox("预览期间保持所选相机灯光常亮");
    private final JButton start = new JButton("▶ 启动高速预览测试");
    private final JButton oneClick = new JButton("一键完整诊断（约 1 分钟）");
    private final JButton stop = new JButton("■ 停止并恢复");
    private final JButton openReport = new JButton("打开报告目录");
    private final JLabel state = new JLabel("尚未开始。诊断功能不会运行视觉 Pipeline。");
    private final PreviewSurface preview = new PreviewSurface();
    private final DefaultTableModel metricModel =
            new DefaultTableModel(new String[] {"指标", "当前值", "说明"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final DefaultTableModel camerasModel =
            new DefaultTableModel(new String[] {"相机", "方向", "分辨率", "接收 FPS",
                    "抖动 ms", "重复帧", "状态"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JTable metricTable = new JTable(metricModel);
    private final JTable camerasTable = new JTable(camerasModel);
    private final Timer displayTimer;
    private final Timer statsTimer;
    private final ExecutorService cameraControl = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OpenPnP-Pro-Camera-Diagnostics");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Map<Camera, Probe> probes = new LinkedHashMap<>();
    private volatile Map<Camera, Probe> lastProbes = new LinkedHashMap<>();
    private volatile Camera displayCamera;
    private volatile boolean sessionRequested;
    private volatile boolean suiteRequested;
    private volatile boolean suiteRunning;
    private volatile double suiteDisplayFps = 30;
    private volatile Path lastReportDirectory;
    private volatile boolean closing;
    private long lastLoggedSecond = -1;
    private ExperimentalGuide guide;

    public CameraDiagnosticsDialog(MainFrame owner) {
        super(owner, "实验模式 · 相机诊断与高速预览", false);
        this.owner = owner;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 700));
        setSize(1240, 820);
        setLocationRelativeTo(owner);
        captureRate.setSelectedIndex(3);
        displayRate.setSelectedIndex(3);
        displayTimer = new Timer(displayDelay(), event -> displayLatestFrame());
        displayTimer.setCoalesce(true);
        statsTimer = new Timer(500, event -> refreshStatistics());
        statsTimer.setCoalesce(true);
        buildUi();
        loadCameras();
        wireActions();
        guide = createGuide();
        guide.startOnFirstOpen();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closing = true;
                suiteRequested = false;
                stopSession();
                statsTimer.stop();
                displayTimer.stop();
                cameraControl.shutdown();
            }
        });
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JPanel controls = new JPanel();
        controls.setLayout(new javax.swing.BoxLayout(controls, javax.swing.BoxLayout.Y_AXIS));
        JPanel first = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        first.add(new JLabel("相机"));
        cameraChoice.setPreferredSize(new Dimension(260, 28));
        first.add(cameraChoice);
        first.add(new JLabel("目标采集"));
        first.add(captureRate);
        first.add(new JLabel("目标显示"));
        first.add(displayRate);
        first.add(start);
        first.add(stop);
        controls.add(first);

        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        oneClick.setToolTipText("自动运行固定对比矩阵并保存逐秒、汇总和中文分析报告");
        second.add(oneClick);
        second.add(monitorAll);
        second.add(keepLightOn);
        JLabel warning = new JLabel("灯光常亮仅用于实验，注意温升；停止或异常时恢复进入测试前的状态。");
        warning.setForeground(new Color(166, 107, 32));
        second.add(warning);
        controls.add(second);
        root.add(controls, BorderLayout.NORTH);

        preview.setPreferredSize(new Dimension(700, 500));
        preview.setBorder(BorderFactory.createLineBorder(ModernTheme.border()));
        JScrollPane metricsScroll = new JScrollPane(metricTable);
        metricsScroll.setPreferredSize(new Dimension(430, 500));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, preview, metricsScroll);
        split.setResizeWeight(0.64);
        split.setContinuousLayout(true);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(split, BorderLayout.CENTER);
        camerasTable.setPreferredScrollableViewportSize(new Dimension(900, 120));
        center.add(new JScrollPane(camerasTable), BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 4));
        JPanel lights = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JButton lightOn = new JButton("💡 打开灯光");
        JButton lightOff = new JButton("关闭灯光");
        JButton restoreLight = new JButton("恢复自动灯光控制");
        lightOn.addActionListener(event -> setSelectedLight(true));
        lightOff.addActionListener(event -> setSelectedLight(false));
        restoreLight.addActionListener(event -> restoreSelectedLight());
        lights.add(lightOn);
        lights.add(lightOff);
        lights.add(restoreLight);
        lights.add(state);
        bottom.add(lights, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton followGuide = new JButton("跟着做");
        followGuide.addActionListener(event -> guide.start());
        JButton export = new JButton("导出 CSV");
        export.addActionListener(event -> exportCsv());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        actions.add(followGuide);
        actions.add(export);
        openReport.setEnabled(false);
        actions.add(openReport);
        actions.add(close);
        bottom.add(actions, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        stop.setEnabled(false);
        metricTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        metricTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        metricTable.getColumnModel().getColumn(2).setPreferredWidth(260);
    }

    private ExperimentalGuide createGuide() {
        return new ExperimentalGuide(this, "camera-diagnostics", "相机诊断与高速预览",
                new Step("选择要诊断的相机",
                        "选择作为主对比对象的顶部或底部相机。诊断只监听连续预览，不运行视觉 Pipeline。",
                        () -> cameraChoice, () -> cameraChoice.getSelectedItem() != null),
                new Step("启动一键完整诊断",
                        "推荐直接点击“一键完整诊断”。它会自动比较单相机、全部相机和显示负载，约一分钟后生成三份报告。",
                        () -> oneClick, () -> sessionRequested || lastReportDirectory != null),
                new Step("等待自动阶段完成",
                        "每阶段先预热再逐秒采样。期间不会开灯、移动贴装头或运行视觉 Pipeline；可随时点击停止安全取消。",
                        () -> state, () -> lastReportDirectory != null),
                new Step("观察实时数据",
                        "运行时可观察接收 FPS、抖动、最大卡顿、原始取帧、图像变换、预览循环和 UI 绘制耗时。",
                        () -> metricTable, () -> metricTable.getRowCount() > 0),
                new Step("查看全部相机对比",
                        "全部相机阶段会同时记录每台相机。若 FPS 同时下降或抖动升高，报告会提示 USB、解码或 CPU 竞争。",
                        () -> camerasTable, () -> camerasTable.getRowCount() > 0),
                new Step("打开完整报告",
                        "完成后点击“打开报告目录”。summary.csv 用于横向对比，timeseries.csv 用于定位秒级卡顿，analysis.txt 给出中文结论。",
                        () -> openReport, () -> lastReportDirectory != null),
                new Step("按需手动复测",
                        "需要验证特殊帧率时，再使用上方目标采集、目标显示和“启动高速预览测试”。灯光常亮只用于短时画质检查。",
                        () -> start, () -> true));
    }
    private void loadCameras() {
        Camera current = owner.getCameraViews().getSelectedCamera();
        for (Camera camera : Configuration.get().getMachine().getAllCameras()) {
            cameraChoice.addItem(camera);
        }
        if (current != null) {
            cameraChoice.setSelectedItem(current);
        }
        displayCamera = (Camera) cameraChoice.getSelectedItem();
        if (cameraChoice.getItemCount() == 0) {
            state.setText("当前机器配置中没有相机。");
            start.setEnabled(false);
        }
    }

    private void wireActions() {
        start.addActionListener(event -> startSession());
        oneClick.addActionListener(event -> runCompleteDiagnostics());
        stop.addActionListener(event -> stopSession());
        openReport.addActionListener(event -> openReportDirectory());
        displayRate.addActionListener(event -> {
            displayTimer.setDelay(displayDelay());
            displayTimer.setInitialDelay(displayDelay());
        });
        cameraChoice.addActionListener(event -> {
            displayCamera = (Camera) cameraChoice.getSelectedItem();
            preview.clear();
            if (sessionRequested && !monitorAll.isSelected()) {
                stopSession();
            }
            else {
                Probe probe = probes.get(displayCamera);
                preview.setMetrics(probe == null ? null : probe.metrics);
            }
        });
    }

    private int displayDelay() {
        if (suiteRunning) {
            return Math.max(8, (int) Math.round(1000.0 / suiteDisplayFps));
        }
        RateOption option = (RateOption) displayRate.getSelectedItem();
        double rate = option == null ? 30 : option.fps;
        return Math.max(8, (int) Math.round(1000.0 / rate));
    }

    private void runCompleteDiagnostics() {
        if (!ExperimentalMotionCenterDialog.isExperimentalModeEnabled()) {
            JOptionPane.showMessageDialog(this, "请先在“窗口”菜单中启用实验模式。",
                    "实验模式未开启", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Camera selected = (Camera) cameraChoice.getSelectedItem();
        if (selected == null || sessionRequested || suiteRunning) {
            return;
        }
        List<Camera> allCameras = new ArrayList<>(
                Configuration.get().getMachine().getAllCameras());
        List<Phase> phases = CameraDiagnosticReport.standardPlan(allCameras.size());
        sessionRequested = true;
        suiteRequested = true;
        suiteRunning = true;
        displayCamera = selected;
        lastReportDirectory = null;
        openReport.setEnabled(false);
        start.setEnabled(false);
        oneClick.setEnabled(false);
        stop.setEnabled(true);
        captureRate.setEnabled(false);
        displayRate.setEnabled(false);
        monitorAll.setEnabled(false);
        keepLightOn.setEnabled(false);
        cameraChoice.setEnabled(false);
        preview.clear();
        state.setText("正在准备一键完整诊断；不会移动机器或运行视觉 Pipeline…");
        cameraControl.submit(() -> executeCompleteDiagnostics(selected, allCameras, phases));
    }

    private void executeCompleteDiagnostics(Camera selected, List<Camera> allCameras,
            List<Phase> phases) {
        List<Sample> samples = new ArrayList<>();
        Path reportDirectory = null;
        boolean completed = true;
        String failure = null;
        try {
            String folder = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            reportDirectory = Configuration.get().getConfigurationDirectory().toPath()
                    .resolve("camera-diagnostics").resolve(folder);
            Files.createDirectories(reportDirectory);
            for (int phaseIndex = 0; phaseIndex < phases.size(); phaseIndex++) {
                if (!suiteRequested || closing) {
                    completed = false;
                    break;
                }
                Phase phase = phases.get(phaseIndex);
                suiteDisplayFps = phase.displayFps;
                List<Camera> phaseCameras = new ArrayList<>();
                if (phase.allCameras) {
                    phaseCameras.addAll(allCameras);
                }
                else {
                    phaseCameras.add(selected);
                }
                Map<Camera, Probe> opened = new LinkedHashMap<>();
                for (Camera camera : phaseCameras) {
                    Probe probe = new Probe(camera, phase.captureFps);
                    probe.open();
                    opened.put(camera, probe);
                }
                probes = opened;
                lastProbes = new LinkedHashMap<>();
                int displayDelayMs = Math.max(8,
                        (int) Math.round(1000.0 / phase.displayFps));
                int currentPhase = phaseIndex + 1;
                SwingUtilities.invokeLater(() -> {
                    displayTimer.setDelay(displayDelayMs);
                    displayTimer.setInitialDelay(displayDelayMs);
                    preview.setMetrics(opened.get(displayCamera) == null ? null
                            : opened.get(displayCamera).metrics);
                    displayTimer.start();
                    statsTimer.start();
                    state.setText("完整诊断 " + currentPhase + "/" + phases.size() + "："
                            + phase.name + "，预热 "
                            + CameraDiagnosticReport.WARMUP_SECONDS + " 秒…");
                });
                if (!waitSeconds(CameraDiagnosticReport.WARMUP_SECONDS)) {
                    completed = false;
                }
                if (completed) {
                    long measurementStart = System.nanoTime();
                    for (Probe probe : opened.values()) {
                        probe.metrics.reset(measurementStart);
                    }
                }
                for (int second = 1; completed
                        && second <= CameraDiagnosticReport.SAMPLE_SECONDS; second++) {
                    if (!waitSeconds(1)) {
                        completed = false;
                        break;
                    }
                    samples.addAll(captureSamples(opened, selected, phase, currentPhase,
                            phases.size(), second));
                    int sampledSecond = second;
                    SwingUtilities.invokeLater(() -> state.setText("完整诊断 "
                            + currentPhase + "/" + phases.size() + "：" + phase.name
                            + "，采样 " + sampledSecond + "/"
                            + CameraDiagnosticReport.SAMPLE_SECONDS + " 秒"));
                }
                probes = new LinkedHashMap<>();
                lastProbes = opened;
                closeProbes(opened);
                if (!completed) {
                    break;
                }
            }
            if (!samples.isEmpty()) {
                String environment = environmentDescription(completed, phases.size(),
                        allCameras.size());
                List<CameraDiagnosticReport.Summary> summaries =
                        CameraDiagnosticReport.summarize(samples);
                writeUtf8Bom(reportDirectory.resolve("timeseries.csv"),
                        CameraDiagnosticReport.timeSeriesCsv(samples));
                writeUtf8Bom(reportDirectory.resolve("summary.csv"),
                        CameraDiagnosticReport.summaryCsv(summaries));
                Files.write(reportDirectory.resolve("analysis.txt"),
                        CameraDiagnosticReport.analysisText(summaries, environment)
                                .getBytes(StandardCharsets.UTF_8));
                lastReportDirectory = reportDirectory;
            }
        }
        catch (Exception error) {
            completed = false;
            failure = shortError(error);
            Logger.error(error);
        }
        finally {
            Map<Camera, Probe> remaining = probes;
            probes = new LinkedHashMap<>();
            if (!remaining.isEmpty()) {
                lastProbes = remaining;
                closeProbes(remaining);
            }
            sessionRequested = false;
            suiteRequested = false;
            suiteRunning = false;
            String finalFailure = failure;
            boolean finalCompleted = completed;
            SwingUtilities.invokeLater(() -> finishCompleteDiagnostics(finalCompleted,
                    finalFailure));
        }
    }

    private List<Sample> captureSamples(Map<Camera, Probe> opened, Camera selected,
            Phase phase, int phaseIndex, int phaseTotal, int sampleSecond) {
        List<Sample> samples = new ArrayList<>();
        double[] load = systemLoad();
        double heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                / (1024.0 * 1024.0);
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        for (Probe probe : opened.values()) {
            Snapshot snapshot = probe.metrics.snapshot(System.nanoTime());
            BufferedImage image = probe.lastFrame;
            Sample sample = new Sample();
            sample.time = now;
            sample.phaseId = phase.id;
            sample.phaseName = phase.name;
            sample.phaseIndex = phaseIndex;
            sample.phaseTotal = phaseTotal;
            sample.allCameras = phase.allCameras;
            sample.sampleSecond = sampleSecond;
            sample.camera = probe.camera.getName();
            sample.primaryCamera = probe.camera == selected;
            sample.looking = String.valueOf(probe.camera.getLooking());
            sample.driver = probe.camera.getClass().getSimpleName();
            sample.configuredResolution = probe.camera.getWidth() + "x"
                    + probe.camera.getHeight();
            sample.outputResolution = image == null ? sample.configuredResolution
                    : image.getWidth() + "x" + image.getHeight();
            sample.format = describeFormat(probe.camera);
            sample.captureTargetFps = phase.captureFps;
            sample.displayTargetFps = phase.displayFps;
            sample.captureFps = snapshot.captureFps;
            sample.displayFps = snapshot.displayFps;
            sample.rawCaptureAverageMs = snapshot.rawCaptureAverageMs;
            sample.captureAverageMs = snapshot.captureAverageMs;
            sample.processingAverageMs = snapshot.processingAverageMs;
            sample.previewLoopAverageMs = snapshot.previewLoopAverageMs;
            sample.renderAverageMs = snapshot.renderAverageMs;
            sample.frameIntervalAverageMs = snapshot.frameIntervalAverageMs;
            sample.jitterMs = snapshot.frameIntervalJitterMs;
            sample.maximumStallMs = snapshot.maximumStallMs;
            sample.receivedFrames = snapshot.receivedFrames;
            sample.displayedFrames = snapshot.displayedFrames;
            sample.droppedFrames = snapshot.droppedFrames;
            sample.duplicateFrames = snapshot.duplicateFrames;
            sample.processCpuPercent = load[0];
            sample.systemCpuPercent = load[1];
            sample.heapUsedMb = heapUsed;
            sample.exposure = describeExposure(probe.camera);
            sample.gain = describeGain(probe.camera);
            sample.brightness = describeBrightness(probe.camera);
            sample.light = describeLight(probe.camera);
            sample.error = probe.error == null ? "" : probe.error;
            samples.add(sample);
        }
        return samples;
    }

    private boolean waitSeconds(int seconds) {
        for (int tick = 0; tick < seconds * 10; tick++) {
            if (!suiteRequested || closing) {
                return false;
            }
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return suiteRequested && !closing;
    }

    private void finishCompleteDiagnostics(boolean completed, String failure) {
        displayTimer.stop();
        statsTimer.stop();
        start.setEnabled(cameraChoice.getItemCount() > 0 && !closing);
        oneClick.setEnabled(cameraChoice.getItemCount() > 0 && !closing);
        stop.setEnabled(false);
        captureRate.setEnabled(true);
        displayRate.setEnabled(true);
        monitorAll.setEnabled(true);
        keepLightOn.setEnabled(true);
        cameraChoice.setEnabled(true);
        openReport.setEnabled(lastReportDirectory != null);
        refreshStatistics();
        if (closing) {
            return;
        }
        if (failure != null) {
            state.setText("完整诊断失败：" + failure);
        }
        else if (lastReportDirectory == null) {
            state.setText("完整诊断已取消，尚未形成有效采样。");
        }
        else {
            state.setText((completed ? "完整诊断完成：" : "完整诊断已取消，部分报告已保存：")
                    + lastReportDirectory.toAbsolutePath());
        }
    }

    private void openReportDirectory() {
        Path directory = lastReportDirectory;
        if (directory == null || !Files.isDirectory(directory)) {
            state.setText("尚未生成一键诊断报告。");
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("当前系统不支持打开目录");
            }
            Desktop.getDesktop().open(directory.toFile());
        }
        catch (Exception error) {
            UiUtils.showError(this, "打开诊断报告目录失败", error);
        }
    }

    private static double[] systemLoad() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean extended =
                    (com.sun.management.OperatingSystemMXBean) bean;
            return new double[] {percent(extended.getProcessCpuLoad()),
                    percent(extended.getSystemCpuLoad())};
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    private static double percent(double load) {
        return load < 0 || !Double.isFinite(load) ? Double.NaN : load * 100;
    }

    private static String environmentDescription(boolean completed, int phaseCount,
            int cameraCount) {
        Runtime runtime = Runtime.getRuntime();
        return "状态=" + (completed ? "完成" : "部分/已取消") + "\r\n"
                + "阶段数=" + phaseCount + "\r\n"
                + "相机数=" + cameraCount + "\r\n"
                + "操作系统=" + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch")
                + "\r\nJava=" + System.getProperty("java.version")
                + "\r\n逻辑处理器=" + runtime.availableProcessors()
                + "\r\nJVM最大内存MB="
                + String.format(Locale.US, "%.0f", runtime.maxMemory() / (1024.0 * 1024.0));
    }

    private static void writeUtf8Bom(Path path, String content) throws Exception {
        Files.write(path, ("\ufeff" + content).getBytes(StandardCharsets.UTF_8));
    }

    private void startSession() {
        if (!ExperimentalMotionCenterDialog.isExperimentalModeEnabled()) {
            JOptionPane.showMessageDialog(this, "请先在“窗口”菜单中启用实验模式。",
                    "实验模式未开启", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Camera selected = (Camera) cameraChoice.getSelectedItem();
        if (selected == null || sessionRequested) {
            return;
        }
        sessionRequested = true;
        displayCamera = selected;
        start.setEnabled(false);
        oneClick.setEnabled(false);
        stop.setEnabled(true);
        captureRate.setEnabled(false);
        monitorAll.setEnabled(false);
        cameraChoice.setEnabled(monitorAll.isSelected());
        state.setText("正在后台连接相机实时流…");
        preview.clear();
        lastLoggedSecond = -1;
        RateOption option = (RateOption) captureRate.getSelectedItem();
        double targetFps = option == null ? 30 : option.fps;
        boolean all = monitorAll.isSelected();
        boolean light = keepLightOn.isSelected();
        List<Camera> cameras = new ArrayList<>();
        if (all) {
            cameras.addAll(Configuration.get().getMachine().getAllCameras());
        }
        else {
            cameras.add(selected);
        }

        cameraControl.submit(() -> {
            Map<Camera, Probe> opened = new LinkedHashMap<>();
            for (Camera camera : cameras) {
                Probe probe = new Probe(camera, targetFps);
                probe.open();
                opened.put(camera, probe);
            }
            if (!sessionRequested) {
                closeProbes(opened);
                return;
            }
            probes = opened;
            lastProbes = new LinkedHashMap<>();
            if (light) {
                Probe selectedProbe = opened.get(selected);
                if (selectedProbe != null) {
                    manageLight(selectedProbe, true);
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (!sessionRequested || closing) {
                    return;
                }
                preview.setMetrics(opened.get(displayCamera) == null ? null
                        : opened.get(displayCamera).metrics);
                displayTimer.start();
                statsTimer.start();
                state.setText(all
                        ? "正在同时监测全部相机；预览区只绘制当前所选相机的最新帧。"
                        : "高速预览测试运行中；旧帧不会排队，UI 跟不上时自动丢弃。");
            });
            Logger.info("OpenPnP Pro camera diagnostics started: cameras={}, targetFps={}",
                    opened.size(), targetFps);
        });
    }

    private void stopSession() {
        if (suiteRunning) {
            suiteRequested = false;
            sessionRequested = false;
            displayTimer.stop();
            statsTimer.stop();
            stop.setEnabled(false);
            state.setText("正在取消完整诊断并恢复全部相机状态…");
            return;
        }
        boolean wasRequested = sessionRequested;
        sessionRequested = false;
        displayTimer.stop();
        statsTimer.stop();
        Map<Camera, Probe> toClose = probes;
        probes = new LinkedHashMap<>();
        if (!toClose.isEmpty()) {
            lastProbes = toClose;
        }
        if (wasRequested || !toClose.isEmpty()) {
            state.setText("正在停止并恢复相机预览参数与灯光状态…");
        }
        start.setEnabled(cameraChoice.getItemCount() > 0 && !closing);
        oneClick.setEnabled(cameraChoice.getItemCount() > 0 && !closing);
        stop.setEnabled(false);
        captureRate.setEnabled(true);
        monitorAll.setEnabled(true);
        cameraChoice.setEnabled(true);
        cameraControl.submit(() -> {
            closeProbes(toClose);
            Logger.info("OpenPnP Pro camera diagnostics stopped.");
            SwingUtilities.invokeLater(() -> {
                refreshStatistics();
                if (!closing) {
                    state.setText("测试已停止；相机预览帧率和灯光已恢复。");
                }
            });
        });
    }

    private void closeProbes(Map<Camera, Probe> values) {
        for (Probe probe : values.values()) {
            probe.close();
        }
    }

    private void displayLatestFrame() {
        Probe probe = probes.get(displayCamera);
        if (probe == null) {
            return;
        }
        BufferedImage image = probe.pending.getAndSet(null);
        if (image != null) {
            preview.setMetrics(probe.metrics);
            preview.setFrame(image);
            probe.metrics.recordDisplay(System.nanoTime());
        }
    }

    private void refreshStatistics() {
        Probe selected = visibleProbes().get(displayCamera);
        metricModel.setRowCount(0);
        if (displayCamera == null) {
            return;
        }
        Snapshot snapshot = selected == null
                ? new CameraPreviewMetrics().snapshot(System.nanoTime())
                : selected.metrics.snapshot(System.nanoTime());
        BufferedImage frame = selected == null ? null : selected.lastFrame;
        addMetric("相机名称", displayCamera.getName(), "当前预览/详细统计对象");
        addMetric("相机方向", String.valueOf(displayCamera.getLooking()),
                "Down 通常为顶部相机，Up 通常为底部相机");
        addMetric("驱动类型", displayCamera.getClass().getSimpleName(), "实际 Camera 实现类");
        addMetric("分辨率", frame == null
                ? displayCamera.getWidth() + " × " + displayCamera.getHeight()
                : frame.getWidth() + " × " + frame.getHeight(), "实际回调图像尺寸");
        addMetric("像素/采集格式", describeFormat(displayCamera),
                "驱动未公开格式时显示可获得的最接近信息");
        addMetric("原预览帧率", selected == null || !Double.isFinite(selected.originalFps)
                ? "驱动未公开" : formatNumber(selected.originalFps) + " FPS",
                "测试停止后会恢复此值");
        addMetric("测试目标帧率", selected == null ? "—"
                : formatNumber(selected.targetFps) + " FPS", "仅在本诊断会话临时生效");
        addMetric("实际接收帧率", formatNumber(snapshot.captureFps) + " FPS",
                "最近 4 秒相机回调的真实帧率");
        addMetric("实际显示帧率", formatNumber(snapshot.displayFps) + " FPS",
                "预览面板真正采用最新帧的速率");
        addMetric("采集耗时 平均/最小/最大",
                snapshot.captureAverageMs <= 0 ? "驱动未提供"
                        : String.format(Locale.US, "%.2f / %.2f / %.2f ms",
                                snapshot.captureAverageMs, snapshot.captureMinimumMs,
                                snapshot.captureMaximumMs),
                "仅统计预览线程主动 captureTransformed 的耗时");
        addMetric("原始取帧耗时", snapshot.rawCaptureAverageMs <= 0 ? "驱动未提供"
                : formatNumber(snapshot.rawCaptureAverageMs) + " ms",
                "从相机驱动取得原始图像，不包含后续变换");
        addMetric("图像变换耗时", snapshot.processingAverageMs <= 0 ? "驱动未提供"
                : formatNumber(snapshot.processingAverageMs) + " ms",
                "旋转、裁剪、镜头校正及颜色变换的平均耗时");
        addMetric("预览循环耗时", snapshot.previewLoopAverageMs <= 0 ? "驱动未提供"
                : formatNumber(snapshot.previewLoopAverageMs) + " ms",
                "取帧、变换和广播完成前的线程占用时间");
        addMetric("帧间隔平均值", formatNumber(snapshot.frameIntervalAverageMs) + " ms",
                "相机回调之间的平均间隔");
        addMetric("帧间隔抖动", formatNumber(snapshot.frameIntervalJitterMs) + " ms",
                "越小越适合时间戳补偿和飞拍");
        addMetric("最大卡顿", formatNumber(snapshot.maximumStallMs) + " ms",
                "最近 4 秒最长帧间隔");
        addMetric("UI 绘制耗时", formatNumber(snapshot.renderAverageMs) + " ms",
                "只计算缩放并绘制最新帧的时间");
        addMetric("收到 / 显示", snapshot.receivedFrames + " / " + snapshot.displayedFrames,
                "测试启动后的累计帧数");
        addMetric("丢弃旧帧", String.valueOf(snapshot.droppedFrames),
                "UI 来不及显示时主动丢弃，避免越看越延迟");
        addMetric("重复内容帧", String.valueOf(snapshot.duplicateFrames),
                "用 8×8 采样指纹估算，静止画面也可能正常重复");
        addMetric("曝光", describeExposure(displayCamera), "驱动报告值；自动曝光会限制 FPS");
        addMetric("增益", describeGain(displayCamera), "驱动报告值");
        addMetric("亮度", describeBrightness(displayCamera), "驱动报告值");
        addMetric("灯光状态", describeLight(displayCamera), "来自灯光执行器最近状态");
        addMetric("任务期间暂停预览", describeTaskSuspension(displayCamera),
                "启用后机器忙碌时预览会主动停止");
        addMetric("视觉 Pipeline", "未运行", "诊断只监听实时流，不介入正式视觉");
        addMetric("测试时长", formatElapsed(snapshot.elapsedMillis), "当前或最近一次会话");
        addMetric("异常", selected == null || selected.error == null ? "无" : selected.error,
                "相机打开、监听或恢复阶段的异常");

        camerasModel.setRowCount(0);
        for (Probe probe : visibleProbes().values()) {
            Snapshot cameraSnapshot = probe.metrics.snapshot(System.nanoTime());
            BufferedImage image = probe.lastFrame;
            camerasModel.addRow(new Object[] {
                    probe.camera.getName(),
                    probe.camera.getLooking(),
                    image == null ? probe.camera.getWidth() + "×" + probe.camera.getHeight()
                            : image.getWidth() + "×" + image.getHeight(),
                    formatNumber(cameraSnapshot.captureFps),
                    formatNumber(cameraSnapshot.frameIntervalJitterMs),
                    cameraSnapshot.duplicateFrames,
                    probe.error == null ? "正常" : probe.error
            });
        }
        if (sessionRequested && selected != null) {
            long elapsedSecond = snapshot.elapsedMillis / 1000;
            if (elapsedSecond != lastLoggedSecond) {
                lastLoggedSecond = elapsedSecond;
                Logger.info("Camera diagnostics {}: capture={} FPS, display={} FPS, "
                        + "jitter={} ms, dropped={}, duplicates={}",
                        displayCamera.getName(), formatNumber(snapshot.captureFps),
                        formatNumber(snapshot.displayFps),
                        formatNumber(snapshot.frameIntervalJitterMs),
                        snapshot.droppedFrames, snapshot.duplicateFrames);
            }
        }
    }

    private Map<Camera, Probe> visibleProbes() {
        Map<Camera, Probe> current = probes;
        return current.isEmpty() ? lastProbes : current;
    }

    private void addMetric(String name, String value, String explanation) {
        metricModel.addRow(new Object[] {name, value, explanation});
    }

    private void setSelectedLight(boolean on) {
        Probe probe = probes.get(displayCamera);
        if (probe == null) {
            state.setText("请先启动所选相机的预览测试。");
            return;
        }
        manageLight(probe, on);
    }

    private void manageLight(Probe probe, boolean on) {
        Actuator light = probe.camera.getLightActuator();
        if (light == null) {
            SwingUtilities.invokeLater(() -> state.setText("所选相机没有配置灯光执行器。"));
            return;
        }
        probe.lightManaged = true;
        Object value = on ? light.getDefaultOnValue() : light.getDefaultOffValue();
        UiUtils.submitUiMachineTask(() -> {
            light.actuate(value);
            return null;
        },
                ignored -> state.setText(on ? "灯光已打开并保持。" : "灯光已关闭。"),
                error -> {
                    probe.error = shortError(error);
                    state.setText("灯光控制失败：" + probe.error);
                });
    }

    private void restoreSelectedLight() {
        Probe probe = probes.get(displayCamera);
        if (probe == null) {
            state.setText("当前没有运行中的所选相机测试。");
            return;
        }
        restoreLight(probe);
        probe.lightManaged = false;
        state.setText("已请求恢复进入测试前的灯光状态。");
    }

    private void restoreLight(Probe probe) {
        Actuator light = probe.camera.getLightActuator();
        if (light == null || !probe.lightManaged) {
            return;
        }
        Object restore = probe.originalLightValue == null
                ? light.getDefaultOffValue() : probe.originalLightValue;
        UiUtils.submitUiMachineTask(() -> {
            light.actuate(restore);
            return null;
        },
                ignored -> { },
                error -> probe.error = shortError(error));
    }

    private void exportCsv() {
        Map<Camera, Probe> source = visibleProbes();
        if (source.isEmpty()) {
            JOptionPane.showMessageDialog(this, "还没有可导出的诊断数据。",
                    "相机诊断", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File file = PathSelectionDialog.chooseSave(this, "camera-diagnostics-export",
                "导出相机诊断数据",
                new File("camera-diagnostics-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv"),
                Configuration.get().getConfigurationDirectory(),
                new javax.swing.filechooser.FileNameExtensionFilter("CSV 数据 (*.csv)", "csv"));
        if (file == null) {
            return;
        }
        try {
            StringBuilder csv = new StringBuilder();
            csv.append("time,camera,looking,driver,resolution,format,configured_fps,")
                    .append("capture_fps,display_fps,raw_capture_avg_ms,capture_avg_ms,")
                    .append("processing_avg_ms,preview_loop_avg_ms,render_avg_ms,")
                    .append("frame_interval_ms,")
                    .append("jitter_ms,max_stall_ms,received_frames,displayed_frames,")
                    .append("dropped_frames,duplicate_frames,")
                    .append("exposure,gain,light,pipeline_enabled,test_duration_ms,error\n");
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            for (Probe probe : source.values()) {
                Snapshot snapshot = probe.metrics.snapshot(System.nanoTime());
                BufferedImage image = probe.lastFrame;
                String resolution = image == null
                        ? probe.camera.getWidth() + "x" + probe.camera.getHeight()
                        : image.getWidth() + "x" + image.getHeight();
                csv.append(csv(now)).append(',')
                        .append(csv(probe.camera.getName())).append(',')
                        .append(csv(String.valueOf(probe.camera.getLooking()))).append(',')
                        .append(csv(probe.camera.getClass().getSimpleName())).append(',')
                        .append(csv(resolution)).append(',')
                        .append(csv(describeFormat(probe.camera))).append(',')
                        .append(formatNumber(probe.targetFps)).append(',')
                        .append(formatNumber(snapshot.captureFps)).append(',')
                        .append(formatNumber(snapshot.displayFps)).append(',')
                        .append(formatNumber(snapshot.rawCaptureAverageMs)).append(',')
                        .append(formatNumber(snapshot.captureAverageMs)).append(',')
                        .append(formatNumber(snapshot.processingAverageMs)).append(',')
                        .append(formatNumber(snapshot.previewLoopAverageMs)).append(',')
                        .append(formatNumber(snapshot.renderAverageMs)).append(',')
                        .append(formatNumber(snapshot.frameIntervalAverageMs)).append(',')
                        .append(formatNumber(snapshot.frameIntervalJitterMs)).append(',')
                        .append(formatNumber(snapshot.maximumStallMs)).append(',')
                        .append(snapshot.receivedFrames).append(',')
                        .append(snapshot.displayedFrames).append(',')
                        .append(snapshot.droppedFrames).append(',')
                        .append(snapshot.duplicateFrames).append(',')
                        .append(csv(describeExposure(probe.camera))).append(',')
                        .append(csv(describeGain(probe.camera))).append(',')
                        .append(csv(describeLight(probe.camera))).append(',')
                        .append("false,")
                        .append(snapshot.elapsedMillis).append(',')
                        .append(csv(probe.error == null ? "" : probe.error)).append('\n');
            }
            Files.write(file.toPath(),
                    ("\ufeff" + csv).getBytes(StandardCharsets.UTF_8));
            state.setText("诊断报告已导出：" + file.getAbsolutePath());
        }
        catch (Exception error) {
            UiUtils.showError(this, "导出相机诊断失败", error);
        }
    }

    private static String describeFormat(Camera camera) {
        try {
            if (camera instanceof OpenPnpCaptureCamera) {
                String format = ((OpenPnpCaptureCamera) camera).getFormatName();
                return format == null ? "OpenPnP Capture（格式未选择）" : format;
            }
            if (camera instanceof OpenCvCamera) {
                OpenCvCamera openCv = (OpenCvCamera) camera;
                int code = (int) openCv.getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_FOURCC);
                String fourcc = fourCc(code);
                double nativeFps = openCv.getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_FPS);
                return (fourcc.isEmpty() ? "OpenCV / 驱动未报告 FOURCC"
                        : "OpenCV / " + fourcc)
                        + (nativeFps > 0 ? " / " + formatNumber(nativeFps) + " FPS" : "");
            }
        }
        catch (Exception ignored) {
        }
        return camera.getClass().getSimpleName() + "（驱动未公开像素格式）";
    }

    private static String describeExposure(Camera camera) {
        try {
            if (camera instanceof OpenPnpCaptureCamera) {
                return describeHolder(((OpenPnpCaptureCamera) camera).getExposure());
            }
            if (camera instanceof OpenCvCamera) {
                OpenCvCamera openCv = (OpenCvCamera) camera;
                double value = openCv.getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_EXPOSURE);
                double auto = openCv.getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_AUTO_EXPOSURE);
                return formatNumber(value) + "（自动标志 " + formatNumber(auto) + "）";
            }
        }
        catch (Exception ignored) {
        }
        return "驱动未公开";
    }

    private static String describeGain(Camera camera) {
        try {
            if (camera instanceof OpenPnpCaptureCamera) {
                return describeHolder(((OpenPnpCaptureCamera) camera).getGain());
            }
            if (camera instanceof OpenCvCamera) {
                return formatNumber(((OpenCvCamera) camera).getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_GAIN));
            }
        }
        catch (Exception ignored) {
        }
        return "驱动未公开";
    }

    private static String describeBrightness(Camera camera) {
        try {
            if (camera instanceof OpenPnpCaptureCamera) {
                return describeHolder(((OpenPnpCaptureCamera) camera).getBrightness());
            }
            if (camera instanceof OpenCvCamera) {
                return formatNumber(((OpenCvCamera) camera).getOpenCvCapturePropertyValue(
                        OpenCvCaptureProperty.CAP_PROP_BRIGHTNESS));
            }
        }
        catch (Exception ignored) {
        }
        return "驱动未公开";
    }

    private static String describeHolder(CapturePropertyHolder holder) {
        if (holder == null || !holder.isSupported()) {
            return "不支持";
        }
        return holder.getValue() + (holder.isAuto() ? "（自动）" : "（手动）");
    }

    private static String describeLight(Camera camera) {
        Actuator light = camera.getLightActuator();
        if (light == null) {
            return "未配置";
        }
        Boolean actuated = light.isActuated();
        return actuated == null ? "未知" : actuated ? "开" : "关";
    }

    private static String describeTaskSuspension(Camera camera) {
        if (camera instanceof AbstractBroadcastingCamera) {
            return ((AbstractBroadcastingCamera) camera).isSuspendPreviewInTasks()
                    ? "是（机器忙碌时暂停）" : "否";
        }
        return "驱动未公开";
    }

    private static String describeHolderSafe(CapturePropertyHolder holder) {
        try {
            return describeHolder(holder);
        }
        catch (Exception error) {
            return "读取失败";
        }
    }

    private static String fourCc(int code) {
        if (code == 0) {
            return "";
        }
        StringBuilder value = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            char character = (char) ((code >> (index * 8)) & 0xff);
            if (character >= 32 && character <= 126) {
                value.append(character);
            }
        }
        return value.toString();
    }

    private static String formatNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "—";
    }

    private static String formatElapsed(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String csv(String value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private static String shortError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "未知异常" : error.getClass().getSimpleName())
                : message;
    }

    private class Probe {
        final Camera camera;
        final double targetFps;
        final CameraPreviewMetrics metrics = new CameraPreviewMetrics();
        final AtomicReference<BufferedImage> pending = new AtomicReference<>();
        final CameraListener listener = this::receive;
        volatile BufferedImage lastFrame;
        volatile String error;
        volatile double originalFps = Double.NaN;
        volatile long lastCaptureSequence = -1;
        volatile Object originalLightValue;
        volatile boolean lightManaged;
        volatile boolean listenerStarted;

        Probe(Camera camera, double targetFps) {
            this.camera = camera;
            this.targetFps = targetFps;
            Actuator light = camera.getLightActuator();
            if (light != null) {
                originalLightValue = light.getLastActuationValue();
            }
        }

        void open() {
            metrics.reset(System.nanoTime());
            try {
                if (camera instanceof AbstractBroadcastingCamera) {
                    AbstractBroadcastingCamera broadcasting =
                            (AbstractBroadcastingCamera) camera;
                    originalFps = broadcasting.getPreviewFps();
                    lastCaptureSequence = broadcasting.getPreviewCaptureSequence();
                    broadcasting.setPreviewFps(targetFps);
                }
                camera.startContinuousCapture(listener);
                listenerStarted = true;
            }
            catch (Exception openError) {
                error = shortError(openError);
                restoreFps();
                Logger.error(openError);
            }
        }

        void receive(BufferedImage image) {
            if (!sessionRequested || image == null) {
                return;
            }
            long now = System.nanoTime();
            lastFrame = image;
            metrics.recordFrame(image, now);
            if (camera instanceof AbstractBroadcastingCamera) {
                AbstractBroadcastingCamera broadcasting =
                        (AbstractBroadcastingCamera) camera;
                long sequence = broadcasting.getPreviewCaptureSequence();
                if (sequence != lastCaptureSequence) {
                    lastCaptureSequence = sequence;
                    metrics.recordCaptureDuration(
                            broadcasting.getLastPreviewCaptureDurationMillis(), now);
                    metrics.recordPreviewLoopDuration(
                            broadcasting.getLastPreviewLoopDurationMillis(), now);
                    if (camera instanceof ReferenceCamera) {
                        ReferenceCamera reference = (ReferenceCamera) camera;
                        metrics.recordRawCaptureDuration(
                                reference.getLastRawCaptureDurationMillis(), now);
                        metrics.recordProcessingDuration(
                                reference.getLastImageTransformDurationMillis(), now);
                    }
                }
            }
            if (camera == displayCamera) {
                if (pending.getAndSet(image) != null) {
                    metrics.recordDroppedFrame();
                }
            }
        }

        void close() {
            try {
                if (listenerStarted) {
                    camera.stopContinuousCapture(listener);
                }
            }
            catch (Exception closeError) {
                error = shortError(closeError);
                Logger.error(closeError);
            }
            finally {
                listenerStarted = false;
                pending.set(null);
                lastFrame = null;
                restoreFps();
                if (lightManaged) {
                    restoreLight(this);
                }
            }
        }

        private void restoreFps() {
            if (camera instanceof AbstractBroadcastingCamera && Double.isFinite(originalFps)) {
                ((AbstractBroadcastingCamera) camera).setPreviewFps(originalFps);
            }
        }
    }

    private static class RateOption {
        final String label;
        final double fps;

        RateOption(String label, double fps) {
            this.label = label;
            this.fps = fps;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class PreviewSurface extends JPanel {
        private volatile BufferedImage frame;
        private volatile CameraPreviewMetrics metrics;

        PreviewSurface() {
            setOpaque(true);
            setBackground(new Color(20, 23, 27));
        }

        void setFrame(BufferedImage frame) {
            this.frame = frame;
            repaint();
        }

        void setMetrics(CameraPreviewMetrics metrics) {
            this.metrics = metrics;
        }

        void clear() {
            frame = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            long started = System.nanoTime();
            super.paintComponent(graphics);
            BufferedImage image = frame;
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                if (image == null) {
                    g2.setColor(new Color(160, 167, 176));
                    String text = "等待相机实时帧…";
                    FontMetrics metrics = g2.getFontMetrics();
                    g2.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2,
                            getHeight() / 2);
                    return;
                }
                double scale = Math.min(getWidth() / (double) image.getWidth(),
                        getHeight() / (double) image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(image, x, y, width, height, null);
            }
            finally {
                g2.dispose();
                CameraPreviewMetrics current = metrics;
                if (current != null) {
                    current.recordRenderDuration(
                            (System.nanoTime() - started) / 1_000_000.0,
                            System.nanoTime());
                }
            }
        }
    }
}
