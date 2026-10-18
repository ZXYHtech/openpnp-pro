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
    private javax.swing.JComboBox<String> navigationScope;
    private final JButton favorite = new JButton("☆ 收藏当前");
    private final java.util.Set<String> favorites = new java.util.LinkedHashSet<>();
    private final java.util.List<String> recent = new ArrayList<>();
    private java.util.List<TreePath> expandedBeforeSearch;
    private boolean filteringNavigation;
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
        favorites.addAll(Arrays.asList(prefs.get("navigation.favorites", "").split("\n")));
        recent.addAll(Arrays.asList(prefs.get("navigation.recent", "").split("\n")));
        favorites.remove("");
        recent.remove("");

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

        JPanel navigation = new JPanel(new BorderLayout(4, 6));
        splitPane.setLeftComponent(navigation);
        JPanel shortcuts = new JPanel(new BorderLayout(4, 0));
        navigationScope = new javax.swing.JComboBox<>(new String[] {"全部配置", "收藏", "最近访问"});
        shortcuts.add(navigationScope, BorderLayout.CENTER);
        shortcuts.add(favorite, BorderLayout.EAST);
        navigation.add(shortcuts, BorderLayout.NORTH);
        resultCountLabel = new JLabel("正在载入机器配置...");
        resultCountLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        navigation.add(resultCountLabel, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane();
        navigation.add(scrollPane, BorderLayout.CENTER);

        tree = new JTree();
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(treeCellRenderer);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(28);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        scrollPane.setViewportView(tree);
        navigationScope.addActionListener(event -> search());
        favorite.addActionListener(event -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) { return; }
            String key = ((PropertySheetHolderTreeNode) path.getLastPathComponent()).navigationKey();
            if (!favorites.remove(key) && favorites.size() < 100) { favorites.add(key); }
            prefs.put("navigation.favorites", String.join("\n", favorites));
            updateFavorite();
            if (navigationScope.getSelectedIndex() == 1) { search(); }
        });
        searchTextField.setToolTipText("搜索全部层级的名称、路径、类型与参数名；支持空格分词。Ctrl+F 定位搜索，Esc 清空。");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                javax.swing.KeyStroke.getKeyStroke("ctrl F"), "findConfiguration");
        getActionMap().put("findConfiguration", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                searchTextField.requestFocusInWindow();
                searchTextField.selectAll();
            }
        });
        searchTextField.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "clearSearch");
        searchTextField.getActionMap().put("clearSearch", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { searchTextField.setText(""); }
        });
        searchTextField.addActionListener(event -> {
            for (int row = 0; row < tree.getRowCount(); row++) {
                PropertySheetHolderTreeNode node = (PropertySheetHolderTreeNode) tree.getPathForRow(row).getLastPathComponent();
                if (directNavigationMatch(node, searchTextField.getText())) {
                    tree.setSelectionRow(row);
                    tree.scrollRowToVisible(row);
                    tree.requestFocusInWindow();
                    break;
                }
            }
        });

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        JPanel detailPanel = new JPanel(new BorderLayout());
        breadcrumbLabel = new JLabel("请选择左侧树中的配置；Ctrl+F 可快速搜索");
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
                if (!filteringNavigation) { selectCurrentTreePath(); }
            }
        });

        Configuration.get().addListener(new ConfigurationListener() {
            @Override
            public void configurationLoaded(Configuration configuration) throws Exception {}

            @Override
            public void configurationComplete(Configuration configuration) throws Exception {
                tree.setModel(treeModel = new DefaultTreeModel(
                        new PropertySheetHolderTreeNode(Configuration.get().getMachine(), null)));
                rebuildNavigation();
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
        rebuildNavigation();
    }

    private void rebuildNavigation() {
        if (treeModel == null || !(treeModel.getRoot() instanceof PropertySheetHolderTreeNode)) { return; }
        TreePath selected = tree.getSelectionPath();
        String filter = searchTextField.getText();
        boolean searching = !filter.trim().isEmpty() || navigationScope.getSelectedIndex() != 0;
        if (searching && expandedBeforeSearch == null) {
            expandedBeforeSearch = new ArrayList<>();
            Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(treeModel.getRoot()));
            if (expanded != null) { while (expanded.hasMoreElements()) { expandedBeforeSearch.add(expanded.nextElement()); } }
        }
        PropertySheetHolderTreeNode root = (PropertySheetHolderTreeNode) treeModel.getRoot();
        int count = updateVisibility(root, filter);
        filteringNavigation = true;
        treeModel.reload();
        if (searching) {
            for (int row = 0; row < tree.getRowCount(); row++) { tree.expandRow(row); }
        }
        else if (expandedBeforeSearch != null) {
            for (TreePath path : expandedBeforeSearch) { tree.expandPath(path); }
            expandedBeforeSearch = null;
        }
        if (selected != null && tree.isVisible(selected)) { tree.setSelectionPath(selected); }
        filteringNavigation = false;
        resultCountLabel.setText((searching ? "匹配配置：" : "全部配置：") + count
                + (count == 0 ? "，请尝试名称或功能关键词" : " · 回车定位 / Esc 清空"));
        updateFavorite();
    }

    private int updateVisibility(PropertySheetHolderTreeNode node, String filter) {
        int matches = 0;
        node.visibleChildren.clear();
        for (PropertySheetHolderTreeNode child : node.children) {
            int nested = updateVisibility(child, filter);
            if (nested > 0) { node.visibleChildren.add(child); }
            matches += nested;
        }
        if (directNavigationMatch(node, filter)) { matches++; }
        return matches;
    }

    private boolean directNavigationMatch(PropertySheetHolderTreeNode node, String filter) {
        int scope = navigationScope.getSelectedIndex();
        return (scope == 0 || (scope == 1 ? favorites : recent).contains(node.navigationKey()))
                && org.openpnp.gui.support.ConfigurationNavigationSearch.matches(filter, node.getBreadcrumb(), node.obj.getClass());
    }

    private void updateFavorite() {
        TreePath path = tree.getSelectionPath();
        favorite.setEnabled(path != null);
        favorite.setText(path != null && favorites.contains(
                ((PropertySheetHolderTreeNode) path.getLastPathComponent()).navigationKey())
                ? "★ 取消收藏" : "☆ 收藏当前");
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
            String key = ((PropertySheetHolderTreeNode) path.getLastPathComponent()).navigationKey();
            recent.remove(key);
            recent.add(0, key);
            while (recent.size() > 12) { recent.remove(recent.size() - 1); }
            prefs.put("navigation.recent", String.join("\n", recent));
            updateFavorite();
            breadcrumbLabel.setText("位置：" + ((PropertySheetHolderTreeNode)
                    path.getLastPathComponent()).getBreadcrumb());
            breadcrumbLabel.setToolTipText(breadcrumbLabel.getText());
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
        private final ArrayList<PropertySheetHolderTreeNode> visibleChildren = new ArrayList<>();

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
                    rebuildNavigation();
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
            visibleChildren.clear();
            visibleChildren.addAll(this.children);
        }

        private String navigationKey() {
            String identity = obj.getClass().getName() + ":" + (obj instanceof org.openpnp.model.Identifiable
                    ? ((org.openpnp.model.Identifiable) obj).getId() : obj.getPropertySheetHolderTitle());
            if (parent instanceof PropertySheetHolderTreeNode) {
                identity = ((PropertySheetHolderTreeNode) parent).navigationKey() + "/" + identity;
            }
            return java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
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
            return visibleChildren.get(childIndex);
        }

        @Override
        public int getChildCount() {
            return visibleChildren.size();
        }

        @Override
        public TreeNode getParent() {
            return parent;
        }

        @Override
        public int getIndex(TreeNode node) {
            return visibleChildren.indexOf(node);
        }

        @Override
        public boolean getAllowsChildren() {
            return !visibleChildren.isEmpty();
        }

        @Override
        public boolean isLeaf() {
            return visibleChildren.isEmpty();
        }

        @Override
        public Enumeration children() {
            return Collections.enumeration(visibleChildren);
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
                setToolTipText(node.getBreadcrumb() + " · " + psh.getClass().getSimpleName());
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
