/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.text.JTextComponent;

import org.openpnp.model.Part;

/** A Part selector whose suggestion window never steals focus from a table cell editor. */
@SuppressWarnings({"rawtypes", "serial", "unchecked"})
public class SearchablePartComboBox extends JComboBox {
    private final DefaultListModel<Object> searchModel = new DefaultListModel<>();
    private final JList<Object> searchList = new JList<>(searchModel);
    private final JPanel searchPanel = new JPanel(new java.awt.BorderLayout());
    private JWindow searchWindow;
    private boolean installed;
    private boolean updatingEditor;
    private boolean acceptingSelection;

    public SearchablePartComboBox() {
        super();
    }

    public SearchablePartComboBox(ComboBoxModel model) {
        super(model);
    }

    public void installSearchSupport() {
        if (installed) {
            return;
        }
        installed = true;
        setEditable(true);
        setMaximumRowCount(18);
        setEditor(new PartEditor());
        getEditor().setItem(getSelectedItem());

        searchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchList.setFixedCellHeight(30);
        searchList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                ListCellRenderer renderer = getRenderer();
                if (renderer != null) {
                    return renderer.getListCellRendererComponent(list, value, index, selected, focus);
                }
                return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
        });
        JScrollPane scrollPane = new JScrollPane(searchList);
        scrollPane.setBorder(null);
        searchPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x3B82F6), 1),
                BorderFactory.createEmptyBorder(3, 3, 3, 3)));
        searchPanel.add(scrollPane);

        JTextComponent text = editorText();
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filterLater();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filterLater();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filterLater();
            }
        });

        text.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_DOWN) {
                    moveSelection(1);
                    event.consume();
                }
                else if (event.getKeyCode() == KeyEvent.VK_UP) {
                    moveSelection(-1);
                    event.consume();
                }

                else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    hidePopup();
                    event.consume();
                }
            }
        });
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                text.selectAll();
            }

            @Override
            public void focusLost(FocusEvent event) {
                SwingUtilities.invokeLater(() -> {
                    Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .getFocusOwner();
                    if (focus == null || !SwingUtilities.isDescendingFrom(focus, searchPanel)) {
                        hidePopup();
                    }
                });
            }
        });
        searchList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int index = searchList.locationToIndex(event.getPoint());
                if (index >= 0) {
                    searchList.setSelectedIndex(index);
                    acceptSelection();
                }
            }
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) {
                hidePopup();
            }
        });
    }

    private void filterLater() {
        if (!updatingEditor) {
            SwingUtilities.invokeLater(this::filterAndShow);
        }
    }

    private void filterAndShow() {
        if (!isShowing() || !editorText().isFocusOwner() || updatingEditor) {
            return;
        }
        String query = editorText().getText().trim();
        searchModel.clear();
        for (int index = 0; index < getModel().getSize(); index++) {
            Object value = getModel().getElementAt(index);
            if (PartSearchSupport.matches(value, query)) {
                searchModel.addElement(value);
                if (searchModel.size() >= 100) {
                    break;
                }
            }
        }
        if (searchModel.isEmpty()) {
            hidePopup();
            return;
        }
        searchList.setSelectedIndex(0);
        showIndependentPopup();
    }

    private void showIndependentPopup() {
        ensureSearchWindow();
        if (searchWindow == null || !isShowing()) {
            return;
        }
        int rows = Math.min(getMaximumRowCount(), searchModel.size());
        int width = Math.min(620, Math.max(getWidth(), 360));
        int height = Math.min(420, Math.max(36, rows * searchList.getFixedCellHeight() + 8));
        Point point = getLocationOnScreen();
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        Rectangle screen = configuration == null
                ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
                : configuration.getBounds();
        Insets insets = configuration == null ? new Insets(0, 0, 0, 0)
                : Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int maxRight = screen.x + screen.width - insets.right;
        int maxBottom = screen.y + screen.height - insets.bottom;
        int x = Math.max(screen.x + insets.left, Math.min(point.x, maxRight - width));
        int below = point.y + getHeight();
        int y = below + height <= maxBottom ? below : Math.max(screen.y + insets.top, point.y - height);
        searchWindow.setBounds(x, y, width, height);
        if (!searchWindow.isVisible()) {
            searchWindow.setVisible(true);
        }
        editorText().requestFocusInWindow();
    }

    private void ensureSearchWindow() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (searchWindow != null && searchWindow.getOwner() != owner) {
            searchWindow.dispose();
            searchWindow = null;
        }
        if (searchWindow == null && owner != null) {
            searchWindow = new JWindow(owner);
            searchWindow.setFocusableWindowState(false);
            searchWindow.setType(Window.Type.POPUP);
            searchWindow.setContentPane(searchPanel);
        }
    }

    private boolean isSearchVisible() {
        return searchWindow != null && searchWindow.isVisible();
    }

    private void moveSelection(int direction) {
        if (!isSearchVisible()) {
            filterAndShow();
            return;
        }
        int size = searchModel.size();
        if (size == 0) {
            return;
        }
        int index = searchList.getSelectedIndex();
        index = Math.max(0, Math.min(size - 1, index + direction));
        searchList.setSelectedIndex(index);
        searchList.ensureIndexIsVisible(index);
    }

    private void acceptSelection() {
        Object selected = searchList.getSelectedValue();
        if (selected == null) {
            return;
        }
        updatingEditor = true;
        acceptingSelection = true;
        setSelectedItem(selected);
        getEditor().setItem(selected);
        acceptingSelection = false;
        updatingEditor = false;
        hidePopup();
    }

    public boolean acceptHighlightedSelection() {
        if (!isSearchVisible() || searchList.getSelectedValue() == null) {
            return false;
        }
        acceptSelection();
        return true;
    }

    public boolean isAcceptingSelection() {
        return acceptingSelection;
    }

    private JTextComponent editorText() {
        return (JTextComponent) getEditor().getEditorComponent();
    }

    @Override
    public void showPopup() {
        if (!installed) {
            super.showPopup();
            return;
        }
        editorText().requestFocusInWindow();
        SwingUtilities.invokeLater(this::filterAndShow);
    }

    @Override
    public void hidePopup() {
        if (searchWindow != null) {
            searchWindow.setVisible(false);
        }
        if (!installed) {
            super.hidePopup();
        }
    }

    @Override
    public boolean isPopupVisible() {
        return installed ? isSearchVisible() : super.isPopupVisible();
    }

    private class PartEditor extends BasicComboBoxEditor {
        @Override
        public void setItem(Object item) {
            updatingEditor = true;
            editor.setText(PartSearchSupport.displayText(item));
            updatingEditor = false;
        }

        @Override
        public Object getItem() {
            if (isSearchVisible() && searchList.getSelectedValue() != null) {
                return searchList.getSelectedValue();
            }
            Object selected = SearchablePartComboBox.this.getSelectedItem();
            if (selected instanceof Part
                    && PartSearchSupport.displayText(selected).equals(editor.getText())) {
                return selected;
            }
            return editor.getText();
        }
    }
}