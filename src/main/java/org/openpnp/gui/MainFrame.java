/*
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

package org.openpnp.gui;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.border.MatteBorder;
import javax.swing.event.ChangeListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.undo.UndoManager;

import javax.swing.plaf.basic.BasicTabbedPaneUI;
import org.openpnp.Main;
import org.openpnp.Translations;
import org.openpnp.gui.components.CameraPanel;
import org.openpnp.gui.components.ThemeDialog;
import org.openpnp.gui.importer.BoardImporter;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.ActuatorItem;
import org.openpnp.gui.support.AutoSaveManager;
import org.openpnp.gui.support.BackupRestoreDialog;
import org.openpnp.gui.support.CameraItem;
import org.openpnp.gui.support.FloatingCameraMonitor;
import org.openpnp.gui.support.HeadCellValue;
import org.openpnp.gui.support.HeadMountableItem;
import org.openpnp.gui.support.NozzleItem;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.gui.support.JobProgressStrip;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.OSXAdapter;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.ProBranding;
import org.openpnp.gui.support.ModernTheme;
import org.openpnp.gui.support.QuickAccessManager;
import org.openpnp.gui.support.RotationCellValue;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.gui.support.ShortcutManager;
import org.openpnp.gui.support.StartupSplash;
import org.openpnp.model.Configuration;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Nozzle;
import org.openpnp.scripting.ScriptFileWatcher;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Cycles;
import org.pmw.tinylog.Logger;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

/**
 * The main window of the application.
 */
@SuppressWarnings("serial")
public class MainFrame extends JFrame {
    private static final String PREF_WINDOW_X = "MainFrame.windowX"; //$NON-NLS-1$
    private static final int PREF_WINDOW_X_DEF = 0;
    private static final String PREF_WINDOW_Y = "MainFrame.windowY"; //$NON-NLS-1$
    private static final int PREF_WINDOW_Y_DEF = 0;
    private static final String PREF_WINDOW_WIDTH = "MainFrame.windowWidth"; //$NON-NLS-1$
    private static final int PREF_WINDOW_WIDTH_DEF = 1440;
    private static final String PREF_WINDOW_HEIGHT = "MainFrame.windowHeight"; //$NON-NLS-1$
    private static final int PREF_WINDOW_HEIGHT_DEF = 900;
    private static final String PREF_DIVIDER_POSITION = "MainFrame.dividerPosition"; //$NON-NLS-1$
    private static final int PREF_DIVIDER_POSITION_DEF = -1;
    private static final String PREF_WINDOW_STYLE_MULTIPLE = "MainFrame.windowStyleMultiple"; //$NON-NLS-1$
    private static final boolean PREF_WINDOW_STYLE_MULTIPLE_DEF = false;
    private static final String PREF_NAVIGATION_COLLAPSED = "MainFrame.navigationCollapsed"; //$NON-NLS-1$
    private static final long SHUTDOWN_STEP_TIMEOUT_SECONDS = 3;

    private static final String PREF_CAMERA_WINDOW_X = "CameraFrame.windowX"; //$NON-NLS-1$
    private static final int PREF_CAMERA_WINDOW_X_DEF = 0;
    private static final String PREF_WORKSPACE_LAYOUT_VERSION = "MainFrame.workspaceLayoutVersion";
    private static final int WORKSPACE_LAYOUT_VERSION = 3;
    private static final String PREF_CAMERA_WINDOW_Y = "CameraFrame.windowY"; //$NON-NLS-1$
    private static final int PREF_CAMERA_WINDOW_Y_DEF = 0;
    private static final String PREF_CAMERA_WINDOW_WIDTH = "CameraFrame.windowWidth"; //$NON-NLS-1$
    private static final int PREF_CAMERA_WINDOW_WIDTH_DEF = 800;
    private static final String PREF_CAMERA_WINDOW_HEIGHT = "CameraFrame.windowHeight"; //$NON-NLS-1$
    private static final int PREF_CAMERA_WINDOW_HEIGHT_DEF = 600;

    private static final String PREF_MACHINECONTROLS_WINDOW_X = "MachineControlsFrame.windowX"; //$NON-NLS-1$
    private static final int PREF_MACHINECONTROLS_WINDOW_X_DEF = 0;
    private static final String PREF_MACHINECONTROLS_WINDOW_Y = "MachineControlsFrame.windowY"; //$NON-NLS-1$
    private static final int PREF_MACHINECONTROLS_WINDOW_Y_DEF = 0;
    private static final String PREF_MACHINECONTROLS_WINDOW_WIDTH = "MachineControlsFrame.windowWidth"; //$NON-NLS-1$
    private static final int PREF_MACHINECONTROLS_WINDOW_WIDTH_DEF = 490;
    private static final String PREF_MACHINECONTROLS_WINDOW_HEIGHT = "MachineControlsFrame.windowHeight"; //$NON-NLS-1$
    private static final int PREF_MACHINECONTROLS_WINDOW_HEIGHT_DEF = 340;
    private static final int MINIMUM_WINDOW_SIZE = 50;

    private final Configuration configuration;

    private static MainFrame mainFrame;

    private MachineControlsPanel machineControlsPanel;
    private PanelsPanel panelsPanel;
    private BoardsPanel boardsPanel;
    private PartsPanel partsPanel;
    private PackagesPanel packagesPanel;
    private FeedersPanel feedersPanel;
    private JobPanel jobPanel;
    private ProductionDashboardPanel productionDashboardPanel;
    private CameraPanel cameraPanel;
    private JPanel panelCameraAndInstructions;
    private JPanel panelMachine;
    private MachineSetupPanel machineSetupPanel;
    private IssuesAndSolutionsPanel issuesAndSolutionsPanel;
    private VisionSettingsPanel visionSettingsPanel;
    private JDialog frameCamera;
    private JDialog frameMachineControls;
    private Map<KeyStroke, Action> hotkeyActionMap;
    private AbstractConfigurationWizard wizardWithActiveProcess = null;
    private UndoManager undoManager = new UndoManager();
    private boolean windowStyleMultiple;
    private final List<JToggleButton> workspaceNavigation = new ArrayList<>();
    private final List<Component> workspaceNavigationExpandedOnly = new ArrayList<>();
    private JPanel workspaceNavigationPanel;
    private JPanel commandBar;
    private JButton workspaceNavigationCollapseButton;
    private boolean quitting;
    private JLabel workspaceTitle;
    private ShortcutManager shortcutManager;
    private QuickAccessManager quickAccessManager;
    private JPanel quickAccessPanel;
    private JComboBox<HeadMountableItem> topToolSelector;
    private boolean updatingTopToolSelector;
    private AutoSaveManager autoSaveManager;
    private FloatingCameraMonitor floatingCameraMonitor;

    public static MainFrame get() {
        return mainFrame;
    }

    /**
     * @return the wizardWithActiveProcess
     */
    public synchronized AbstractConfigurationWizard getWizardWithActiveProcess() {
        return wizardWithActiveProcess;
    }

    /**
     * @param wizardWithActiveProcess the wizardWithActiveProcess to set
     */
    public synchronized void setWizardWithActiveProcess(AbstractConfigurationWizard wizardWithActiveProcess) {
        if (this.wizardWithActiveProcess == null) {
            if (wizardWithActiveProcess.getId() != null) {
                this.wizardWithActiveProcess = wizardWithActiveProcess;
            }
            else {
                throw new InvalidParameterException("Wizard does not have a valid Id.");
            }
        }
        else {
            throw new IllegalStateException(String.format("Another wizard (%s - %s) already has an active process, it must complete or be cancelled before this wizard can start another process.", this.wizardWithActiveProcess.getName(), ((PropertySheetWizardAdapter) (this.wizardWithActiveProcess.getWizardContainer())).getPropertySheetTitle()));
        }
    }

    public synchronized void clearWizardWithActiveProcess(AbstractConfigurationWizard wizardWithActiveProcess) {
        if (this.wizardWithActiveProcess == wizardWithActiveProcess) {
            this.wizardWithActiveProcess = null;
        }
    }

    public UndoManager getUndoManager() {
        return undoManager;
    }

    public MachineControlsPanel getMachineControls() {
        return machineControlsPanel;
    }

    public PanelsPanel getPanelsTab() {
        return panelsPanel;
    }

    public BoardsPanel getBoardsTab() {
        return boardsPanel;
    }

    public PartsPanel getPartsTab() {
        return partsPanel;
    }

    public PackagesPanel getPackagesTab() {
        return packagesPanel;
    }

    public VisionSettingsPanel getVisionSettingsTab() {
        return visionSettingsPanel;
    }

    public FeedersPanel getFeedersTab() {
        return feedersPanel;
    }

    public JobPanel getJobTab() {
        return jobPanel;
    }

    public CameraPanel getCameraViews() {
        return cameraPanel;
    }

    public MachineSetupPanel getMachineSetupTab() {
        return machineSetupPanel;
    }

    public IssuesAndSolutionsPanel getIssuesAndSolutionsTab() {
        return issuesAndSolutionsPanel;
    }

    private JPanel contentPane;
    private JTabbedPane tabs;
    private JSplitPane splitPaneMachineAndTabs;
    private TitledBorder panelInstructionsBorder;
    private JPanel panelInstructions;
    private JPanel panelInstructionActions;
    private JPanel panel_1;
    private JButton btnInstructionsNext;
    private JButton btnInstructionsCancel;
    private JTextPane lblInstructions;
    private JPanel panel_2;
    private ScheduledExecutorService scheduledExecutor;
    private JMenuBar menuBar;
    private JMenu mnImport;
    private JMenu mnScripts;
    private JMenu mnWindows;
    private JMenu experimentalFeaturesMenu;

    public JTabbedPane getTabs() {
        return tabs;
    }

    public Component getProductionDashboardTab() {
        return productionDashboardPanel;
    }

    Component getGuideNavigationTarget() {
        return workspaceNavigationPanel;
    }

    Component getGuideCommandBarTarget() {
        return commandBar;
    }

    Component getGuideDashboardTarget() {
        return productionDashboardPanel;
    }

    Component getGuideStatusTarget() {
        return panelStatusAndDros;
    }

    public Map<KeyStroke, Action> getHotkeyActionMap() {
        return hotkeyActionMap;
    }

    private Preferences prefs = Preferences.userNodeForPackage(MainFrame.class);

    private ActionListener instructionsCancelActionListener;
    private ActionListener instructionsProceedActionListener;

    private ScriptFileWatcher scriptFileWatcher;
    private JMenuItem mnEditRemoveBoard;
    private JMenu mnEditAddBoard;
    private JMenuItem mnCaptureToolLocation;

    private boolean isShiftDown;
    public boolean getShiftDown() {
        return isShiftDown;
    }

    public MainFrame(Configuration configuration) {
        StartupSplash.update(34, "Initializing core services...");
        mainFrame = this;
        this.configuration = configuration;
        setTitle("OpenPnP Pro " + Main.getVersionString());
        setIconImages(ProBranding.windowIcons());
        LengthCellValue.setConfiguration(configuration);
        RotationCellValue.setConfiguration(configuration);
        HeadCellValue.setConfiguration(configuration);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // Get handlers for Mac application menu in place.
        boolean macOsXMenus = registerForMacOSXEvents();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(
                        () -> splitPaneMachineAndTabs.setDividerLocation(0.68));
            }

        });
        if (prefs.getInt(PREF_WINDOW_WIDTH, MINIMUM_WINDOW_SIZE) < MINIMUM_WINDOW_SIZE) {
            prefs.putInt(PREF_WINDOW_WIDTH, PREF_WINDOW_WIDTH_DEF);
        }

        if (prefs.getInt(PREF_WINDOW_HEIGHT, MINIMUM_WINDOW_SIZE) < MINIMUM_WINDOW_SIZE) {
            prefs.putInt(PREF_WINDOW_HEIGHT, PREF_WINDOW_HEIGHT_DEF);
        }

        windowStyleMultiple = prefs.getBoolean(PREF_WINDOW_STYLE_MULTIPLE, PREF_WINDOW_STYLE_MULTIPLE_DEF);

        windowStyleMultiple = false;
        prefs.putBoolean(PREF_WINDOW_STYLE_MULTIPLE, false);
        setBounds(prefs.getInt(PREF_WINDOW_X, PREF_WINDOW_X_DEF),
                prefs.getInt(PREF_WINDOW_Y, PREF_WINDOW_Y_DEF),
                prefs.getInt(PREF_WINDOW_WIDTH, PREF_WINDOW_WIDTH_DEF),
                prefs.getInt(PREF_WINDOW_HEIGHT, PREF_WINDOW_HEIGHT_DEF));

        // Ensure the window is within the bounds of a screen.
        Rectangle windowBounds = getBounds();
        boolean isWithinScreen = false;
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screenBounds = gd.getDefaultConfiguration().getBounds();
            if (windowBounds.intersects(screenBounds)) {
                isWithinScreen = true;
                break;
            }
        }
        if (!isWithinScreen) {
        	// If the window is not within any screen, reset it to the default position.
            setBounds(PREF_WINDOW_X_DEF, PREF_WINDOW_Y_DEF, PREF_WINDOW_WIDTH_DEF, PREF_WINDOW_HEIGHT_DEF);
        }
        jobPanel = new JobPanel(configuration, this);
        productionDashboardPanel = new ProductionDashboardPanel(jobPanel);
        panelsPanel = new PanelsPanel(configuration, this);
        boardsPanel = new BoardsPanel(configuration, this);
        partsPanel = new PartsPanel(configuration, this);
        packagesPanel = new PackagesPanel(configuration, this);
        StartupSplash.update(45, "Preparing job and component libraries...");
        setMinimumSize(new Dimension(760, 420));
        if (prefs.getInt(PREF_WORKSPACE_LAYOUT_VERSION, 0) < WORKSPACE_LAYOUT_VERSION) {
            windowStyleMultiple = false;
            prefs.putBoolean(PREF_WINDOW_STYLE_MULTIPLE, false);
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            int availableWidth = Math.max(760, screen.width - 40);
            int availableHeight = Math.max(420, screen.height - 40);
            int width = Math.min(PREF_WINDOW_WIDTH_DEF, availableWidth);
            int height = Math.min(PREF_WINDOW_HEIGHT_DEF, availableHeight);
            setSize(width, height);
            setLocation(screen.x + Math.max(0, (screen.width - width) / 2),
                    screen.y + Math.max(0, (screen.height - height) / 2));
            prefs.putInt(PREF_WORKSPACE_LAYOUT_VERSION, WORKSPACE_LAYOUT_VERSION);
        }
        feedersPanel = new FeedersPanel(configuration, this);
        machineSetupPanel = new MachineSetupPanel();
        issuesAndSolutionsPanel = new IssuesAndSolutionsPanel(configuration, this);
        visionSettingsPanel = new VisionSettingsPanel(this);

        menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        // File
        //////////////////////////////////////////////////////////////////////
        JMenu mnFile = new JMenu(Translations.getString("Menu.File")); //$NON-NLS-1$
        mnFile.setMnemonic(KeyEvent.VK_F);
        menuBar.add(mnFile);

        mnFile.add(new JMenuItem(jobPanel.newJobAction));
        mnFile.add(new JMenuItem(jobPanel.openJobAction));

        mnFile.add(jobPanel.mnOpenRecent);

        mnFile.addSeparator();
        mnFile.add(new JMenuItem(jobPanel.saveJobAction));
        mnFile.add(new JMenuItem(jobPanel.saveJobAsAction));
        mnFile.addSeparator();
        mnFile.add(new JMenuItem(saveConfigAction));


        // File -> Import
        //////////////////////////////////////////////////////////////////////
        mnFile.addSeparator();
        mnImport = new JMenu(Translations.getString("BoardsPanel.BoardPlacements.Action.Import")); //$NON-NLS-1$
        mnImport.setMnemonic(KeyEvent.VK_I);
        mnImport.setEnabled(false);
        mnFile.add(mnImport);


        if (!macOsXMenus) {
            mnFile.addSeparator();
            mnFile.add(new JMenuItem(quitAction));
        }

        // Edit
        //////////////////////////////////////////////////////////////////////
        JMenu mnEdit = new JMenu(Translations.getString("Menu.Edit")); //$NON-NLS-1$
        mnEdit.setMnemonic(KeyEvent.VK_E);
        menuBar.add(mnEdit);

        mnEdit.add(new JMenuItem(undoAction));
        mnEdit.add(new JMenuItem(redoAction));
        mnEdit.addSeparator();
        mnEditAddBoard = new JMenu(jobPanel.addBoardAction);
        mnEditAddBoard.add(new JMenuItem(jobPanel.addNewBoardAction));
        mnEditAddBoard.add(new JMenuItem(jobPanel.addExistingBoardAction));
        mnEditAddBoard.addSeparator();
        mnEditAddBoard.add(new JMenuItem(jobPanel.addNewPanelAction));
        mnEditAddBoard.add(new JMenuItem(jobPanel.addExistingPanelAction));
        mnEdit.add(mnEditAddBoard);
        mnEditRemoveBoard = new JMenuItem(jobPanel.removeBoardAction);
        mnEdit.add(mnEditRemoveBoard);
        mnEdit.addSeparator();
        mnCaptureToolLocation = new JMenuItem(jobPanel.captureToolBoardLocationAction);
        mnEdit.add(mnCaptureToolLocation);

        // View
        //////////////////////////////////////////////////////////////////////
        JMenu mnView = new JMenu(Translations.getString("Menu.View")); //$NON-NLS-1$
        mnView.setMnemonic(KeyEvent.VK_V);
        menuBar.add(mnView);

        // View -> System Units
        ButtonGroup buttonGroup = new ButtonGroup();
        JMenu mnUnits = new JMenu(Translations.getString("Menu.View.SystemUnits")); //$NON-NLS-1$
        mnUnits.setMnemonic(KeyEvent.VK_S);
        mnView.add(mnUnits);

        JMenuItem menuItem;
        menuItem = new JCheckBoxMenuItem(inchesUnitSelected);
        buttonGroup.add(menuItem);
        if (configuration.getSystemUnits() == LengthUnit.Inches) {
            menuItem.setSelected(true);
        }
        mnUnits.add(menuItem);
        menuItem = new JCheckBoxMenuItem(millimetersUnitSelected);
        buttonGroup.add(menuItem);
        if (configuration.getSystemUnits() == LengthUnit.Millimeters) {
            menuItem.setSelected(true);
        }
        mnUnits.add(menuItem);

        // View -> Tables Linked
        buttonGroup = new ButtonGroup();
        JMenu tablesLinked = new JMenu(Translations.getString("Menu.View.TablesLinked")); //$NON-NLS-1$
        mnView.add(tablesLinked);

        menuItem = new JCheckBoxMenuItem(tablesUnlinkedSelected);
        buttonGroup.add(menuItem);
        if (configuration.getTablesLinked() == TablesLinked.Unlinked) {
            menuItem.setSelected(true);
        }
        tablesLinked.add(menuItem);
        menuItem = new JCheckBoxMenuItem(tablesLinkedSelected);
        buttonGroup.add(menuItem);
        if (configuration.getTablesLinked() == TablesLinked.Linked) {
            menuItem.setSelected(true);
        }
        tablesLinked.add(menuItem);

        // View -> Language
        buttonGroup = new ButtonGroup();
        JMenu mnLanguage = new JMenu(Translations.getString("Menu.View.Language")); //$NON-NLS-1$
        mnView.add(mnLanguage);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(Locale.US));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("ru")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("es")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("fr")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("it")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("de")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        menuItem = new JCheckBoxMenuItem(new LanguageSelectionAction(new Locale("zh", "CN")));
        buttonGroup.add(menuItem);
        mnLanguage.add(menuItem);

        for (int i = 0; i < mnLanguage.getItemCount(); i++) {
            JCheckBoxMenuItem item = (JCheckBoxMenuItem) mnLanguage.getItem(i);
            LanguageSelectionAction action = (LanguageSelectionAction) item.getAction();
            if (action.getLocale().equals(Configuration.get().getLocale())) {
                item.setSelected(true);
            }
        }


        // Job
        //////////////////////////////////////////////////////////////////////
        JMenu mnJob = new JMenu(Translations.getString("Menu.Job")); //$NON-NLS-1$
        mnJob.setMnemonic(KeyEvent.VK_J);
        menuBar.add(mnJob);

        mnJob.add(new JMenuItem(jobPanel.startPauseResumeJobAction));
        mnJob.add(new JMenuItem(jobPanel.stepJobAction));
        mnJob.add(new JMenuItem(jobPanel.stopJobAction));

        mnJob.addSeparator();

        mnJob.add(new JMenuItem(jobPanel.resetAllPlacedAction));

        // Machine
        //////////////////////////////////////////////////////////////////////
        JMenu mnCommands = new JMenu(Translations.getString("Menu.Machine")); //$NON-NLS-1$
        mnCommands.setMnemonic(KeyEvent.VK_M);
        menuBar.add(mnCommands);
        mnCommands.addSeparator();

        // Scripts
        /////////////////////////////////////////////////////////////////////
        mnScripts = new JMenu(Translations.getString("Menu.Scripts")); //$NON-NLS-1$
        mnScripts.setMnemonic(KeyEvent.VK_S);
        menuBar.add(mnScripts);

        // Windows
        /////////////////////////////////////////////////////////////////////
        mnWindows = new JMenu(Translations.getString("Menu.Window")); //$NON-NLS-1$
        mnWindows.setMnemonic(KeyEvent.VK_W);
        menuBar.add(mnWindows);

        JCheckBoxMenuItem windowStyleMultipleMenuItem =
                new JCheckBoxMenuItem(windowStyleMultipleSelected);
        mnWindows.add(windowStyleMultipleMenuItem);
        if (windowStyleMultiple) {
            windowStyleMultipleMenuItem.setSelected(true);
        }

        mnWindows.add(new JMenuItem(editThemeAction));
        mnWindows.add(new JMenuItem(floatingCameraSettingsAction));
        mnWindows.add(new JMenuItem(backupRestoreAction));
        mnWindows.addSeparator();
        JCheckBoxMenuItem experimentalModeItem = new JCheckBoxMenuItem("实验模式");
        experimentalModeItem.setSelected(
                ExperimentalMotionCenterDialog.isExperimentalModeEnabled());
        experimentalModeItem.setToolTipText("启用正在验证中的 OpenPnP Pro 功能，默认关闭");
        experimentalModeItem.addActionListener(event -> {
            boolean enabled = experimentalModeItem.isSelected();
            ExperimentalMotionCenterDialog.setExperimentalModeEnabled(enabled);
            experimentalFeaturesMenu.setVisible(enabled);
            if (enabled) {
                MessageBoxes.infoBox("实验模式",
                        "实验模式已开启。\n\n实验功能默认不会自动改写运动参数；"
                                + "驱动转换前会备份 machine.xml，物理运动测试需要单独安全确认。");
            }
        });
        mnWindows.add(experimentalModeItem);
        experimentalFeaturesMenu = new JMenu("实验功能");
        experimentalFeaturesMenu.add(new JMenuItem(motionPerformanceCenterAction));
        experimentalFeaturesMenu.add(new JMenuItem(flyingVisionCenterAction));
        experimentalFeaturesMenu.add(new JMenuItem(cameraDiagnosticsAction));
        experimentalFeaturesMenu.add(new JMenuItem(safetyWorkbenchAction));
        experimentalFeaturesMenu.setVisible(
                ExperimentalMotionCenterDialog.isExperimentalModeEnabled());
        mnWindows.add(experimentalFeaturesMenu);

        // Help
        /////////////////////////////////////////////////////////////////////
        JMenu mnHelp = new JMenu(Translations.getString("Menu.Help")); //$NON-NLS-1$
        mnHelp.setMnemonic(KeyEvent.VK_H);
        menuBar.add(mnHelp);
        if (!macOsXMenus) {
            mnHelp.add(new JMenuItem(aboutAction));
        }
        mnHelp.add(new JMenuItem(firstUseGuideAction));
        mnHelp.add(new JMenuItem(proChangeLogAction));
        mnHelp.addSeparator();
        mnHelp.add(quickStartLinkAction);
        mnHelp.add(setupAndCalibrationLinkAction);
        mnHelp.add(userManualLinkAction);
        mnHelp.addSeparator();
        mnHelp.add(changeLogAction);
        mnHelp.addSeparator();
        mnHelp.add(submitDiagnosticsAction);
        if (isInstallerAvailable()) {
            mnHelp.add(new JMenuItem(checkForUpdatesAction));
        }

        contentPane = new JPanel();
        contentPane.setBorder(null);
        contentPane.setBackground(ModernTheme.surface(false));
        setContentPane(contentPane);
        contentPane.setLayout(new BorderLayout(0, 0));

        splitPaneMachineAndTabs = new JSplitPane();
        splitPaneMachineAndTabs.setBorder(null);
        splitPaneMachineAndTabs.setContinuousLayout(true);
        contentPane.add(splitPaneMachineAndTabs, BorderLayout.CENTER);

        panelMachine = new JPanel();
        splitPaneMachineAndTabs.setRightComponent(panelMachine);
        panelMachine.setLayout(new BorderLayout(0, 0));
        panelMachine.setPreferredSize(new Dimension(340, 700));
        panelMachine.setBorder(new MatteBorder(0, 1, 0, 0, ModernTheme.border()));
        panelMachine.setBackground(ModernTheme.surface(true));

        // Add global hotkeys for the arrow keys
        hotkeyActionMap = new HashMap<>();

        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            protected void dispatchEvent(AWTEvent event) {
                if (event instanceof KeyEvent) {
                    // Skip hotkey processing if a text input component has focus.
                    // This prevents accidental machine motion when editing text
                    // (e.g., using Ctrl+Shift+Arrow to select words).
                    if (!UiUtils.isTextInputFocused()) {
                        KeyStroke ks = KeyStroke.getKeyStrokeForEvent((KeyEvent) event);
                        Action action = hotkeyActionMap.get(ks);
                        if (action != null && action.isEnabled()) {
                            action.actionPerformed(null);
                            return;
                        }
                    }
                }
                super.dispatchEvent(event);
            }
        });
        cameraPanel = new CameraPanel();

        panelCameraAndInstructions = new JPanel();
        panelMachine.add(panelCameraAndInstructions, BorderLayout.CENTER);

        panelInstructions = new JPanel();
        panelInstructions.setVisible(false);
        panelCameraAndInstructions.setLayout(new BorderLayout(0, 0));
        JPanel cameraHeader = new JPanel(new BorderLayout());
        cameraHeader.setBorder(new EmptyBorder(11, 14, 8, 14));
        cameraHeader.setBackground(ModernTheme.surface(true));
        JLabel liveCamera = new JLabel("LIVE CAMERA");
        liveCamera.setFont(liveCamera.getFont().deriveFont(Font.BOLD, 11f));
        liveCamera.setForeground(ModernTheme.mutedForeground());
        cameraHeader.add(liveCamera, BorderLayout.WEST);
        panelCameraAndInstructions.add(cameraHeader, BorderLayout.NORTH);
        panelInstructions.setBorder(panelInstructionsBorder = new TitledBorder(null, Translations.getString("General.Instructions"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        panelCameraAndInstructions.add(panelInstructions, BorderLayout.SOUTH);
        panelInstructions.setLayout(new BorderLayout(0, 0));

        panelInstructionActions = new JPanel();
        panelInstructionActions.setAlignmentY(Component.BOTTOM_ALIGNMENT);
        panelInstructions.add(panelInstructionActions, BorderLayout.EAST);
        panelInstructionActions.setLayout(new BorderLayout(0, 0));

        panel_2 = new JPanel();
        FlowLayout flowLayout_2 = (FlowLayout) panel_2.getLayout();
        flowLayout_2.setVgap(0);
        flowLayout_2.setHgap(0);
        panelInstructionActions.add(panel_2, BorderLayout.SOUTH);

        btnInstructionsCancel = new JButton(Translations.getString("General.Cancel")); //$NON-NLS-1$
        btnInstructionsCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                if (instructionsCancelActionListener != null) {
                    instructionsCancelActionListener.actionPerformed(arg0);
                }
            }
        });
        panel_2.add(btnInstructionsCancel);

        btnInstructionsNext = new JButton(Translations.getString("General.Next")); //$NON-NLS-1$
        btnInstructionsNext.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                if (instructionsProceedActionListener != null) {
                    instructionsProceedActionListener.actionPerformed(arg0);
                }
            }
        });
        panel_2.add(btnInstructionsNext);

        panel_1 = new JPanel();
        panelInstructions.add(panel_1, BorderLayout.CENTER);
        panel_1.setLayout(new BorderLayout(0, 0));

        lblInstructions = new JTextPane();
        // does not seem to work with html
        //lblInstructions.setFont(new Font("Lucida Grande", Font.PLAIN, 14)); //$NON-NLS-1$
        // instead use the HONOR_DISPLAY_PROPERTIES to set the proper system dialog font and size
        lblInstructions.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        lblInstructions.setBackground(UIManager.getColor("Panel.background")); //$NON-NLS-1$
        lblInstructions.setContentType("text/html"); //$NON-NLS-1$
        lblInstructions.setEditable(false);
        panel_1.add(lblInstructions);

        labelIcon = new JLabel();
        labelIcon.setIcon(Icons.processActivity1Icon);
        panelInstructions.add(labelIcon, BorderLayout.WEST);

        machineControlsPanel = new MachineControlsPanel(configuration, jobPanel);
        panelMachine.add(machineControlsPanel, BorderLayout.SOUTH);

        mnCommands.add(new JMenuItem(machineControlsPanel.homeAction));
        mnCommands.add(new JMenuItem(machineControlsPanel.startStopMachineAction));

        int[] ctrl_shift_mask = {KeyEvent.CTRL_DOWN_MASK, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK};
        for (int mask : ctrl_shift_mask) {
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, mask),
                    machineControlsPanel.getJogControlsPanel().yPlusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, mask),
                    machineControlsPanel.getJogControlsPanel().yMinusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, mask),
                    machineControlsPanel.getJogControlsPanel().xMinusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, mask),
                    machineControlsPanel.getJogControlsPanel().xPlusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_QUOTE, mask),
                    machineControlsPanel.getJogControlsPanel().zPlusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, mask),
                    machineControlsPanel.getJogControlsPanel().zMinusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, mask),
                    machineControlsPanel.getJogControlsPanel().cPlusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, mask),
                    machineControlsPanel.getJogControlsPanel().cMinusAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, mask),
                    machineControlsPanel.getJogControlsPanel().lowerIncrementAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, mask),
                    machineControlsPanel.getJogControlsPanel().raiseIncrementAction);
            hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, mask),
                    machineControlsPanel.homeAction);
        }
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                jobPanel.startPauseResumeJobAction); // Ctrl-Shift-R for Start
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                jobPanel.stepJobAction); // Ctrl-Shift-S for Step
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                jobPanel.stopJobAction); // Ctrl-Shift-A for Stop
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().xyParkAction); // Ctrl-Shift-P for xyPark
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().zParkAction); // Ctrl-Shift-P for zPark
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().safezAction); // Ctrl-Shift-Z for safezAction
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().discardAction); // Ctrl-Shift-D for discard
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().setIncrement1Action);
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().setIncrement2Action);
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().setIncrement3Action);
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().setIncrement4Action);
        hotkeyActionMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                machineControlsPanel.getJogControlsPanel().setIncrement5Action);

        isShiftDown = false;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(
            new KeyEventDispatcher() {
                public boolean dispatchKeyEvent(KeyEvent e) {
                    isShiftDown = e.isShiftDown();
                    return false;
                }
            });

        tabs = new WorkspaceTabbedPane();
        splitPaneMachineAndTabs.setLeftComponent(tabs);

        splitPaneMachineAndTabs
                .setDividerLocation(prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF));
        splitPaneMachineAndTabs.addPropertyChangeListener("dividerLocation", //$NON-NLS-1$
                new PropertyChangeListener() {
                    @Override
                    public void propertyChange(PropertyChangeEvent evt) {
                        prefs.putInt(PREF_DIVIDER_POSITION,
                                splitPaneMachineAndTabs.getDividerLocation());
                    }
                });

        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Job"), //$NON-NLS-1$
                null, jobPanel, null);
        tabs.addTab("生产看板", null, productionDashboardPanel,
                "最近任务、产能、质量、异常分析与改善建议");
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Panels"), //$NON-NLS-1$
                null, panelsPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Boards" //$NON-NLS-1$
        ),null, boardsPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Parts"), //$NON-NLS-1$
                null, partsPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Packages" //$NON-NLS-1$
        ),null, packagesPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Vision"), //$NON-NLS-1$
                null, visionSettingsPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Feeders"), //$NON-NLS-1$
                null, feedersPanel, null); //$NON-NLS-1$
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.MachineSetup"), //$NON-NLS-1$
                null, machineSetupPanel, null);
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.IssuesAndSolutions"), //$NON-NLS-1$
                null, issuesAndSolutionsPanel, null);

        LogPanel logPanel = new LogPanel();
        tabs.addTab(Translations.getString("MainFrame.RightComponent.tabs.Log"),
                null, logPanel, null); //$NON-NLS-1$

        contentPane.add(createWorkspaceNavigation(), BorderLayout.WEST);
        contentPane.add(createCommandBar(), BorderLayout.NORTH);

        tabs.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                updateMenuState(tabs.getSelectedComponent());
                updateWorkspaceNavigation();
            }
        });

        updateWorkspaceNavigation();

        panelStatusAndDros = new JPanel();
        panelStatusAndDros.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ModernTheme.border()),
                new EmptyBorder(5, 10, 5, 10)));
        panelStatusAndDros.setBackground(ModernTheme.surface(true));
        contentPane.add(panelStatusAndDros, BorderLayout.SOUTH);
        panelStatusAndDros.setLayout(new FormLayout(
                new ColumnSpec[] {ColumnSpec.decode("default:grow"), ColumnSpec.decode("8px"), //$NON-NLS-1$ //$NON-NLS-2$
                        FormSpecs.DEFAULT_COLSPEC, ColumnSpec.decode("10px"),
                        FormSpecs.DEFAULT_COLSPEC,},
                new RowSpec[] {FormSpecs.DEFAULT_ROWSPEC,}));

        // Status Information
        lblStatus = new JLabel(" "); //$NON-NLS-1$
        lblStatus.setBorder(new EmptyBorder(0, 5, 0, 5));
        panelStatusAndDros.add(lblStatus, "1, 1"); //$NON-NLS-1$

        // Original placement progress and the live task metrics form one status group.
        JPanel productionProgress = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        productionProgress.setOpaque(true);
        productionProgress.setBackground(ModernTheme.surface(false));
        productionProgress.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ModernTheme.border()),
                new EmptyBorder(1, 5, 1, 5)));
        lblPlacements = new JLabel(Translations.getString(
                "MainFrame.StatusPanel.PlacementsLabel.initial.text")); //$NON-NLS-1$
        lblPlacements.setBorder(new EmptyBorder(0, 3, 0, 3));
        productionProgress.add(lblPlacements);

        prgbrPlacements = new JProgressBar();
        prgbrPlacements.setMinimum(0);
        prgbrPlacements.setMaximum(100);
        prgbrPlacements.setStringPainted(true);
        prgbrPlacements.setPreferredSize(new Dimension(150, 17));
        prgbrPlacements.setValue(0);
        productionProgress.add(prgbrPlacements);
        productionProgress.add(new JobProgressStrip(jobPanel.getTelemetryManager()));
        panelStatusAndDros.add(productionProgress, "3, 1"); //$NON-NLS-1$

        // Machine coordinates use a dedicated high-contrast theme and retain it after config load.
        droLbl = new JLabel("●  X:0000.0000  Y:0000.0000  Z:0000.0000  C:0000.0000"); //$NON-NLS-1$
        droLbl.setOpaque(true);
        droLbl.setFont(new Font("Monospaced", Font.BOLD, 13)); //$NON-NLS-1$
        applyDroCoordinateStyle(false);
        panelStatusAndDros.add(droLbl, "5, 1"); //$NON-NLS-1$

        cameraPanel.setBorder(new TitledBorder(null,
                Translations.getString("MainFrame.CameraPanel.Border.title"), //$NON-NLS-1$
                TitledBorder.LEADING,
                TitledBorder.TOP, null, null)); //$NON-NLS-1$
        panelCameraAndInstructions.add(cameraPanel, BorderLayout.CENTER);

        splitPaneMachineAndTabs.setResizeWeight(0.70);

        addImporterMenuOptions();

        addComponentListener(mainFrameListener);

        StartupSplash.update(68, "Loading machine and production configuration...");
        boolean configurationLoaded = false;
        while (!configurationLoaded) {
	        try {
	            configuration.load();
	            StartupSplash.update(84, "Synchronizing tools and machine controls...");
	            refreshTopToolSelector();
	            selectTopTool(machineControlsPanel.getSelectedTool());
	            configurationLoaded = true;
	        }
	        catch (Exception e) {
                StartupSplash.close();
	            e.printStackTrace();
	            if (!MessageBoxes.errorBoxWithRetry(this, "Configuration Load Error", //$NON-NLS-1$
	                    "There was a problem loading the configuration. The reason was:<br/><br/>" //$NON-NLS-1$
	                            + e.getMessage() + "<br/><br/>" //$NON-NLS-1$
	                            + "Please check your configuration files and try again. They are located at: " //$NON-NLS-1$
	                            + configuration.getConfigurationDirectory().getAbsolutePath()
	                            + "<br/><br/>" //$NON-NLS-1$
	                            + "If you would like to start with a fresh configuration, just delete the entire directory at the location above.<br/><br/>" //$NON-NLS-1$
	                            + "Retry loading (else openpnp will exit) ?")) { //$NON-NLS-1$
	            	System.exit(1);
	            }
	        }
	    }
        shortcutManager = new ShortcutManager(this, hotkeyActionMap);
        shortcutManager.install(menuBar, contentPane);
        splitWindows();
        quickAccessManager = new QuickAccessManager(this, quickAccessPanel);
        quickAccessManager.install(menuBar, contentPane);
        StartupSplash.update(95, "Starting recovery and autosave...");
        autoSaveManager = new AutoSaveManager(configuration, jobPanel::getJob);
        autoSaveManager.start();
        // Let the main window paint before scanning and attaching the scripts menu.
        SwingUtilities.invokeLater(this::initializeScriptWatcher);
        SwingUtilities.invokeLater(this::initializeFloatingCameraMonitor);
        SwingUtilities.invokeLater(() -> FirstUseGuideDialog.showIfNeeded(this));
    }

    private void initializeFloatingCameraMonitor() {
        if (floatingCameraMonitor == null && !quitting) {
            floatingCameraMonitor = new FloatingCameraMonitor(this);
        }
    }

    private void initializeScriptWatcher() {
        try {
            scriptFileWatcher = new ScriptFileWatcher(configuration.getScripting());
            scriptFileWatcher.setMenu(mnScripts);
        }
        catch (Exception e) {
            Logger.warn(e, "Unable to initialize the scripts menu.");
        }
    }

    private JPanel createWorkspaceNavigation() {
        workspaceNavigationPanel = new JPanel();
        JPanel navigation = workspaceNavigationPanel;
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));
        navigation.setPreferredSize(new Dimension(184, 700));
        navigation.setBackground(ModernTheme.surface(false));
        navigation.setBorder(new MatteBorder(0, 0, 0, 1, ModernTheme.border()));

        JPanel collapseRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        collapseRow.setOpaque(false);
        collapseRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        workspaceNavigationCollapseButton = new JButton("\u00AB");
        workspaceNavigationCollapseButton.setFocusable(false);
        workspaceNavigationCollapseButton.setToolTipText("Collapse or expand the navigation menu");
        workspaceNavigationCollapseButton.setMargin(new Insets(2, 7, 2, 7));
        workspaceNavigationCollapseButton.addActionListener(event -> setWorkspaceNavigationCollapsed(
                !prefs.getBoolean(PREF_NAVIGATION_COLLAPSED, false)));
        collapseRow.add(workspaceNavigationCollapseButton);

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(new EmptyBorder(19, 18, 14, 14));
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        JLabel product = new JLabel("OPENPNP Pro");
        product.setFont(product.getFont().deriveFont(Font.BOLD, 17f));
        product.setForeground(ModernTheme.ACCENT);
        JLabel productLine =
                new JLabel(Main.getVersionString() + " · Workspace");
        productLine.setFont(productLine.getFont().deriveFont(11f));
        productLine.setForeground(ModernTheme.mutedForeground());
        brand.add(product);
        brand.add(Box.createVerticalStrut(3));
        brand.add(productLine);
        navigation.add(brand);
        workspaceNavigationExpandedOnly.add(brand);
        navigation.add(collapseRow);

        ButtonGroup group = new ButtonGroup();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            if (index == 0) {
                navigation.add(createNavigationGroup("PRODUCTION"));
            }
            else if (index == 2) {
                navigation.add(createNavigationGroup("LIBRARIES"));
            }
            else if (index == 8) {
                navigation.add(createNavigationGroup("SYSTEM"));
            }
            final int tabIndex = index;
            JToggleButton button = new JToggleButton(tabs.getTitleAt(index), navigationIcon(index));
            button.setHorizontalAlignment(javax.swing.SwingConstants.LEADING);
            button.setIconTextGap(11);
            button.setFocusPainted(false);
            button.setBorder(new EmptyBorder(7, 16, 7, 12));
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 39));
            button.setPreferredSize(new Dimension(183, 39));
            button.putClientProperty("expandedText", tabs.getTitleAt(index));
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.addActionListener(event -> tabs.setSelectedIndex(tabIndex));
            group.add(button);
            workspaceNavigation.add(button);
            navigation.add(button);
        }
        navigation.add(Box.createVerticalGlue());

        setWorkspaceNavigationCollapsed(prefs.getBoolean(PREF_NAVIGATION_COLLAPSED, false));
        return navigation;
    }

    private JLabel createNavigationGroup(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.setForeground(ModernTheme.mutedForeground());
        label.setBorder(new EmptyBorder(9, 17, 4, 10));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        workspaceNavigationExpandedOnly.add(label);
        return label;
    }

    private javax.swing.Icon navigationIcon(int index) {
        javax.swing.Icon[] icons = {Icons.job, Icons.panel, Icons.board, Icons.pick,
                Icons.footprintQuad, Icons.captureCamera, Icons.feeder, Icons.driver,
                Icons.solutions, Icons.info};
        return index >= 0 && index < icons.length ? icons[index] : null;
    }

    private final Action topPickAction = new AbstractAction(
            Translations.getString("FeedersPanel.Action.PickFeeder"), Icons.pick) {
        {
            putValue(SHORT_DESCRIPTION,
                    "Pick from the selected feeder; confirms discard when the nozzle is occupied");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            safePickFromSelectedFeeder();
        }
    };

    /** Runs the same guarded pick workflow used by the top toolbar. */
    public void performSafePick() {
        safePickFromSelectedFeeder();
    }

    private void safePickFromSelectedFeeder() {
        HeadMountable selectedTool = machineControlsPanel.getSelectedTool();
        if (!(selectedTool instanceof Nozzle)) {
            JOptionPane.showMessageDialog(this,
                    "Select a nozzle in the tool selector before picking.",
                    "Pick", JOptionPane.WARNING_MESSAGE);
            return;
        }
        org.openpnp.spi.Feeder feeder = feedersPanel.getSelection();
        if (feeder == null || feeder.getPart() == null) {
            JOptionPane.showMessageDialog(this,
                    "Select one configured feeder before using the top Pick button.",
                    "Pick", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Nozzle nozzle = (Nozzle) selectedTool;
        UiUtils.submitUiMachineTask(() -> {
            Boolean sensorOccupied = null;
            for (Nozzle.PartOnStep step : Nozzle.PartOnStep.values()) {
                if (nozzle.isPartOnEnabled(step)) {
                    sensorOccupied = nozzle.isPartOn();
                    break;
                }
            }
            boolean occupied = sensorOccupied == null ? nozzle.getPart() != null : sensorOccupied;
            if (occupied) {
                final int[] choice = {JOptionPane.NO_OPTION};
                SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(
                        MainFrame.this,
                        "The selected nozzle already contains or detects a part.\n"
                                + "Discard it and then pick from the selected feeder?",
                        "Nozzle occupied", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE));
                if (choice[0] != JOptionPane.YES_OPTION) {
                    return;
                }
                Cycles.discardAlways(nozzle);
            }
            FeedersPanel.pickFeeder(feeder);
        });
    }
    private JPanel createCommandBar() {
        commandBar = new JPanel(new BorderLayout(16, 0));
        commandBar.setBackground(ModernTheme.surface(true));
        commandBar.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, ModernTheme.border()),
                new EmptyBorder(10, 15, 10, 14)));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel context = new JLabel("WORKSPACE");
        context.setFont(context.getFont().deriveFont(Font.BOLD, 10f));
        context.setForeground(ModernTheme.mutedForeground());
        workspaceTitle = new JLabel(tabs.getTitleAt(tabs.getSelectedIndex()));
        workspaceTitle.setFont(workspaceTitle.getFont().deriveFont(Font.BOLD, 19f));
        heading.add(context);
        heading.add(Box.createVerticalStrut(2));
        heading.add(workspaceTitle);
        commandBar.add(heading, BorderLayout.WEST);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setOpaque(false);
        toolbar.setBorder(null);
        toolbar.add(createCommandButton(jobPanel.newJobAction, false));
        toolbar.add(createCommandButton(jobPanel.openJobAction, false));
        toolbar.add(createCommandButton(jobPanel.saveJobAction, false));
        toolbar.addSeparator(new Dimension(14, 1));
        toolbar.add(createCommandButton(machineControlsPanel.startStopMachineAction, true));
        toolbar.add(createCommandButton(machineControlsPanel.homeAction, true));
        toolbar.add(createCommandButton(topPickAction, true));
        toolbar.addSeparator(new Dimension(14, 1));
        toolbar.add(createCommandButton(jobPanel.startPauseResumeJobAction, true));
        JToggleButton pauseWhenEmpty =
                new JToggleButton(jobPanel.pauseWhenNozzlesEmptyAction);
        pauseWhenEmpty.setHideActionText(true);
        pauseWhenEmpty.setFocusable(false);
        pauseWhenEmpty.setFocusPainted(false);
        pauseWhenEmpty.setMargin(new Insets(6, 10, 6, 10));
        pauseWhenEmpty.putClientProperty("JButton.buttonType", "roundRect");
        toolbar.add(pauseWhenEmpty);
        toolbar.add(createCommandButton(jobPanel.stepJobAction, false));
        toolbar.add(createCommandButton(jobPanel.stopJobAction, false));
        toolbar.addSeparator(new Dimension(14, 1));
        addSpecialFunctionButtons(toolbar);
        toolbar.addSeparator(new Dimension(8, 1));
        topToolSelector = createTopToolSelector();
        toolbar.add(topToolSelector);
        commandBar.add(toolbar, BorderLayout.CENTER);

        JPanel quickAccess = new JPanel(new BorderLayout());
        quickAccess.setOpaque(false);
        quickAccessPanel = new JPanel();
        quickAccessPanel.setOpaque(false);
        quickAccess.add(quickAccessPanel, BorderLayout.CENTER);
        commandBar.add(quickAccess, BorderLayout.EAST);
        return commandBar;
    }

    private void addSpecialFunctionButtons(JToolBar toolbar) {
        JogControlsPanel jogControls = machineControlsPanel.getJogControlsPanel();
        JLabel label = new JLabel(Translations.getString("JogControlsPanel.Tab.Special") + ":"); //$NON-NLS-1$ //$NON-NLS-2$
        label.setForeground(ModernTheme.mutedForeground());
        toolbar.add(label);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(createCompactSpecialButton(jogControls.safezAction));
        toolbar.add(createCompactSpecialButton(jogControls.discardAction));
        toolbar.add(createCompactSpecialButton(jogControls.recycleAction));
    }

    private JButton createCompactSpecialButton(Action action) {
        JButton button = createCommandButton(action, true);
        String fullName = String.valueOf(action.getValue(Action.NAME));
        int characterCount = fullName.codePointCount(0, fullName.length());
        if (characterCount > 4) {
            int abbreviationEnd = fullName.offsetByCodePoints(0, 4);
            button.setText(fullName.substring(0, abbreviationEnd) + "\u2026");
        }
        button.setToolTipText(fullName);
        return button;
    }
    private JComboBox<HeadMountableItem> createTopToolSelector() {
        JComboBox<HeadMountableItem> selector = new JComboBox<>();
        topToolSelector = selector;
        selector.setMaximumRowCount(20);
        selector.setToolTipText("Select the active nozzle, camera or actuator");
        selector.setPreferredSize(new Dimension(205, 30));
        selector.setMaximumSize(new Dimension(235, 30));
        selector.addActionListener(event -> {
            if (!updatingTopToolSelector) {
                HeadMountableItem item = (HeadMountableItem) selector.getSelectedItem();
                if (item != null && item.getItem() != machineControlsPanel.getSelectedTool()) {
                    machineControlsPanel.setSelectedTool(item.getItem());
                }
            }
        });
        selector.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                refreshTopToolSelector();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
            }
        });
        machineControlsPanel.addPropertyChangeListener("selectedTool",
                event -> selectTopTool((HeadMountable) event.getNewValue())); //$NON-NLS-1$
        refreshTopToolSelector();
        return selector;
    }

    private void refreshTopToolSelector() {
        if (topToolSelector == null) {
            return;
        }
        HeadMountable selected = machineControlsPanel.getSelectedTool();
        updatingTopToolSelector = true;
        try {
            topToolSelector.removeAllItems();
            if (configuration.getMachine() != null) {
                for (Head head : configuration.getMachine().getHeads()) {
                    for (HeadMountable tool : head.getHeadMountables()) {
                        HeadMountableItem item;
                        if (tool instanceof Nozzle) {
                            item = new NozzleItem((Nozzle) tool);
                        }
                        else if (tool instanceof Camera) {
                            item = new CameraItem((Camera) tool);
                        }
                        else if (tool instanceof Actuator) {
                            item = new ActuatorItem((Actuator) tool);
                        }
                        else {
                            item = new HeadMountableItem(tool);
                        }
                        topToolSelector.addItem(item);
                        if (tool == selected) {
                            topToolSelector.setSelectedItem(item);
                        }
                    }
                }
            }
        }
        finally {
            updatingTopToolSelector = false;
        }
    }

    private void selectTopTool(HeadMountable tool) {
        if (topToolSelector == null || tool == null) {
            return;
        }
        updatingTopToolSelector = true;
        try {
            for (int i = 0; i < topToolSelector.getItemCount(); i++) {
                HeadMountableItem item = topToolSelector.getItemAt(i);
                if (item.getItem() == tool) {
                    topToolSelector.setSelectedIndex(i);
                    break;
                }
            }
        }
        finally {
            updatingTopToolSelector = false;
        }
    }
    private JButton createCommandButton(Action action, boolean showText) {
        JButton button = new JButton(action);
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(6, 10, 6, 10));
        button.putClientProperty("JButton.buttonType", "roundRect");
        if (!showText && button.getIcon() != null) {
            button.setText(null);
        }
        return button;
    }

    private void setWorkspaceNavigationCollapsed(boolean collapsed) {
        prefs.putBoolean(PREF_NAVIGATION_COLLAPSED, collapsed);
        int width = collapsed ? 56 : 184;
        workspaceNavigationPanel.setPreferredSize(new Dimension(width, 700));
        workspaceNavigationPanel.setMinimumSize(new Dimension(width, 0));
        workspaceNavigationCollapseButton.setText(collapsed ? "\u00BB" : "\u00AB");
        for (Component component : workspaceNavigationExpandedOnly) {
            component.setVisible(!collapsed);
        }
        for (JToggleButton button : workspaceNavigation) {
            Object expandedText = button.getClientProperty("expandedText");
            button.setText(collapsed ? "" : String.valueOf(expandedText));
            button.setHorizontalAlignment(collapsed ? javax.swing.SwingConstants.CENTER
                    : javax.swing.SwingConstants.LEADING);
            button.setBorder(new EmptyBorder(7, collapsed ? 8 : 16, 7, collapsed ? 8 : 12));
            button.setToolTipText(collapsed ? String.valueOf(expandedText) : null);
        }
        workspaceNavigationPanel.revalidate();
        workspaceNavigationPanel.repaint();
    }
    private void updateWorkspaceNavigation() {
        int selected = tabs.getSelectedIndex();
        if (selected >= 0 && selected < workspaceNavigation.size()) {
            workspaceNavigation.get(selected).setSelected(true);
            if (workspaceTitle != null) {
                workspaceTitle.setText(tabs.getTitleAt(selected));
            }
        }
    }

    private static class WorkspaceTabbedPane extends JTabbedPane {
        WorkspaceTabbedPane() {
            super(JTabbedPane.TOP);
            setBorder(new EmptyBorder(8, 10, 8, 10));
        }

        @Override
        public void updateUI() {
            setUI(new HiddenTabbedPaneUI());
        }
    }

    private static class HiddenTabbedPaneUI extends BasicTabbedPaneUI {
        @Override
        protected int calculateTabAreaHeight(int tabPlacement, int horizontalRunCount,
                int maxTabHeight) {
            return 0;
        }

        @Override
        protected int calculateTabAreaWidth(int tabPlacement, int verticalRunCount,
                int maxTabWidth) {
            return 0;
        }

        @Override
        protected void paintTabArea(Graphics graphics, int tabPlacement, int selectedIndex) {
        }

        @Override
        protected Insets getContentBorderInsets(int tabPlacement) {
            return new Insets(0, 0, 0, 0);
        }
    }

    // 20161222 - ldpgh/lutz_dd
    /**
     * Add multiple windows (aka JFrame) to OpenPnp for the camera (frameCamera) and the machine
     * controls (frameMachineControls).
     *
     * ATTENTION ... the current implementation in MainFrame.java requires a refactoring on the
     * long-term to separate JFrame from JPanels
     */
    public void splitWindows() {
        if (windowStyleMultiple) {
            // pin panelCameraAndInstructions to a separate JFrame
            frameCamera = new JDialog(this, "OpenPnp - Camera", false); //$NON-NLS-1$
            // as of today no smart way found to get an adjusted size
            // ... so main window size is used for the camera window
            frameCamera.getContentPane().add(panelCameraAndInstructions);
            frameCamera.setVisible(true);
            frameCamera.addComponentListener(cameraWindowListener);

            if (prefs.getInt(PREF_CAMERA_WINDOW_WIDTH, 50) < 50) {
                prefs.putInt(PREF_CAMERA_WINDOW_WIDTH, PREF_CAMERA_WINDOW_WIDTH_DEF);
            }

            if (prefs.getInt(PREF_CAMERA_WINDOW_HEIGHT, 50) < 50) {
                prefs.putInt(PREF_CAMERA_WINDOW_HEIGHT, PREF_CAMERA_WINDOW_HEIGHT_DEF);
            }

            frameCamera.setBounds(prefs.getInt(PREF_CAMERA_WINDOW_X, PREF_CAMERA_WINDOW_X_DEF),
                    prefs.getInt(PREF_CAMERA_WINDOW_Y, PREF_CAMERA_WINDOW_Y_DEF),
                    prefs.getInt(PREF_CAMERA_WINDOW_WIDTH, PREF_CAMERA_WINDOW_WIDTH_DEF),
                    prefs.getInt(PREF_CAMERA_WINDOW_HEIGHT, PREF_CAMERA_WINDOW_HEIGHT_DEF));

            // pin machineControlsPanel to a separate JFrame
            frameMachineControls = new JDialog(this, "OpenPnp - Machine Controls", false); //$NON-NLS-1$
            // as of today no smart way found to get an adjusted size
            // ... so hardcoded values used (usually not a good idea)
            frameMachineControls.getContentPane().add(machineControlsPanel);
            frameMachineControls.setVisible(true);
            frameMachineControls.pack();
            frameMachineControls.addComponentListener(machineControlsWindowListener);

            if (prefs.getInt(PREF_MACHINECONTROLS_WINDOW_WIDTH, 50) < 50) {
                prefs.putInt(PREF_MACHINECONTROLS_WINDOW_WIDTH, PREF_MACHINECONTROLS_WINDOW_WIDTH_DEF);
            }

            if (prefs.getInt(PREF_MACHINECONTROLS_WINDOW_HEIGHT, 50) < 50) {
                prefs.putInt(PREF_MACHINECONTROLS_WINDOW_HEIGHT, PREF_MACHINECONTROLS_WINDOW_HEIGHT_DEF);
            }

            frameMachineControls.setBounds(prefs.getInt(PREF_MACHINECONTROLS_WINDOW_X, PREF_MACHINECONTROLS_WINDOW_X_DEF),
                    prefs.getInt(PREF_MACHINECONTROLS_WINDOW_Y, PREF_MACHINECONTROLS_WINDOW_Y_DEF),
                    prefs.getInt(PREF_MACHINECONTROLS_WINDOW_WIDTH, PREF_MACHINECONTROLS_WINDOW_WIDTH_DEF),
                    prefs.getInt(PREF_MACHINECONTROLS_WINDOW_HEIGHT, PREF_MACHINECONTROLS_WINDOW_HEIGHT_DEF));
            // move the splitPaneDivider to position 0 to fill the gap of the
            // relocated panels 'panelCameraAndInstructions' & 'machineControlsPanel'
            splitPaneMachineAndTabs.setDividerLocation(0);
        }
        else {
            panelMachine.add(panelCameraAndInstructions, BorderLayout.CENTER);
            // A value of 0 indicates 'multiple window style' was used before.
            if (0 == prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF)) {
                // Reset the Divider position back to the default value.
                splitPaneMachineAndTabs.setDividerLocation(PREF_DIVIDER_POSITION_DEF);
            }
        }
    }

    public boolean isInstallerAvailable() {
        try {
            Class.forName("com.install4j.api.launcher.ApplicationLauncher"); //$NON-NLS-1$
            return true;
        }
        catch (Throwable e) {
            return false;
        }
    }

    public JLabel getDroLabel() {
        return droLbl;
    }

    public void applyDroCoordinateStyle(boolean relativeCoordinates) {
        boolean dark = ModernTheme.isDark();
        Color background = relativeCoordinates
                ? (dark ? new Color(0x4A3514) : new Color(0xFFF0C7))
                : (dark ? new Color(0x102B46) : new Color(0xDCEBFA));
        Color foreground = dark ? new Color(0xF5FAFF) : new Color(0x0B2F50);
        Color outline = relativeCoordinates ? new Color(0xE5A93D) : new Color(0x3B82F6);
        droLbl.setForeground(foreground);
        droLbl.setBackground(background);
        droLbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(outline, 1),
                new EmptyBorder(4, 11, 4, 11)));
        droLbl.setToolTipText(relativeCoordinates
                ? "相对坐标：再次单击可恢复机床绝对坐标"
                : "当前工具头机床坐标：单击可设置相对坐标零点，右键可复制");
    }

    private void addImporterMenuOptions() {
        for (BoardImporter bi : boardsPanel.getBoardPlacementsPanel().getBoardImporters()) {
            final BoardImporter boardImporter = bi;
            JMenuItem menuItem = new JMenuItem(new AbstractAction() {
                {
                    putValue(NAME, boardImporter.getImporterName());
                    putValue(SHORT_DESCRIPTION, boardImporter.getImporterDescription());
                    putValue(MNEMONIC_KEY, KeyEvent.VK_I);
                }

                @Override
                public void actionPerformed(ActionEvent e) {
                    if (tabs.getSelectedComponent() == jobPanel) {
                        boardsPanel.selectBoard((Board) jobPanel.getSelection().getPlacementsHolder().getDefinition());
                    }
                    boardsPanel.getBoardPlacementsPanel().importBoard(boardImporter.getClass());
                }
            });
            mnImport.add(menuItem);
        }
    }


    /**
     * Enables/disables the Import Board, Add Board/Panel, and Remove Board(s)/Panel(s) menu items
     * appropriately depending on which tab is selected and what is selected within the tab
     * @param selectedTab - the selected tab
     */
    public void updateMenuState(Component selectedTab) {
        if (selectedTab != tabs.getSelectedComponent()) {
            return;
        }
        if (selectedTab == jobPanel) {
            if (jobPanel.getSelections().size() == 1 && jobPanel.getSelection() instanceof BoardLocation &&
                    jobPanel.getJob().instanceCount(jobPanel.getSelection().getPlacementsHolder()) == 1 &&
                    jobPanel.getJob().getRootPanelLocation().getChildren().containsAll(jobPanel.getSelections())) {
                mnImport.setEnabled(true);
            }
            else {
                mnImport.setEnabled(false);
            }
            mnEditAddBoard.setEnabled(true);
            if (jobPanel.getSelections().size() >= 1) {
                mnEditRemoveBoard.setEnabled(jobPanel.getJob().getRootPanelLocation().getChildren().
                        containsAll(jobPanel.getSelections()));
            }
            else {
                mnEditRemoveBoard.getAction().setEnabled(false);
            }
            if (jobPanel.getSelections().size() == 1 && jobPanel.getJob().getRootPanelLocation().getChildren().containsAll(jobPanel.getSelections()) ) {
                mnCaptureToolLocation.setEnabled(true);
            }
            else {
                mnCaptureToolLocation.setEnabled(false);
            }
        }
        else if (selectedTab == panelsPanel) {
            mnImport.setEnabled(false);
            mnEditAddBoard.setEnabled(false);
            mnEditRemoveBoard.setEnabled(false);
            mnCaptureToolLocation.setEnabled(false);
        }
        else if (selectedTab == boardsPanel) {
            if (boardsPanel.getSelections().size() == 1) {
                mnImport.setEnabled(true);
            }
            else {
                mnImport.setEnabled(false);
            }
            mnEditAddBoard.setEnabled(false);
            mnEditRemoveBoard.setEnabled(false);
            mnCaptureToolLocation.setEnabled(false);
        }
        else {
            mnImport.setEnabled(false);
            mnEditAddBoard.setEnabled(false);
            mnEditRemoveBoard.setEnabled(false);
            mnCaptureToolLocation.setEnabled(false);
        }
    }

    public void showInstructions(String title, String instructions, boolean showCancelButton,
            boolean showProceedButton, String proceedButtonText,
            ActionListener cancelActionListener, ActionListener proceedActionListener) {
        panelInstructionsBorder.setTitle(title);
        lblInstructions.setText(instructions);
        btnInstructionsCancel.setVisible(showCancelButton);
        btnInstructionsNext.setVisible(showProceedButton);
        btnInstructionsNext.setText(proceedButtonText);
        instructionsCancelActionListener = cancelActionListener;
        instructionsProceedActionListener = proceedActionListener;
        panelInstructions.setVisible(true);
        doLayout();
        panelInstructions.repaint();
        if (scheduledExecutor == null) {
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutor.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    labelIcon.setIcon(labelIcon.getIcon() == Icons.processActivity1Icon ? Icons.processActivity2Icon : Icons.processActivity1Icon);
                }
            }, 0, 1000, TimeUnit.MILLISECONDS);
        }
    }

    public void hideInstructions() {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
            scheduledExecutor = null;
        }
        panelInstructions.setVisible(false);
        doLayout();
    }

    public boolean registerForMacOSXEvents() {
        if ((System.getProperty("os.name").toLowerCase().startsWith("mac os x"))) { //$NON-NLS-1$ //$NON-NLS-2$
            try {
                // Generate and register the OSXAdapter, passing it a hash of
                // all the methods we wish to
                // use as delegates for various
                // com.apple.eawt.ApplicationListener methods
                OSXAdapter.setQuitHandler(this,
                        getClass().getDeclaredMethod("quit", (Class[]) null)); //$NON-NLS-1$
                OSXAdapter.setAboutHandler(this,
                        getClass().getDeclaredMethod("about", (Class[]) null)); //$NON-NLS-1$
                // OSXAdapter.setPreferencesHandler(this, getClass()
                // .getDeclaredMethod("preferences", (Class[]) null));
                // OSXAdapter.setFileHandler(
                // this,
                // getClass().getDeclaredMethod("loadImageFile",
                // new Class[] { String.class }));
                return true;
            }
            catch (Exception e) {
                System.err.println("Error while loading the OSXAdapter: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return false;
    }

    public void about() {
        AboutDialog dialog = new AboutDialog(this);
        dialog.setSize(750, 550);
        dialog.setLocationRelativeTo(null);
        dialog.setModal(true);
        dialog.setVisible(true);
    }

    public boolean saveConfig() {
        // Save the configuration
        try {
            Preferences.userRoot().flush();
        }
        catch (Exception e) {
            MessageBoxes.errorBox(MainFrame.this, "Save Preferences", e); //$NON-NLS-1$
        }

        try {
            configuration.save();
        }
        catch (Exception e) {
			String message = "There was a problem saving the configuration. The reason was:\n\n" + e.getMessage() //$NON-NLS-1$
					+ "\n\n"; //$NON-NLS-1$
			message = message.replaceAll("\n", "<br/>"); //$NON-NLS-1$ //$NON-NLS-2$
			message = message.replaceAll("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
			message = "<html><body width=\"400\">" + message + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
			JOptionPane.showMessageDialog(this, message, "Configuration Save Error", JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
			return false;
        }

        Logger.debug("Config saved successfully!"); //$NON-NLS-1$
        return true;
    }

    public synchronized boolean quit() {
        if (quitting) {
            return true;
        }
        Logger.info("Shutting down..."); //$NON-NLS-1$
        try {
            Preferences.userRoot().flush();
        }
        catch (Exception e) {
            Logger.warn(e);
        }

        // Save the configuration before any hardware shutdown starts.
        try {
            configuration.save();
        }
        catch (Exception e) {
            String message = "There was a problem saving the configuration. The reason was:\n\n" //$NON-NLS-1$
                    + e.getMessage() + "\n\nDo you want to quit without saving?"; //$NON-NLS-1$
            message = message.replaceAll("\n", "<br/>"); //$NON-NLS-1$ //$NON-NLS-2$
            message = message.replaceAll("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
            message = "<html><body width=\"400\">" + message + "</body></html>"; //$NON-NLS-1$ //$NON-NLS-2$
            int result = JOptionPane.showConfirmDialog(this, message, "Configuration Save Error", //$NON-NLS-1$
                    JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        if (!jobPanel.checkForModifications()) {
            return false;
        }

        quitting = true;
        if (autoSaveManager != null) {
            autoSaveManager.stop();
        }
        if (floatingCameraMonitor != null) {
            floatingCameraMonitor.dispose();
        }
        jobPanel.getTelemetryManager().dispose();
        setVisible(false);
        Thread shutdownThread = new Thread(this::shutdownMachineAndExit, "OpenPnP-Shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
        return true;
    }

    private void shutdownMachineAndExit() {
        runShutdownStep("disable machine", () -> {
            configuration.getMachine().setEnabled(false);
            return null;
        });
        runShutdownStep("close machine", () -> {
            configuration.getMachine().close();
            return null;
        });
        Logger.info("Shutdown complete, exiting."); //$NON-NLS-1$
        System.exit(0);
    }

    private void runShutdownStep(String name, Callable<Void> step) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OpenPnP-Shutdown-" + name);
            thread.setDaemon(true);
            return thread;
        });
        Future<Void> future = executor.submit(step);
        try {
            future.get(SHUTDOWN_STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            Logger.warn("Shutdown step timed out: {}", name);
            future.cancel(true);
        }
        catch (Exception e) {
            Logger.warn(e, "Shutdown step failed: {}", name);
        }
        finally {
            executor.shutdownNow();
        }
    }
    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText(status);
        });
    }

    public void setPlacementCompletionStatus(int totalPlacementsCompleted, int totalPlacements, int boardPlacementsCompleted, int boardPlacements) {
        SwingUtilities.invokeLater(() -> {
            lblPlacements.setText(String.format(Translations.getString(
                    "MainFrame.StatusPanel.PlacementsLabel.initial.format.text"), //$NON-NLS-1$
                    totalPlacementsCompleted, totalPlacements, boardPlacementsCompleted, boardPlacements));
        	prgbrPlacements.setValue((int)(((float)totalPlacementsCompleted / (float)totalPlacements) * 100.0f));
        });
    }

    public void showTab(String title) {
        int index = tabs.indexOfTab(title);
        if (index >= 0) {
            tabs.setSelectedIndex(index);
        }
    }

    /**
     * Select a main tab by its component identity. Unlike the title based overload this remains
     * reliable when tab captions are translated or customized.
     */
    public boolean showTab(Component component) {
        int index = tabs.indexOfComponent(component);
        if (index < 0) {
            return false;
        }
        tabs.setSelectedIndex(index);
        return true;
    }

    private ComponentListener mainFrameListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            prefs.putInt(PREF_WINDOW_X, getLocation().x);
            prefs.putInt(PREF_WINDOW_Y, getLocation().y);
        }

        @Override
        public void componentResized(ComponentEvent e) {
            prefs.putInt(PREF_WINDOW_WIDTH, getSize().width);
            prefs.putInt(PREF_WINDOW_HEIGHT, getSize().height);
            if (!windowStyleMultiple) {
                /* when resizing then divider is limited apparently by panel constraints
                   but they are somehow magically changed constraining e.g. jog panel
                   is not reflected by outer panel as expected.
                */
                Dimension size = splitPaneMachineAndTabs.getSize();
                Dimension dim = splitPaneMachineAndTabs.getLeftComponent().getMinimumSize();
                dim.width = (int) Math.min(Math.round(size.width * 0.1), 300);
                splitPaneMachineAndTabs.getLeftComponent().setMinimumSize(dim);
                dim = splitPaneMachineAndTabs.getRightComponent().getMinimumSize();
                dim.width = (int) Math.min(Math.round(size.width * 0.1), 200);
                splitPaneMachineAndTabs.getRightComponent().setMinimumSize(dim);
            }
        }
    };

    private ComponentListener cameraWindowListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            prefs.putInt(PREF_CAMERA_WINDOW_X, frameCamera.getLocation().x);
            prefs.putInt(PREF_CAMERA_WINDOW_Y, frameCamera.getLocation().y);
        }

        @Override
        public void componentResized(ComponentEvent e) {
            prefs.putInt(PREF_CAMERA_WINDOW_WIDTH, frameCamera.getSize().width);
            prefs.putInt(PREF_CAMERA_WINDOW_HEIGHT, frameCamera.getSize().height);
        }
    };

    private ComponentListener machineControlsWindowListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            prefs.putInt(PREF_MACHINECONTROLS_WINDOW_X, frameMachineControls.getLocation().x);
            prefs.putInt(PREF_MACHINECONTROLS_WINDOW_Y, frameMachineControls.getLocation().y);
        }

        @Override
        public void componentResized(ComponentEvent e) {
            prefs.putInt(PREF_MACHINECONTROLS_WINDOW_WIDTH, frameMachineControls.getSize().width);
            prefs.putInt(PREF_MACHINECONTROLS_WINDOW_HEIGHT, frameMachineControls.getSize().height);
        }
    };

    private Action inchesUnitSelected = new AbstractAction(LengthUnit.Inches.name()) {
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_I);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            configuration.setSystemUnits(LengthUnit.Inches);
            MessageBoxes.infoBox(Translations.getString("CommonWords.notice"), //$NON-NLS-1$
                  Translations.getString("CommonPhrases.restartToTakeEffect")); //$NON-NLS-1$
      }
    };

    private Action millimetersUnitSelected = new AbstractAction(LengthUnit.Millimeters.name()) {
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_M);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            configuration.setSystemUnits(LengthUnit.Millimeters);
            MessageBoxes.infoBox(Translations.getString("CommonWords.notice"), //$NON-NLS-1$
                    Translations.getString("CommonPhrases.restartToTakeEffect")); //$NON-NLS-1$
        }
    };

    private Action tablesUnlinkedSelected = new AbstractAction(TablesLinked.Unlinked.name()) {
        @Override
        public void actionPerformed(ActionEvent arg0) {
            configuration.setTablesLinked(TablesLinked.Unlinked);
        }
    };

    private Action tablesLinkedSelected = new AbstractAction(TablesLinked.Linked.name()) {
        @Override
        public void actionPerformed(ActionEvent arg0) {
            configuration.setTablesLinked(TablesLinked.Linked);
        }
    };

    private Action windowStyleMultipleSelected = new AbstractAction(Translations.getString("Menu.Window.MultipleStyle")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_M);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (mnWindows.getItem(0).isSelected()) {
                prefs.putBoolean(PREF_WINDOW_STYLE_MULTIPLE, true);
            }
            else {
                prefs.putBoolean(PREF_WINDOW_STYLE_MULTIPLE, false);
            }
            MessageBoxes.infoBox(Translations.getString("CommonPhrases.windowsStyleChanged"), //$NON-NLS-1$
                    Translations.getString("CommonPhrases.windowsStyleChangedRestartToTakeEffect")); //$NON-NLS-1$
        }
    };

    private Action flyingVisionCenterAction = new AbstractAction("飞拍视觉中心...") {
        {
            putValue(SHORT_DESCRIPTION, "静止连拍、低速飞拍、多帧选帧和普通停拍回退实验");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            new FlyingVisionCenterDialog(MainFrame.this).setVisible(true);
        }
    };
    private Action cameraDiagnosticsAction = new AbstractAction("相机诊断与高速预览...") {
        {
            putValue(SHORT_DESCRIPTION,
                    "测量顶部/底部相机真实采集与显示帧率、延迟抖动、格式和灯光状态");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            new CameraDiagnosticsDialog(MainFrame.this).setVisible(true);
        }
    };
    private Action safetyWorkbenchAction = new AbstractAction("3D 安全工作台...") {
        {
            putValue(SHORT_DESCRIPTION,
                    "拖拽建立机器数字孪生，采点标定并在真机前检查 XYZ 联动轨迹");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            new SafetyWorkbenchDialog(MainFrame.this).setVisible(true);
        }
    };
    private Action motionPerformanceCenterAction = new AbstractAction("运动提速验证中心...") {
        {
            putValue(SHORT_DESCRIPTION, "分级测速、异步队列等待对比、理论效率与结果导出");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            new ExperimentalMotionCenterDialog(MainFrame.this, configuration).setVisible(true);
        }
    };

    private Action firstUseGuideAction = new AbstractAction("使用引导...") {
        @Override
        public void actionPerformed(ActionEvent event) {
            FirstUseGuideDialog.showAgain(MainFrame.this);
        }
    };
    private Action backupRestoreAction = new AbstractAction("备份与恢复中心...") {
        @Override
        public void actionPerformed(ActionEvent event) {
            new BackupRestoreDialog(MainFrame.this, configuration).setVisible(true);
        }
    };

    private Action floatingCameraSettingsAction = new AbstractAction("悬浮监控设置...") {
        @Override
        public void actionPerformed(ActionEvent event) {
            if (floatingCameraMonitor == null) {
                initializeFloatingCameraMonitor();
            }
            floatingCameraMonitor.showSettingsDialog();
        }
    };

    private Action editThemeAction = new AbstractAction(Translations.getString("Menu.Window.Theme")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            ThemeDialog.showThemeDialog(mainFrame);
        }
    };

    private Action saveConfigAction = new AbstractAction(Translations.getString("Menu.File.SaveConfiguration")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
			saveConfig();
        }
    };

    private Action quitAction = new AbstractAction(Translations.getString("Menu.File.Exit")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_X);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            quit();
        }
    };

    private Action aboutAction = new AbstractAction(Translations.getString("Menu.Help.About")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_A);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            about();
        }
    };

    private Action checkForUpdatesAction = new AbstractAction(Translations.getString("Menu.Help.CheckForUpdates")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_U);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            try {
                Class applicationLauncher = Class.forName("com.install4j.api.launcher.ApplicationLauncher"); //$NON-NLS-1$
                Class callback = Class.forName("com.install4j.api.launcher.ApplicationLauncher$Callback"); //$NON-NLS-1$
                Method launchApplication = applicationLauncher.getMethod("launchApplication", String.class, String[].class, boolean.class, callback); //$NON-NLS-1$
                launchApplication.invoke(null, "125", null, false, null); //$NON-NLS-1$
            }
            catch (Exception e) {
                MessageBoxes.errorBox(MainFrame.this, "Unable to launch update application.", e); //$NON-NLS-1$
            }
        }
    };

    private Action quickStartLinkAction = new AbstractAction(Translations.getString("Menu.Help.QuickStart")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.browseUri("https://github.com/openpnp/openpnp/wiki/Quick-Start"); //$NON-NLS-1$
        }
    };

    private Action setupAndCalibrationLinkAction = new AbstractAction(Translations.getString("Menu.Help.SetupAndCalibration")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.browseUri("https://github.com/openpnp/openpnp/wiki/Setup-and-Calibration"); //$NON-NLS-1$
        }
    };

    private Action userManualLinkAction = new AbstractAction(Translations.getString("Menu.Help.UserManual")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.browseUri("https://github.com/openpnp/openpnp/wiki/User-Manual"); //$NON-NLS-1$
        }
    };

    private Action changeLogAction = new AbstractAction(Translations.getString("Menu.Help.ChangeLog")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.browseUri(Main.getSourceUri()+"CHANGES.md"); //$NON-NLS-1$
        }
    };

    private Action proChangeLogAction = new AbstractAction("Pro 更新日志") {
        @Override
        public void actionPerformed(ActionEvent event) {
            AboutDialog dialog = new AboutDialog(MainFrame.this);
            dialog.showProChangeLog();
            dialog.setLocationRelativeTo(MainFrame.this);
            dialog.setVisible(true);
        }
    };

    private Action submitDiagnosticsAction = new AbstractAction(Translations.getString("Menu.Help.SubmitDiagnostics")) { //$NON-NLS-1$
        @Override
        public void actionPerformed(ActionEvent arg0) {
            SubmitDiagnosticsDialog dialog = new SubmitDiagnosticsDialog();
            dialog.setModal(true);
            dialog.setSize(620, 720);
            dialog.setLocationRelativeTo(MainFrame.get());
            dialog.setVisible(true);
        }
    };

    public final Action undoAction = new AbstractAction(Translations.getString("Menu.Edit.Undo")) { //$NON-NLS-1$
        {
            putValue(MNEMONIC_KEY, KeyEvent.VK_Z);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke('Z',
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            try {
                undoManager.undo();
            }
            catch (Exception e) {

            }
        }
    };

    public final Action redoAction = new AbstractAction(Translations.getString("Menu.Edit.Redo")) { //$NON-NLS-1$
        {
//            putValue(MNEMONIC_KEY, KeyEvent.VK_Y);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke('Z',
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            try {
                undoManager.redo();
            }
            catch (Exception e) {

            }
        }
    };

    public class LanguageSelectionAction extends AbstractAction {
        private final Locale locale;

        public LanguageSelectionAction(Locale locale) {
            this.locale = locale;
            this.putValue(NAME, locale.getDisplayName());
        }

        public Locale getLocale() {
            return locale;
        }

        public void actionPerformed(ActionEvent arg0) {
            configuration.setLocale(locale);
            MessageBoxes.infoBox(Translations.getString("CommonWords.notice"), //$NON-NLS-1$
                    Translations.getString("CommonPhrases.restartToTakeEffect")); //$NON-NLS-1$
      }
    }

    private JPanel panelStatusAndDros;
    private JLabel droLbl;
    private JLabel lblStatus;
    private JLabel lblPlacements;
    private JProgressBar prgbrPlacements;
    private JLabel labelIcon;
}
