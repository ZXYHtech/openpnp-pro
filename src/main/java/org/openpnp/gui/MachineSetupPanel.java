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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.beans.IndexedPropertyChangeEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.openpnp.ConfigurationListener;
import org.openpnp.Translations;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.WrapLayout;
import org.openpnp.gui.support.Wizard;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.model.Configuration;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.spi.PropertySheetHolder.PropertySheet;
import org.openpnp.util.BeanUtils;

@SuppressWarnings("serial")
public class MachineSetupPanel extends JPanel implements WizardContainer {


    private static final String PREF_DIVIDER_POSITION = "MachineSetupPanel.tileDividerPosition";
    private static final int PREF_DIVIDER_POSITION_DEF = 360;

    private JTextField searchTextField;

    private Preferences prefs = Preferences.userNodeForPackage(MachineSetupPanel.class);
    private JTree tree;
    private DefaultTreeModel treeModel;
    private JTabbedPane tabbedPane;
    private JTabbedPane navigationTabs;
    private JPanel tilePanel;
    private JLabel resultCountLabel;
    private JLabel breadcrumbLabel;
    private JToolBar toolBar;
    private final Action action = new SwingAction();
    private JCheckBox cbExp;
    
    /**
     * These three variables are used to manage state so that if the user is flipping between nodes
     * of the same type we select the same tab that was previously selected. The idea is that if
     * you are looking at the Part Detection settings for a Nozzle Tip, and you switch to another
     * Nozzle Tip you probably want to look at the Part Detection settings for it, and not
     * whatever the first tab is.
     */
    private boolean disableLastSelectedListener = false;
    private Object lastSelectedNode = null;
    private HashMap<Class, Integer> lastSelectedTabIndex = new HashMap<>();

    public MachineSetupPanel() {
        setLayout(new BorderLayout(0, 0));

        JPanel panel = new JPanel();
        add(panel, BorderLayout.NORTH);
        panel.setLayout(new BorderLayout(0, 0));

        toolBar = new JToolBar();
        toolBar.setFloatable(false);
        panel.add(toolBar, BorderLayout.CENTER);

        JPanel panel_1 = new JPanel();
        panel.add(panel_1, BorderLayout.EAST);
        
                cbExp = new JCheckBox(Translations.getString("MachineSetupPanel.ExpandChkBox.text")); //$NON-NLS-1$
                panel_1.add(cbExp);
                cbExp.setAction(action);

        JLabel lblSearch = new JLabel(Translations.getString("MachineSetupPanel.SearchLabel.text")); //$NON-NLS-1$
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

        final JSplitPane splitPane = new JSplitPane();
        splitPane.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
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

        navigationTabs = new JTabbedPane(JTabbedPane.TOP);
        splitPane.setLeftComponent(navigationTabs);

        tilePanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
        tilePanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane tileScrollPane = new JScrollPane(tilePanel);
        tileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        JPanel tileContainer = new JPanel(new BorderLayout());
        resultCountLabel = new JLabel("正在载入机器配置...");
        resultCountLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        tileContainer.add(resultCountLabel, BorderLayout.NORTH);
        tileContainer.add(tileScrollPane, BorderLayout.CENTER);
        navigationTabs.addTab("板块", tileContainer);

        JScrollPane scrollPane = new JScrollPane();
        navigationTabs.addTab("树状", scrollPane);

        tree = new JTree();
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(treeCellRenderer);
        scrollPane.setViewportView(tree);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        JPanel detailPanel = new JPanel(new BorderLayout());
        breadcrumbLabel = new JLabel("请选择左侧配置板块");
        breadcrumbLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        detailPanel.add(breadcrumbLabel, BorderLayout.NORTH);
        detailPanel.add(tabbedPane, BorderLayout.CENTER);
        splitPane.setRightComponent(detailPanel);
        
        tabbedPane.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (lastSelectedNode != null) {
                    if (disableLastSelectedListener) {
                        return;
                    }
                    lastSelectedTabIndex.put(lastSelectedNode.getClass(), tabbedPane.getSelectedIndex());
                }
            }
        });

        tree.getSelectionModel().addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                selectCurrentTreePath();
            }
        });

        Configuration.get().addListener(new ConfigurationListener() {
            @Override
            public void configurationLoaded(Configuration configuration) throws Exception {}

            @Override
            public void configurationComplete(Configuration configuration) throws Exception {
                tree.setModel(treeModel = new DefaultTreeModel(
                        new PropertySheetHolderTreeNode(Configuration.get().getMachine(), null)));
                rebuildTiles();
                for (int i = 1; i < tree.getRowCount(); i++) {
                  if (cbExp.isSelected()) {
                    tree.expandRow(i);
                  }
                  else {
                    tree.collapseRow(i);
                  }
                }
            }
        });
    }

    private void search() {
        rebuildTiles();
    }

    private void rebuildTiles() {
        if (tilePanel == null) {
            return;
        }
        tilePanel.removeAll();
        if (treeModel == null || !(treeModel.getRoot() instanceof PropertySheetHolderTreeNode)) {
            resultCountLabel.setText("机器配置尚未载入");
            tilePanel.revalidate();
            tilePanel.repaint();
            return;
        }
        String filter = searchTextField.getText();
        List<PropertySheetHolderTreeNode> nodes = new ArrayList<>();
        collectNodes((PropertySheetHolderTreeNode) treeModel.getRoot(), nodes);
        int matches = 0;
        for (PropertySheetHolderTreeNode node : nodes) {
            String title = node.toString();
            String path = node.getBreadcrumb();
            String type = node.obj.getClass().getSimpleName();
            if (!matchesNavigationFilter(filter, title, path, type)) {
                continue;
            }
            JButton card = new JButton("<html><b>" + escapeHtml(title)
                    + "</b><br><font color='#808080'>" + escapeHtml(node.getParentBreadcrumb())
                    + "</font></html>", node.obj.getPropertySheetHolderIcon());
            card.setHorizontalAlignment(JButton.LEFT);
            card.setVerticalAlignment(JButton.CENTER);
            card.setFont(card.getFont().deriveFont(Font.PLAIN));
            card.setPreferredSize(new Dimension(235, 70));
            card.setMinimumSize(new Dimension(190, 64));
            card.setToolTipText("<html>" + escapeHtml(path) + "<br>类型："
                    + escapeHtml(type) + "</html>");
            card.addActionListener(event -> tree.setSelectionPath(node.getTreePath()));
            tilePanel.add(card);
            matches++;
        }
        resultCountLabel.setText((filter == null || filter.trim().isEmpty() ? "全部配置" : "搜索结果")
                + "：" + matches + " 个板块");
        if (matches == 0) {
            JLabel empty = new JLabel("没有匹配项，请尝试设备名称、功能或类型关键词。");
            empty.setBorder(BorderFactory.createEmptyBorder(18, 12, 18, 12));
            tilePanel.add(empty);
        }
        tilePanel.revalidate();
        tilePanel.repaint();
    }

    private void collectNodes(PropertySheetHolderTreeNode node,
            List<PropertySheetHolderTreeNode> result) {
        result.add(node);
        for (PropertySheetHolderTreeNode child : node.children) {
            collectNodes(child, result);
        }
    }

    static boolean matchesNavigationFilter(String filter, String... values) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void wizardCompleted(Wizard wizard) {}

    @Override
    public void wizardCancelled(Wizard wizard) {}

    public void selectCurrentTreePath() {
        disableLastSelectedListener = true;

        for (Component comp : tabbedPane.getComponents()) {
            if (comp instanceof AbstractConfigurationWizard) {
                ((AbstractConfigurationWizard) comp).dispose();
            }
        }
        tabbedPane.removeAll();
        
        toolBar.removeAll();

        TreePath path = tree.getSelectionPath();
        if (path != null) {
            breadcrumbLabel.setText("位置：" + ((PropertySheetHolderTreeNode)
                    path.getLastPathComponent()).getBreadcrumb());
            List<Object> pathsReverse = Arrays.asList(path.getPath());
            Collections.reverse(pathsReverse);
            for (Object o : pathsReverse) {
                PropertySheetHolderTreeNode node = (PropertySheetHolderTreeNode) o;
                Action[] actions = node.obj.getPropertySheetHolderActions();
                if (actions != null) {
                    for (Action action : actions) {
                        toolBar.add(action);
                    }
                }
            }

            PropertySheetHolderTreeNode node =
                    (PropertySheetHolderTreeNode) path.getLastPathComponent();
            if (node != null) {
                PropertySheet[] propertySheets = node.obj.getPropertySheets();
                if (propertySheets != null) {
                    for (PropertySheet propertySheet : propertySheets) {
                        String title = propertySheet.getPropertySheetTitle();
                        JPanel panel = propertySheet.getPropertySheetPanel();
                        if(panel instanceof AbstractConfigurationWizard) {
                            AbstractConfigurationWizard wizard = (AbstractConfigurationWizard) panel;
                            if (wizard.getWizardContainer() == null) {
                                wizard.setWizardContainer(MachineSetupPanel.this);
                            }
                        }
                        if (title == null) {
                            title = Translations.getString("MachineSetupPanel.RightComponent.tabs.configuration.title"); //$NON-NLS-1$
                        }
                        if (panel != null) {
                            tabbedPane.add(title, panel);
                        }
                    }
                    
                    if (node.obj != null) {
                        if (lastSelectedTabIndex.get(node.obj.getClass()) != null) {
                            // Sometimes when clicking around quickly, the lastSelectedTabIndex update seems to be wrong, 
                            // resulting in an IndexOutOfBoundsException. Therefore we check the tab count too. This covers variable per class Wizards too. 
                            tabbedPane.setSelectedIndex(Math.min(tabbedPane.getTabCount()-1, lastSelectedTabIndex.get(node.obj.getClass())));
                        }
                    }
                    
                    lastSelectedNode = node.obj;
                }
                else {
                    lastSelectedNode = null;
                }
            }
        }
        
        disableLastSelectedListener = false;

        revalidate();
        repaint();
    }

    public class PropertySheetHolderTreeNode implements TreeNode {
        private final PropertySheetHolder obj;
        private final TreeNode parent;
        private final ArrayList<PropertySheetHolderTreeNode> children = new ArrayList<>();

        public PropertySheetHolderTreeNode(PropertySheetHolder obj, TreeNode parent) {
            this.obj = obj;
            this.parent = parent;
            loadChildren();
            /**
             * If the object we're creating a node for supports property change then we add
             * a listener. When we get an indexed changed we refresh our children and
             * when we get a non-indexed change we refresh ourself.
             * 
             * TODO: Note: Since we don't know which child got refreshed, we refresh them
             * all and this causes the JTree to collapse all the other children. This sucks
             * but there isn't a clean way to know which child changed without including the
             * property name somewhere.
             */
            BeanUtils.addPropertyChangeListener(obj, (PropertyChangeListener) (e) -> {
                SwingUtilities.invokeLater(() -> {
                    if (e instanceof IndexedPropertyChangeEvent) {
                        for (PropertySheetHolderTreeNode node : children) {
                            node.loadChildren();
                            treeModel.nodeStructureChanged(node);
                        }
                    }
                    else {
                        treeModel.nodeChanged(this);
                    }
                    rebuildTiles();
                });
            }); 
        }

        private void loadChildren() {
            this.children.clear();
            PropertySheetHolder[] children = obj.getChildPropertySheetHolders();
            if (children != null) {
                for (PropertySheetHolder child : children) {
                    this.children.add(new PropertySheetHolderTreeNode(child, this));
                }
            }
        }

        public PropertySheetHolder getPropertySheetHolder() {
            return obj;
        }

        public TreePath getTreePath() {
            List<PropertySheetHolderTreeNode> path = new ArrayList<>();
            PropertySheetHolderTreeNode node = this;
            while (node != null) {
                path.add(node);
                node = node.parent instanceof PropertySheetHolderTreeNode
                        ? (PropertySheetHolderTreeNode) node.parent : null;
            }
            Collections.reverse(path);
            return new TreePath(path.toArray());
        }

        public String getBreadcrumb() {
            Object[] path = getTreePath().getPath();
            StringBuilder text = new StringBuilder();
            for (Object item : path) {
                if (text.length() > 0) {
                    text.append("  ›  ");
                }
                text.append(item);
            }
            return text.toString();
        }

        public String getParentBreadcrumb() {
            if (!(parent instanceof PropertySheetHolderTreeNode)) {
                return "机器配置";
            }
            return ((PropertySheetHolderTreeNode) parent).getBreadcrumb();
        }

        @Override
        public TreeNode getChildAt(int childIndex) {
            return children.get(childIndex);
        }

        @Override
        public int getChildCount() {
            return children.size();
        }

        @Override
        public TreeNode getParent() {
            return parent;
        }

        @Override
        public int getIndex(TreeNode node) {
            return children.indexOf(node);
        }

        @Override
        public boolean getAllowsChildren() {
            return children.size() > 0;
        }

        @Override
        public boolean isLeaf() {
            return children.size() < 1;
        }

        @Override
        public Enumeration children() {
            return Collections.enumeration(children);
        }

        @Override
        public String toString() {
            return obj.getPropertySheetHolderTitle();
        }
    }

    private TreeCellRenderer treeCellRenderer = new DefaultTreeCellRenderer() {
        // http://stackoverflow.com/questions/20691946/set-icon-to-each-node-in-jtree
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row,
                    hasFocus);
            if (value instanceof PropertySheetHolderTreeNode) {
                PropertySheetHolderTreeNode node = (PropertySheetHolderTreeNode) value;
                PropertySheetHolder psh = node.getPropertySheetHolder();
                setIcon(psh.getPropertySheetHolderIcon());
            }
            return this;
        }
    };
    private class SwingAction extends AbstractAction {
        public SwingAction() {
            putValue(NAME, Translations.getString("MachineSetupPanel.Action.Expand")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("MachineSetupPanel.Action.Expand.Description")); //$NON-NLS-1$
        }
        
        public void actionPerformed(ActionEvent e) {
          for (int i = 1; i < tree.getRowCount(); i++) {
            if (cbExp.isSelected()) {
              tree.expandRow(i);
            }
            else {
              tree.collapseRow(i);
            }
          }
        }
    }
}
