/*
 * Copyright (C) 2022 <mark@makr.zone>
 * inspired and based on work by
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui.components;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.opencv.core.Mat;
import org.openpnp.Translations;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.SmartVisionStudioDialog;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.gui.support.PathSelectionDialog.Mode;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.CvAbstractParameterStage;
import org.openpnp.vision.pipeline.CvAbstractScalarParameterStage;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;
import org.openpnp.vision.pipeline.ui.CvPipelineFileIo;
import org.pmw.tinylog.Logger;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

public abstract class PipelineControls extends JPanel {
    private CvPipeline pipeline;
    private Map<String, Object> pipelineParameterAssignments;

    private JButton btnEdit;
    private JButton btnReset;
    private JButton btnSmartVision;
    private boolean editable = true;
    private boolean resetable = true;
    private boolean smartVisionEnabled;
    private Timer timer;

    public PipelineControls() { 
        rebuildUi();
    }

    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void setPipeline(CvPipeline pipeline) {
        Object oldValue = this.pipeline;
        this.pipeline = pipeline;
        firePropertyChange("pipeline", oldValue, pipeline);
        invokeRebuildUi();
    }

    private void invokeRebuildUi() {
        if (SwingUtilities.isEventDispatchThread()) {
            rebuildUi();
        }
        else {
            SwingUtilities.invokeLater(() -> { 
                rebuildUi();
            });
        }
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        if (btnEdit != null) { 
            btnEdit.setVisible(editable);
        }
    }

    public boolean isResetable() {
        return resetable;
    }

    public void setResetable(boolean resetable) {
        this.resetable = resetable;
        if (btnReset != null) { 
            btnReset.setVisible(resetable);
        }
    }

    public boolean isSmartVisionEnabled() {
        return smartVisionEnabled;
    }

    public void setSmartVisionEnabled(boolean smartVisionEnabled) {
        this.smartVisionEnabled = smartVisionEnabled;
        if (btnSmartVision != null) {
            btnSmartVision.setVisible(smartVisionEnabled);
        }
    }

    public Map<String, Object> getPipelineParameterAssignments() {
        return pipelineParameterAssignments;
    }

    public void setPipelineParameterAssignments(Map<String, Object> pipelineParameterAssignments) {
        this.pipelineParameterAssignments = pipelineParameterAssignments;
        firePropertyChange("pipelineParameterAssignments", null, this.pipelineParameterAssignments);
        invokeRebuildUi();
    }

    @Override
    public void setEnabled(boolean enabled) {
        for (Component comp : getComponents()) { 
            comp.setEnabled(enabled);
        }
        super.setEnabled(enabled);
    }

    /**
     * Override this method to prepare the pipeline properties the same way as it will be done when the pipeline is used
     * for vision operations. Typically a common method should be called, which takes the same pipeline and 
     * pipelineParameterAssignments parameters. 
     * As a minimum the "camera" property must be set, and pipeline.setProperties(pipelineParameterAssignments) should be 
     * called to propagate the parameters. 
     * 
     * @param pipeline 
     * @param pipelineParameterAssignments 
     * @param edit If true, open the Pipeline Editor using {@link #openPipelineEditor}. 
     * @throws Exception
     */
    public abstract void configurePipeline(CvPipeline pipeline, Map<String, Object> pipelineParameterAssignments, boolean edit) throws Exception;

    public abstract Camera getCamera() throws Exception;

    /**
     * Override this method to reset the pipeline to the default.
     * 
     * @throws Exception
     */
    public abstract void resetPipeline() throws Exception;

    /**
     * Open the context-specific smart vision assistant. Vision setting wizards
     * opt in and prepare the pipeline exactly as they do for normal editing.
     */
    public void openSmartVision() throws Exception {
        throw new UnsupportedOperationException("Smart vision is not available for this pipeline.");
    }

    /**
     * Open the Pipeline Editor with all the necessary handling before/after.
     * Including to move the camera or subject (Nozzle) to the right location before the editing takes place. 
     * The user is asked to confirm and can also skip the move. 
     * 
     * @param pipelineTitle
     * @param pipeline
     * @param moveBeforeEditDescription
     * @param movable
     * @param location
     */
    public void openPipelineEditor(String pipelineTitle, CvPipeline pipeline,
            String moveBeforeEditDescription, HeadMountable movable, Location location) {
        UiUtils.confirmMoveToLocationAndAct(getTopLevelAncestor(), 
                moveBeforeEditDescription, true, 
                () -> {
                    if (pipeline.getPipelineShotsCount() > 0) {
                        // Start with the first shot.
                        pipeline.getPipelineShot(0).apply();
                    }
                    else {
                        // not a multi-shot pipeline, move to location.
                        MovableUtils.moveToLocationAtSafeZ(movable, location);
                        MovableUtils.fireTargetedUserAction(movable);
                        movable.waitForCompletion(CompletionType.WaitForStillstand);
                    }
                },
                () -> {
                    CvPipelineEditor editor = new CvPipelineEditor(pipeline);
                    CvPipelineEditorDialog dialog = new CvPipelineEditorDialog(MainFrame.get(), pipelineTitle, editor) {

                        @Override
                        public void pipelineChanged() {
                            super.pipelineChanged();
                            // We need to make sure, the settings is recognized as a "deep" change, otherwise 
                            // somehow the firePropertyChange() will not be propagated. So toggle to null first.
                            setPipeline(null);
                            setPipeline(pipeline);
                        }
                    };
                    dialog.setVisible(true);
                });
    }

    /**
     * Open the Pipeline Editor with all the necessary handling before/after.
     * 
     * @param pipelineTitle
     * @param pipeline
     */
    public void openPipelineEditor(String pipelineTitle, CvPipeline pipeline) {
        openPipelineEditor(pipelineTitle, pipeline, null, null, null);
    }

    /**
     * Open smart assistance from a concrete vision-settings editor. When a
     * movable and location are supplied, this deliberately reuses the same
     * confirmation and safe-Z positioning sequence as the native pipeline
     * editor. The assistant itself does not invent a second motion path.
     */
    public void openSmartVisionStudio(String title, CvPipeline pipeline,
            SmartVisionStudioDialog.VisionContext context, Camera camera,
            String moveBeforeEditDescription, HeadMountable movable, Location location,
            SmartVisionStudioDialog.VisionTestAction testAction) {
        java.util.concurrent.atomic.AtomicBoolean livePositionPrepared =
                new java.util.concurrent.atomic.AtomicBoolean(
                        context != SmartVisionStudioDialog.VisionContext.BOTTOM);
        UiUtils.confirmMoveToLocationAndAct(getTopLevelAncestor(),
                moveBeforeEditDescription, true,
                () -> {
                    if (pipeline.getPipelineShotsCount() > 0) {
                        pipeline.getPipelineShot(0).apply();
                    }
                    else if (movable != null && location != null) {
                        MovableUtils.moveToLocationAtSafeZ(movable, location);
                        MovableUtils.fireTargetedUserAction(movable);
                        movable.waitForCompletion(CompletionType.WaitForStillstand);
                    }
                    livePositionPrepared.set(true);
                },
                () -> new SmartVisionStudioDialog(MainFrame.get(), title, context,
                        pipeline, camera, testAction, livePositionPrepared.get()).setVisible(true));
    }

    public void openSmartVisionStudio(String title, CvPipeline pipeline,
            SmartVisionStudioDialog.VisionContext context, Camera camera,
            SmartVisionStudioDialog.VisionTestAction testAction) {
        openSmartVisionStudio(title, pipeline, context, camera, null, null, null, testAction);
    }

    private Object getParameterValue(CvAbstractParameterStage paramStage) {
        if (pipelineParameterAssignments != null 
                && pipelineParameterAssignments.containsKey(paramStage.parameterName())) {
            return pipelineParameterAssignments.get(paramStage.parameterName());
        }
        return paramStage.defaultParameterValue();
    }

    private void setParameterValue(CvAbstractParameterStage paramStage, Object value) {
        if (value != null
                && !value.equals(getParameterValue(paramStage))) {
            Map<String, Object> newMap = new HashMap<>();
            if (this.pipelineParameterAssignments != null) {
                newMap.putAll(this.pipelineParameterAssignments);
            }
            this.pipelineParameterAssignments = newMap;
            pipelineParameterAssignments.put(paramStage.parameterName(), value);
            firePropertyChange("pipelineParameterAssignments", null, pipelineParameterAssignments);
            SwingUtilities.invokeLater(() -> previewParameterChangeEffect(paramStage, value));
        }
    }

    protected void previewParameterChangeEffect(CvAbstractParameterStage paramStage, Object value) {
        if (!value.equals(getParameterValue(paramStage))) {
            // Value has changed since the invokeLater call, no point in previewing.
            return;
        }
        boolean hasEffectStage = paramStage.getEffectStageName() != null 
                && !paramStage.getEffectStageName().isEmpty();
        if (hasEffectStage || paramStage.isPreviewResult()) {
            if (timer != null) {
                // If another preview is triggered, while a former preview timer is still running, the former is stopped.  
                timer.stop();
                timer = null;
            }
            // Process the pipeline to show preview images. 
            try (CvPipeline pipeline = getPipeline()) {
                configurePipeline(pipeline, getPipelineParameterAssignments(), false);
                Camera camera = (Camera) pipeline.getProperty("camera");
                CameraView cameraView = MainFrame.get().getCameraViews().getCameraView(camera); 
                pipeline.process();
                List<BufferedImage> showImages = new ArrayList<>(); 
                CvStage effectStage = hasEffectStage ? pipeline.getStage(paramStage.getEffectStageName()) : null;
                if (effectStage != null) {
                    Result result = pipeline.getResult(effectStage);
                    if (result != null && result.getImage() != null) {
                        Mat image = OpenCvUtils.toRGB(result.getImage().clone(), result.getColorSpace());
                        showImages.add(OpenCvUtils.toBufferedImage(image));
                        image.release();
                    }
                }
                if (paramStage.isPreviewResult() && pipeline.getWorkingImage() != null) {
                    Mat image = OpenCvUtils.toRGB(pipeline.getWorkingImage().clone(), pipeline.getWorkingColorSpace());
                    showImages.add(OpenCvUtils.toBufferedImage(image));
                    image.release();
                }
                if (showImages.size() > 0) {
                    // Show the first image directly.
                    cameraView.showFilteredImage(showImages.get(0), paramStage.getParameterLabel()+" = "+paramStage.displayValue(value), 3000);
                    showImages.remove(0);
                    if (showImages.size() > 0) {
                        // Show subsequent images with a timer.
                        timer = new Timer(1000, e -> {
                            cameraView.showFilteredImage(showImages.get(0), 
                                    paramStage.getParameterLabel()+" = "+paramStage.displayValue(value), 3000);
                            showImages.remove(0);
                            if (showImages.isEmpty()) {
                                // No more images, stop.
                                timer.stop();
                                timer = null;
                            }
                        });
                        timer.start();
                    }
                }
            }
            catch (Exception e) {
                Logger.warn(e);
            }
        }
    }

    private RowSpec[] dynamicRowspec(int rows) {
        RowSpec[] rowspec = new RowSpec[rows*2];
        for (int i = 0; i < rows*2; i+=2) {
            rowspec[i] = FormSpecs.RELATED_GAP_ROWSPEC;
            rowspec[i+1] = FormSpecs.DEFAULT_ROWSPEC;
        }
        return rowspec;
    }

    private int invokation = 0;
    private JButton btnCopy;
    private JButton btnPaste;

    private void rebuildUi() {
        removeAll();
        invokation++;
        //Logger.trace("rebuild "+this.hashCode()+" invokation "+invokation);
        JPanel panel = this;
        List<CvAbstractParameterStage> parameterStages = getPipeline() != null ? 
                getPipeline().getParameterStages() : new ArrayList<>();
        int rows = 2 + parameterStages.size();
        panel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("max(70dlu;default)"),
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("max(70dlu;default)"),
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("max(70dlu;default)"),
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.UNRELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,},
            dynamicRowspec(rows)));

        JLabel lblPipeline = new JLabel(Translations.getString("PipelinePanel.PipelineLabel.title")); //$NON-NLS-1$
        lblPipeline.setEnabled(isEnabled());
        panel.add(lblPipeline, "2, 2, right, default");

        btnEdit = new JButton(Translations.getString("PipelinePanel.EditButton.title")); //$NON-NLS-1$
        btnEdit.setToolTipText(Translations.getString("PipelinePanel.EditButton.toolTipText")); //$NON-NLS-1$
        btnEdit.setEnabled(isEnabled());
        btnEdit.setVisible(editable);
        btnEdit.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                UiUtils.messageBoxOnException(() -> configurePipeline(getPipeline(), getPipelineParameterAssignments(), true));
            }
        });
        panel.add(btnEdit, "4, 2, default, fill");

        btnReset = new JButton(Translations.getString("PipelinePanel.ResetButton.text")); //$NON-NLS-1$
        btnReset.setToolTipText(Translations.getString("PipelinePanel.ResetButton.toolTipText")); //$NON-NLS-1$
        btnReset.setEnabled(isEnabled());
        btnReset.setVisible(resetable);
        btnReset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                UiUtils.messageBoxOnException(() -> resetPipeline());
            }
        });
        panel.add(btnReset, "6, 2, default, fill");

        btnSmartVision = new JButton("智能视觉辅助...");
        btnSmartVision.setToolTipText("在当前顶部/底部视觉设置中分析图像、离线样本和 AI 建议；保留原管线全部自定义能力。");
        btnSmartVision.setEnabled(isEnabled());
        btnSmartVision.setVisible(smartVisionEnabled);
        btnSmartVision.addActionListener(event ->
                UiUtils.messageBoxOnException(() -> openSmartVision()));
        panel.add(btnSmartVision, "8, 2, 3, 1, default, fill");

        JLabel lblExchange = new JLabel("文件 / 剪贴板");
        lblExchange.setToolTipText("XML 文件导入导出与原有复制粘贴使用完全相同的管线格式。");
        lblExchange.setEnabled(isEnabled());
        panel.add(lblExchange, "2, 4, right, default");

        JButton btnImport = new JButton(importFileAction);
        btnImport.setEnabled(isEnabled() && editable);
        btnImport.setVisible(editable);
        panel.add(btnImport, "4, 4, default, fill");

        JButton btnExport = new JButton(exportFileAction);
        btnExport.setEnabled(isEnabled());
        panel.add(btnExport, "6, 4, default, fill");

        btnCopy = new JButton(copyAction);
        add(btnCopy, "8, 4, right, default");

        btnPaste = new JButton(pasteAction);
        btnPaste.setEnabled(isEnabled());
        btnPaste.setVisible(editable);
        add(btnPaste, "10, 4, left, default");

        int formRow = 3;
        for (CvAbstractParameterStage parameter : parameterStages) {
            //Logger.trace("    rebuild "+parameter.getParameterName()+" invokation "+invokation);
            if (parameter instanceof CvAbstractScalarParameterStage) {
                CvAbstractScalarParameterStage scalarParameter = (CvAbstractScalarParameterStage) parameter;
                try {
                    JLabel lbl = new JLabel(parameter.getParameterLabel());
                    lbl.setToolTipText(parameter.getParameterDescription());
                    lbl.setEnabled(isEnabled());
                    panel.add(lbl, "2, "+(formRow*2)+", right, default");
                    JSlider slider = new JSlider(JSlider.HORIZONTAL,
                            scalarParameter.minimumScalar(), scalarParameter.maximumScalar(), 
                            scalarParameter.convertToScalar(getParameterValue(parameter)));
                    slider.setEnabled(isEnabled());
                    slider.addChangeListener(new ChangeListener() {
                        public void stateChanged(ChangeEvent e) {
                            int value = slider.getValue();
                            // Store as value.
                            setParameterValue(parameter, scalarParameter.convertToValue(value));
                            // Convert back, it may have been sanitized.
                            int newValue = scalarParameter.convertToScalar(getParameterValue(parameter));
                            if (newValue != value) {
                                slider.setValue(newValue);
                            }
                        }
                    });
                    // Just clicking the slider also shows the preview.
                    slider.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            int value = (int) slider.getValue();
                            SwingUtilities.invokeLater(() -> previewParameterChangeEffect(parameter, scalarParameter.convertToValue(value)));
                            super.mousePressed(e);
                        }
                    });
                    slider.setToolTipText(parameter.getParameterDescription());
                    slider.setEnabled(isEnabled());
                    panel.add(slider, "4, "+(formRow*2)+", 7, 1, fill, default");
                }
                catch (Exception e) {
                    Logger.warn(e);
                }
            }
            formRow++;
        }
        revalidate();
        repaint();
    }

    public final Action importFileAction = new AbstractAction("导入 XML...") {
        {
            putValue(SMALL_ICON, Icons.importt);
            putValue(SHORT_DESCRIPTION, "从 UTF-8 XML 文件替换当前管线。导入前会要求确认。");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            java.io.File[] selected = PathSelectionDialog.chooseOpen(getTopLevelAncestor(),
                    "pipeline-controls-import", "导入视觉管线 XML", Mode.FILES, false,
                    org.openpnp.model.Configuration.get().getConfigurationDirectory(),
                    pipelineFileFilter());
            if (selected == null) {
                return;
            }
            java.io.File file = selected[0];
            int result = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                    "将用文件中的管线替换当前管线。是否继续？\n" + file,
                    "导入视觉管线", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                CvPipeline pipeline = getPipeline();
                CvPipelineFileIo.replace(pipeline, file);
                setPipeline(null);
                setPipeline(pipeline);
            }
            catch (Exception error) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "导入视觉管线失败", error);
            }
        }
    };

    public final Action exportFileAction = new AbstractAction("导出 XML...") {
        {
            putValue(SMALL_ICON, Icons.export);
            putValue(SHORT_DESCRIPTION, "把当前管线以 UTF-8 XML 文件保存，可再次导入或备份。");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            java.io.File selected = PathSelectionDialog.chooseSave(getTopLevelAncestor(),
                    "pipeline-controls-export", "导出视觉管线 XML",
                    new java.io.File("vision-pipeline.xml"),
                    org.openpnp.model.Configuration.get().getConfigurationDirectory(),
                    pipelineFileFilter());
            if (selected == null) {
                return;
            }
            java.io.File file = CvPipelineFileIo.normalizeExportFile(selected);
            if (file.exists()) {
                int result = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                        "文件已存在，是否覆盖？\n" + file,
                        "导出视觉管线", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            try {
                CvPipelineFileIo.write(getPipeline(), file);
                JOptionPane.showMessageDialog(getTopLevelAncestor(),
                        "视觉管线已导出：\n" + file,
                        "导出完成", JOptionPane.INFORMATION_MESSAGE);
            }
            catch (Exception error) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "导出视觉管线失败", error);
            }
        }
    };

    private FileNameExtensionFilter pipelineFileFilter() {
        return new FileNameExtensionFilter("OpenPnP vision pipeline (*.xml)", "xml");
    }

    public final Action copyAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.copy);
            //putValue(NAME, "Copy pipeline to clipboard");
            putValue(SHORT_DESCRIPTION, Translations.getString("PipelinePanel.Action.CopyPipeline.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            try {
                CvPipeline pipeline = getPipeline();
                pipeline.setProperty("camera",getCamera());
                StringSelection stringSelection =
                        new StringSelection(pipeline.toXmlString());
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(stringSelection, null);
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), Translations.getString(
                        "PipelinePanel.Action.CopyPipeline.errorMessage"), e); //$NON-NLS-1$
            }
        }
    };

    public final Action pasteAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.paste);
            //putValue(NAME, "Create pipeline from clipboard");
            putValue(SHORT_DESCRIPTION, Translations.getString("PipelinePanel.Action.PastePipeline.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            try {
                int result = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                        Translations.getString("PipelinePanel.Action.PasteConfirmDialog.message"), //$NON-NLS-1$
                        null, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    String s = (String) clipboard.getData(DataFlavor.stringFlavor);
                    CvPipeline pipeline = getPipeline();
                    pipeline.fromXmlString(s);
                    setPipeline(null);
                    setPipeline(pipeline);
                }
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), Translations.getString(
                        "PipelinePanel.Action.PastePipeline.errorMessage"), e); //$NON-NLS-1$
            }
        }
    };


}
