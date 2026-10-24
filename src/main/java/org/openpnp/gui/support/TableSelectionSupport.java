package org.openpnp.gui.support;

import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.AWTEvent;
import java.awt.event.ContainerEvent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;

/** Common deselection action for existing tables and tables created by later dialogs. */
public final class TableSelectionSupport {
    private static final String INSTALLED = "openpnp.clearTableSelection";
    private static boolean installed;

    private TableSelectionSupport() { }

    public static void install() {
        if (installed) { return; }
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof ContainerEvent
                    && event.getID() == ContainerEvent.COMPONENT_ADDED) {
                attach(((ContainerEvent) event).getChild());
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
        for (Window window : Window.getWindows()) { attach(window); }
    }

    private static void attach(Component component) {
        if (component instanceof JTable) { install((JTable) component); }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) { attach(child); }
        }
    }

    public static void install(JTable table) {
        if (table.getClientProperty(INSTALLED) != null) { return; }
        table.putClientProperty(INSTALLED, Boolean.TRUE);
        JPopupMenu popup = table.getComponentPopupMenu();
        if (popup == null) {
            popup = new JPopupMenu();
            table.setComponentPopupMenu(popup);
        }
        augment(table, popup);
        table.addPropertyChangeListener("componentPopupMenu", event -> {
            if (event.getNewValue() instanceof JPopupMenu) {
                augment(table, (JPopupMenu) event.getNewValue());
            }
        });
    }

    public static void augment(JTable table, JPopupMenu popup) {
        if (popup.getClientProperty(INSTALLED) != null) { return; }
        popup.putClientProperty(INSTALLED, Boolean.TRUE);
        if (popup.getComponentCount() > 0) { popup.addSeparator(); }
        JMenuItem clear = new JMenuItem("取消选择");
        clear.addActionListener(event -> clear(table));
        popup.add(clear);
    }

    public static void clear(JTable table) {
        if (table.isEditing()) { table.getCellEditor().cancelCellEditing(); }
        table.clearSelection();
    }
}
