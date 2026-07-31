/*
 * Copyright (C) 2023 Jason von Nieda <jason@vonnieda.org>, Tony Luken <tonyluken62+openpnp@gmail.com>
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

package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import org.openpnp.ConfigurationListener;
import org.openpnp.Main;
import org.openpnp.Translations;
import org.openpnp.events.DefinitionStructureChangedEvent;
import org.openpnp.events.PlacementsHolderLocationSelectedEvent;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.events.PlacementSelectedEvent;
import org.openpnp.events.PlacementsHolderLocationChangedEvent;
import org.openpnp.gui.JobPanel.OpenRecentJobAction;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.gui.components.ExistingBoardOrPanelDialog;
import org.openpnp.gui.processes.MultiPlacementBoardLocationProcess;
import org.openpnp.gui.support.ActionGroup;
import org.openpnp.gui.support.CustomBooleanRenderer;
import org.openpnp.gui.support.MonospacedFontTableCellRenderer;
import org.openpnp.gui.support.MonospacedFontWithAffineStatusTableCellRenderer;
import org.openpnp.gui.support.MultisortTableHeaderCellRenderer;
import org.openpnp.gui.support.CustomPlacementsHolderRenderer;
import org.openpnp.gui.support.Helpers;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.gui.support.JobTelemetryManager;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.RotationCellValue;
import org.openpnp.gui.support.TableUtils;
import org.openpnp.gui.tablemodel.PlacementsHolderLocationsTableModel;
import org.openpnp.gui.viewers.PlacementsHolderLocationViewerDialog;
import org.openpnp.model.Board;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.model.Part;
import org.openpnp.model.Motion;
import org.openpnp.model.Placement;
import org.openpnp.model.Placement.Type;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.JobProcessor;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.JobProcessor.TextStatusListener;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;
import com.google.common.eventbus.Subscribe;

@SuppressWarnings("serial")  //$NON-NLS-1$
public class JobPanel extends JPanel {
    enum State {
        Stopped,
        Paused,
        Running,
        Pausing,
        Stopping
    }
    
    final private Configuration configuration;
    final private MainFrame mainFrame;

    private static final String PREF_DIVIDER_POSITION = "JobPanel.dividerPosition"; //$NON-NLS-1$
    private static final int PREF_DIVIDER_POSITION_DEF = -1;

    private static final String UNTITLED_JOB_FILENAME = "Untitled.job.xml"; //$NON-NLS-1$

    private static final String PREF_RECENT_FILES = "JobPanel.recentFiles"; //$NON-NLS-1$
    private static final int PREF_RECENT_FILES_MAX = 10;

    private PlacementsHolderLocationsTableModel jobTableModel;
    private JTable jobTable;
    private JSplitPane splitPane;

    private PlacementsHolderLocationViewerDialog jobViewer;
    
    private ActionGroup singleSelectionActionGroup;
    private ActionGroup multiSelectionActionGroup;
    private ActionGroup singleTopLevelSelectionActionGroup;
    private ActionGroup multiTopLevelSelectionActionGroup;

    private Preferences prefs = Preferences.userNodeForPackage(JobPanel.class);

    public JMenu mnOpenRecent;

    private List<File> recentJobs = new ArrayList<>();

    private final JobPlacementsPanel jobPlacementsPanel;

    private Job job;

    private JobProcessor jobProcessor;
    private final JobTelemetryManager telemetryManager;
    
    private State state = State.Stopped;
    private volatile boolean pauseWhenNozzlesEmptyRequested;
    
    public JobPanel(Configuration configuration, MainFrame frame) {
        this.configuration = configuration;
        this.mainFrame = frame;
        telemetryManager = new JobTelemetryManager(configuration);

        singleSelectionActionGroup =
                new ActionGroup(captureToolBoardLocationAction, moveCameraToBoardLocationAction,
                        moveCameraToBoardLocationNextAction, moveToolToBoardLocationAction,
                        twoPointLocateBoardLocationAction, fiducialCheckAction, packageAlignmentCheckAction,
                        setEnabledAction, setCheckFidsAction, setSideAction);
        singleSelectionActionGroup.setEnabled(false);
        
        multiSelectionActionGroup = new ActionGroup(captureToolBoardLocationAction, setEnabledAction, 
                setCheckFidsAction, setSideAction);
        multiSelectionActionGroup.setEnabled(false);
        
        singleTopLevelSelectionActionGroup = new ActionGroup(captureToolBoardLocationAction, removeBoardAction, captureCameraBoardLocationAction,
                moveCameraToBoardLocationAction,
                moveCameraToBoardLocationNextAction, moveToolToBoardLocationAction,
                twoPointLocateBoardLocationAction, fiducialCheckAction, packageAlignmentCheckAction,
                setEnabledAction, setCheckFidsAction, setSideAction);
        singleTopLevelSelectionActionGroup.setEnabled(false);
        
        multiTopLevelSelectionActionGroup = new ActionGroup(captureToolBoardLocationAction, removeBoardAction, setEnabledAction, 
                setCheckFidsAction, setSideAction);
        multiTopLevelSelectionActionGroup.setEnabled(false);
        
        jobTableModel = new PlacementsHolderLocationsTableModel(configuration);


        // Suppress because adding the type specifiers breaks WindowBuilder.
        @SuppressWarnings({"unchecked", "rawtypes"})  //$NON-NLS-1$  //$NON-NLS-2$
        JComboBox sidesComboBox = new JComboBox(Side.values());

        jobTable = new AutoSelectTextTable(jobTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {

                java.awt.Point p = e.getPoint();
                int row = rowAtPoint(p);
                int col = columnAtPoint(p);

                if (row >= 0) {
                    if (col == 0) {
                        row = jobTable.convertRowIndexToModel(row);
                        PlacementsHolderLocation<?> placementsHolderLocation =
                                job.getBoardAndPanelLocations().get(row);
                        return placementsHolderLocation.getUniqueId();
                    }
                    else if (col == 1) {
                        row = jobTable.convertRowIndexToModel(row);
                        PlacementsHolderLocation<?> placementsHolderLocation =
                                job.getBoardAndPanelLocations().get(row);
                        if (placementsHolderLocation != null) {
                            return placementsHolderLocation.getPlacementsHolder()
                                                .getFile()
                                                .toString();
                        }
                    }
                }

                return super.getToolTipText();
            }
        };

        //Filter out the first row because it is the job's root panel which is just a holder 
        //for all the real boards and panels of the job
        RowFilter<Object, Object> notFirstRow = new RowFilter<Object, Object>() {
            public boolean include(Entry<? extends Object, ? extends Object> entry) {
                return (Integer) entry.getIdentifier() != 0;
            }};
        jobTable.setAutoCreateRowSorter(true);
        ((TableRowSorter<? extends TableModel>) jobTable.getRowSorter()).setRowFilter(notFirstRow);
        
        jobTable.getTableHeader().setDefaultRenderer(new MultisortTableHeaderCellRenderer());
        jobTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        jobTable.setDefaultEditor(Side.class, new DefaultCellEditor(sidesComboBox));
        jobTable.setDefaultRenderer(Boolean.class, new CustomBooleanRenderer());
        jobTable.getColumnModel().getColumn(0).setCellRenderer(new CustomPlacementsHolderRenderer());
        jobTable.setDefaultRenderer(LengthCellValue.class, new MonospacedFontWithAffineStatusTableCellRenderer());
        jobTable.setDefaultRenderer(RotationCellValue.class, new MonospacedFontWithAffineStatusTableCellRenderer());
        jobTable.getColumnModel().getColumn(2).setCellRenderer(new MonospacedFontTableCellRenderer());
        jobTable.getColumnModel().getColumn(3).setCellRenderer(new MonospacedFontTableCellRenderer());
        jobTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        TableUtils.setColumnAlignment(jobTableModel, jobTable);
        
        TableUtils.installColumnWidthSavers(jobTable, prefs, "JobPanel.jobTable.columnWidth");  //$NON-NLS-1$
        
        jobTable.getModel().addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                SwingUtilities.invokeLater(() -> {
                    jobPlacementsPanel.refresh();
                });
            }
        });
        
        jobTable.getSelectionModel()
                .addListSelectionListener(new ListSelectionListener() {
                    @Override
                    public void valueChanged(ListSelectionEvent e) {
                        if (e.getValueIsAdjusting()) {
                            return;
                        }
                        
                        boolean updateLinkedTables = mainFrame.getTabs().getSelectedComponent() == mainFrame.getJobTab() 
                                && Configuration.get().getTablesLinked() == TablesLinked.Linked;
                        
                        List<PlacementsHolderLocation<?>> selections = getSelections();
                        if (selections.size() == 0) {
                            singleSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(false);
                            singleTopLevelSelectionActionGroup.setEnabled(false);
                            multiTopLevelSelectionActionGroup.setEnabled(false);
                            jobPlacementsPanel.setBoardOrPanelLocation(null);
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderLocationSelectedEvent(null, JobPanel.this));
                                Configuration.get().getBus()
                                    .post(new PlacementSelectedEvent(null, null, JobPanel.this));
                            }
                        }
                        else if (selections.size() == 1) {
                            multiSelectionActionGroup.setEnabled(false);
                            multiTopLevelSelectionActionGroup.setEnabled(false);
                            if (selections.get(0).getParent() == job.getRootPanelLocation()) {
                                singleSelectionActionGroup.setEnabled(false);
                                singleTopLevelSelectionActionGroup.setEnabled(true);
                            }
                            else {
                                singleTopLevelSelectionActionGroup.setEnabled(false);
                                singleSelectionActionGroup.setEnabled(true);
                            }
                            jobPlacementsPanel.setBoardOrPanelLocation(selections.get(0));
                            if (updateLinkedTables) {
                                if (selections.get(0).getParent() != job.getRootPanelLocation()) {
                                    Configuration.get().getBus()
                                    .post(new PlacementsHolderLocationSelectedEvent(
                                            selections.get(0).getParent().getDefinition(), JobPanel.this));
                                }
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderLocationSelectedEvent(
                                            selections.get(0).getDefinition(), JobPanel.this));
                                Configuration.get().getBus()
                                    .post(new PlacementSelectedEvent(null, selections.get(0), JobPanel.this));
                            }
                        }
                        else {
                            singleSelectionActionGroup.setEnabled(false);
                            singleTopLevelSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(false);
                            multiTopLevelSelectionActionGroup.setEnabled(true);
                            for (PlacementsHolderLocation<?> fll : selections) {
                                boolean ancestorSelected = false;
                                for (PlacementsHolderLocation<?> fll2 : selections) {
                                    if (fll.isDescendantOf(fll2)) {
                                        ancestorSelected = true;
                                        break;
                                    }
                                }
                                if (fll.getParent() != job.getRootPanelLocation() && !ancestorSelected) {
                                    multiTopLevelSelectionActionGroup.setEnabled(false);
                                    multiSelectionActionGroup.setEnabled(true);
                                    break;
                                }
                            }
                            jobPlacementsPanel.setBoardOrPanelLocation(null);
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderLocationSelectedEvent(null, JobPanel.this));
                                Configuration.get().getBus()
                                    .post(new PlacementSelectedEvent(null, null, JobPanel.this));
                            }
                        }
                        MainFrame.get().updateMenuState(JobPanel.this);
                        if (jobViewer != null) {
                            jobViewer.setPlacementsHolder(job.getRootPanelLocation().getPlacementsHolder(), getSelections());
                        }
                    }
                });

        setLayout(new BorderLayout(0, 0));

        splitPane = new JSplitPane();
        splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);
        splitPane
                .setDividerLocation(prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF));
        splitPane.addPropertyChangeListener("dividerLocation", new PropertyChangeListener() { //$NON-NLS-1$
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                prefs.putInt(PREF_DIVIDER_POSITION, splitPane.getDividerLocation());
            }
        });

        JPanel pnlBoards = new JPanel();
        pnlBoards.setBorder(new TitledBorder(null,
                Translations.getString("JobPanel.Tab.Boards"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null));
        pnlBoards.setLayout(new BorderLayout(0, 0));

        JToolBar toolBarBoards = new JToolBar();
        toolBarBoards.setFloatable(false);
        pnlBoards.add(toolBarBoards, BorderLayout.NORTH);

        JButton btnStartPauseResumeJob = new JButton(startPauseResumeJobAction);
        btnStartPauseResumeJob.setHideActionText(true);
        toolBarBoards.add(btnStartPauseResumeJob);
        JButton btnStepJob = new JButton(stepJobAction);
        btnStepJob.setHideActionText(true);
        toolBarBoards.add(btnStepJob);
        JButton btnStopJob = new JButton(stopJobAction);
        btnStopJob.setHideActionText(true);
        toolBarBoards.add(btnStopJob);
        JToggleButton btnPauseWhenNozzlesEmpty =
                new JToggleButton(pauseWhenNozzlesEmptyAction);
        btnPauseWhenNozzlesEmpty.setHideActionText(true);
        btnPauseWhenNozzlesEmpty.setFocusable(false);
        toolBarBoards.add(btnPauseWhenNozzlesEmpty);
        toolBarBoards.addSeparator();
        JButton btnDeferErrors = new JButton(deferErrorsAction);
        btnDeferErrors.setHideActionText(true);
        toolBarBoards.add(btnDeferErrors);
        toolBarBoards.addSeparator();
        JButton btnAddBoard = new JButton(addBoardAction);
        btnAddBoard.setHideActionText(true);
        btnAddBoard.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JPopupMenu menu = new JPopupMenu();
                menu.add(new JMenuItem(addNewBoardAction));
                menu.add(new JMenuItem(addExistingBoardAction));
                menu.addSeparator();
                menu.add(new JMenuItem(addNewPanelAction));
                menu.add(new JMenuItem(addExistingPanelAction));
                menu.show(btnAddBoard, (int) btnAddBoard.getWidth(), (int) btnAddBoard.getHeight());
            }
        });
        toolBarBoards.add(btnAddBoard);
        JButton btnRemoveBoard = new JButton(removeBoardAction);
        btnRemoveBoard.setHideActionText(true);
        toolBarBoards.add(btnRemoveBoard);
        
        toolBarBoards.addSeparator();
        
        JButton btnPositionCameraBoardLocation = new JButton(moveCameraToBoardLocationAction);
        btnPositionCameraBoardLocation.setHideActionText(true);
        toolBarBoards.add(btnPositionCameraBoardLocation);

        JButton btnPositionCameraBoardLocationNext =
                new JButton(moveCameraToBoardLocationNextAction);
        btnPositionCameraBoardLocationNext.setHideActionText(true);
        toolBarBoards.add(btnPositionCameraBoardLocationNext);
        
        JButton btnPositionToolBoardLocation = new JButton(moveToolToBoardLocationAction);
        btnPositionToolBoardLocation.setHideActionText(true);
        toolBarBoards.add(btnPositionToolBoardLocation);
        
        toolBarBoards.addSeparator();

        JButton btnCaptureCameraBoardLocation = new JButton(captureCameraBoardLocationAction);
        btnCaptureCameraBoardLocation.setHideActionText(true);
        toolBarBoards.add(btnCaptureCameraBoardLocation);

        JButton btnCaptureToolBoardLocation = new JButton(captureToolBoardLocationAction);
        btnCaptureToolBoardLocation.setHideActionText(true);
        toolBarBoards.add(btnCaptureToolBoardLocation);

        
        toolBarBoards.addSeparator();

        JButton btnTwoPointBoardLocation = new JButton(twoPointLocateBoardLocationAction);
        toolBarBoards.add(btnTwoPointBoardLocation);
        btnTwoPointBoardLocation.setHideActionText(true);

        JButton btnFiducialCheck = new JButton(fiducialCheckAction);
        toolBarBoards.add(btnFiducialCheck);
        btnFiducialCheck.setHideActionText(true);

        JButton btnPackageAlignmentCheck = new JButton(packageAlignmentCheckAction);
        toolBarBoards.add(btnPackageAlignmentCheck);
        btnPackageAlignmentCheck.setHideActionText(true);
        
        toolBarBoards.addSeparator();
        
        JButton btnViewer = new JButton(viewerAction);
        btnViewer.setHideActionText(true);
        toolBarBoards.add(btnViewer);

        pnlBoards.add(new JScrollPane(jobTable));

        splitPane.setLeftComponent(pnlBoards);

        jobPlacementsPanel = new JobPlacementsPanel(this);
        splitPane.setRightComponent(jobPlacementsPanel);
        
        add(splitPane);

        mnOpenRecent = new JMenu(Translations.getString("JobPanel.Action.Job.RecentJobs")); //$NON-NLS-1$
        mnOpenRecent.setMnemonic(KeyEvent.VK_R);
        loadRecentJobs();

        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            // FYI: this listener is executed asynchronously
            public void configurationComplete(Configuration configuration) throws Exception {
                Machine machine = configuration.getMachine();

                machine.addListener(machineListener);

                machine.getPnpJobProcessor().addTextStatusListener(textStatusListener);

                if (machine.isAutoLoadMostRecentJob()) {
                    // try to load the most recent job
                    if (recentJobs.size() > 0) {
                        File file = recentJobs.get(0);
                        loadJobExec(file);
                    }
                }

                // Create an empty Job if one is not loaded
                if (getJob() == null) {
                    setJob(new Job());
                }
            }
        });

        JPopupMenu popupMenu = new JPopupMenu();

        JMenu setSideMenu = new JMenu(setSideAction);
        for (Side side : Side.values()) {
            setSideMenu.add(new SetSideAction(side));
        }
        popupMenu.add(setSideMenu);

        JMenu setEnabledMenu = new JMenu(setEnabledAction);
        setEnabledMenu.add(new SetEnabledAction(true));
        setEnabledMenu.add(new SetEnabledAction(false));
        popupMenu.add(setEnabledMenu);

        JMenu setCheckFidsMenu = new JMenu(setCheckFidsAction);
        setCheckFidsMenu.add(new SetCheckFidsAction(true));
        setCheckFidsMenu.add(new SetCheckFidsAction(false));
        popupMenu.add(setCheckFidsMenu);

        jobTable.setComponentPopupMenu(popupMenu);

        Configuration.get().getBus().register(this);
    }
    
    void setState(State newState) {
        this.state = newState;
        if (newState == State.Stopped || newState == State.Paused
                || newState == State.Stopping) {
            setPauseWhenNozzlesEmptyRequested(false);
        }
        telemetryManager.stateChanged(newState.name(), job);
        updateJobActions();
    }

    private void setPauseWhenNozzlesEmptyRequested(boolean requested) {
        pauseWhenNozzlesEmptyRequested = requested;
        pauseWhenNozzlesEmptyAction.putValue(Action.SELECTED_KEY, requested);
        pauseWhenNozzlesEmptyAction.putValue(Action.SHORT_DESCRIPTION, requested
                ? "已请求：完成吸嘴上的物料贴装后暂停；再次点击可取消"
                : "完成所有吸嘴上的物料贴装，确认吸嘴为空后暂停");
    }

    private boolean shouldPauseWithEmptyNozzles() {
        return pauseWhenNozzlesEmptyRequested
                && areAllNozzlesEmpty(configuration.getMachine());
    }

    static boolean areAllNozzlesEmpty(Machine machine) {
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean shouldPauseImmediatelyWhenNozzlesEmpty(State state, Machine machine) {
        return state == State.Running && areAllNozzlesEmpty(machine);
    }
    
    public JTable getPlacementsHolderLocationsTable() {
        return jobTable;
    }

    @Subscribe
    public void placementsHolderLocationSelected(PlacementsHolderLocationSelectedEvent event) {
        if (event.source == this || event.source == jobTableModel) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            selectPlacementsHolderLocation(event.placementsHolderLocation);
        });
    }

    @Subscribe
    public void panelStructureChanged(DefinitionStructureChangedEvent event) {
        for (PanelLocation panelLocation : job.getPanelLocations()) {
            if (event.source != this && panelLocation.getPanel() != null && 
                    event.definition == panelLocation.getPanel().getDefinition() && 
                    event.changedName.contentEquals("children")) {  //$NON-NLS-1$
                PanelLocation.setParentsOfAllDescendants(job.getRootPanelLocation());
                SwingUtilities.invokeLater(() -> {
                    refresh();
                });
                break;
            }
        }
        job.getRootPanelLocation().dump("");  //$NON-NLS-1$
    }

    public void selectPlacementsHolderLocation(PlacementsHolderLocation<?> placementsHolderLocation) {
        if (placementsHolderLocation == null) {
            jobTable.getSelectionModel().clearSelection();
        }
        for (int i = 0; i < jobTableModel.getRowCount(); i++) {
            if (job.getBoardAndPanelLocations().get(i).getDefinition() == placementsHolderLocation) {
                int index = jobTable.convertRowIndexToView(i);
                jobTable.getSelectionModel().setSelectionInterval(index, index);
                jobTable.scrollRectToVisible(
                        new Rectangle(jobTable.getCellRect(index, 0, true)));
                break;
            }
        }
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        if (this.job != null) {
            this.job.removePropertyChangeListener("dirty", titlePropertyChangeListener); //$NON-NLS-1$
            this.job.removePropertyChangeListener("file", titlePropertyChangeListener); //$NON-NLS-1$
            this.job.getRootPanelLocation().getPanel().removeAllChildren();
        }
        this.job = job;
        jobTableModel.setJob(job);
        job.addPropertyChangeListener("dirty", titlePropertyChangeListener); //$NON-NLS-1$
        job.addPropertyChangeListener("file", titlePropertyChangeListener); //$NON-NLS-1$
        updateTitle();
        updateJobActions();
        jobPlacementsPanel.updateActivePlacements();
        if (jobViewer != null) {
            jobViewer.setPlacementsHolder(job.getRootPanelLocation().getPlacementsHolder(), getSelections());
        }
        Configuration.get().getBus().post(new JobLoadedEvent(job));
    }

    public JobPlacementsPanel getJobPlacementsPanel() {
        return jobPlacementsPanel;
    }

    public PlacementsHolderLocationViewerDialog getJobViewer() {
        return jobViewer;
    }

    private void updateRecentJobsMenu() {
        mnOpenRecent.removeAll();
        for (File file : recentJobs) {
            mnOpenRecent.add(new OpenRecentJobAction(file));
        }
    }

    private void loadRecentJobs() {
        recentJobs.clear();
        for (int i = 0; i < PREF_RECENT_FILES_MAX; i++) {
            String path = prefs.get(PREF_RECENT_FILES + "_" + i, null); //$NON-NLS-1$
            if (path != null && new File(path).exists()) {
                File file = new File(path);
                recentJobs.add(file);
            }
        }
        updateRecentJobsMenu();
    }

    public List<File> getRecentJobs() {
        return Collections.unmodifiableList(new ArrayList<>(recentJobs));
    }

    private void saveRecentJobs() {
        // blow away all the existing values
        for (int i = 0; i < PREF_RECENT_FILES_MAX; i++) {
            prefs.remove(PREF_RECENT_FILES + "_" + i); //$NON-NLS-1$
        }
        // update with what we have now
        for (int i = 0; i < recentJobs.size(); i++) {
            prefs.put(PREF_RECENT_FILES + "_" + i, recentJobs.get(i).getAbsolutePath()); //$NON-NLS-1$
        }
        updateRecentJobsMenu();
    }

    private void addRecentJob(File file) {
        while (recentJobs.contains(file)) {
            recentJobs.remove(file);
        }
        // add to top
        recentJobs.add(0, file);
        // limit length
        while (recentJobs.size() > PREF_RECENT_FILES_MAX) {
            recentJobs.remove(recentJobs.size() - 1);
        }
        saveRecentJobs();
    }

    public void refresh() {
        jobTableModel.fireTableDataChanged();
    }

    public void refreshSelectedRow() {
        int index = jobTable.convertRowIndexToModel(jobTable.getSelectedRow());
        List<PlacementsHolderLocation<?>> boardAndPanelLocations = job.getBoardAndPanelLocations();
        PlacementsHolderLocation<?> selectedLocation = boardAndPanelLocations.get(index);
        jobTableModel.fireTableCellDecendantsUpdated(selectedLocation, TableModelEvent.ALL_COLUMNS);
    }

    public PlacementsHolderLocation<?> getSelection() {
        List<PlacementsHolderLocation<?>> selections = getSelections();
        if (selections.isEmpty()) {
            return null;
        }
        return selections.get(0);
    }

    public List<PlacementsHolderLocation<?>> getSelections() {
        ArrayList<PlacementsHolderLocation<?>> selections = new ArrayList<>();
        int[] selectedRows = jobTable.getSelectedRows();
        for (int selectedRow : selectedRows) {
            selectedRow = jobTable.convertRowIndexToModel(selectedRow);
            selections.add(job.getBoardAndPanelLocations().get(selectedRow));
        }
        return selections;
    }

    /**
     * Checks if there are any modifications that need to be saved. Prompts the user if there are.
     * Returns true if it's okay to exit.
     * 
     * @return
     */
    public boolean checkForModifications() {
        if (!checkForJobModifications()) {
            return false;
        }
        return true;
    }

    private boolean checkForJobModifications() {
        if (getJob().isDirty()) {
            String name = (job.getFile() == null ? UNTITLED_JOB_FILENAME : job.getFile().getName());
            int result = JOptionPane.showConfirmDialog(mainFrame,
                    Translations.getString("JobPanel.CheckForModifications.Dialog.Question") + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                            + Translations.getString("JobPanel.CheckForModifications.Dialog.Message"), //$NON-NLS-1$
                    Translations.getString("JobPanel.CheckForModifications.Dialog.Title") //$NON-NLS-1$
                    + " - " + name, JOptionPane.YES_NO_CANCEL_OPTION); //$NON-NLS-1$ //$NON-NLS-2$
            if (result == JOptionPane.YES_OPTION) {
                return saveJob();
            }
            else if (result == JOptionPane.CANCEL_OPTION) {
                return false;
            }
        }
        return true;
    }

    private boolean saveJob() {
        if (getJob().getFile() == null) {
            return saveJobAs();
        }
        else {
            try {
                File file = getJob().getFile();
                configuration.saveJob(getJob(), file);
                addRecentJob(file);
                return true;
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.SaveJob.Error.ErrorBox.Title"), e.toString()); //$NON-NLS-1$
                return false;
            }
        }
    }

    private boolean saveJobAs() {
        FileDialog fileDialog = new FileDialog(mainFrame, 
                Translations.getString("JobPanel.SaveJobAs.FileDialog.Title"), FileDialog.SAVE); //$NON-NLS-1$
        fileDialog.setFilenameFilter(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".job.xml"); //$NON-NLS-1$
            }
        });
        fileDialog.setFile("*.job.xml"); //$NON-NLS-1$
        fileDialog.setVisible(true);
        try {
            String filename = fileDialog.getFile();
            if (filename == null) {
                return false;
            }
            if (!filename.toLowerCase().endsWith(".job.xml")) { //$NON-NLS-1$
                filename = filename + ".job.xml"; //$NON-NLS-1$
            }
            File file = new File(new File(fileDialog.getDirectory()), filename);
            if (file.exists()) {
                int ret = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                        file.getName() + Translations.getString("JobPanel.SaveJobAs.ConfirmDialog.Title"), //$NON-NLS-1$
                        Translations.getString("JobPanel.SaveJobAs.ConfirmDialog.Question"),  //$NON-NLS-1$
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ret != JOptionPane.YES_OPTION) {
                    return false;
                }
            }
            configuration.saveJob(getJob(), file);
            addRecentJob(file);
            return true;
        }
        catch (Exception e) {
            MessageBoxes.errorBox(mainFrame, 
                    Translations.getString("JobPanel.SaveJobAs.ErrorBox.Title"), e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Updates the Job controls based on the Job state and the Machine's readiness.
     */
    private void updateJobActions() {
        pauseWhenNozzlesEmptyAction.setEnabled(state == State.Running);
        if (state == State.Stopped) {
            startPauseResumeJobAction.setEnabled(true);
            startPauseResumeJobAction.putValue(AbstractAction.NAME,
                    Translations.getString("JobPanel.Action.Job.Start")); //$NON-NLS-1$
            startPauseResumeJobAction.putValue(AbstractAction.SMALL_ICON, Icons.start);
            startPauseResumeJobAction.putValue(AbstractAction.SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Start.Description")); //$NON-NLS-1$
            stopJobAction.setEnabled(false);
            stepJobAction.setEnabled(true);
        }
        else if (state == State.Running) {
            startPauseResumeJobAction.setEnabled(true);
            startPauseResumeJobAction.putValue(AbstractAction.NAME,
                    Translations.getString("JobPanel.Action.Job.Pause")); //$NON-NLS-1$
            startPauseResumeJobAction.putValue(AbstractAction.SMALL_ICON, Icons.pause);
            startPauseResumeJobAction.putValue(AbstractAction.SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Pause.Description")); //$NON-NLS-1$
            stopJobAction.setEnabled(true);
            stepJobAction.setEnabled(false);
        }
        else if (state == State.Paused) {
            startPauseResumeJobAction.setEnabled(true);
            startPauseResumeJobAction.putValue(AbstractAction.NAME,
                    Translations.getString("JobPanel.Action.Job.Resume")); //$NON-NLS-1$
            startPauseResumeJobAction.putValue(AbstractAction.SMALL_ICON, Icons.start);
            startPauseResumeJobAction.putValue(AbstractAction.SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Resume.Description")); //$NON-NLS-1$
            stopJobAction.setEnabled(true);
            stepJobAction.setEnabled(true);
        }
        else if (state == State.Pausing) {
            startPauseResumeJobAction.setEnabled(false);
            stopJobAction.setEnabled(false);
            stepJobAction.setEnabled(false);
        }
        else if (state == State.Stopping) {
            startPauseResumeJobAction.setEnabled(false);
            stopJobAction.setEnabled(false);
            stepJobAction.setEnabled(false);
        }

        if(job.getErrorHandling()==Job.ErrorHandling.Defer) {
            deferErrorsAction.putValue(AbstractAction.SMALL_ICON, Icons.errorDefer);
            deferErrorsAction.putValue(AbstractAction.NAME, Translations.getString("JobPanel.Action.Job.ErrorDefer")); //$NON-NLS-1$
            deferErrorsAction.putValue(AbstractAction.SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.ErrorDefer.Description")); //$NON-NLS-1$
        } else {
            deferErrorsAction.putValue(AbstractAction.SMALL_ICON, Icons.errorAlert);
            deferErrorsAction.putValue(AbstractAction.NAME, Translations.getString("JobPanel.Action.Job.ErrorAlert")); //$NON-NLS-1$
            deferErrorsAction.putValue(AbstractAction.SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.ErrorAlert.Description")); //$NON-NLS-1$
        }

        // We allow the above to run first so that all state is represented
        // correctly even if the machine is disabled.
        if (!configuration.getMachine().isEnabled()) {
            startPauseResumeJobAction.setEnabled(false);
            stopJobAction.setEnabled(false);
            stepJobAction.setEnabled(false);
            pauseWhenNozzlesEmptyAction.setEnabled(false);
        }
    }

    private void updateTitle() {
        String title = String.format("OpenPnP Pro %s - %s%s", Main.getVersionString(),
                job.isDirty() ? "*" : "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                (job.getFile() == null ? UNTITLED_JOB_FILENAME : job.getFile().getName()));
        mainFrame.setTitle(title);
        if (jobViewer != null) {
            jobViewer.setTitle(title);
        }
    }
    
    private boolean checkJobStopped() {
        if (state != State.Stopped) {
            MessageBoxes.errorBox(this, 
                    Translations.getString("JobPanel.CheckJobStopped.Error.ErrorBox.Title"),  //$NON-NLS-1$
                    Translations.getString("JobPanel.CheckJobStopped.Error.ErrorBox.Message")); //$NON-NLS-1$
            return false;
        }
        return true;
    }

    public final Action openJobAction = new AbstractAction(Translations.getString("JobPanel.Action.Job.Open")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_O);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke('O',
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (!checkJobStopped()) {
                return;
            }
            if (!checkForModifications()) {
                return;
            }
            FileDialog fileDialog = new FileDialog(mainFrame);
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".job.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setVisible(true);
            try {
                if (fileDialog.getFile() == null) {
                    return;
                }
                File file = new File(new File(fileDialog.getDirectory()), fileDialog.getFile());
                loadJobExec(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.Open.ErrorBox.Title"), e.getMessage()); //$NON-NLS-1$
            }
        }
    };

    public final Action newJobAction = new AbstractAction(Translations.getString("JobPanel.Action.Job.New")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke('N',
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (!checkJobStopped()) {
                return;
            }
            if (!checkForModifications()) {
                return;
            }
            setJob(new Job());
        }
    };

    public final Action saveJobAction = new AbstractAction(Translations.getString("JobPanel.Action.Job.Save")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_S);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke('S',
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            saveJob();
        }
    };

    public final Action saveJobAsAction = new AbstractAction(Translations.getString("JobPanel.Action.Job.SaveAs")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_A);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            saveJobAs();
        }
    };

    /**
     * Initialize the job processor and start the run thread. The run thread will run one step and
     * then either loop if the state is Running or exit if the state is Stepping.
     * 
     * @throws Exception
     */
    public void jobStart() throws Exception {
        jobProcessor = Configuration.get().getMachine().getPnpJobProcessor();
        if (isAllPlaced()) {
            int ret = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                    Translations.getString("JobPanel.JobStart.ResetPlacements.ConfirmDialog.Question"), //$NON-NLS-1$
                    Translations.getString("JobPanel.JobStart.ResetPlacements.ConfirmDIalog.Title"), JOptionPane.YES_NO_OPTION, //$NON-NLS-1$
                    JOptionPane.WARNING_MESSAGE);
            if (ret == JOptionPane.YES_OPTION) {
                job.removeAllPlacedStatus();
                jobPlacementsPanel.refresh();
            }
        }
        telemetryManager.start(job);
        try {
            jobProcessor.initialize(job);
            jobRun();
        }
        catch (Exception exception) {
            setState(State.Stopped);
            throw exception;
        }
    }
    
    public void jobRun() {
        UiUtils.submitUiMachineTask(() -> {
            // For optional motion stepping, remember the past move.
            MotionPlanner motionPlanner = Configuration.get().getMachine().getMotionPlanner();
            Motion pastMotion = motionPlanner.getLastMotion();
            do {
                do {
                    if (shouldPauseWithEmptyNozzles()) {
                        setState(State.Pausing);
                        break;
                    }
                    if (!jobProcessor.next()) {
                        setState(State.Stopped);
                        break;
                    }
                    else if (shouldPauseWithEmptyNozzles()) {
                        setState(State.Pausing);
                        break;
                    }
                    else if (state == State.Pausing) {
                        // We're pausing, but check if we need motion before we can pause for real.
                        Motion lastMotion = motionPlanner.getLastMotion();
                        if (! (jobProcessor.isSteppingToNextMotion() && lastMotion == pastMotion)) {
                            break;
                        }
                    }
                }
                while (state == State.Pausing);
            } while (state == State.Running);
            
            if (state == State.Pausing) {
                setState(State.Paused);
            }

            return null;
        }, (e) -> {

        }, (t) -> {
            telemetryManager.recordError(
                    Translations.getString("JobPanel.JobRun.Error.ErrorBox.Title"), t); //$NON-NLS-1$
            /**
             * TODO It would be nice to give the user the ability to single click suppress errors
             * on the currently processing placement, but that requires knowledge of the currently
             * processing placement. With the current model where JobProcessor is available for
             * both dispense and PnP this is not possible. Once dispense is removed we can include
             * the current placement in the thrown error and add this feature.
             */
            JobProcessorException processorException = null;
            if (t instanceof JobProcessorException) {
                processorException = (JobProcessorException)t;
                selectJobProcessorExceptionSource(processorException.getSecondarySource(),false);
                selectJobProcessorExceptionSource(processorException.getSource(),true);
            }
            
            // update the state before showing the error to allow exceptions with continuations to change it
            if (state == State.Running || state == State.Pausing) {
                setState(State.Paused);
            }
            else if (state == State.Stopping) {
                setState(State.Stopped);
            }
            
            // call showError() to support exceptions with continuation
            if (processorException == null || !showBottomVisionRecovery(processorException)) {
                UiUtils.showError(getTopLevelAncestor(),
                        Translations.getString("JobPanel.JobRun.Error.ErrorBox.Title"), t); //$NON-NLS-1$
            }
        });
    }

    private boolean showBottomVisionRecovery(JobProcessorException exception) {
        if (!(jobProcessor instanceof ReferencePnpJobProcessor)
                || !(exception.getSource() instanceof Part)
                || !(exception.getSecondarySource() instanceof Nozzle)) {
            return false;
        }

        ReferencePnpJobProcessor processor = (ReferencePnpJobProcessor) jobProcessor;
        Nozzle nozzle = (Nozzle) exception.getSecondarySource();
        if (!processor.canRecoverFailedAlignment(nozzle)) {
            return false;
        }
        Part part = (Part) exception.getSource();
        Object[] options = {
                "Discard + Re-pick",
                "Retry Vision",
                "Keep Paused",
                "Abort Job",
                "Details"
        };
        String message = "Bottom-vision alignment failed for " + part.getId()
                + " on " + nozzle.getName() + ".\n\n"
                + exception.getMessage() + "\n\nChoose a safe recovery action:";
        int choice = JOptionPane.showOptionDialog(getTopLevelAncestor(), message,
                "Bottom Vision Recovery", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);

        if (choice == 0) {
            UiUtils.submitUiMachineTask(
                    () -> processor.discardAndRequeueFailedAlignment(nozzle),
                    requeued -> {
                        if (Boolean.TRUE.equals(requeued)) {
                            jobResume();
                        }
                        else {
                            UiUtils.showError(new Exception(
                                    "The failed alignment is no longer active; nothing was discarded."));
                        }
                    },
                    failure -> UiUtils.showError(getTopLevelAncestor(),
                            "Bottom Vision Recovery", failure));
        }
        else if (choice == 1) {
            jobResume();
        }
        else if (choice == 3) {
            setState(State.Stopping);
            jobAbort();
        }
        else if (choice == 4) {
            UiUtils.showError(getTopLevelAncestor(), "Bottom Vision Details", exception);
        }
        // Keep Paused and closing the dialog intentionally leave the processor paused.
        return true;
    }
    private void selectJobProcessorExceptionSource(Object source,boolean primary) {
        // decode the source of the exception and try to select as much as possible
        if (source==null)
        {
        }
        else if (source instanceof BoardLocation) {
            BoardLocation b = (BoardLocation)source;
            Logger.debug("Exception source is board location: {}", b);
            // select the board
            Helpers.selectObjectTableRow(jobTable, b);
            if(primary) {
                // focus the job tab
                MainFrame.get().getTabs().setSelectedComponent(MainFrame.get().getJobTab());
            }
        } else if (source instanceof Placement) {
            Placement p = (Placement)source;
            Logger.debug("Exception source is placement: {}", p);

            // select the board this placement belongs to
            for (BoardLocation boardLocation : job.getBoardLocations()) {
                if (boardLocation.getBoard().getPlacements().contains(p)) {
                    // this is the board, that contains the placement that caused the error
                    Helpers.selectObjectTableRow(jobTable, boardLocation);
                }
            }

            // select the placement itself
            Helpers.selectObjectTableRow(jobPlacementsPanel.getTable(), p);
            if(primary) {
                // focus the job tab
                MainFrame.get().getTabs().setSelectedComponent(MainFrame.get().getJobTab());
            }
        } else if (source instanceof Part) {
            Part p = (Part)source;
            Logger.debug("Exception source is part: {}", p);
            // select part in parts tab
            MainFrame.get().getPartsTab().selectPartInTableAndUpdateLinks(p);
            if(primary) {
                // focus the parts tab
                MainFrame.get().getTabs().setSelectedComponent(MainFrame.get().getPartsTab());
            }
        } else if (source instanceof Feeder) {
            Feeder f = (Feeder)source;
            Logger.debug("Exception source is feeder: {}", f);
            // select the feeder in the feeders tab
            MainFrame.get().getFeedersTab().selectFeederInTable(f);
            if(primary) {
                // focus the feeder tab
                MainFrame.get().getTabs().setSelectedComponent(MainFrame.get().getFeedersTab());
            }
        } else if (source instanceof Nozzle) {
            Nozzle n = (Nozzle)source;
            Logger.debug("Exception source is nozzle: {}", n);
            // select the nozzle and part in the table
            MainFrame.get().getMachineControls().setSelectedTool(n);
            MainFrame.get().getPartsTab().selectPartInTableAndUpdateLinks(n.getPart());
        } else {
            Logger.debug("Exception contains an unsupported source: {}", source.getClass());
        }
    }

    private void jobAbort() {
        UiUtils.submitUiMachineTask(() -> {
            try {
                jobProcessor.abort();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            setState(State.Stopped);
        });
    }
    
    // resume a job that's currently in state Paused - used to continue after a manual nozzle tip change from within the JobProcessor
    public void jobResume() {
        if (state == State.Paused) {
            setState(State.Running);
            jobRun();
        } else {
            Logger.debug("Can't resume, job not in Paused state.");
        }
    }
    
    public final Action startPauseResumeJobAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.start);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Start")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Start.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                if (state == State.Stopped) {
                    setState(State.Running);
                    jobStart();
                }
                else if (state == State.Paused) {
                    setState(State.Running);
                    jobRun();
                }
                // If we're running and the user hits pause we pause.
                else if (state == State.Running) {
                    setState(State.Pausing);
                }
                else {
                    throw new Exception("Don't know how to change from state " + state); //$NON-NLS-1$
                }
            });
        }
    };

    public final Action stepJobAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.step);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Step")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Step.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                if (state == State.Stopped) {
                    setState(State.Pausing);
                    jobStart();
                }
                else if (state == State.Paused) {
                    setState(State.Pausing);
                    jobRun();
                }
            });
        }
    };

    public final Action stopJobAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.stop);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Stop")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Stop.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                setState(State.Stopping);
                jobAbort();
            });
        }
    };

    public final Action pauseWhenNozzlesEmptyAction = new AbstractAction(
            "吸嘴清空后暂停", Icons.pauseWhenNozzlesEmpty) {
        {
            putValue(SHORT_DESCRIPTION,
                    "完成所有吸嘴上的物料贴装，确认吸嘴为空后暂停");
            putValue(SELECTED_KEY, false);
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            if (state != State.Running) {
                return;
            }
            if (shouldPauseImmediatelyWhenNozzlesEmpty(state, configuration.getMachine())) {
                // With no carried part there is nothing to drain. Behave exactly like the normal
                // pause button instead of arming a request that can appear to do nothing.
                setPauseWhenNozzlesEmptyRequested(false);
                setState(State.Pausing);
                return;
            }
            setPauseWhenNozzlesEmptyRequested(!pauseWhenNozzlesEmptyRequested);
        }
    };

    public final Action deferErrorsAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.errorDefer);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Defer")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Defer.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            job.setErrorHandling(job.getErrorHandling()==Job.ErrorHandling.Defer ? Job.ErrorHandling.Alert : Job.ErrorHandling.Defer );
            updateJobActions();
        }
    };
    
    public final Action resetAllPlacedAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.ResetAllPlaced")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.ResetAllPlaced.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            int result = JOptionPane.showConfirmDialog(JobPanel.this,
                    "确定把当前任务的全部器件复位为未贴片吗？此操作不会移动机器。",
                    "复位贴片状态", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                job.removeAllPlacedStatus();
                jobPlacementsPanel.refresh();
            }
        }
    };

    public final Action addBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.AddBoard")); //$NON-NLS-1$
            putValue(SMALL_ICON, Icons.add);
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.AddBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_A);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    public final Action addNewBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.AddBoard.NewBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.AddBoard.NewBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(mainFrame, 
                    Translations.getString("JobPanel.Action.Job.SaveNewBoardAs.FileDialog.Title"), FileDialog.SAVE); //$NON-NLS-1$
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".board.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.board.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".board.xml")) { //$NON-NLS-1$
                    filename = filename + ".board.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                addBoard(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.AddNewBoard.Error.ErrorBox.Title"), e.getMessage()); //$NON-NLS-1$
            }
        }
    };

    public final Action addExistingBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.AddBoard.ExistingBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.AddBoard.ExistingBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_E);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            ExistingBoardOrPanelDialog existingBoardDialog = new ExistingBoardOrPanelDialog(
                    Configuration.get(), Board.class, 
                    Translations.getString("JobPanel.Action.Job.AddBoard.ExistingBoard.Dialog.Title")); //$NON-NLS-1$
            existingBoardDialog.setVisible(true);
            File file = existingBoardDialog.getFile();
            existingBoardDialog.dispose();
            if (file == null) {
                return;
            }
            try {
                addBoard(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.AddBoard.ExistingBoard.Error.ErrorBox.Title"),  //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    protected void addBoard(File file) throws Exception {
        Board board = new Board(configuration.getBoard(file));
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setLocation(Configuration.get().getMachine().getDefaultBoardLocation());
        
        Configuration.get().resolveBoard(job, boardLocation);
        
        job.addBoardOrPanelLocation(boardLocation);
        job.getRootPanelLocation().dump(""); //$NON-NLS-1$
        // TODO: Move to a list property listener.
        jobTableModel.fireTableDataChanged();
        Configuration.get().getBus().post(new PlacementsHolderLocationChangedEvent(boardLocation, "all", null, null, this));
        Helpers.selectObjectTableRow(jobTable, boardLocation);
    }
    
    public final Action addNewPanelAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.AddBoard.NewPanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.AddBoard.NewPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(mainFrame, 
                    Translations.getString("JobPanel.Action.Job.AddBoard.NewPanel.FileDialog.Title"), FileDialog.SAVE); //$NON-NLS-1$
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".panel.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.panel.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".panel.xml")) { //$NON-NLS-1$
                    filename = filename + ".panel.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                addPanel(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.AddBoard.NewPanel.Error.ErrorBox.Title"), //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    public final Action addExistingPanelAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.AddBoard.ExistingPanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.AddBoard.ExistingPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_E);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            ExistingBoardOrPanelDialog existingPanelDialog = new ExistingBoardOrPanelDialog(
                    Configuration.get(), Panel.class, 
                    Translations.getString("JobPanel.Action.Job.AddBoard.ExitingPanel.Dialog.Title")); //$NON-NLS-1$
            existingPanelDialog.setVisible(true);
            File file = existingPanelDialog.getFile();
            existingPanelDialog.dispose();
            if (file == null) {
                return;
            }
            try {
                addPanel(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.AddBoard.ExistingPanel.Error.ErrorBox.Title"),  //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    protected void addPanel(File file) throws Exception {
        Panel panel = new Panel(configuration.getPanel(file));
        PanelLocation panelLocation = new PanelLocation(panel);
        panelLocation.setLocation(Configuration.get().getMachine().getDefaultBoardLocation());
        
        Configuration.get().resolvePanel(job, panelLocation);
        
        job.addBoardOrPanelLocation(panelLocation);
        job.getRootPanelLocation().dump(""); //$NON-NLS-1$
        // TODO: Move to a list property listener.
        jobTableModel.fireTableDataChanged();
        Configuration.get().getBus().post(new PlacementsHolderLocationChangedEvent(panelLocation, "all", null, null, this));
        Helpers.selectObjectTableRow(jobTable, panelLocation);
    }
    
    public final Action removeBoardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.delete);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.RemoveBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.RemoveBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            for (PlacementsHolderLocation<?> selection : getSelections()) {
                job.removeBoardOrPanelLocation(selection);
            }
            jobTableModel.fireTableDataChanged();
            Configuration.get().getBus().post(new DefinitionStructureChangedEvent(job.getRootPanelLocation().getPanel(), "children", this));
        }
    };

    public final Action captureCameraBoardLocationAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.captureCamera);
            putValue(NAME,Translations.getString("JobPanel.Action.Job.Board.CaptureCameraLocation")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Board.CaptureCameraLocation.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                PlacementsHolderLocation<?> selectedLocation = getSelection();
                if (selectedLocation.getParent() != job.getRootPanelLocation()) {
                    throw new Exception(Translations.getString("JobPanel.Action.Job.Board.CaptureCameraLocation.Exception.Message")); //$NON-NLS-1$
                }
                HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                Camera camera = tool.getHead().getDefaultCamera();
                double z = selectedLocation.getGlobalLocation().getZ();
                selectedLocation.setLocation(camera.getLocation().derive(null, null, z, null));
                refreshSelectedRow();
            });
        }
    };

    public final Action captureToolBoardLocationAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.captureTool);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.CaptureToolLocation")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Board.CaptureToolLocation.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                List<PlacementsHolderLocation<?>> selections = getSelections();
                if (selections.size() == 1 && selections.get(0).getParent() != job.getRootPanelLocation()) {
                    throw new Exception(Translations.getString("JobPanel.Action.Job.Board.CaptureToolLocation.Exception.Message")); //$NON-NLS-1$
                }
                Length toolZ = MainFrame.get().getMachineControls().getSelectedTool().getLocation().getLengthZ();
                for (PlacementsHolderLocation<?> selection : selections) {
                    if (selection.getParent() == job.getRootPanelLocation()) {
                        selection.setLocation(selection.getGlobalLocation().deriveLengths(null, null, toolZ, null));
                        jobTableModel.fireTableCellDecendantsUpdated(selection, "Z"); //$NON-NLS-1$
                    }
                }
            });
        }
    };

    public final Action moveCameraToBoardLocationAction = new AbstractAction() {
                {
                    putValue(SMALL_ICON, Icons.centerCamera);
                    putValue(NAME, Translations.getString("JobPanel.Action.Job.Camera.PositionAtBoardLocation")); //$NON-NLS-1$
                    putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Camera.PositionAtBoardLocation.Description")); //$NON-NLS-1$
                }

                @Override
                public void actionPerformed(ActionEvent arg0) {
                    UiUtils.submitUiMachineTask(() -> {
                        HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                        Camera camera = tool.getHead().getDefaultCamera();
                        Location location = getSelection().getGlobalLocation();
                        MovableUtils.moveToLocationAtSafeZ(camera, location);
                        MovableUtils.fireTargetedUserAction(camera);

                        Map<String, Object> globals = new HashMap<>();
                        globals.put("camera", camera); //$NON-NLS-1$
                        Configuration.get().getScripting().on("Camera.AfterPosition", globals); //$NON-NLS-1$
                    });
                }
            };
    public final Action packageAlignmentCheckAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.colorTrue);
            putValue(NAME, "Package Alignment Check");
            putValue(SHORT_DESCRIPTION,
                    "Preview every component and optionally move the camera to the selected board origin");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            UiUtils.messageBoxOnException(() -> {
                PlacementsHolderLocation<?> selection = getSelection();
                if (!(selection instanceof BoardLocation)) {
                    throw new Exception("Select one board before starting package alignment check.");
                }
                if (selection.getPlacementsTransformStatus()
                        == PlacementsHolderLocation.PlacementsTransformStatus.NotSet) {
                    throw new Exception(
                            "The selected board origin is not configured. Set the board origin before moving.");
                }

                HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                Camera camera = tool.getHead().getDefaultCamera();
                Machine machine = configuration.getMachine();
                if (machine.isEnabled() && !machine.isHomed()) {
                    viewerAction.actionPerformed(event);
                    JOptionPane.showMessageDialog(JobPanel.this,
                            "All components are shown, but camera movement was skipped because the machine is not homed.",
                            "Package Alignment Check", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                UiUtils.confirmMoveToLocationAndAct(JobPanel.this,
                        "move the camera to the selected board origin", camera,
                        selection.getGlobalLocation(), true,
                        () -> viewerAction.actionPerformed(event));
            });
        }
    };
    public final Action moveCameraToBoardLocationNextAction = new AbstractAction() {
                {
                    putValue(SMALL_ICON, Icons.centerCameraMoveNext);
                    putValue(NAME, Translations.getString("JobPanel.Action.Job.Camera.PositionAtNextBoardLocation")); //$NON-NLS-1$
                    putValue(SHORT_DESCRIPTION,
                            Translations.getString("JobPanel.Action.Job.Camera.PositionAtNextBoardLocation.Description")); //$NON-NLS-1$
                }

                @Override
                public void actionPerformed(ActionEvent arg0) {
                    UiUtils.submitUiMachineTask(() -> {
                        // Need to keep current focus owner so that the space bar can be
                        // used after the initial click. Otherwise, button focus is lost
                        // when table is updated
                    	Component comp = MainFrame.get().getFocusOwner();
                    	Helpers.selectNextTableRow(jobTable);
                    	comp.requestFocus();
                        HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                        Camera camera = tool.getHead().getDefaultCamera();
                        Location location = getSelection().getGlobalLocation();
                        MovableUtils.moveToLocationAtSafeZ(camera, location);
                        MovableUtils.fireTargetedUserAction(camera);

                        Map<String, Object> globals = new HashMap<>();
                        globals.put("camera", camera); //$NON-NLS-1$
                        Configuration.get().getScripting().on("Camera.AfterPosition", globals); //$NON-NLS-1$
                    });
                }
            };

    public final Action moveToolToBoardLocationAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.centerTool);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Tool.PositionAtBoardLocation")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Tool.PositionAtBoardLocation.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.submitUiMachineTask(() -> {
                HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                Location location = getSelection().getGlobalLocation();
                MovableUtils.moveToLocationAtSafeZ(tool, location);
                MovableUtils.fireTargetedUserAction(tool);
            });
        }
    };

    public final Action twoPointLocateBoardLocationAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.twoPointLocate);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.TwoPointBoardLocation")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Board.TwoPointBoardLocation.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.messageBoxOnException(() -> {
                new MultiPlacementBoardLocationProcess(mainFrame, JobPanel.this);
            });
        }
    };

    public final Action fiducialCheckAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.fiducialCheck);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.FiducialCheck")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Board.FiducialCheck.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.submitUiMachineTask(() -> {
                PlacementsHolderLocation<?> placementsHolderLocation = getSelection();
                Location location = Configuration.get().getMachine().getFiducialLocator()
                        .locatePlacementsHolder(placementsHolderLocation);
                
                /**
                 * Update the board/panel's location to the one returned from the fiducial check. We
                 * have to store and restore the placement transform because setting the location
                 * clears it.  Note that we only update the location if the board/panel is
                 * not a part of another panel.
                 */
                if (placementsHolderLocation.getParent() == job.getRootPanelLocation()) {
                    AffineTransform tx = placementsHolderLocation.getLocalToGlobalTransform();
                    placementsHolderLocation.setLocation(location);
                    placementsHolderLocation.setLocalToGlobalTransform(tx);
                }
                refreshSelectedRow();
                
                /**
                 * Move the camera to the calculated position.
                 */
                HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                Camera camera = tool.getHead().getDefaultCamera();
                MovableUtils.moveToLocationAtSafeZ(camera, location);
                MovableUtils.fireTargetedUserAction(camera);
                
                Helpers.selectObjectTableRow(jobTable, placementsHolderLocation);
            });
        }
    };

    public final Action viewerAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.colorTrue);
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.ViewJob")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Board.ViewJob.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {            
            if (jobViewer == null) {
                jobViewer = new PlacementsHolderLocationViewerDialog(job.getRootPanelLocation(), true, getSelections());
                jobViewer.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        jobViewer = null;
                    }
                });
            }
            else {
                jobViewer.setPlacementsHolder(job.getRootPanelLocation().getPlacementsHolder(), getSelections());
                jobViewer.setExtendedState(Frame.NORMAL);
            }
            jobViewer.setVisible(true);
        }
    };

    public final Action setEnabledAction = new AbstractAction() {
        {
            putValue(NAME,
                    Translations.getString("JobPanel.Action.Job.Board.SetEnabled")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("JobPanel.Action.Job.Board.SetEnabled.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    class SetEnabledAction extends AbstractAction {
        final Boolean value;

        public SetEnabledAction(Boolean value) {
            this.value = value;
            String name = value ? 
                    Translations.getString("General.Enabled") :  //$NON-NLS-1$
                    Translations.getString("General.Disabled"); //$NON-NLS-1$
            putValue(NAME, name);
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("JobPanel.Action.Job.Board.SetEnabled.MenuTip") + " " + name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<PlacementsHolderLocation<?>> selections = getSelections();
            for (PlacementsHolderLocation<?> bl : selections) {
                if (bl.isParentBranchEnabled()) {
                    bl.setLocallyEnabled(value);
                    jobTableModel.fireTableCellDecendantsUpdated(bl, 
                            Translations.getString("PlacementsHolderLocationsTableModel.ColumnName.Enabled")); //$NON-NLS-1$
                }
            }
            Helpers.selectObjectTableRows(jobTable, selections);
        }
    };

    public final Action setCheckFidsAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.SetCheckFids")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Board.SetCheckFids.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    class SetCheckFidsAction extends AbstractAction {
        final Boolean value;

        public SetCheckFidsAction(Boolean value) {
            this.value = value;
            String name = value ?
                    Translations.getString("JobPanel.Action.Job.Board.SetCheckFids.Check") : //$NON-NLS-1$
                    Translations.getString("JobPanel.Action.Job.Board.SetCheckFids.NoCheck"); //$NON-NLS-1$
            putValue(NAME, name);
            putValue(SHORT_DESCRIPTION, Translations.getString("JobPanel.Action.Job.Board.SetCheckFids.MenuTip") + " " + name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<PlacementsHolderLocation<?>> selections = getSelections();
            for (PlacementsHolderLocation<?> bl : selections) {
                bl.setCheckFiducials(value);
                jobTableModel.fireTableCellUpdated(bl,
                        Translations.getString("PlacementsHolderLocationsTableModel.ColumnName.CheckFids")); //$NON-NLS-1$
            }
            Helpers.selectObjectTableRows(jobTable, selections);
        }
    };
    
    public final Action setSideAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("JobPanel.Action.Job.Board.SetSide")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("JobPanel.Action.Job.Board.SetSide.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    class SetSideAction extends AbstractAction {
        final Side side;

        public SetSideAction(Side side) {
            this.side = side;
            String name;
            if (side == Side.Top) {
                name = Translations.getString("JobPanel.Action.Job.Board.SetSide.Top"); //$NON-NLS-1$
            }
            else {
                name = Translations.getString("JobPanel.Action.Job.Board.SetSide.Bottom"); //$NON-NLS-1$
            }
            putValue(NAME, name);
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("JobPanel.Action.Job.Board.SetSide.MenuTip") + " " + name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<PlacementsHolderLocation<?>> selections = getSelections();
            for (PlacementsHolderLocation<?> bl : selections) {
                if (bl.getParent() == job.getRootPanelLocation() && bl.getGlobalSide() != side) {
                    Side oldSide = bl.getGlobalSide();
                    if (side != oldSide) {
                        Location savedLocation = bl.getGlobalLocation();
                        bl.setGlobalSide(side);
                        if (bl.getParent() == job.getRootPanelLocation()) {
                            bl.setGlobalLocation(savedLocation);
                        }
                        bl.setLocalToParentTransform(null);
                    }
                    jobTableModel.fireTableCellDecendantsUpdated(bl, TableModelEvent.ALL_COLUMNS );
                }
            }
            jobPlacementsPanel.refresh();
            Helpers.selectObjectTableRows(jobTable, selections);
        }
    };
    
    public class OpenRecentJobAction extends AbstractAction {
        private final File file;

        public OpenRecentJobAction(File file) {
            this.file = file;
            putValue(NAME, file.getName());
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (!checkJobStopped()) {
                return;
            }
            if (!checkForModifications()) {
                return;
            }
            try {
                loadJobExec(file);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(mainFrame, 
                        Translations.getString("JobPanel.Action.Job.RecentJobs.ErrorBox.Title"), e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    /**
     * Perform all action required to load a job from a given file
     * 
     * @param file job to load
     * @throws Exception
     */
    private void loadJobExec(File file) throws Exception {
        Job job = configuration.loadJob(file);
        setJob(job);
        addRecentJob(file);
        mainFrame.getFeedersTab().updateView();
    }

    public void loadJobFromDashboard(File file) throws Exception {
        if (!checkJobStopped() || !checkForModifications()) {
            return;
        }
        loadJobExec(file);
    }

    public JobTelemetryManager getTelemetryManager() {
        return telemetryManager;
    }

    private final MachineListener machineListener = new MachineListener.Adapter() {
        @Override
        public void machineEnabled(Machine machine) {
            updateJobActions();
        }

        @Override
        public void machineDisabled(Machine machine, String reason) {
            setState(State.Stopped);
            updateJobActions();
        }
    };

    private final PropertyChangeListener titlePropertyChangeListener =
            new PropertyChangeListener() {
                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    updateTitle();
                }
            };

    private final TextStatusListener textStatusListener = this::handleTextStatus;

    private void handleTextStatus(String text) {
        MainFrame.get().setStatus(text);
        telemetryManager.recordStatus(text);
        // Repainting here refreshes the tables, which contain status that needs to be updated.
        // Would be better to have property notifiers but this is going to have to do for now.
        repaint();
    }
    
    boolean isAllPlaced() {
    	for (BoardLocation boardLocation : job.getBoardLocations()) {
    	    if (!boardLocation.isEnabled()) {
    	        continue;
    	    }
        	for (Placement placement : boardLocation.getBoard().getPlacements()) {
                if (placement.getType() != Type.Placement) {
                    continue;
                }
                if (!placement.isEnabled()) {
                    continue;
                }
        	    if (placement.getSide() != boardLocation.getGlobalSide()) {
        	        continue;
        	    }
                if (!job.retrievePlacedStatus(boardLocation, placement.getId())) {
                    return false;
                }
        	}
    	}
    	return true;
    }
}
