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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.opencv.core.Mat;
import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.support.ExperimentalGuide;
import org.openpnp.gui.support.ExperimentalGuide.Step;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.gui.support.PathSelectionDialog.Mode;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.ui.CvPipelineFileIo;
import org.openpnp.vision.pipeline.stages.ActuatorWrite;
import org.openpnp.vision.pipeline.stages.ImageCapture;
import org.openpnp.vision.pipeline.stages.ImageWrite;
import org.openpnp.vision.pipeline.stages.ImageWriteDebug;
import org.openpnp.vision.pipeline.stages.ScriptRun;
import org.openpnp.vision.pipeline.stages.WritePartTemplateImage;
import org.openpnp.vision.smart.AiModelRecommender;
import org.openpnp.vision.smart.AiProfileStore;
import org.openpnp.vision.smart.AiProviderPreset;
import org.openpnp.vision.smart.AiProviderProfile;
import org.openpnp.vision.smart.AiVisionClient;
import org.openpnp.vision.smart.OfflineVisionSample;
import org.openpnp.vision.smart.RoiAnalysis;
import org.openpnp.vision.smart.SmartVisionAnalyzer;
import org.openpnp.vision.smart.SmartVisionDiagnosis;
import org.openpnp.vision.smart.SmartVisionPipelineTuner;
import org.openpnp.vision.smart.SmartVisionScheme;
import org.openpnp.vision.smart.SmartVisionSchemeTransfer;
import org.openpnp.vision.smart.SmartVisionSchemeStore;
import org.openpnp.vision.smart.SmartVisionTemplate;
import org.openpnp.vision.smart.VisionCaptureStore;
import org.openpnp.vision.smart.VisionDatasetAnalysis;
import org.openpnp.vision.smart.VisionDatasetAnalyzer;

/**
 * Context-aware assistant opened from an existing top or bottom vision
 * settings editor. Offline images remain analysis-only; live capture and real
 * tests reuse the prepared native pipeline and its normal machine semantics.
 */
@SuppressWarnings("serial")
public final class SmartVisionStudioDialog extends JDialog {
    public enum VisionContext {
        TOP("顶部视觉", "顶部相机在当前目标位置运行原视觉管线。"),
        BOTTOM("底部视觉", "吸嘴必须携带器件；先移动到下视相机中心，再按器件高度设置 Z，随后运行原 Bottom Vision 管线。"),
        GENERIC("视觉", "使用当前视觉设置和原管线运行。");

        private final String displayName;
        private final String liveSemantics;

        VisionContext(String displayName, String liveSemantics) {
            this.displayName = displayName;
            this.liveSemantics = liveSemantics;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getLiveSemantics() {
            return liveSemantics;
        }
    }

    @FunctionalInterface
    public interface VisionTestAction {
        void run() throws Exception;
    }

    private final MainFrame owner;
    private final VisionContext visionContext;
    private final CvPipeline configuredPipeline;
    private final Camera configuredCamera;
    private final VisionTestAction visionTestAction;
    private volatile boolean livePositionPrepared;
    private final CameraView cameraView = new CameraView();
    private final JPanel imageViewport = new JPanel(new BorderLayout());
    private final JLabel noCamera = new JLabel("未选择相机 · 可点击下方“载入样本图片”进行离线分析",
            JLabel.CENTER);
    private final RoiPreview roiPreview = new RoiPreview();
    private final JLabel imageState = new JLabel("请在画面上拖框，或载入一张样本图像。");
    private final JLabel analysisState = new JLabel("尚未分析 ROI");
    private final JComboBox<SmartVisionTemplate> template =
            new JComboBox<>(SmartVisionTemplate.values());
    private final JTextArea targetDescription = new JTextArea(3, 34);
    private final JSpinner schemeConfidence =
            new JSpinner(new SpinnerNumberModel(85.0, 50.0, 99.5, 0.5));
    private final JCheckBox preserveMasks = new JCheckBox("保留现有蒙版和自定义阶段", true);
    private final DefaultComboBoxModel<SmartVisionScheme> schemeModel = new DefaultComboBoxModel<>();
    private final JComboBox<SmartVisionScheme> schemeCombo = new JComboBox<>(schemeModel);
    private final DefaultTableModel recipeModel = new DefaultTableModel(
            new Object[] { "参数 / 阶段", "建议值", "中文说明" }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 1; }
    };
    private final JTable recipeTable = new JTable(recipeModel);
    private final JTextArea aiResult = textArea();
    private final JTextArea diagnosis = textArea();
    private final JTextArea parameterHelp = textArea();
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final DefaultTableModel datasetModel = new DefaultTableModel(
            new Object[] { "使用", "图片", "来源", "尺寸", "亮度", "对比度" }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
    };
    private final JTable datasetTable = new JTable(datasetModel);
    private final JLabel datasetState = new JLabel("可导入历史图片，或按当前视觉 Pipeline 保存配置相机照片；此页不会自行生成额外运动路径。");
    private final JComboBox<Camera> captureCamera = new JComboBox<>();
    private final JButton importImagesButton = new JButton("导入图片…");
    private final JButton importFolderButton = new JButton("导入文件夹…");
    private final JButton captureStoreButton = new JButton("拍照并存储");
    private final JButton analyzeDatasetButton = new JButton("分析并优化方案");
    private final List<OfflineVisionSample> datasetSamples = new ArrayList<>();
    private final AiProfileStore profileStore = new AiProfileStore();
    private final AiVisionClient aiClient = new AiVisionClient();
    private final DefaultComboBoxModel<AiProviderProfile> profiles = new DefaultComboBoxModel<>();
    private final JComboBox<AiProviderProfile> profileCombo = new JComboBox<>(profiles);
    private final JComboBox<AiProviderPreset> presetCombo =
            new JComboBox<>(AiProviderPreset.values());
    private final JTextField profileName = new JTextField(18);
    private final JTextField baseUrl = new JTextField(30);
    private final JPasswordField apiKey = new JPasswordField(28);
    private final JComboBox<String> model = new JComboBox<>();
    private final JComboBox<String> imageDetail = new JComboBox<>(new String[] { "high", "auto", "low" });
    private final JSpinner apiTimeout = new JSpinner(new SpinnerNumberModel(60, 5, 600, 5));
    private final JLabel apiState = new JLabel("密钥仅保存在当前 Windows 用户配置中，不写入 machine.xml 或方案文件。");
    private final JSpinner brightnessAdjustment = new JSpinner(new SpinnerNumberModel(0, -100, 100, 1));
    private final JSpinner contrastAdjustment = new JSpinner(new SpinnerNumberModel(0, -100, 100, 1));
    private final JSpinner validationSamples = new JSpinner(new SpinnerNumberModel(50, 1, 100000, 1));
    private final JSpinner validationPassed = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1));
    private final JSpinner targetAccuracy = new JSpinner(new SpinnerNumberModel(99.5, 50.0, 100.0, 0.1));
    private final JSpinner totalTimeoutMinutes = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JLabel validationState = new JLabel("尚未开始验证计时");
    private final JButton captureButton = new JButton("① 采集框选 ROI");
    private final JButton buildButton = new JButton("② 自动构建本地方案");
    private final JButton copyToBaselineButton = new JButton();
    private final JButton aiButton = new JButton("发送 ROI，让 AI 复核方案");
    private final JButton guideButton = new JButton("跟着做");
    private BufferedImage roiImage;
    private RoiAnalysis roiAnalysis;
    private boolean offlinePreviewActive;
    private VisionDatasetAnalysis datasetAnalysis;
    private final VisionCaptureStore captureStore;
    private final SmartVisionSchemeStore schemeStore;
    private final List<SmartVisionScheme> schemes = new ArrayList<>();
    private ExperimentalGuide guide;
    private Timer validationTimer;
    private long validationDeadline;

    public SmartVisionStudioDialog(MainFrame owner, String title, VisionContext visionContext,
            CvPipeline configuredPipeline, Camera configuredCamera,
            VisionTestAction visionTestAction, boolean livePositionPrepared) {
        super(owner, title, false);
        this.owner = owner;
        this.visionContext = visionContext == null ? VisionContext.GENERIC : visionContext;
        this.configuredPipeline = configuredPipeline;
        this.configuredCamera = configuredCamera;
        this.visionTestAction = visionTestAction;
        this.livePositionPrepared = livePositionPrepared;
        captureStore = new VisionCaptureStore(Configuration.get().getConfigurationDirectory());
        schemeStore = new SmartVisionSchemeStore(Configuration.get().getConfigurationDirectory(),
                this.visionContext.name().toLowerCase() + "-vision");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildHeader(), BorderLayout.NORTH);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildImagePanel(), buildRightPanel());
        main.setContinuousLayout(true);
        main.setResizeWeight(0.58);
        main.setDividerLocation(0.58);
        add(main, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        configureCamera();
        loadProfiles();
        loadSchemes();
        installActions();
        parameterHelp.setText(buildParameterHelp());
        setMinimumSize(new Dimension(760, 520));
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        setSize(Math.min(1480, Math.max(760, screen.width - 80)),
                Math.min(900, Math.max(520, screen.height - 100)));
        setLocationRelativeTo(owner);
        guide = createGuide();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (validationTimer != null) {
                    validationTimer.stop();
                }
                if (cameraView.getCamera() != null) {
                    cameraView.getCamera().stopContinuousCapture(cameraView);
                }
                cameraView.clearTransientImageState();
                roiPreview.setImage(null);
                roiImage = null;
                roiAnalysis = null;
                datasetSamples.clear();
                if (guide != null) {
                    guide.stop(false);
                }
            }
        });
        SwingUtilities.invokeLater(guide::startOnFirstOpen);
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(10, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        JPanel title = new JPanel();
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(visionContext.getDisplayName() + " · 智能视觉辅助");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 22f));
        title.add(name);
        title.add(new JLabel("框选目标并描述用途可生成新方案；旧 Pipeline 作为历史方案完整保留，可随时恢复"));
        panel.add(title, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JLabel safe = new JLabel(visionContext == VisionContext.BOTTOM && !livePositionPrepared
                ? "● 离线分析模式 · 实时采样尚未定位"
                : "● 已融合到当前" + visionContext.getDisplayName() + "配置");
        safe.setToolTipText(visionContext.getLiveSemantics()
                + " 离线图片分析不会移动机器，也不会替代实时识别。");
        actions.add(safe);
        actions.add(guideButton);
        if (visionTestAction != null) {
            JButton realTest = new JButton(visionContext == VisionContext.BOTTOM
                    ? "按原 Bottom Vision 流程测试" : "按原顶部视觉流程测试");
            realTest.setToolTipText(visionContext.getLiveSemantics()
                    + " 这是原有真机测试入口，会执行机器运动，请先确认机器已启用、归位且路径安全。");
            realTest.addActionListener(event -> runNativeVisionTest());
            actions.add(realTest);
        }
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildImagePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("1 · 框选识别目标"));
        cameraView.setSelectionEnabled(true);
        cameraView.setShowImageInfo(true);
        imageViewport.add(noCamera, BorderLayout.CENTER);
        panel.add(imageViewport, BorderLayout.CENTER);
        JPanel controls = new JPanel(new BorderLayout(4, 4));
        JPanel imageActions = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton load = new JButton("导入历史图片…");
        load.setToolTipText("可一次导入多张历史图片进行离线优化；不会访问相机或移动机器。");
        load.addActionListener(event -> loadImage());
        imageActions.add(captureButton);
        imageActions.add(load);
        JButton sync = new JButton("恢复当前配置相机");
        sync.setToolTipText("恢复本视觉设置实际使用的相机，不跟随主界面临时选中的其他相机。");
        sync.addActionListener(event -> configureCamera());
        imageActions.add(sync);
        controls.add(imageActions, BorderLayout.NORTH);
        imageState.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        controls.add(imageState, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildRightPanel() {
        rightTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        rightTabs.addTab("智能模板", buildTemplatePanel());
        rightTabs.addTab("离线样本与拍照", buildDatasetPanel());
        rightTabs.addTab("AI 账户与模型", buildAiPanel());
        rightTabs.addTab("验证与诊断", buildValidationPanel());
        rightTabs.addTab("原参数中文说明", new JScrollPane(parameterHelp));
        return rightTabs;
    }

    private JPanel buildDatasetPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel explanation = new JLabel("历史图片用于离线优化：软件只读取图像，不访问驱动、不移动贴装头。相机照片按日期自动归档。");
        explanation.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        top.add(explanation);
        captureCamera.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Camera) {
                    Camera camera = (Camera) value;
                    setText(cameraPosition(camera) + " · " + camera.getName());
                }
                return this;
            }
        });
        captureCamera.setToolTipText("仅列出当前视觉设置实际使用的相机，避免把其他相机照片误当作本设置样本。");
        captureStoreButton.setToolTipText("运行当前视觉 Pipeline 的灯光、settle 与 ImageCapture，再把原始帧保存为 PNG；不会另行移动贴装头。");
        top.add(row("拍照相机", captureCamera, captureStoreButton));
        panel.add(top, BorderLayout.NORTH);

        datasetTable.setRowHeight(Math.max(24, datasetTable.getRowHeight()));
        datasetTable.setAutoCreateRowSorter(true);
        datasetTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        datasetTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        datasetTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        panel.add(new JScrollPane(datasetTable), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        importImagesButton.setToolTipText("可多选 PNG/JPG/JPEG/BMP；完整图片不常驻内存，适合较旧电脑。");
        importFolderButton.setToolTipText("扫描所选文件夹中的图片，单次最多导入 500 张，避免误选大目录造成卡顿。");
        actions.add(importImagesButton);
        actions.add(importFolderButton);
        actions.add(button("移除所选", event -> removeSelectedSamples()));
        actions.add(button("清空列表", event -> clearSamples()));
        actions.add(analyzeDatasetButton);
        JButton testOfflinePipeline = new JButton("用当前 Pipeline 离线验证");
        testOfflinePipeline.setToolTipText("把选中的历史图片作为 ImageCapture 输入运行当前视觉 Pipeline；不移动机器、不改变管线设置，也不替代真机验证。");
        testOfflinePipeline.addActionListener(event -> runOfflinePipeline());
        actions.add(testOfflinePipeline);
        bottom.add(actions, BorderLayout.NORTH);
        datasetState.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bottom.add(datasetState, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildTemplatePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        copyToBaselineButton.setText("复制到当前" + visionContext.getDisplayName() + "通道");
        copyToBaselineButton.addActionListener(event -> copySelectedSchemeToBaseline());
        copyToBaselineButton.setToolTipText("覆盖当前基准视觉 Pipeline 前自动建立一份带时间的备份；备份会显示在本列表中。");
        JPanel schemesRow = row("识别方案", schemeCombo, copyToBaselineButton);
        schemeCombo.setToolTipText("● 表示当前已复制到基准视觉通道的方案；自动备份和旧方案都可重新选择恢复。");
        header.add(schemesRow);
        JButton importXml = button("导入 Pipeline XML…", event -> importPipelineXml());
        importXml.setToolTipText("导入格式与原生视觉 Pipeline 编辑器一致；先加入方案列表，不会立即覆盖基准通道。");
        JButton exportXml = button("导出所选 Pipeline XML…", event -> exportSelectedPipelineXml());
        exportXml.setToolTipText("导出的 XML 可直接在原生顶部/底部视觉 Pipeline 编辑器中导入。");
        header.add(row("方案文件", importXml, exportXml,
                new JLabel("与原生视觉通道统一使用 XML")));
        targetDescription.setLineWrap(true);
        targetDescription.setWrapStyleWord(true);
        targetDescription.setText(visionContext == VisionContext.BOTTOM
                ? "识别吸嘴上器件的本体和引脚，忽略吸嘴、背景与反光"
                : "识别框选的器件、焊盘或基准点，忽略框外背景");
        targetDescription.setToolTipText("请说明要识别什么，以及需要忽略的孔、丝印、反光或相邻物体。");
        JPanel descriptionRow = new JPanel(new BorderLayout(6, 3));
        descriptionRow.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        descriptionRow.add(new JLabel("识别目标描述"), BorderLayout.WEST);
        descriptionRow.add(new JScrollPane(targetDescription), BorderLayout.CENTER);
        header.add(descriptionRow);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("目标类型："));
        template.setToolTipText("可手动指定；选择“自动判断”时会结合文字描述和 ROI 图像判断。");
        top.add(template);
        top.add(new JLabel("最低置信度（%）："));
        top.add(schemeConfidence);
        preserveMasks.setEnabled(false);
        preserveMasks.setToolTipText("为保护原有配置，新方案始终保留所有蒙版和自定义阶段。");
        top.add(preserveMasks);
        JButton oneClick = new JButton("框选 + 描述：生成并复制到基准通道");
        oneClick.setToolTipText("生成新方案并直接复制到当前基准视觉通道；覆盖前自动备份旧 Pipeline。");
        oneClick.addActionListener(event -> generateAndActivateScheme());
        top.add(oneClick);
        top.add(buildButton);
        header.add(top);
        analysisState.setBorder(BorderFactory.createEmptyBorder(2, 6, 5, 6));
        header.add(analysisState);
        panel.add(header, BorderLayout.NORTH);
        recipeTable.setRowHeight(Math.max(24, recipeTable.getRowHeight()));
        recipeTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        recipeTable.getColumnModel().getColumn(1).setPreferredWidth(250);
        recipeTable.getColumnModel().getColumn(2).setPreferredWidth(380);
        panel.add(new JScrollPane(recipeTable), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bottom.add(new JLabel("软件亮度建议："));
        brightnessAdjustment.setToolTipText("仅作为 Pipeline 候选值；优先调整物理灯光/曝光，避免放大噪声。");
        bottom.add(brightnessAdjustment);
        bottom.add(new JLabel("软件对比度建议："));
        contrastAdjustment.setToolTipText("正值增强、负值减弱；最终必须用独立样本验证误检与漏检。");
        bottom.add(contrastAdjustment);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAiPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(6, 6));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        form.add(row("账户配置", profileCombo, button("新建", event -> newProfile()),
                button("删除", event -> deleteProfile())));
        form.add(row("配置名称", profileName));
        form.add(row("服务预设", presetCombo));
        form.add(row("API Base URL", baseUrl));
        form.add(row("API 密钥", apiKey, button("清除密钥", event -> clearKey())));
        model.setEditable(true);
        form.add(row("视觉模型", model, button("刷新模型", event -> refreshModels()),
                button("推荐", event -> recommendModel())));
        form.add(row("图像细节", imageDetail, new JLabel("请求超时（秒）"), apiTimeout));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton save = new JButton("保存账户与模型");
        save.addActionListener(event -> saveProfile());
        aiButton.setToolTipText("仅发送当前预览的代表图和方案/样本集统计；不会批量上传历史图片、machine.xml 或作业。");
        buttons.add(save);
        buttons.add(aiButton);
        form.add(buttons);
        apiState.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        wrapper.add(new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.NORTH);
        wrapper.add(new JScrollPane(aiResult), BorderLayout.CENTER);
        wrapper.add(apiState, BorderLayout.SOUTH);
        return wrapper;
    }

    private JPanel buildValidationPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        form.add(row("验证样本总数", validationSamples, new JLabel("识别正确数"), validationPassed));
        form.add(row("目标准确率（%）", targetAccuracy, new JLabel("总超时（分钟）"), totalTimeoutMinutes));
        JCheckBox edgePlan = new JCheckBox("包含视野中心/四角/四边的边缘覆盖计划", true);
        edgePlan.setToolTipText("只生成采样位置清单，不会自动移动贴装头；请在 3D 安全工作台预演后人工执行。");
        form.add(edgePlan);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton start = new JButton("开始限时验证");
        start.addActionListener(event -> startValidation());
        JButton evaluate = new JButton("立即评估并分析原因");
        evaluate.addActionListener(event -> evaluateValidation(false));
        JButton plan = new JButton("生成边缘视野安全计划");
        plan.addActionListener(event -> buildEdgePlan());
        actions.add(start);
        actions.add(evaluate);
        actions.add(plan);
        form.add(actions);
        form.add(validationState);
        panel.add(form, BorderLayout.NORTH);
        panel.add(new JScrollPane(diagnosis), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(2, 12, 8, 12));
        footer.add(new JLabel("准确率必须来自独立验证集；AI 建议、图像质量评分和训练集结果都不等于生产准确率。"),
                BorderLayout.WEST);
        JButton close = new JButton("关闭");
        close.addActionListener(event -> dispose());
        footer.add(close, BorderLayout.EAST);
        return footer;
    }

    private void installActions() {
        captureButton.addActionListener(event -> captureRoi());
        buildButton.addActionListener(event -> buildLocalRecipe());
        aiButton.addActionListener(event -> askAi());
        guideButton.addActionListener(event -> guide.start());
        importImagesButton.addActionListener(event -> loadImage());
        importFolderButton.addActionListener(event -> loadImageFolder());
        captureStoreButton.addActionListener(event -> captureAndStore());
        analyzeDatasetButton.addActionListener(event -> analyzeDataset());
        datasetModel.addTableModelListener(event -> {
            int row = event.getFirstRow();
            if (event.getColumn() == 0 && row >= 0 && row < datasetSamples.size()) {
                datasetSamples.get(row).setIncluded(Boolean.TRUE.equals(datasetModel.getValueAt(row, 0)));
                datasetAnalysis = null;
                datasetState.setText("样本选择已变化，请重新点击“分析并优化方案”。");
            }
        });
        datasetTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    showSelectedSample();
                }
            }
        });
        presetCombo.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                AiProviderPreset selected = (AiProviderPreset) presetCombo.getSelectedItem();
                if (selected != null && !selected.getDefaultBaseUrl().isBlank()) {
                    baseUrl.setText(selected.getDefaultBaseUrl());
                }
            }
        });
        profileCombo.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                showProfile((AiProviderProfile) event.getItem());
            }
        });
    }

    private void configureCamera() {
        Camera camera = configuredCamera;
        Camera captureSelection = (Camera) captureCamera.getSelectedItem();
        captureCamera.removeAllItems();
        if (camera != null) {
            captureCamera.addItem(camera);
        }
        if (camera != null) {
            captureCamera.setSelectedItem(camera);
        }
        else if (captureSelection != null) {
            captureCamera.setSelectedItem(captureSelection);
        }
        captureStoreButton.setEnabled(captureCamera.getItemCount() > 0);
        imageViewport.removeAll();
        offlinePreviewActive = false;
        if (camera != null) {
            cameraView.setCamera(camera);
            imageViewport.add(cameraView, BorderLayout.CENTER);
        }
        else {
            imageViewport.add(noCamera, BorderLayout.CENTER);
        }
        imageViewport.revalidate();
        imageViewport.repaint();
        imageState.setText(camera == null ? "当前视觉设置没有可用相机，可载入离线图片。"
                : "当前配置相机：" + camera.getName() + "；" + visionContext.getLiveSemantics());
    }

    private void runNativeVisionTest() {
        if (visionTestAction == null) {
            return;
        }
        String message = visionContext.getLiveSemantics()
                + "\n\n该操作会执行真实机器运动与视觉识别。请确认机器已启用并归位、吸嘴和器件状态正确、运动区域无人和障碍物。是否继续？";
        int result = JOptionPane.showConfirmDialog(this, message,
                "运行原生" + visionContext.getDisplayName() + "测试",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        imageState.setText("正在按原生" + visionContext.getDisplayName() + "逻辑测试…");
        UiUtils.submitUiMachineTask(() -> {
            visionTestAction.run();
            livePositionPrepared = true;
            SwingUtilities.invokeLater(() -> imageState.setText(
                    "原生" + visionContext.getDisplayName() + "测试完成；结果由原视觉流程显示，实时采样位置已就绪。"));
        });
    }

    private void captureRoi() {
        if (offlinePreviewActive) {
            Rectangle selection = roiPreview.getSelection();
            if (selection == null || selection.width < 2 || selection.height < 2) {
                showWarning("尚未框选", "请在离线图片上拖出一个矩形，只包含待识别目标并保留少量背景。");
                return;
            }
            acceptImage(crop(roiPreview.getImage(), selection), true);
            imageState.setText("已从离线历史图片裁取 ROI：" + roiImage.getWidth() + "×" + roiImage.getHeight());
            return;
        }
        if (visionContext == VisionContext.BOTTOM && !livePositionPrepared) {
            showWarning("底部视觉尚未定位",
                    "当前只允许离线图片分析。实时底部视觉必须让吸嘴携带器件，安全移动到底部相机中心，并按器件高度设置 Z。\n"
                            + "请关闭后从底部视觉配置重新进入并选择移动，或点击顶部的原 Bottom Vision 测试。");
            return;
        }
        Camera camera = cameraView.getCamera();
        if (camera == null) {
            showWarning("没有相机", "当前视觉设置没有可用相机，可改用离线图片分析。");
            return;
        }
        Rectangle selection = cameraView.getSelection();
        if (selection == null || selection.width < 2 || selection.height < 2) {
            showWarning("尚未框选", "请在相机画面中拖出一个矩形，只包含待识别目标并保留少量背景。");
            return;
        }
        captureButton.setEnabled(false);
        imageState.setText("正在运行当前" + visionContext.getDisplayName()
                + " Pipeline，并从其 ImageCapture 帧裁取 ROI…");
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                BufferedImage image;
                if (configuredPipeline != null) {
                    synchronized (configuredPipeline) {
                        configuredPipeline.process();
                        image = configuredPipeline.getLastCapturedImage();
                        if (image == null) {
                            throw new Exception("当前 Pipeline 没有产生 ImageCapture 原始帧。请检查采集阶段是否启用。");
                        }
                    }
                }
                else {
                    image = camera.lightSettleAndCapture();
                }
                return crop(image, selection);
            }
            @Override protected void done() {
                captureButton.setEnabled(true);
                try {
                    acceptImage(get(), false);
                }
                catch (Exception error) {
                    showError("采集失败", error);
                }
            }
        }.execute();
    }

    private void loadImage() {
        File[] files = PathSelectionDialog.chooseOpen(this, "smart-vision-images",
                "导入历史视觉图片", Mode.FILES, true,
                Configuration.get().getConfigurationDirectory(),
                new FileNameExtensionFilter("视觉图片 (*.png, *.jpg, *.jpeg, *.bmp)",
                        "png", "jpg", "jpeg", "bmp"));
        if (files == null) {
            return;
        }
        importSamples(Arrays.asList(files), OfflineVisionSample.Source.IMPORTED);
    }

    private void loadImageFolder() {
        File[] selected = PathSelectionDialog.chooseOpen(this, "smart-vision-image-folder",
                "导入历史视觉图片目录", Mode.DIRECTORIES, false,
                Configuration.get().getConfigurationDirectory(), null);
        if (selected == null) {
            return;
        }
        List<File> files = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(selected[0].toPath())) {
            paths.filter(Files::isRegularFile).map(java.nio.file.Path::toFile)
                    .filter(SmartVisionStudioDialog::isImageFile).limit(500).forEach(files::add);
        }
        catch (Exception error) {
            showError("读取文件夹失败", error);
            return;
        }
        if (files.isEmpty()) {
            showWarning("没有图片", "所选文件夹中没有 PNG、JPG、JPEG 或 BMP 图片。");
            return;
        }
        importSamples(files, OfflineVisionSample.Source.IMPORTED);
    }

    private void importSamples(List<File> files, OfflineVisionSample.Source source) {
        List<File> filtered = new ArrayList<>();
        for (File file : files) {
            if (file != null && isImageFile(file) && !containsSample(file) && filtered.size() < 500) {
                filtered.add(file);
            }
        }
        if (filtered.isEmpty()) {
            datasetState.setText("没有新增图片：可能已导入或格式不受支持。");
            return;
        }
        setDatasetBusy(true, "正在后台分析 " + filtered.size() + " 张图片…");
        new SwingWorker<DatasetImportResult, Void>() {
            @Override protected DatasetImportResult doInBackground() {
                DatasetImportResult result = new DatasetImportResult();
                for (File file : filtered) {
                    try {
                        result.samples.add(OfflineVisionSample.analyze(file, source));
                    }
                    catch (Exception error) {
                        result.failures.add(file.getName() + "：" + error.getMessage());
                    }
                }
                return result;
            }
            @Override protected void done() {
                setDatasetBusy(false, "");
                try {
                    DatasetImportResult result = get();
                    for (OfflineVisionSample sample : result.samples) {
                        addSample(sample);
                    }
                    datasetAnalysis = null;
                    datasetState.setText("已新增 " + result.samples.size() + " 张，列表共 "
                            + datasetSamples.size() + " 张"
                            + (result.failures.isEmpty() ? "。双击图片可查看。"
                                    : "；失败 " + result.failures.size() + " 张。"));
                    if (!result.samples.isEmpty()) {
                        showSample(result.samples.get(0));
                    }
                    if (!result.failures.isEmpty()) {
                        diagnosis.setText("以下图片未导入：\n• " + String.join("\n• ", result.failures));
                    }
                }
                catch (Exception error) {
                    showError("导入图片失败", error);
                }
            }
        }.execute();
    }

    private void acceptImage(BufferedImage image, boolean offlinePreview) {
        roiImage = image;
        roiAnalysis = SmartVisionAnalyzer.analyze(image);
        if (offlinePreview || cameraView.getCamera() == null) {
            offlinePreviewActive = true;
            roiPreview.setImage(image);
            imageViewport.removeAll();
            imageViewport.add(roiPreview, BorderLayout.CENTER);
            imageViewport.revalidate();
            imageViewport.repaint();
        }
        imageState.setText("已取得 ROI：" + image.getWidth() + "×" + image.getHeight());
        analysisState.setText(roiAnalysis.toChineseSummary());
        buildLocalRecipe();
    }

    private void captureAndStore() {
        Camera camera = (Camera) captureCamera.getSelectedItem();
        if (camera == null) {
            showWarning("没有相机", "当前机器配置中没有可用相机。");
            return;
        }
        if (visionContext == VisionContext.BOTTOM && !livePositionPrepared) {
            showWarning("底部视觉尚未定位",
                    "不能把未按器件高度定位的底部相机画面当作有效 Bottom Vision 样本。可先导入历史图片进行离线分析。");
            return;
        }
        captureStoreButton.setEnabled(false);
        datasetState.setText("正在按当前 Pipeline 读取并保存 " + cameraPosition(camera) + "画面；不会生成额外运动…");
        new SwingWorker<VisionCaptureStore.CaptureResult, Void>() {
            @Override protected VisionCaptureStore.CaptureResult doInBackground() throws Exception {
                BufferedImage image;
                if (configuredPipeline != null && camera == configuredCamera) {
                    synchronized (configuredPipeline) {
                        configuredPipeline.process();
                        image = configuredPipeline.getLastCapturedImage();
                        if (image == null) {
                            throw new Exception("当前 Pipeline 没有产生 ImageCapture 原始帧。");
                        }
                    }
                }
                else {
                    image = camera.lightSettleAndCapture();
                }
                return captureStore.save(camera, image);
            }
            @Override protected void done() {
                captureStoreButton.setEnabled(true);
                try {
                    VisionCaptureStore.CaptureResult result = get();
                    OfflineVisionSample sample = OfflineVisionSample.analyze(result.getFile(),
                            result.getPosition().getSampleSource());
                    addSample(sample);
                    datasetAnalysis = null;
                    acceptImage(result.getImage(), true);
                    datasetState.setText(result.getPosition().getDisplayName() + "照片已保存："
                            + result.getFile().getAbsolutePath());
                }
                catch (Exception error) {
                    showError("拍照存储失败", error);
                }
            }
        }.execute();
    }

    private void analyzeDataset() {
        try {
            datasetAnalysis = VisionDatasetAnalyzer.analyze(datasetSamples);
            roiAnalysis = datasetAnalysis.getRepresentative();
            analysisState.setText(datasetAnalysis.toChineseSummary());
            buildLocalRecipe();
            diagnosis.setText(datasetAnalysis.buildAdvice()
                    + "\n\n已用勾选样本的平均特征生成候选参数；完整图片仍保留在磁盘，不会常驻内存。"
                    + "\nAI 复核只发送当前预览的代表图片与本批统计，不会批量上传全部历史图片。");
            datasetState.setText("离线样本分析完成：" + datasetAnalysis.toChineseSummary());
            rightTabs.setSelectedIndex(0);
        }
        catch (Exception error) {
            showError("样本集分析失败", error);
        }
    }

    private void runOfflinePipeline() {
        if (configuredPipeline == null) {
            showWarning("没有当前 Pipeline", "请从顶部视觉或底部视觉配置中的“智能视觉辅助”进入。");
            return;
        }
        OfflineVisionSample selectedSample = null;
        int viewRow = datasetTable.getSelectedRow();
        if (viewRow >= 0) {
            selectedSample = datasetSamples.get(datasetTable.convertRowIndexToModel(viewRow));
        }
        final OfflineVisionSample sample = selectedSample;
        final BufferedImage currentImage = sample == null ? roiImage : null;
        if (sample == null && currentImage == null) {
            showWarning("没有离线图片", "请先导入并选中一张历史图片，或载入一张离线图片。");
            return;
        }
        setDatasetBusy(true, "正在用当前 " + visionContext.getDisplayName()
                + " Pipeline 做离线单帧验证；不会移动机器…");
        new SwingWorker<OfflinePipelineResult, Void>() {
            @Override protected OfflinePipelineResult doInBackground() throws Exception {
                BufferedImage input = sample == null ? currentImage : sample.loadImage();
                synchronized (configuredPipeline) {
                    int shotCount = configuredPipeline.getPipelineShotsCount();
                    try (CvPipeline offlinePipeline = configuredPipeline.clone()) {
                        configuredPipeline.copyPropertiesTo(offlinePipeline);
                        offlinePipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY, input);
                        for (CvStage stage : offlinePipeline.getStages()) {
                            if (isOfflineSideEffectStage(stage)) {
                                stage.setEnabled(false);
                            }
                        }
                        offlinePipeline.process();
                        Mat working = offlinePipeline.getWorkingImage();
                        if (working == null) {
                            throw new Exception("Pipeline 没有产生输出图像。");
                        }
                        Mat rgb = OpenCvUtils.toRGB(working.clone(),
                                offlinePipeline.getWorkingColorSpace());
                        try {
                            OfflinePipelineResult result = new OfflinePipelineResult();
                            result.image = OpenCvUtils.toBufferedImage(rgb);
                            result.model = String.valueOf(offlinePipeline.getWorkingModel());
                            result.processingMs = offlinePipeline.getTotalProcessingTimeNs() / 1_000_000.0;
                            result.shotCount = shotCount;
                            return result;
                        }
                        finally {
                            rgb.release();
                        }
                    }
                }
            }

            @Override protected void done() {
                setDatasetBusy(false, "");
                try {
                    OfflinePipelineResult result = get();
                    offlinePreviewActive = true;
                    roiPreview.setImage(result.image);
                    imageViewport.removeAll();
                    imageViewport.add(roiPreview, BorderLayout.CENTER);
                    imageViewport.revalidate();
                    imageViewport.repaint();
                    String multiShot = result.shotCount > 1
                            ? "\n注意：当前 Pipeline 有 " + result.shotCount
                                    + " 个拍摄点，本次仅验证当前单张离线图，不能替代多拍摄点合成和真机运动。"
                            : "";
                    diagnosis.setText("当前 " + visionContext.getDisplayName()
                            + " Pipeline 离线验证完成。\n处理耗时："
                            + String.format("%.2f ms", result.processingMs)
                            + "\n最终模型：" + result.model + multiShot
                            + "\n\n该结果只说明历史图片能否通过当前算法，不验证相机位置、器件高度 Z、灯光、settle 或机器重复精度。");
                    datasetState.setText("离线 Pipeline 验证完成；输出图已显示，原 Pipeline 配置未改动。");
                    rightTabs.setSelectedIndex(3);
                }
                catch (Exception error) {
                    showError("离线 Pipeline 验证失败", error);
                }
            }
        }.execute();
    }

    private void addSample(OfflineVisionSample sample) {
        datasetSamples.add(sample);
        RoiAnalysis analysis = sample.getAnalysis();
        datasetModel.addRow(new Object[] { Boolean.TRUE, sample.getFile().getName(),
                sample.getSource().getDisplayName(), sample.getWidth() + "×" + sample.getHeight(),
                String.format("%.1f", analysis.getBrightness()), String.format("%.1f", analysis.getContrast()) });
    }

    private void showSelectedSample() {
        int viewRow = datasetTable.getSelectedRow();
        if (viewRow >= 0) {
            showSample(datasetSamples.get(datasetTable.convertRowIndexToModel(viewRow)));
        }
    }

    private void showSample(OfflineVisionSample sample) {
        datasetState.setText("正在读取 " + sample.getFile().getName() + "…");
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception { return sample.loadImage(); }
            @Override protected void done() {
                try {
                    acceptImage(get(), true);
                    datasetState.setText("当前预览：" + sample.getFile().getAbsolutePath());
                }
                catch (Exception error) {
                    showError("读取样本失败", error);
                }
            }
        }.execute();
    }

    private void removeSelectedSamples() {
        int[] rows = datasetTable.getSelectedRows();
        int[] models = Arrays.stream(rows).map(datasetTable::convertRowIndexToModel).sorted().toArray();
        for (int index = models.length - 1; index >= 0; index--) {
            datasetSamples.remove(models[index]);
            datasetModel.removeRow(models[index]);
        }
        datasetAnalysis = null;
        datasetState.setText("已从列表移除；磁盘上的原图和拍照归档均未删除。");
    }

    private void clearSamples() {
        datasetSamples.clear();
        datasetModel.setRowCount(0);
        datasetAnalysis = null;
        datasetState.setText("列表已清空；磁盘图片未删除，可随时重新导入。");
    }

    private void setDatasetBusy(boolean busy, String message) {
        importImagesButton.setEnabled(!busy);
        importFolderButton.setEnabled(!busy);
        analyzeDatasetButton.setEnabled(!busy);
        if (!message.isEmpty()) {
            datasetState.setText(message);
        }
    }

    private boolean containsSample(File file) {
        try {
            File canonical = file.getCanonicalFile();
            for (OfflineVisionSample sample : datasetSamples) {
                if (sample.getFile().equals(canonical)) {
                    return true;
                }
            }
        }
        catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isOfflineSideEffectStage(CvStage stage) {
        return stage instanceof ActuatorWrite
                || stage instanceof ScriptRun
                || stage instanceof ImageWrite
                || stage instanceof ImageWriteDebug
                || stage instanceof WritePartTemplateImage;
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".bmp");
    }

    private static String cameraPosition(Camera camera) {
        return VisionCaptureStore.classify(camera.getLooking()).getDisplayName();
    }

    private static String snapshotPipeline(CvPipeline pipeline) throws Exception {
        if (pipeline == null) {
            throw new IllegalArgumentException("当前视觉设置没有 Pipeline。");
        }
        return pipeline.getProperty("camera") == null
                ? pipeline.toXmlStringPreservingRuntimeStageValues()
                : pipeline.toXmlString();
    }

    private void buildLocalRecipe() {
        if (roiAnalysis == null) {
            showWarning("缺少样本", "请先采集框选 ROI 或载入样本图片。");
            return;
        }
        SmartVisionTemplate selected = (SmartVisionTemplate) template.getSelectedItem();
        if (selected == SmartVisionTemplate.AUTO) {
            selected = SmartVisionTemplate.choose(targetDescription.getText(), roiAnalysis);
            template.setSelectedItem(selected);
        }
        recipeModel.setRowCount(0);
        recipeModel.addRow(new Object[] { "模板", selected.getDisplayName(), selected.getDescription() });
        for (Map.Entry<String, String> entry : selected.recommendedParameters(roiAnalysis).entrySet()) {
            recipeModel.addRow(new Object[] { entry.getKey(), entry.getValue(), explanation(entry.getKey()) });
        }
        if (roiAnalysis.isTooDark()) {
            brightnessAdjustment.setValue(20);
        }
        else if (roiAnalysis.isTooBright()) {
            brightnessAdjustment.setValue(-20);
        }
        else {
            brightnessAdjustment.setValue(0);
        }
        contrastAdjustment.setValue(roiAnalysis.isLowContrast() ? 20 : 0);
        diagnosis.setText("本地候选参数已生成。点击“一键生成并启用”会先保存当前 Pipeline，"
                + "再启用保留全部蒙版和自定义阶段的新版本。\n\n"
                + String.join("\n", SmartVisionDiagnosis.suggestions(roiAnalysis, 0, 0, false)));
    }

    private void loadSchemes() {
        try {
            schemes.clear();
            schemes.addAll(schemeStore.load());
            if (schemes.isEmpty() && configuredPipeline != null) {
                SmartVisionScheme baseline = new SmartVisionScheme();
                baseline.setName("原始识别方案");
                baseline.setDescription("首次打开智能视觉时保存的原 Pipeline。");
                baseline.setTemplate("原 Pipeline");
                baseline.setConfidence(0.85);
                baseline.setMasksPreserved(true);
                baseline.setPipelineXml(snapshotPipeline(configuredPipeline));
                baseline.setActive(true);
                schemes.add(baseline);
                schemeStore.save(schemes);
            }
            refreshSchemeModel();
        }
        catch (Exception error) {
            analysisState.setText("方案历史载入失败：" + error.getMessage());
        }
    }

    private void refreshSchemeModel() {
        SmartVisionScheme selected = (SmartVisionScheme) schemeCombo.getSelectedItem();
        schemeModel.removeAllElements();
        SmartVisionScheme active = null;
        for (SmartVisionScheme scheme : schemes) {
            schemeModel.addElement(scheme);
            if (scheme.isActive()) {
                active = scheme;
            }
        }
        if (selected != null && schemes.contains(selected)) {
            schemeCombo.setSelectedItem(selected);
        }
        else if (active != null) {
            schemeCombo.setSelectedItem(active);
        }
    }

    private void generateAndActivateScheme() {
        try {
            if (configuredPipeline == null) {
                throw new Exception("当前视觉设置没有可替换的 Pipeline。");
            }
            if (roiAnalysis == null || roiImage == null) {
                throw new Exception("请先在相机或历史图片中框选目标，再点击“采集框选 ROI”。");
            }
            String description = targetDescription.getText().trim();
            if (description.isEmpty()) {
                throw new Exception("请用文字描述要识别的器件、焊盘或需要忽略的背景。");
            }
            buildLocalRecipe();
            double confidence = ((Number) schemeConfidence.getValue()).doubleValue() / 100.0;
            CvPipeline tuned = SmartVisionPipelineTuner.tune(configuredPipeline,
                    roiAnalysis, confidence);
            SmartVisionScheme generated = new SmartVisionScheme();
            SmartVisionTemplate selected = (SmartVisionTemplate) template.getSelectedItem();
            generated.setName("智能方案 " + (schemes.size() + 1) + " · " + selected.getDisplayName());
            generated.setDescription(description);
            generated.setTemplate(selected.getDisplayName());
            generated.setConfidence(confidence);
            generated.setMasksPreserved(true);
            generated.setPipelineXml(snapshotPipeline(tuned));
            generated.setActive(false);
            schemes.add(generated);
            SmartVisionScheme backup;
            try {
                backup = copySchemeToBaseline(generated);
            }
            catch (Exception copyError) {
                schemes.remove(generated);
                try {
                    schemeStore.save(schemes);
                }
                catch (Exception ignored) {
                }
                throw copyError;
            }
            refreshSchemeModel();
            schemeCombo.setSelectedItem(generated);
            analysisState.setText("已复制“" + generated.getName() + "”到"
                    + visionContext.getDisplayName() + "通道；旧配置已自动备份。");
            diagnosis.setText("新方案已保存并复制到基准视觉通道。\n自动备份："
                    + backup.getName() + "\n目标：" + description
                    + "\n最低置信度：" + String.format("%.1f%%", confidence * 100.0)
                    + "\n蒙版与自定义阶段：完整保留\n\n请用独立图片或原真机流程验证，"
                    + "如效果不理想可从上方列表一键恢复旧方案。");
        }
        catch (Exception error) {
            showError("生成识别方案失败", error);
        }
    }

    private void copySelectedSchemeToBaseline() {
        SmartVisionScheme selected = (SmartVisionScheme) schemeCombo.getSelectedItem();
        if (selected == null || configuredPipeline == null) {
            showWarning("没有方案", "请先选择要复制到基准视觉通道的识别方案。");
            return;
        }
        try {
            SmartVisionScheme backup = copySchemeToBaseline(selected);
            schemeConfidence.setValue(selected.getConfidence() * 100.0);
            refreshSchemeModel();
            schemeCombo.setSelectedItem(selected);
            analysisState.setText("已复制“" + selected.getName() + "”到"
                    + visionContext.getDisplayName() + "通道；备份为“"
                    + backup.getName() + "”。");
        }
        catch (Exception error) {
            showError("复制到基准视觉通道失败", error);
        }
    }

    private SmartVisionScheme copySchemeToBaseline(SmartVisionScheme selected) throws Exception {
        if (selected == null || configuredPipeline == null) {
            throw new Exception("当前没有可复制的方案或基准视觉 Pipeline。");
        }
        if (selected.getPipelineXml() == null || selected.getPipelineXml().trim().isEmpty()) {
            throw new Exception("所选方案不包含有效的 Pipeline XML。");
        }
        String previousXml = snapshotPipeline(configuredPipeline);
        List<SmartVisionScheme> previousActive = new ArrayList<>();
        for (SmartVisionScheme scheme : schemes) {
            if (scheme.isActive()) {
                previousActive.add(scheme);
            }
        }
        SmartVisionScheme backup = SmartVisionSchemeTransfer.createAutomaticBackup(
                previousXml, visionContext.getDisplayName());
        schemes.add(backup);
        SmartVisionSchemeTransfer.activateExclusive(schemes, selected);
        try {
            // Persist the backup before replacing the native channel.
            schemeStore.save(schemes);
            configuredPipeline.fromXmlString(selected.getPipelineXml());
            if (!owner.saveConfig()) {
                throw new Exception("保存 OpenPnP 配置失败，已恢复原基准视觉 Pipeline。");
            }
            return backup;
        }
        catch (Exception error) {
            configuredPipeline.fromXmlString(previousXml);
            schemes.remove(backup);
            for (SmartVisionScheme scheme : schemes) {
                scheme.setActive(previousActive.contains(scheme));
            }
            try {
                schemeStore.save(schemes);
            }
            catch (Exception ignored) {
            }
            throw error;
        }
    }

    private void askAi() {
        if (roiImage == null || roiAnalysis == null) {
            showWarning("缺少 ROI", "请先框选并采集目标图像。");
            return;
        }
        saveProfile();
        AiProviderProfile selected = (AiProviderProfile) profileCombo.getSelectedItem();
        String key = profileStore.loadApiKey(selected);
        aiButton.setEnabled(false);
        apiState.setText("正在调用 " + selected + "；仅发送框选 ROI…");
        String prompt = buildAiPrompt();
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return aiClient.analyzeImage(selected, key, roiImage, prompt);
            }
            @Override protected void done() {
                aiButton.setEnabled(true);
                try {
                    aiResult.setText(get());
                    aiResult.setCaretPosition(0);
                    apiState.setText("AI 复核完成。请人工检查后生成方案，再复制到基准视觉通道。");
                }
                catch (Exception error) {
                    apiState.setText("AI 调用失败，已保留本地方案。");
                    showError("AI 调用失败", error);
                }
            }
        }.execute();
    }

    private String buildAiPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务：为 OpenPnP 框选目标复核并优化传统视觉方案。\n");
        prompt.append("视觉上下文：").append(visionContext.getDisplayName()).append("。")
                .append(visionContext.getLiveSemantics())
                .append(" 离线图片只能用于参数优化，不能改变或替代该真机流程。\n");
        prompt.append(roiAnalysis.toChineseSummary()).append("\n目标模板：")
                .append(template.getSelectedItem()).append("\n");
        prompt.append("用户描述：").append(targetDescription.getText().trim()).append("\n")
                .append("目标最低置信度：").append(schemeConfidence.getValue()).append("%\n")
                .append("必须保留现有蒙版和自定义阶段。\n");
        if (datasetAnalysis != null) {
            prompt.append("离线历史样本统计：").append(datasetAnalysis.toChineseSummary()).append("\n")
                    .append("当前附图只是代表样本，不可假设已看到其余历史图片。\n");
        }
        prompt.append("请输出：1目标特征；2推荐 Pipeline 阶段顺序；3每个关键参数的初值、搜索范围和中文作用；")
                .append("4需要补采的正负样本；5超时或未达 99.5% 时的原因排序。不要声称已验证准确率。\n候选参数：\n");
        for (int row = 0; row < recipeModel.getRowCount(); row++) {
            prompt.append(recipeModel.getValueAt(row, 0)).append(" = ")
                    .append(recipeModel.getValueAt(row, 1)).append('\n');
        }
        return prompt.toString();
    }

    private void loadProfiles() {
        for (AiProviderProfile profile : profileStore.loadProfiles()) {
            profiles.addElement(profile);
        }
        if (profiles.getSize() > 0) {
            profileCombo.setSelectedIndex(0);
            showProfile(profiles.getElementAt(0));
        }
    }

    private void showProfile(AiProviderProfile profile) {
        if (profile == null) {
            return;
        }
        profileName.setText(profile.getName());
        presetCombo.setSelectedItem(profile.getPreset());
        baseUrl.setText(profile.getBaseUrl());
        model.setSelectedItem(profile.getModel());
        imageDetail.setSelectedItem(profile.getImageDetail());
        apiTimeout.setValue(profile.getTimeoutSeconds());
        apiKey.setText(AiProfileStore.maskedKey(profileStore.loadApiKey(profile)));
    }

    private void saveProfile() {
        AiProviderProfile profile = (AiProviderProfile) profileCombo.getSelectedItem();
        if (profile == null) {
            return;
        }
        profile.setName(profileName.getText());
        profile.setPreset((AiProviderPreset) presetCombo.getSelectedItem());
        profile.setBaseUrl(baseUrl.getText());
        Object selectedModel = model.getEditor().getItem();
        profile.setModel(selectedModel == null ? "" : selectedModel.toString());
        profile.setImageDetail((String) imageDetail.getSelectedItem());
        profile.setTimeoutSeconds((Integer) apiTimeout.getValue());
        profileStore.save(profile, new String(apiKey.getPassword()));
        profileCombo.repaint();
        apiKey.setText(AiProfileStore.maskedKey(profileStore.loadApiKey(profile)));
        apiState.setText("已保存账户与模型；密钥不会进入导出方案或日志。");
    }

    private void newProfile() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setName("新 AI 视觉账户");
        profileStore.save(profile, "");
        profiles.addElement(profile);
        profileCombo.setSelectedItem(profile);
    }

    private void deleteProfile() {
        AiProviderProfile profile = (AiProviderProfile) profileCombo.getSelectedItem();
        if (profile == null) {
            return;
        }
        if (profiles.getSize() <= 1) {
            showWarning("至少保留一个配置", "可清除密钥或修改现有配置，不必删除最后一个账户。");
            return;
        }
        profileStore.delete(profile);
        profiles.removeElement(profile);
    }

    private void clearKey() {
        AiProviderProfile profile = (AiProviderProfile) profileCombo.getSelectedItem();
        profileStore.clearApiKey(profile);
        apiKey.setText("");
        apiState.setText("当前账户密钥已清除。");
    }

    private void refreshModels() {
        saveProfile();
        AiProviderProfile profile = (AiProviderProfile) profileCombo.getSelectedItem();
        String key = profileStore.loadApiKey(profile);
        apiState.setText("正在从服务商 /models 读取模型列表…");
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return aiClient.listModels(profile, key);
            }
            @Override protected void done() {
                try {
                    List<String> values = get();
                    String current = profile.getModel();
                    model.removeAllItems();
                    for (String value : values) {
                        model.addItem(value);
                    }
                    model.setSelectedItem(current);
                    apiState.setText("已读取 " + values.size() + " 个模型；“推荐”会优先筛选视觉模型。");
                }
                catch (Exception error) {
                    showError("读取模型失败", error);
                }
            }
        }.execute();
    }

    private void recommendModel() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < model.getItemCount(); index++) {
            ids.add(model.getItemAt(index));
        }
        Optional<String> recommendation = AiModelRecommender.recommend(ids);
        if (recommendation.isPresent()) {
            model.setSelectedItem(recommendation.get());
            apiState.setText("已从当前服务实时模型列表中选择较合适的视觉模型：" + recommendation.get());
        }
        else {
            apiState.setText("当前列表中未识别到明确视觉模型；请按服务商文档选择支持图像输入的模型。");
        }
    }

    private void startValidation() {
        if (validationTimer != null) {
            validationTimer.stop();
        }
        validationDeadline = System.currentTimeMillis() + ((Integer) totalTimeoutMinutes.getValue()) * 60000L;
        validationTimer = new Timer(1000, event -> {
            long remaining = validationDeadline - System.currentTimeMillis();
            if (remaining <= 0) {
                validationTimer.stop();
                evaluateValidation(true);
            }
            else {
                validationState.setText(String.format("验证计时中：剩余 %d:%02d；请持续录入独立样本结果",
                        remaining / 60000, (remaining / 1000) % 60));
            }
        });
        validationTimer.setCoalesce(true);
        validationTimer.start();
        validationState.setText("验证已开始；到时未达标将自动保留最佳结果并分析原因。");
    }

    private void evaluateValidation(boolean timedOut) {
        int samples = (Integer) validationSamples.getValue();
        int passed = Math.min(samples, (Integer) validationPassed.getValue());
        double accuracy = samples == 0 ? 0 : passed / (double) samples;
        double target = ((Double) targetAccuracy.getValue()) / 100.0;
        List<String> suggestions = SmartVisionDiagnosis.suggestions(roiAnalysis, samples, accuracy, timedOut);
        StringBuilder report = new StringBuilder();
        report.append(String.format("独立验证：%d/%d，准确率 %.3f%%，目标 %.3f%%\n",
                passed, samples, accuracy * 100, target * 100));
        report.append(accuracy >= target ? "状态：已达到当前目标；仍需检查漏检、误检和不同批次。\n"
                : "状态：未达到目标，当前方案仅保留为候选。\n");
        if (timedOut) {
            report.append("状态：总超时已触发，未继续无界搜索。\n");
        }
        report.append("\n原因与改进方向：\n• ").append(String.join("\n• ", suggestions));
        diagnosis.setText(report.toString());
        diagnosis.setCaretPosition(0);
        validationState.setText(timedOut ? "验证超时，已自动生成诊断。" : "已完成本次评估。");
    }

    private void buildEdgePlan() {
        diagnosis.setText("边缘视野采样计划（仅计划，不移动机器）：\n"
                + "1. 先在 3D 安全工作台导入相机、吸嘴、飞达与障碍物并完成认证。\n"
                + "2. 在视野中心建立基准样本。\n"
                + "3. 依次采集左上、上、右上、右、右下、下、左下、左九个区域。\n"
                + "4. 每个区域保持同一目标、Z 高度、灯光和旋转角，并记录正确/误检/漏检。\n"
                + "5. 任何真实移动都应回到原生控制面板或 3D 工作台，经人工确认和软限位预演后执行。\n"
                + "6. 若边缘明显变差，优先校准镜头畸变、视场照明均匀性和 Units Per Pixel，而不是盲目放宽阈值。");
    }

    private void importPipelineXml() {
        File[] selectedFiles = PathSelectionDialog.chooseOpen(this, "smart-vision-pipeline-xml",
                "导入 OpenPnP Pipeline XML", Mode.FILES, false,
                Configuration.get().getConfigurationDirectory(),
                new FileNameExtensionFilter("OpenPnP Pipeline XML (*.xml)", "xml"));
        if (selectedFiles == null || selectedFiles.length == 0) {
            return;
        }
        try (CvPipeline imported = CvPipelineFileIo.read(selectedFiles[0])) {
            SmartVisionScheme scheme = new SmartVisionScheme();
            scheme.setName("XML 导入 · " + removeExtension(selectedFiles[0].getName()));
            scheme.setDescription("从原生 OpenPnP Pipeline XML 导入；尚未覆盖基准视觉通道。");
            scheme.setTemplate("原生 Pipeline XML");
            scheme.setConfidence(((Number) schemeConfidence.getValue()).doubleValue() / 100.0);
            scheme.setMasksPreserved(true);
            scheme.setPipelineXml(imported.toXmlStringPreservingRuntimeStageValues());
            scheme.setActive(false);
            schemes.add(scheme);
            schemeStore.save(schemes);
            refreshSchemeModel();
            schemeCombo.setSelectedItem(scheme);
            analysisState.setText("已导入“" + scheme.getName()
                    + "”；确认后点击“复制到当前" + visionContext.getDisplayName() + "通道”。");
        }
        catch (Exception error) {
            showError("导入 Pipeline XML 失败", error);
        }
    }

    private void exportSelectedPipelineXml() {
        SmartVisionScheme selected = (SmartVisionScheme) schemeCombo.getSelectedItem();
        if (selected == null || selected.getPipelineXml() == null) {
            showWarning("没有方案", "请先选择要导出的识别方案。");
            return;
        }
        String suggestedName = safeFileName(selected.getName()) + ".xml";
        File file = PathSelectionDialog.chooseSave(this, "smart-vision-pipeline-xml",
                "导出 OpenPnP Pipeline XML", new File(suggestedName),
                Configuration.get().getConfigurationDirectory(),
                new FileNameExtensionFilter("OpenPnP Pipeline XML (*.xml)", "xml"));
        if (file == null) {
            return;
        }
        try {
            CvPipelineFileIo.writeXml(selected.getPipelineXml(), file);
            File normalized = CvPipelineFileIo.normalizeExportFile(file);
            analysisState.setText("已导出原生兼容 Pipeline XML：" + normalized.getName());
        }
        catch (Exception error) {
            showError("导出 Pipeline XML 失败", error);
        }
    }

    private static String removeExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : String.valueOf(name);
    }

    private static String safeFileName(String value) {
        String safe = value == null ? "smart-vision-pipeline"
                : value.replaceAll("[\\\\/:*?\"<>|]+", "-").trim();
        return safe.isEmpty() ? "smart-vision-pipeline" : safe;
    }

    private ExperimentalGuide createGuide() {
        return new ExperimentalGuide(this, "smart-vision-studio", "智能视觉方案",
                new Step("选择在线或离线入口", "在线框选使用当前视觉设置及其 Pipeline；底部视觉必须先完成吸嘴携件、相机中心和器件高度 Z 定位。离线图片不访问机器。",
                        () -> imageViewport, () -> roiImage != null),
                new Step("采集 ROI", "在实时画面或导入图片上拖框后采集。实时方式运行当前 Pipeline 的灯光、settle 和 ImageCapture；离线方式只读图片。",
                        () -> captureButton, () -> roiImage != null),
                new Step("积累历史样本", "导入图片或选择顶部/底部相机拍照存储。照片按日期归档，列表清空也不会删除原图。",
                        () -> rightTabs, () -> !datasetSamples.isEmpty()),
                new Step("离线批量优化", "勾选有代表性的历史图片后分析。软件在后台计算亮度、对比度和稳定范围，完整图片不常驻内存。",
                        () -> analyzeDatasetButton, () -> datasetAnalysis != null),
                new Step("自动构建本地方案", "先用可解释的传统视觉模板得到参数起点；没有 AI 账号也能完成。",
                        () -> buildButton, () -> recipeModel.getRowCount() > 0),
                new Step("复制到基准视觉通道", "选择方案后复制到当前顶部/底部视觉通道。覆盖前会自动建立带时间的备份，导入导出与原生编辑器统一使用 Pipeline XML。",
                        () -> copyToBaselineButton,
                        () -> schemeCombo.getSelectedItem() instanceof SmartVisionScheme
                                && ((SmartVisionScheme) schemeCombo.getSelectedItem()).isActive()),
                new Step("按需配置 AI", "在 AI 页选择硅基流动或其他 OpenAI 兼容 API，刷新模型后选择支持图像输入的模型。",
                        () -> rightTabs, () -> true),
                new Step("独立验证", "用不同角度、亮度、批次和视野边缘样本记录结果。到达超时会自动停止并分析原因。",
                        () -> validationSamples, () -> true));
    }

    private static JPanel row(String label, Component... fields) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JLabel name = new JLabel(label + "：");
        name.setPreferredSize(new Dimension(118, name.getPreferredSize().height));
        panel.add(name);
        for (Component field : fields) {
            panel.add(field);
        }
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JButton button(String name, java.awt.event.ActionListener listener) {
        JButton button = new JButton(name);
        button.addActionListener(listener);
        return button;
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return area;
    }

    private static BufferedImage crop(BufferedImage source, Rectangle selection) {
        if (source == null) {
            throw new IllegalArgumentException("相机没有返回图像");
        }
        Rectangle bounds = new Rectangle(0, 0, source.getWidth(), source.getHeight());
        Rectangle clipped = selection.intersection(bounds);
        if (clipped.width < 2 || clipped.height < 2) {
            throw new IllegalArgumentException("框选区域超出当前相机图像");
        }
        BufferedImage copy = new BufferedImage(clipped.width, clipped.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, clipped.width, clipped.height,
                clipped.x, clipped.y, clipped.x + clipped.width, clipped.y + clipped.height, null);
        graphics.dispose();
        return copy;
    }

    private static String explanation(String key) {
        if ("预处理".equals(key)) { return "改善光照、噪声和对比度，为后续检测提供稳定输入。"; }
        if ("核心方法".equals(key)) { return "决定用圆、轮廓、模板或 OCR 等何种特征定位目标。"; }
        if ("阈值范围".equals(key)) { return "把前景和背景分开；过宽会误检，过窄会漏检。"; }
        if ("最小置信度".equals(key)) { return "结果低于此分数即判失败，不能用来替代真实验证准确率。"; }
        if ("旋转范围".equals(key)) { return "限定要搜索的角度；越小越快，也越不容易匹配到错误方向。"; }
        if ("ROI 边距".equals(key)) { return "限制搜索区域，减少相邻器件和背景干扰。"; }
        return "候选参数，应用前请在原 Pipeline 编辑器中核对。";
    }

    private static String buildParameterHelp() {
        return "原有 Pipeline 与自定义功能完整保留\n\n"
                + "智能辅助已从当前顶部/底部视觉设置进入，并与当前 Pipeline 使用同一个配置对象。生成或导入方案时不会立即覆盖基准通道；只有点击“复制到当前视觉通道”才会替换，并先在方案列表建立带时间的自动备份。智能方案与原生编辑器统一使用 Pipeline XML 导入导出；关闭本窗口后仍可使用原有编辑、重置、参数控制、复制粘贴、多拍摄点和调试图像。\n\n"
                + "常用参数中文说明\n\n"
                + "enabled：是否启用本阶段；关闭后便于对比该阶段的影响。\n"
                + "name：阶段名称；其他阶段可能按名称读取它的图像或模型结果。\n"
                + "threshold / thresholdMin / thresholdMax：灰度或颜色分割界限，过窄会漏检，过宽会带入背景。\n"
                + "invert：反转前景与背景；目标比背景暗时经常需要。\n"
                + "kernelSize：滤波/形态学核尺寸；更大会去掉更多细节，也可能吞掉小目标。\n"
                + "sigma：高斯模糊强度；降低噪声但会削弱细边缘。\n"
                + "minArea / maxArea：允许的目标面积范围，用于排除灰尘和大块背景。\n"
                + "minWidth / maxWidth / minHeight / maxHeight：目标尺寸约束，单位取决于该阶段定义。\n"
                + "minDiameter / maxDiameter：圆形目标直径范围；应依据相机 UPP 和实物尺寸设置。\n"
                + "minDistance：多个检测结果之间的最小间距，避免同一目标被重复识别。\n"
                + "minScore / thresholdScore：最低匹配置信度；提高可减少误检，但可能增加漏检。\n"
                + "searchWidth / searchHeight：搜索区域大小；越小越快，但必须覆盖实际位置误差。\n"
                + "angle / minAngle / maxAngle：旋转角和搜索范围；按供料方向收窄可明显提速。\n"
                + "margin：目标周围保留边距，过小容易裁掉特征，过大容易混入邻近物。\n"
                + "color / colorSpace：颜色及色彩空间；HSV 通常更适合分离颜色，灰度更适合形状。\n"
                + "propertyName / modelName：从 Pipeline 上下文读取的属性或前序模型名称，拼写必须对应。\n"
                + "diagnostics / debug：输出调试图或中间结果；生产时关闭可减少磁盘和 CPU 开销。\n\n"
                + "提示：原生属性面板仍会显示各阶段 BeanInfo 提供的详细说明；智能工作室只增加中文解释层。";
    }

    private void showWarning(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String title, Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        JOptionPane.showMessageDialog(this, cause.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static final class DatasetImportResult {
        final List<OfflineVisionSample> samples = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }

    private static final class OfflinePipelineResult {
        BufferedImage image;
        String model;
        double processingMs;
        int shotCount;
    }

    private static final class RoiPreview extends JPanel {
        private BufferedImage image;
        private Point dragStart;
        private Rectangle selection;

        RoiPreview() {
            MouseAdapter selectionHandler = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    dragStart = toImagePoint(event.getPoint());
                    selection = null;
                    repaint();
                }

                @Override public void mouseDragged(MouseEvent event) {
                    Point end = toImagePoint(event.getPoint());
                    if (dragStart != null && end != null) {
                        selection = new Rectangle(Math.min(dragStart.x, end.x), Math.min(dragStart.y, end.y),
                                Math.abs(end.x - dragStart.x), Math.abs(end.y - dragStart.y));
                        repaint();
                    }
                }

                @Override public void mouseReleased(MouseEvent event) {
                    mouseDragged(event);
                    dragStart = null;
                }
            };
            addMouseListener(selectionHandler);
            addMouseMotionListener(selectionHandler);
            setToolTipText("拖动鼠标框选离线图片中的识别目标，然后点击“采集框选 ROI”。");
        }

        void setImage(BufferedImage image) {
            this.image = image;
            selection = null;
            repaint();
        }

        BufferedImage getImage() { return image; }
        Rectangle getSelection() { return selection == null ? null : new Rectangle(selection); }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                return;
            }
            double scale = Math.min(getWidth() / (double) image.getWidth(),
                    getHeight() / (double) image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;
            Graphics2D graphics2d = (Graphics2D) graphics.create();
            graphics2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics2d.drawImage(image, x, y, width, height, null);
            if (selection != null) {
                double scaleX = width / (double) image.getWidth();
                double scaleY = height / (double) image.getHeight();
                int selectionX = x + (int) Math.round(selection.x * scaleX);
                int selectionY = y + (int) Math.round(selection.y * scaleY);
                int selectionWidth = (int) Math.round(selection.width * scaleX);
                int selectionHeight = (int) Math.round(selection.height * scaleY);
                graphics2d.setColor(new Color(255, 184, 56));
                graphics2d.drawRect(selectionX, selectionY, selectionWidth, selectionHeight);
                graphics2d.drawRect(selectionX + 1, selectionY + 1,
                        Math.max(0, selectionWidth - 2), Math.max(0, selectionHeight - 2));
            }
            graphics2d.dispose();
        }

        private Point toImagePoint(Point point) {
            if (image == null || getWidth() <= 0 || getHeight() <= 0) {
                return null;
            }
            double scale = Math.min(getWidth() / (double) image.getWidth(),
                    getHeight() / (double) image.getHeight());
            int displayWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int displayHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int offsetX = (getWidth() - displayWidth) / 2;
            int offsetY = (getHeight() - displayHeight) / 2;
            int imageX = (int) Math.floor((point.x - offsetX) / scale);
            int imageY = (int) Math.floor((point.y - offsetY) / scale);
            imageX = Math.max(0, Math.min(image.getWidth() - 1, imageX));
            imageY = Math.max(0, Math.min(image.getHeight() - 1, imageY));
            return new Point(imageX, imageY);
        }
    }
}
