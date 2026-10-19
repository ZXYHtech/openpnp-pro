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
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.FeederAssignmentSupport;
import org.openpnp.gui.support.Wizard;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.PropertySheetHolder.PropertySheet;
import org.openpnp.util.UiUtils;

/**
 * Assigns the Part from a Job placement back to a feeder without leaving the Job tab.
 */
@SuppressWarnings("serial")
public class PlacementFeederAssignmentDialog extends JDialog implements WizardContainer {
    private final Runnable placementsRefresh;
    private final Placement placement;
    private final Part part;
    private final List<Feeder> allFeeders = new ArrayList<>();
    private final List<AbstractConfigurationWizard> activeWizards = new ArrayList<>();

    private final JTextField searchField = new JTextField(22);
    private final DefaultListModel<Feeder> feederModel = new DefaultListModel<>();
    private final JList<Feeder> feederList = new JList<>(feederModel);
    private final JTabbedPane configurationTabs = new JTabbedPane();
    private final JCheckBox feederEnabled = new JCheckBox("启用所选飞达");
    private final JCheckBox overrideRotation = new JCheckBox("覆盖取料旋转");
    private final JSpinner rotation = new JSpinner(new SpinnerNumberModel(0.0, -360.0, 360.0, 1.0));
    private final JLabel selectionSummary = new JLabel("请选择飞达");
    private final JLabel status = new JLabel(" ");

    private Feeder selectedFeeder;
    private boolean selectionGuard;
    private boolean enabledGuard;
    private boolean saving;
    private final java.util.Map<Component, Boolean> savedEnabled = new java.util.IdentityHashMap<>();

    public PlacementFeederAssignmentDialog(JobPlacementsPanel placementsPanel,
            Placement placement) throws Exception {
        this(placement, placementsPanel::refresh);
    }

    public PlacementFeederAssignmentDialog(BoardPlacementsPanel placementsPanel,
            Placement placement) throws Exception {
        this(placement, placementsPanel::refresh);
    }

    private PlacementFeederAssignmentDialog(Placement placement,
            Runnable placementsRefresh) throws Exception {
        super(MainFrame.get(), "替换到飞达", ModalityType.MODELESS);
        this.placementsRefresh = placementsRefresh;
        this.placement = placement;
        this.part = placement == null ? null : placement.getPart();
        if (part == null) {
            throw new Exception("当前任务元件没有指定物料，无法配置飞达。");
        }

        allFeeders.addAll(Configuration.get().getMachine().getFeeders());
        allFeeders.sort(Comparator.comparing(PlacementFeederAssignmentDialog::feederSortName,
                String.CASE_INSENSITIVE_ORDER));
        createUi();
        refreshFeederList();
        selectInitialFeeder();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { closeDialog(); }
        });
        setAlwaysOnTop(true);
        setMinimumSize(new Dimension(760, 560));
        Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        setPreferredSize(new Dimension(Math.min(1040, screen.width - 60),
                Math.min(920, screen.height - 80)));
        pack();
        setLocationRelativeTo(MainFrame.get());
    }

    private void createUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setContentPane(root);

        JLabel heading = new JLabel("贴片项：" + placement.getId() + "    当前物料："
                + part.getId());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD,
                heading.getFont().getSize2D() + 2f));
        JPanel header = new JPanel(new BorderLayout(6, 4));
        header.add(heading, BorderLayout.NORTH);
        header.add(new JLabel("选择飞达后可直接使用其原生配置和定位按钮；“确认替换/应用”不会关闭窗口。"),
                BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        JPanel configurationPanel = new JPanel(new BorderLayout());
        configurationPanel.setBorder(BorderFactory.createTitledBorder(
                "所选飞达配置（含原生相机/吸嘴定位与坐标采集按钮）"));
        configurationPanel.add(configurationTabs, BorderLayout.CENTER);

        JPanel selector = new JPanel(new BorderLayout(6, 6));
        selector.setBorder(BorderFactory.createTitledBorder("选择飞达（可输入名称、编号、类型或当前物料匹配）"));
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filterRow.add(new JLabel("匹配："));
        filterRow.add(searchField);
        overrideRotation.setToolTipText("勾选后仅覆盖该飞达的取料角度，不改变 X、Y、Z。默认不修改。");
        filterRow.add(overrideRotation);
        filterRow.add(rotation);
        filterRow.add(new JLabel("°"));
        selector.add(filterRow, BorderLayout.NORTH);

        feederList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        feederList.setVisibleRowCount(11);
        feederList.setCellRenderer(new FeederRenderer());
        selector.add(new JScrollPane(feederList), BorderLayout.CENTER);
        JPanel selectionOptions = new JPanel(new BorderLayout(0, 6));
        selectionOptions.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        feederEnabled.setToolTipText("立即修改并保存所选飞达的启用状态，不改变其他飞达配置。");
        selectionOptions.add(feederEnabled, BorderLayout.NORTH);
        selectionOptions.add(new JLabel("<html>可保持本窗口打开，<br>回到主页面微调位置。</html>"),
                BorderLayout.CENTER);
        selector.add(selectionOptions, BorderLayout.EAST);
        selectionSummary.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        selector.add(selectionSummary, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                configurationPanel, selector);
        split.setResizeWeight(0.48);
        split.setContinuousLayout(true);
        javax.swing.SwingUtilities.invokeLater(() -> split.setDividerLocation(.45));
        root.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        footer.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton apply = new JButton("确认替换/应用");
        apply.addActionListener(event -> applyAssignment());
        JButton close = new JButton("关闭");
        close.addActionListener(event -> closeDialog());
        buttons.add(apply);
        buttons.add(close);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(apply);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refreshFeederList();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refreshFeederList();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refreshFeederList();
            }
        });
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER && feederModel.size() > 0) {
                    feederList.setSelectedIndex(0);
                    feederList.requestFocusInWindow();
                }
            }
        });
        feederList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !selectionGuard) {
                requestFeederSelection(feederList.getSelectedValue());
            }
        });
        overrideRotation.addActionListener(event -> updateRotationControls());
        feederEnabled.addActionListener(event -> updateSelectedFeederEnabled());
    }

    private void refreshFeederList() {
        Feeder keepSelected = selectedFeeder;
        selectionGuard = true;
        try {
            feederModel.clear();
            for (Feeder feeder : allFeeders) {
                if (FeederAssignmentSupport.matches(feeder, searchField.getText())) {
                    feederModel.addElement(feeder);
                }
            }
            if (keepSelected != null && feederModel.contains(keepSelected)) {
                feederList.setSelectedValue(keepSelected, true);
            }
        }
        finally {
            selectionGuard = false;
        }
    }

    private void selectInitialFeeder() {
        Feeder initial = null;
        for (Feeder feeder : allFeeders) {
            if (feeder.isEnabled() && part.isInterchangeableWith(feeder.getPart())) {
                initial = feeder;
                break;
            }
        }
        if (initial == null) {
            for (Feeder feeder : allFeeders) {
                if (part.isInterchangeableWith(feeder.getPart())) {
                    initial = feeder;
                    break;
                }
            }
        }
        if (initial == null) {
            Feeder current = MainFrame.get().getFeedersTab().getSelection();
            initial = current != null ? current : (allFeeders.isEmpty() ? null : allFeeders.get(0));
        }
        if (initial != null) {
            feederList.setSelectedValue(initial, true);
            requestFeederSelection(initial);
        }
        else {
            configurationTabs.addTab("无飞达", new JLabel("当前机器尚未配置任何飞达。"));
            updateRotationControls();
        }
    }

    private void requestFeederSelection(Feeder candidate) {
        if (saving || candidate == null || candidate == selectedFeeder) {
            return;
        }
        if (!resolveDirtyConfiguration()) {
            selectionGuard = true;
            try {
                feederList.setSelectedValue(selectedFeeder, true);
            }
            finally {
                selectionGuard = false;
            }
            return;
        }
        showFeeder(candidate);
    }

    private boolean resolveDirtyConfiguration() {
        if (!hasDirtyWizard()) {
            return true;
        }
        Object[] options = {"应用后切换", "放弃并切换", "取消"};
        int result = JOptionPane.showOptionDialog(this,
                "当前飞达配置还有未应用的修改。", "切换飞达",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (result == 0) {
            applyDirtyWizards();
            return true;
        }
        return result == 1;
    }

    private void showFeeder(Feeder feeder) {
        disposeWizards();
        configurationTabs.removeAll();
        selectedFeeder = feeder;
        enabledGuard = true;
        try {
            feederEnabled.setSelected(feeder.isEnabled());
            feederEnabled.setEnabled(true);
        }
        finally {
            enabledGuard = false;
        }
        try {
            for (PropertySheet sheet : feeder.getPropertySheets()) {
                JPanel panel = sheet.getPropertySheetPanel();
                if (panel == null) {
                    continue;
                }
                if (panel instanceof AbstractConfigurationWizard) {
                    AbstractConfigurationWizard wizard = (AbstractConfigurationWizard) panel;
                    wizard.setWizardContainer(this);
                    activeWizards.add(wizard);
                }
                configurationTabs.addTab(sheet.getPropertySheetTitle(), panel);
            }
        }
        catch (Exception exception) {
            configurationTabs.addTab("配置不可用", new JLabel(exception.getMessage()));
        }
        if (configurationTabs.getTabCount() == 0) {
            configurationTabs.addTab("配置", new JLabel("该飞达没有可嵌入的配置页。"));
        }
        String loaded = feeder.getPart() == null ? "未配置" : feeder.getPart().getId();
        selectionSummary.setText("已选：" + feeder.getName() + "    当前飞达物料：" + loaded
                + "    目标物料：" + part.getId());
        status.setText(" ");
        overrideRotation.setSelected(false);
        try {
            rotation.setValue(FeederAssignmentSupport.getRotation(feeder));
        }
        catch (Exception exception) {
            rotation.setValue(0.0);
        }
        updateRotationControls();
        configurationTabs.revalidate();
        configurationTabs.repaint();
    }

    private void updateRotationControls() {
        boolean supported = selectedFeeder != null
                && FeederAssignmentSupport.supportsRotationOverride(selectedFeeder);
        overrideRotation.setEnabled(supported);
        rotation.setEnabled(supported && overrideRotation.isSelected());
        if (!supported) {
            overrideRotation.setToolTipText("该飞达类型没有公开可写的取料角度，请在其专用配置页调整。");
        }
        else {
            overrideRotation.setToolTipText("勾选后仅覆盖该飞达的取料角度，不改变 X、Y、Z。默认不修改。");
        }
    }

    private void updateSelectedFeederEnabled() {
        if (saving || enabledGuard || selectedFeeder == null) {
            return;
        }
        boolean oldEnabled = selectedFeeder.isEnabled();
        boolean newEnabled = feederEnabled.isSelected();
        if (oldEnabled == newEnabled) {
            return;
        }
        Feeder feeder = selectedFeeder;
        feeder.setEnabled(newEnabled);
        persistChange(() -> feeder.setEnabled(oldEnabled),
                newEnabled ? "已启用并保存所选飞达。" : "已停用并保存所选飞达。", false);
    }

    private void applyAssignment() {
        if (saving) { return; }
        UiUtils.messageBoxOnException(() -> {
            if (selectedFeeder == null) {
                throw new Exception("请先从下方列表选择一个飞达。");
            }
            applyDirtyWizards();
            Part previousPart = selectedFeeder.getPart();
            org.openpnp.model.Location previousLocation = selectedFeeder instanceof org.openpnp.machine.reference.ReferenceFeeder
                    ? ((org.openpnp.machine.reference.ReferenceFeeder) selectedFeeder).getLocation() : null;
            String oldPart = selectedFeeder.getPart() == null ? "未配置"
                    : selectedFeeder.getPart().getId();
            FeederAssignmentSupport.assign(selectedFeeder, part,
                    overrideRotation.isSelected(), ((Number) rotation.getValue()).doubleValue());
            MainFrame.get().getFeedersTab().refresh(selectedFeeder);
            placementsRefresh.run();
            selectionSummary.setText("已选：" + selectedFeeder.getName()
                    + "    当前飞达物料：" + part.getId());
            String angle = overrideRotation.isSelected()
                    ? "，取料角度 " + rotation.getValue() + "°" : "";
            Feeder feeder = selectedFeeder;
            showFeeder(feeder); // Discard cached wizard bindings to the previous Part immediately.
            persistChange(() -> {
                feeder.setPart(previousPart);
                if (previousLocation != null) {
                    ((org.openpnp.machine.reference.ReferenceFeeder) feeder).setLocation(previousLocation);
                }
            }, "已保存：" + feeder.getName() + "，物料 " + oldPart
                    + " → " + part.getId() + angle + "。窗口保持打开。", true);
        });
    }

    private void persistChange(Runnable rollback, String success, boolean reloadConfiguration) {
        saving = true;
        setBusy(getContentPane());
        status.setText("正在保存所选飞达，窗口保持响应…");
        FeederAssignmentSupport.saveMachineAsync(Configuration.get(), reloadConfiguration, () -> {
            restoreControls();
            MainFrame.get().getFeedersTab().refresh(selectedFeeder);
            placementsRefresh.run();
            refreshAfterSave(reloadConfiguration);
            refreshFeederList();
            status.setForeground(new Color(36, 150, 70));
            status.setText(success);
        }, failure -> {
            restoreControls();
            rollback.run();
            MainFrame.get().getFeedersTab().refresh(selectedFeeder);
            placementsRefresh.run();
            refreshAfterSave(reloadConfiguration);
            refreshFeederList();
            status.setForeground(new Color(190, 55, 55));
            status.setText("保存失败，已恢复原状态：" + failure.getMessage());
        });
    }

    private void refreshAfterSave(boolean reloadConfiguration) {
        if (reloadConfiguration) { showFeeder(selectedFeeder); }
        enabledGuard = true;
        try { feederEnabled.setSelected(selectedFeeder.isEnabled()); }
        finally { enabledGuard = false; }
    }

    private void setBusy(Component component) {
        savedEnabled.put(component, component.isEnabled());
        component.setEnabled(false);
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) { setBusy(child); }
        }
    }

    private void restoreControls() {
        savedEnabled.forEach(Component::setEnabled);
        savedEnabled.clear();
        saving = false;
    }

    private void applyDirtyWizards() {
        for (AbstractConfigurationWizard wizard : activeWizards) {
            if (wizard.isDirty()) {
                wizard.apply();
            }
        }
    }

    private boolean hasDirtyWizard() {
        for (AbstractConfigurationWizard wizard : activeWizards) {
            if (wizard.isDirty()) {
                return true;
            }
        }
        return false;
    }

    private void closeDialog() {
        if (saving) { return; }
        dispose();
    }

    @Override
    public void dispose() {
        disposeWizards();
        super.dispose();
    }

    private void disposeWizards() {
        for (AbstractConfigurationWizard wizard : activeWizards) {
            wizard.dispose();
        }
        activeWizards.clear();
    }

    @Override
    public void wizardCompleted(Wizard wizard) {
        if (selectedFeeder != null) {
            MainFrame.get().getFeedersTab().refresh(selectedFeeder);
        }
    }

    @Override
    public void wizardCancelled(Wizard wizard) {
    }

    private static String feederSortName(Feeder feeder) {
        return (feeder.getName() == null ? "" : feeder.getName()) + "\n" + feeder.getId();
    }

    private static class FeederRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                    isSelected, cellHasFocus);
            if (value instanceof Feeder) {
                Feeder feeder = (Feeder) value;
                String loaded = feeder.getPart() == null ? "未配置物料" : feeder.getPart().getId();
                label.setText(feeder.getName() + "  [" + feeder.getClass().getSimpleName() + "]  —  "
                        + loaded + (feeder.isEnabled() ? "  · 已启用" : "  · 已停用"));
                label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
                if (!isSelected && !feeder.isEnabled()) {
                    Color disabled = UIManager.getColor("Label.disabledForeground");
                    label.setForeground(disabled == null ? Color.GRAY : disabled);
                }
            }
            return label;
        }
    }
}
