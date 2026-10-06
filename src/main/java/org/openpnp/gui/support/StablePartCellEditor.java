/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

/** Prevents search-as-you-type changes from prematurely closing a Part table editor. */
@SuppressWarnings("serial")
public class StablePartCellEditor extends DefaultCellEditor {
    private final JComboBox<?> comboBox;
    private boolean explicitCommit;
    private boolean editing;

    public StablePartCellEditor(JComboBox<?> comboBox) {
        super(comboBox);
        this.comboBox = comboBox;
        Component editor = comboBox.getEditor().getEditorComponent();
        if (editor instanceof JTextComponent) {
            editor.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_ENTER
                            && comboBox instanceof SearchablePartComboBox
                            && comboBox.isPopupVisible()) {
                        event.consume();
                        SwingUtilities.invokeLater(() -> {
                            explicitCommit = true;
                            SearchablePartComboBox searchable =
                                    (SearchablePartComboBox) comboBox;
                            if (!searchable.acceptHighlightedSelection() && editing) {
                                stopCellEditing();
                            }
                        });
                    }
                    else if (event.getKeyCode() == KeyEvent.VK_ENTER
                            || event.getKeyCode() == KeyEvent.VK_TAB) {
                        explicitCommit = true;
                        SwingUtilities.invokeLater(StablePartCellEditor.this::stopCellEditing);
                    }
                    else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        cancelCellEditing();
                    }
                }
            });
        }
        comboBox.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                if (editing && comboBox.isFocusOwner()) {
                    explicitCommit = true;
                    SwingUtilities.invokeLater(StablePartCellEditor.this::stopCellEditing);
                }
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
            }
        });
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean selected,
            int row, int column) {
        explicitCommit = false;
        editing = true;
        return super.getTableCellEditorComponent(table, value, selected, row, column);
    }

    @Override
    public boolean stopCellEditing() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Component editor = comboBox.getEditor().getEditorComponent();
        if (editing && !explicitCommit
                && !(comboBox instanceof SearchablePartComboBox
                        && ((SearchablePartComboBox) comboBox).isAcceptingSelection())
                && (focusOwner == comboBox || focusOwner == editor
                || SwingUtilities.isDescendingFrom(focusOwner, comboBox))) {
            return false;
        }
        editing = false;
        return super.stopCellEditing();
    }

    @Override
    public void cancelCellEditing() {
        editing = false;
        super.cancelCellEditing();
    }
}
