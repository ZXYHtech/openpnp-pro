/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is package of OpenPnP.
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

package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableRowSorter;

import org.openpnp.Translations;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.gui.components.CameraView;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.ActionGroup;
import org.openpnp.gui.support.Helpers;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.JobUsageFilter;
import org.openpnp.gui.support.JobUsageSnapshot;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.MultisortTableHeaderCellRenderer;
import org.openpnp.gui.support.NamedListCellRenderer;
import org.openpnp.gui.support.NamedTableCellRenderer;
import org.openpnp.gui.support.VisionSettingsComboBoxModel;
import org.openpnp.gui.support.Wizard;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.gui.tablemodel.PackagesTableModel;
import org.openpnp.gui.wizards.PackageCompositingWizard;
import org.openpnp.gui.wizards.PackageNozzleTipsWizard;
import org.openpnp.gui.wizards.PackageSettingsWizard;
import org.openpnp.gui.wizards.PackageVisionWizard;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Job;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.packageimport.PackageCategoryRegistry;
import org.openpnp.packageimport.PackageCategoryRegistry.Category;
import org.openpnp.packageimport.PackageClassifier;
import org.openpnp.packageimport.PackageLibraryAuditService;
import org.openpnp.packageimport.PackageResolutionService;
import org.openpnp.spi.Camera;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PartAlignment;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Serializer;

import com.google.common.eventbus.Subscribe;

@SuppressWarnings("serial")
public class PackagesPanel extends JPanel implements WizardContainer {


    private static final String PREF_DIVIDER_POSITION = "PackagesPanel.dividerPosition";
    private static final int PREF_DIVIDER_POSITION_DEF = -1;
    private Preferences prefs = Preferences.userNodeForPackage(PackagesPanel.class);

    final private Configuration configuration;
    final private Frame frame;

    private PackagesTableModel tableModel;
    private TableRowSorter<PackagesTableModel> tableSorter;
    private JTextField searchTextField;
    private JComboBox<JobUsageFilter> usageFilter;
    private JComboBox<CategoryFilterOption> categoryFilter;
    private JComboBox<ReviewFilterOption> reviewFilter;
    private JComboBox<SourceFilterOption> sourceFilter;
    private JobUsageSnapshot usageSnapshot;
    private JTable table;
    private ActionGroup singleSelectionActionGroup;
    private ActionGroup multiSelectionActionGroup;
    private JTabbedPane tabbedPane;
    private Package selectedPackage;

    public PackagesPanel(Configuration configuration, Frame frame) {
        this.configuration = configuration;
        this.frame = frame;

        singleSelectionActionGroup = new ActionGroup(deletePackageAction, copyPackageToClipboardAction);
        singleSelectionActionGroup.setEnabled(false);
        multiSelectionActionGroup = new ActionGroup(deletePackageAction);
        multiSelectionActionGroup.setEnabled(false);
        
        setLayout(new BorderLayout(0, 0));
        tableModel = new PackagesTableModel(configuration);
        tableSorter = new TableRowSorter<>(tableModel);

        JPanel toolbarAndSearch = new JPanel();
        add(toolbarAndSearch, BorderLayout.NORTH);
        toolbarAndSearch.setLayout(new BorderLayout(0, 0));

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolbarAndSearch.add(toolBar);

        JPanel panel_1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolbarAndSearch.add(panel_1, BorderLayout.SOUTH);

        panel_1.add(new JLabel("显示"));
        usageFilter = new JComboBox<>(JobUsageFilter.values());
        usageFilter.addActionListener(event -> refreshUsageDisplay());
        panel_1.add(usageFilter);

        panel_1.add(new JLabel("分类"));
        categoryFilter = new JComboBox<>(categoryFilterOptions());
        categoryFilter.setMaximumRowCount(24);
        categoryFilter.addActionListener(event -> search());
        categoryFilter.setToolTipText("一级分类会同时显示其二级分类；可与任务、审核、来源和搜索组合使用。");
        panel_1.add(categoryFilter);

        panel_1.add(new JLabel("审核状态"));
        reviewFilter = new JComboBox<>(reviewFilterOptions());
        reviewFilter.addActionListener(event -> search());
        reviewFilter.setToolTipText("待审核包含自动归类但尚未确认的封装；疑似重复、缺少信息和冲突可单独筛选。");
        panel_1.add(reviewFilter);

        panel_1.add(new JLabel("来源"));
        sourceFilter = new JComboBox<>(sourceFilterOptions());
        sourceFilter.addActionListener(event -> search());
        panel_1.add(sourceFilter);

        JLabel lblSearch = new JLabel(Translations.getString("PackagesPanel.SearchLabel.text"));
        //$NON-NLS-1$
        panel_1.add(lblSearch);

        searchTextField = new JTextField();
        searchTextField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(DocumentEvent e) {
                search();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                search();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                search();
            }
        });
        panel_1.add(searchTextField);
        searchTextField.setColumns(15);

        JSplitPane splitPane = new JSplitPane();
        splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        splitPane.setContinuousLayout(true);
        splitPane
                .setDividerLocation(prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF));
        splitPane.addPropertyChangeListener("dividerLocation", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                prefs.putInt(PREF_DIVIDER_POSITION, splitPane.getDividerLocation());
            }
        });
        add(splitPane, BorderLayout.CENTER);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);

        table = new AutoSelectTextTable(tableModel) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row,
                    int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                Package pkg = tableModel.getRowObjectAt(convertRowIndexToModel(row));
                boolean used = usageSnapshot != null && usageSnapshot.isUsed(pkg);
                if (!isRowSelected(row)) {
                    java.awt.Color metadataColor = metadataColor(pkg);
                    component.setBackground(metadataColor != null ? metadataColor : used
                            ? new java.awt.Color(ModernTheme.isDark() ? 0x234A3D : 0xE2F5EC)
                            : getBackground());
                }
                if (component instanceof JComponent) {
                    ((JComponent) component).setToolTipText("分类："
                            + PackageCategoryRegistry.label(pkg.getCategoryId()) + "；审核："
                            + pkg.getReviewState() + "；来源：" + pkg.getSourceType()
                            + (used ? "；当前任务使用" : ""));
                }
                return component;
            }

            @Override
            public String getToolTipText(MouseEvent evt) {
                String fullValue = super.getToolTipText(evt);
                if (fullValue != null) {
                    return fullValue;
                }
                int viewColumn = columnAtPoint(evt.getPoint());
                if (viewColumn < 0) {
                    return null;
                }
                int column = convertColumnIndexToModel(viewColumn);
                if(column==2) { return Translations.getString("PackagesTableModel.Column.TapeSpecification.toolTip"); } //$NON-NLS-1$
                return null;
            }
        };
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ((AutoSelectTextTable) table).setMultiRowEditEnabled(true);

        JComboBox<BottomVisionSettings> bottomVisionCombo = new JComboBox<>(
                new VisionSettingsComboBoxModel(BottomVisionSettings.class));
        bottomVisionCombo.setMaximumRowCount(20);
        bottomVisionCombo.setRenderer(new NamedListCellRenderer<>());
        table.setDefaultEditor(BottomVisionSettings.class,
                new DefaultCellEditor(bottomVisionCombo));

        JComboBox<BottomVisionSettings> fiducialVisionCombo = new JComboBox<>(
                new VisionSettingsComboBoxModel(FiducialVisionSettings.class));
        fiducialVisionCombo.setMaximumRowCount(20);
        fiducialVisionCombo.setRenderer(new NamedListCellRenderer<>());
        table.setDefaultEditor(FiducialVisionSettings.class,
                new DefaultCellEditor(fiducialVisionCombo));

        table.setDefaultRenderer(AbstractVisionSettings.class,
                new NamedTableCellRenderer<AbstractVisionSettings>());

        JComboBox<Category> categoryCombo = new JComboBox<>(
                PackageCategoryRegistry.all().toArray(new Category[0]));
        categoryCombo.setMaximumRowCount(24);
        table.setDefaultEditor(Category.class, new DefaultCellEditor(categoryCombo));
        table.setDefaultEditor(Package.ReviewState.class,
                new DefaultCellEditor(new JComboBox<>(Package.ReviewState.values())));

        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                
                firePackageSelectionChanged();
            }
        });

        Configuration.get().addPropertyChangeListener("visionSettings", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                // Handle vision settings changes like selection changes, as the inherited settings might change. 
                firePackageSelectionChanged();
            }
        });

        tableModel.addTableModelListener(e -> {
            if (selectedPackage != null && table.getSelectedRowCount() <= 1
                    && !((AutoSelectTextTable) table).isMultiRowEditInProgress()) {
                // Reselect previously selected settings.
                Helpers.selectObjectTableRow(table, selectedPackage);
            }
        });

        table.setRowSorter(tableSorter);
        ((AutoSelectTextTable) table).setRowReorderHandler(this::reorderRows);
        table.getTableHeader().setDefaultRenderer(new MultisortTableHeaderCellRenderer());

        splitPane.setLeftComponent(new JScrollPane(table));
        splitPane.setRightComponent(tabbedPane);

        toolBar.add(newPackageAction);
        toolBar.add(deletePackageAction);
        toolBar.addSeparator();
        toolBar.add(copyPackageToClipboardAction);
        toolBar.add(pastePackageToClipboardAction);
        toolBar.addSeparator();
        toolBar.add(openKicadImportCenterAction);
        toolBar.add(reclassifyPackagesAction);
        toolBar.add(markReviewedAction);
        toolBar.add(showDuplicateCandidatesAction);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                try {
                    Camera camera =
                            Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
                    CameraView cameraView = MainFrame.get().getCameraViews().getCameraView(camera);
                    if (cameraView == null) {
                        return;
                    }
                    cameraView.removeReticle(PackageVisionWizard.class.getName());
                }
                catch (Exception e1) {
                }
            }
        });
        configuration.getBus().register(this);
        refreshUsageSnapshot();
    }

    public Package getSelectedPackage() {
        List<Package> selections = getSelections();
        if (selections.size() != 1) {
            return null;
        }
        return selections.get(0);
    }

    private List<Package> getSelections() {
        List<Package> selections = new ArrayList<>();
        for (int selectedRow : table.getSelectedRows()) {
            selectedRow = table.convertRowIndexToModel(selectedRow);
            try {
                selections.add(tableModel.getRowObjectAt(selectedRow));
            }
            catch (IndexOutOfBoundsException e) {
                // sometimes this happens when deleting a row, if the gui state
                // updates after the model state
                Logger.warn("package selection index {} out of bounds", selectedRow);
            }
        }
        return selections;
    }
    private boolean reorderRows(List<Integer> rows, int targetRow) {
        List<Package> selected = new ArrayList<>();
        for (Integer row : rows) {
            if (row != null && row >= 0 && row < tableModel.getRowCount()) {
                selected.add(tableModel.getRowObjectAt(row));
            }
        }
        boolean changed = tableModel.reorderRows(rows, targetRow);
        if (changed) {
            tableSorter.setSortKeys(null);
            Helpers.selectObjectTableRows(table, selected);

        }
        return changed;
    }

    private void search() {
        final RowFilter<PackagesTableModel, Object> textFilter;
        try {
            textFilter = RowFilter.regexFilter("(?i)" + searchTextField.getText().trim());
        }
        catch (PatternSyntaxException e) {
            Logger.warn(e, "Search failed");
            return;
        }
        final JobUsageFilter selectedFilter = (JobUsageFilter) usageFilter.getSelectedItem();
        final CategoryFilterOption selectedCategory =
                (CategoryFilterOption) categoryFilter.getSelectedItem();
        final ReviewFilterOption selectedReview =
                (ReviewFilterOption) reviewFilter.getSelectedItem();
        final SourceFilterOption selectedSource =
                (SourceFilterOption) sourceFilter.getSelectedItem();
        RowFilter<PackagesTableModel, Object> combinedFilter =
                new RowFilter<PackagesTableModel, Object>() {
                    @Override
                    public boolean include(
                            Entry<? extends PackagesTableModel, ? extends Object> entry) {
                        Package pkg = tableModel.getRowObjectAt((Integer) entry.getIdentifier());
                        boolean used = usageSnapshot != null && usageSnapshot.isUsed(pkg);
                        return (selectedFilter == null || selectedFilter.includes(used))
                                && (selectedCategory == null || selectedCategory.includes(pkg))
                                && (selectedReview == null || selectedReview.includes(pkg))
                                && (selectedSource == null || selectedSource.includes(pkg))
                                && (textFilter == null || textFilter.include(entry));
                    }
                };
        tableSorter.setRowFilter(combinedFilter);
    }

    private void refreshUsageDisplay() {
        refreshUsageSnapshot();
        search();
        table.repaint();
    }

    private void refreshUsageSnapshot() {
        Job job = frame instanceof MainFrame ? ((MainFrame) frame).getJobTab().getJob() : null;
        usageSnapshot = JobUsageSnapshot.capture(job, configuration.getMachine());
    }

    @Subscribe
    public void jobLoaded(JobLoadedEvent event) {
        SwingUtilities.invokeLater(this::refreshUsageDisplay);
    }

    public final Action newPackageAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.add);
            putValue(NAME, Translations.getString("PackagesPanel.Action.NewPackage"));
            //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PackagesPanel.Action.NewPackage.Description"));
            //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            String id;
            while ((id = JOptionPane.showInputDialog(frame,
                    "Please enter an ID for the new package.")) != null) {
                id = id.trim();
                if (id.isEmpty()) {
                    break;
                }
                if (configuration.getPackage(id) != null) {
                    MessageBoxes.errorBox(frame, "Error", "Package ID " + id + " already exists.");
                    continue;
                }
                Package this_package = new Package(id);

                PackageResolutionService.initializeNewPackage(this_package,
                        Package.SourceType.MANUAL, "封装库手工新建", null);

                configuration.addPackage(this_package);
                tableModel.fireTableDataChanged();
                Helpers.selectObjectTableRow(table, this_package);
                break;
            }
        }
    };

    public final Action deletePackageAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.delete);
            putValue(NAME, Translations.getString("PackagesPanel.Action.DeletePackage"));
            //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PackagesPanel.Action.DeletePackage.Description"));
            //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            // Check to make sure there are no parts using this package.
            List<Package> selections = getSelections();
            for (Package pkg : selections) {
                for (Part part : Configuration.get().getParts()) {
                    if (part.getPackage() == pkg) {
                        MessageBoxes.errorBox(getTopLevelAncestor(),
                                Translations.getString("CommonWords.error"), //$NON-NLS-1$
                                pkg.getId() + " " + Translations.getString( //$NON-NLS-1$
                                        "CommonPhrases.cannotBeDeletedUsedBy" //$NON-NLS-1$
                                ) + " " + part.getId());
                                //$NON-NLS-1$
                        return;
                    }
                }
            }
            
            List<String> ids = selections.stream().map(Package::getId).collect(Collectors.toList());
            String formattedIds;
            if (ids.size() <= 3) {
                formattedIds = String.join(", ", ids);
            }
            else {
                formattedIds = String.join(", ", ids.subList(0, 3)) + ", and " + (ids.size() - 3) + " others";
            }
            
            int ret = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                    Translations.getString("DialogMessages.ConfirmDelete.text" //$NON-NLS-1$
                    ) + " " + formattedIds + "?", //$NON-NLS-1$ //$NON-NLS-1$
                    Translations.getString("DialogMessages.ConfirmDelete.title" //$NON-NLS-1$
                    ) + selections.size() + " " + Translations.getString(
                                    "CommonWords.packages") + "?", JOptionPane.YES_NO_OPTION);
                                    //$NON-NLS-1$ //$NON-NLS-2$
            if (ret == JOptionPane.YES_OPTION) {
                for (Package pkg : selections) {
                    Configuration.get().removePackage(pkg);
                }
            }
        }
    };

    public final Action copyPackageToClipboardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.copy);
            putValue(NAME, Translations.getString("PackagesPanel.Action.CopyPackage"));
            //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PackagesPanel.Action.CopyPackage.Description"));
            //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Package pkg = getSelectedPackage();
            if (pkg == null) {
                return;
            }
            try {
                Serializer s = Configuration.createSerializer();
                StringWriter w = new StringWriter();
                s.write(pkg, w);
                StringSelection stringSelection = new StringSelection(w.toString());
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(stringSelection, null);
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Copy Failed", e);
            }
        }
    };

    public final Action pastePackageToClipboardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.paste);
            putValue(NAME, Translations.getString("PackagesPanel.Action.PastePackage"));
            //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PackagesPanel.Action.PastePackage.Description"));
            //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            String id;
            while ((id = JOptionPane.showInputDialog(frame,
                    "Please enter an ID for the pasted package.")) != null) {
                id = id.trim();
                if (id.isEmpty()) {
                    break;
                }
                if (configuration.getPackage(id) == null) {
                    break;
                }
                MessageBoxes.errorBox(frame, "Error", "Package ID " + id + " already exists.");
            }
            if (id == null || id.isEmpty()) {
                return;
            }
            try {
                try {
                    Configuration.get().lockListeners();
                    Serializer ser = Configuration.createSerializer();
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    String s = (String) clipboard.getData(DataFlavor.stringFlavor);
                    StringReader r = new StringReader(s);
                    Package pkg = ser.read(Package.class, s);
                    pkg.setId(id);
                    pkg.setSourceType(Package.SourceType.CLIPBOARD);
                    pkg.setSourceReference("封装库剪贴板粘贴");
                    if (pkg.getCategoryId() == null) {
                        PackageClassifier.applySuggestion(pkg, false);
                    }
                    Configuration.get().addPackage(pkg);
                    tableModel.fireTableDataChanged();
                    Helpers.selectObjectTableRow(table, pkg);
                } finally {
                    Configuration.get().unlockListeners();
                }
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Paste Failed", e);
            }
        }
    };
    private int selectedTab;

    @Override
    public void wizardCompleted(Wizard wizard) {}

    @Override
    public void wizardCancelled(Wizard wizard) {}

    public void firePackageSelectionChanged() {
        List<Package> selections = getSelections();

        if (selections.size() > 1) {
            singleSelectionActionGroup.setEnabled(false);
            multiSelectionActionGroup.setEnabled(true);
        }
        else {
            multiSelectionActionGroup.setEnabled(false);
            singleSelectionActionGroup.setEnabled(!selections.isEmpty());
        }

        Package selectedPackage = getSelectedPackage();
        if (selectedPackage != null) {
            this.selectedPackage = selectedPackage; 
        }

        if (tabbedPane.getTabCount() > 0) {
            selectedTab = tabbedPane.getSelectedIndex();
        }
        
        for (Component comp : tabbedPane.getComponents()) {
            if (comp instanceof AbstractConfigurationWizard) {
                ((AbstractConfigurationWizard) comp).dispose();
            }
        }
        tabbedPane.removeAll();
        
        if (selectedPackage != null) {
            PackageNozzleTipsWizard packageNozzleTipsWizard = new PackageNozzleTipsWizard(selectedPackage);
            packageNozzleTipsWizard.setWizardContainer(PackagesPanel.this);
            tabbedPane.add(Translations.getString("PackagesPanel.NozzleTipsTab.title"), //$NON-NLS-1$
                    packageNozzleTipsWizard);
            
            PackageSettingsWizard packageSettingsPanel = new PackageSettingsWizard(selectedPackage);
            packageSettingsPanel.setWizardContainer(PackagesPanel.this);
            tabbedPane.add(Translations.getString("PackagesPanel.SettingsTab.title"), //$NON-NLS-1$
                    packageSettingsPanel);
            
            PackageVisionWizard packageVisionPanel = new PackageVisionWizard(selectedPackage);
            packageVisionPanel.setWizardContainer(PackagesPanel.this);
            tabbedPane.add(Translations.getString("PackagesPanel.VisionTab.title"), //$NON-NLS-1$
                    packageVisionPanel);
            
            PackageCompositingWizard packageCompositingPanel = new PackageCompositingWizard(selectedPackage);
            packageCompositingPanel.setWizardContainer(PackagesPanel.this);
            tabbedPane.add(Translations.getString("PackagesPanel.VisionCompositingTab.title"), //$NON-NLS-1$
                    packageCompositingPanel);
            
            Machine machine = Configuration.get().getMachine();
            for (PartAlignment partAlignment : machine.getPartAlignments()) {
                Wizard wizard = partAlignment.getPartConfigurationWizard(selectedPackage);
                if (wizard != null) {
                    tabbedPane.add(wizard.getWizardName(), (JPanel) wizard);
                    wizard.setWizardContainer(PackagesPanel.this);
                }
            }
            
            FiducialLocator fiducialLocator = machine.getFiducialLocator();
            Wizard wizard = fiducialLocator.getPartConfigurationWizard(selectedPackage);
            if (wizard != null) {
                tabbedPane.add(wizard.getWizardName(), (JPanel) wizard);
                wizard.setWizardContainer(PackagesPanel.this);
            }
            
            if (selectedTab != -1 
                    && tabbedPane.getTabCount() > selectedTab) {
                tabbedPane.setSelectedIndex(selectedTab);
            }
            MainFrame mainFrame = MainFrame.get();
            if (mainFrame.getTabs().getSelectedComponent() == mainFrame.getPackagesTab() 
                    && Configuration.get().getTablesLinked() == TablesLinked.Linked) {
                 mainFrame.getVisionSettingsTab().selectVisionSettingsInTable(selectedPackage);
            }
        }

        revalidate();
        repaint();
    }

    public void selectPackageInTable(Package packag) {
        if (getSelectedPackage() != packag) {
            Helpers.selectObjectTableRow(table, packag);
        }
    }

    public final Action openKicadImportCenterAction = new AbstractAction() {
        {
            putValue(NAME, "KiCad 导入中心...");
            putValue(SHORT_DESCRIPTION, "分步扫描单个/多个 .kicad_mod 或 .pretty 目录，审核分类、1 脚方向和冲突后再写入。");
        }
        @Override public void actionPerformed(ActionEvent event) {
            KicadPackageImportDialog dialog = new KicadPackageImportDialog(frame, configuration);
            dialog.setVisible(true);
            tableModel.fireTableDataChanged();
            search();
        }
    };

    public final Action reclassifyPackagesAction = new AbstractAction() {
        {
            putValue(NAME, "重新自动分类");
            putValue(SHORT_DESCRIPTION, "为选中封装生成分类建议；已确认或已有手工分类不会被覆盖。");
        }
        @Override public void actionPerformed(ActionEvent event) {
            List<Package> targets = getSelections();
            if (targets.isEmpty()) {
                targets = new ArrayList<>(configuration.getPackages());
            }
            final List<Package> selectedTargets = targets;
            reclassifyPackagesAction.setEnabled(false);
            new SwingWorker<PackageLibraryAuditService.Result, Void>() {
                @Override
                protected PackageLibraryAuditService.Result doInBackground() {
                    return PackageLibraryAuditService.audit(configuration.getPackages(),
                            selectedTargets);
                }

                @Override
                protected void done() {
                    reclassifyPackagesAction.setEnabled(true);
                    try {
                        PackageLibraryAuditService.Result result = get();
                        tableModel.fireTableDataChanged();
                        search();
                        JOptionPane.showMessageDialog(PackagesPanel.this,
                                "已分析 " + result.analyzed + " 个封装，新增分类建议 "
                                + result.classified + " 个；发现重复组 "
                                + result.duplicateGroups + " 组（" + result.duplicatePackages
                                + " 个封装），缺少几何 " + result.missingFootprints
                                + " 个。\n已确认/手工分类保持不变，未自动合并或删除。",
                                "封装库审核", JOptionPane.INFORMATION_MESSAGE);
                    }
                    catch (Exception exception) {
                        MessageBoxes.errorBox(PackagesPanel.this, "封装库审核失败", exception);
                    }
                }
            }.execute();
        }
    };

    public final Action markReviewedAction = new AbstractAction() {
        {
            putValue(NAME, "标记已审核");
            putValue(SHORT_DESCRIPTION, "确认选中封装当前分类和几何；不会合并、删除或覆盖其他封装。");
        }
        @Override public void actionPerformed(ActionEvent event) {
            List<Package> targets = getSelections();
            if (targets.isEmpty()) { return; }
            for (Package pkg : targets) {
                pkg.setReviewState(Package.ReviewState.VERIFIED);
                pkg.setLastReviewedAt(Instant.now().toString());
            }
            tableModel.fireTableDataChanged();
            search();
        }
    };

    public final Action showDuplicateCandidatesAction = new AbstractAction() {
        {
            putValue(NAME, "查看重复候选");
            putValue(SHORT_DESCRIPTION, "切换到疑似重复筛选；重复组编号用于连续审核，不会自动合并。");
        }
        @Override public void actionPerformed(ActionEvent event) {
            for (int i = 0; i < reviewFilter.getItemCount(); i++) {
                if (reviewFilter.getItemAt(i).state == Package.ReviewState.DUPLICATE_CANDIDATE) {
                    reviewFilter.setSelectedIndex(i);
                    break;
                }
            }
        }
    };

    private java.awt.Color metadataColor(Package pkg) {
        boolean dark = ModernTheme.isDark();
        switch (pkg.getReviewState()) {
            case CONFLICT: return new java.awt.Color(dark ? 0x583238 : 0xFFE5E5);
            case DUPLICATE_CANDIDATE: return new java.awt.Color(dark ? 0x51452D : 0xFFF2CC);
            case MISSING_FOOTPRINT: return new java.awt.Color(dark ? 0x4B3D55 : 0xF3E6FF);
            default:
                return pkg.getImportBatchId() == null ? null
                        : new java.awt.Color(dark ? 0x2E4653 : 0xE5F3FA);
        }
    }

    private static CategoryFilterOption[] categoryFilterOptions() {
        List<CategoryFilterOption> values = new ArrayList<>();
        values.add(new CategoryFilterOption(null, "全部"));
        for (Category category : PackageCategoryRegistry.all()) {
            values.add(new CategoryFilterOption(category.id, category.toString()));
        }
        values.add(new CategoryFilterOption("__uncategorized__", "未分类"));
        return values.toArray(new CategoryFilterOption[0]);
    }
    private static ReviewFilterOption[] reviewFilterOptions() {
        return new ReviewFilterOption[] { new ReviewFilterOption(null, "全部"),
                new ReviewFilterOption(Package.ReviewState.NEEDS_REVIEW, "待审核"),
                new ReviewFilterOption(Package.ReviewState.DUPLICATE_CANDIDATE, "疑似重复"),
                new ReviewFilterOption(Package.ReviewState.MISSING_FOOTPRINT, "缺少封装信息"),
                new ReviewFilterOption(Package.ReviewState.CONFLICT, "有冲突"),
                new ReviewFilterOption(Package.ReviewState.VERIFIED, "已确认") };
    }
    private static SourceFilterOption[] sourceFilterOptions() {
        List<SourceFilterOption> values = new ArrayList<>();
        values.add(new SourceFilterOption(null, "全部"));
        for (Package.SourceType type : Package.SourceType.values()) {
            values.add(new SourceFilterOption(type, type.toString()));
        }
        return values.toArray(new SourceFilterOption[0]);
    }

    private static final class CategoryFilterOption {
        final String id;
        final String label;
        CategoryFilterOption(String id, String label) { this.id=id;
        this.label=label; }
        boolean includes(Package pkg) { return PackageCategoryRegistry.includes(id, pkg.getCategoryId()); }
        @Override public String toString() { return label; }
    }
    private static final class ReviewFilterOption {
        final Package.ReviewState state;
        final String label;
        ReviewFilterOption(Package.ReviewState state, String label) { this.state=state;
        this.label=label; }
        boolean includes(Package pkg) {
            if (state == null) { return true; }
            if (state == Package.ReviewState.NEEDS_REVIEW)
                { return pkg.getReviewState() == Package.ReviewState.NEEDS_REVIEW
                        || pkg.getReviewState() == Package.ReviewState.AUTO_CLASSIFIED; }
            return pkg.getReviewState() == state;
        }
        @Override public String toString() { return label; }
    }
    private static final class SourceFilterOption {
        final Package.SourceType source;
        final String label;
        SourceFilterOption(Package.SourceType source, String label) { this.source=source;
        this.label=label; }
        boolean includes(Package pkg) { return source == null || pkg.getSourceType() == source; }
        @Override public String toString() { return label; }
    }
}
