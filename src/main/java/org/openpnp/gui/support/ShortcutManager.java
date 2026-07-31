/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.prefs.Preferences;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Altium-style shortcut customization for Swing actions.
 *
 * Hold Ctrl (Cmd on macOS) and click any menu command or action-backed button to edit its primary
 * and alternate shortcuts. Assignments are persisted in user preferences and shared by every
 * presentation of the same Action.
 */
public class ShortcutManager {
    public static final String ACTION_ID_KEY = "openpnp.shortcut.id";
    private static final String NONE = "<none>";
    private static final String PREF_PRIMARY = ".primary";
    private static final String PREF_ALTERNATE = ".alternate";

    private final Window owner;
    private final Map<KeyStroke, Action> dispatchMap;
    private final Preferences preferences =
            Preferences.userNodeForPackage(ShortcutManager.class).node("custom-shortcuts");
    private final Map<Action, ShortcutDefinition> definitions = new IdentityHashMap<>();
    private final Map<KeyStroke, Action> assigned = new LinkedHashMap<>();
    private AbstractButton suppressedButton;
    private AWTEventListener mouseListener;

    public ShortcutManager(Window owner, Map<KeyStroke, Action> dispatchMap) {
        this.owner = owner;
        this.dispatchMap = dispatchMap;
    }

    public void install(JMenuBar menuBar, Container content) {
        registerTree(menuBar);
        registerTree(content);
        rebuildAssignments();
        installCtrlClickListener();
    }

    public void registerTree(Component component) {
        if (component instanceof AbstractButton) {
            register(((AbstractButton) component).getAction());
        }
        if (component instanceof JMenuItem) {
            register(((JMenuItem) component).getAction());
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                registerTree(child);
            }
        }
    }

    public void register(Action action) {
        if (action == null || definitions.containsKey(action)) {
            return;
        }

        String name = actionName(action);
        String id = Objects.toString(action.getValue(ACTION_ID_KEY), null);
        if (id == null || id.trim().isEmpty()) {
            String source = action.getClass().getName() + "|" + name;
            id = Integer.toHexString(source.hashCode());
            action.putValue(ACTION_ID_KEY, id);
        }

        List<KeyStroke> defaults = new ArrayList<>();
        Object accelerator = action.getValue(Action.ACCELERATOR_KEY);
        if (accelerator instanceof KeyStroke) {
            defaults.add((KeyStroke) accelerator);
        }
        for (Map.Entry<KeyStroke, Action> entry : dispatchMap.entrySet()) {
            if (entry.getValue() == action && !defaults.contains(entry.getKey())) {
                defaults.add(entry.getKey());
            }
        }

        ShortcutDefinition definition = new ShortcutDefinition(action, id, name,
                defaults.size() > 0 ? defaults.get(0) : null,
                defaults.size() > 1 ? defaults.get(1) : null);
        definition.primary = readShortcut(id + PREF_PRIMARY, definition.defaultPrimary);
        definition.alternate = readShortcut(id + PREF_ALTERNATE, definition.defaultAlternate);
        definitions.put(action, definition);
    }

    private void installCtrlClickListener() {
        if (mouseListener != null) {
            return;
        }
        mouseListener = event -> {
            if (!(event instanceof MouseEvent)) {
                return;
            }
            MouseEvent mouseEvent = (MouseEvent) event;
            AbstractButton button = findButton(mouseEvent.getComponent());
            boolean customizeGesture = SwingUtilities.isLeftMouseButton(mouseEvent)
                    && isMenuShortcutDown(mouseEvent);

            if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED && customizeGesture
                    && button != null && button.getAction() != null) {
                suppressedButton = button;
                mouseEvent.consume();
                return;
            }

            if (suppressedButton != null && button == suppressedButton
                    && (mouseEvent.getID() == MouseEvent.MOUSE_RELEASED
                            || mouseEvent.getID() == MouseEvent.MOUSE_CLICKED)) {
                mouseEvent.consume();
                if (mouseEvent.getID() == MouseEvent.MOUSE_RELEASED) {
                    Action action = suppressedButton.getAction();
                    suppressedButton = null;
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    SwingUtilities.invokeLater(() -> edit(action));
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    private boolean isMenuShortcutDown(MouseEvent event) {
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        return (event.getModifiersEx() & shortcutMask) == shortcutMask;
    }

    private AbstractButton findButton(Component component) {
        Component current = component;
        while (current != null && !(current instanceof Window)
                && !(current instanceof JPopupMenu)) {
            if (current instanceof AbstractButton) {
                return (AbstractButton) current;
            }
            current = current.getParent();
        }
        if (current instanceof JPopupMenu) {
            Component invoker = ((JPopupMenu) current).getInvoker();
            return invoker instanceof AbstractButton ? (AbstractButton) invoker : null;
        }
        return null;
    }

    public void edit(Action action) {
        register(action);
        ShortcutDefinition definition = definitions.get(action);
        if (definition == null) {
            return;
        }
        ShortcutEditorDialog dialog = new ShortcutEditorDialog(owner, definition);
        dialog.setVisible(true);
    }

    private void apply(ShortcutDefinition definition, KeyStroke primary, KeyStroke alternate,
            boolean persist) {
        if (primary != null && primary.equals(alternate)) {
            alternate = null;
        }

        List<ShortcutDefinition> conflicts = new ArrayList<>();
        collectConflict(conflicts, definition, primary);
        collectConflict(conflicts, definition, alternate);
        if (!conflicts.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (ShortcutDefinition conflict : conflicts) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(conflict.name);
            }
            int result = JOptionPane.showConfirmDialog(owner,
                    "The shortcut is already assigned to:\n" + names
                            + "\n\nReassign it to " + definition.name + "?",
                    "Shortcut conflict", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
            for (ShortcutDefinition conflict : conflicts) {
                if (Objects.equals(conflict.primary, primary)
                        || Objects.equals(conflict.primary, alternate)) {
                    conflict.primary = null;
                }
                if (Objects.equals(conflict.alternate, primary)
                        || Objects.equals(conflict.alternate, alternate)) {
                    conflict.alternate = null;
                }
                persist(conflict);
            }
        }

        definition.primary = primary;
        definition.alternate = alternate;
        if (persist) {
            persist(definition);
        }
        rebuildAssignments();
    }

    private void collectConflict(List<ShortcutDefinition> conflicts,
            ShortcutDefinition definition, KeyStroke stroke) {
        if (stroke == null) {
            return;
        }
        Action conflictAction = assigned.get(stroke);
        if (conflictAction != null && conflictAction != definition.action) {
            ShortcutDefinition conflict = definitions.get(conflictAction);
            if (conflict != null && !conflicts.contains(conflict)) {
                conflicts.add(conflict);
            }
        }
    }

    private void restoreDefaults(ShortcutDefinition definition) {
        preferences.remove(definition.id + PREF_PRIMARY);
        preferences.remove(definition.id + PREF_ALTERNATE);
        apply(definition, definition.defaultPrimary, definition.defaultAlternate, false);
    }

    private void clear(ShortcutDefinition definition) {
        apply(definition, null, null, true);
    }

    private void persist(ShortcutDefinition definition) {
        writeShortcut(definition.id + PREF_PRIMARY, definition.primary);
        writeShortcut(definition.id + PREF_ALTERNATE, definition.alternate);
    }

    private void writeShortcut(String key, KeyStroke stroke) {
        preferences.put(key, stroke == null ? NONE : stroke.toString());
    }

    private KeyStroke readShortcut(String key, KeyStroke fallback) {
        String value = preferences.get(key, null);
        if (value == null) {
            return fallback;
        }
        if (NONE.equals(value)) {
            return null;
        }
        KeyStroke stroke = KeyStroke.getKeyStroke(value);
        return stroke == null ? fallback : stroke;
    }

    private void rebuildAssignments() {
        for (ShortcutDefinition definition : definitions.values()) {
            dispatchMap.entrySet().removeIf(entry -> entry.getValue() == definition.action);
            definition.action.putValue(Action.ACCELERATOR_KEY, definition.primary);
        }
        assigned.clear();
        for (ShortcutDefinition definition : definitions.values()) {
            putAssignment(definition.primary, definition.action);
            putAssignment(definition.alternate, definition.action);
        }
    }

    private void putAssignment(KeyStroke stroke, Action action) {
        if (stroke != null) {
            assigned.put(stroke, action);
            dispatchMap.put(stroke, action);
        }
    }

    private String actionName(Action action) {
        String name = Objects.toString(action.getValue(Action.NAME), "").trim();
        if (name.isEmpty()) {
            name = Objects.toString(action.getValue(Action.SHORT_DESCRIPTION), "").trim();
        }
        return name.isEmpty() ? "Unnamed command" : name;
    }

    private String conflictText(ShortcutDefinition current, KeyStroke stroke) {
        if (stroke == null) {
            return "Not assigned";
        }
        Action conflict = assigned.get(stroke);
        if (conflict == null || conflict == current.action) {
            return "Available";
        }
        return "Currently used by " + definitions.get(conflict).name;
    }

    public static String format(KeyStroke stroke) {
        if (stroke == null) {
            return "None";
        }
        String modifiers = InputEvent.getModifiersExText(stroke.getModifiers());
        String key = KeyEvent.getKeyText(stroke.getKeyCode());
        return modifiers.isEmpty() ? key : modifiers + "+" + key;
    }

    private static boolean isUnsafeBareTypingKey(KeyStroke stroke) {
        if (stroke == null || stroke.getModifiers() != 0) {
            return false;
        }
        int code = stroke.getKeyCode();
        return (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z)
                || (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9)
                || code == KeyEvent.VK_SPACE;
    }

    private class ShortcutEditorDialog extends JDialog {
        private final ShortcutDefinition definition;
        private final ShortcutField primaryField;
        private final ShortcutField alternateField;
        private final JLabel primaryStatus = new JLabel();
        private final JLabel alternateStatus = new JLabel();

        ShortcutEditorDialog(Window owner, ShortcutDefinition definition) {
            super(owner instanceof Frame ? (Frame) owner : null, "Customize shortcut",
                    Dialog.ModalityType.APPLICATION_MODAL);
            this.definition = definition;
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(540, 330));

            JPanel root = new JPanel(new BorderLayout(0, 18));
            root.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));
            setContentPane(root);

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(definition.name);
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
            JLabel help = new JLabel(
                    "Press a key combination. Backspace or Delete clears a field.");
            help.setForeground(ModernTheme.mutedForeground());
            heading.add(title);
            heading.add(Box.createVerticalStrut(5));
            heading.add(help);
            root.add(heading, BorderLayout.NORTH);

            JPanel fields = new JPanel(new GridLayout(2, 1, 0, 14));
            fields.setOpaque(false);
            primaryField = createField("Primary shortcut", definition.primary, primaryStatus);
            alternateField =
                    createField("Alternate shortcut", definition.alternate, alternateStatus);
            fields.add(primaryField.getParent());
            fields.add(alternateField.getParent());
            root.add(fields, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            JPanel secondary = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            secondary.setOpaque(false);
            JButton clear = new JButton("Remove shortcuts");
            clear.addActionListener(event -> {
                ShortcutManager.this.clear(definition);
                dispose();
            });
            JButton reset = new JButton("Restore defaults");
            reset.addActionListener(event -> {
                ShortcutManager.this.restoreDefaults(definition);
                dispose();
            });
            secondary.add(clear);
            secondary.add(reset);
            footer.add(secondary, BorderLayout.WEST);

            JPanel primary = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            primary.setOpaque(false);
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(event -> dispose());
            JButton assignButton = new JButton("Assign");
            assignButton.putClientProperty("JButton.buttonType", "roundRect");
            assignButton.addActionListener(event -> {
                if (isUnsafeBareTypingKey(primaryField.getStroke())
                        || isUnsafeBareTypingKey(alternateField.getStroke())) {
                    JOptionPane.showMessageDialog(this,
                            "Letters, numbers and Space require Ctrl, Alt or Shift so normal typing "
                                    + "is not intercepted.",
                            "Shortcut needs a modifier", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                ShortcutManager.this.apply(definition, primaryField.getStroke(),
                        alternateField.getStroke(), true);
                dispose();
            });
            primary.add(cancel);
            primary.add(assignButton);
            footer.add(primary, BorderLayout.EAST);
            root.add(footer, BorderLayout.SOUTH);

            updateStatus();
            getRootPane().setDefaultButton(assignButton);
            pack();
            setLocationRelativeTo(owner);
        }

        private ShortcutField createField(String labelText, KeyStroke value, JLabel status) {
            JPanel wrapper = new JPanel(new BorderLayout(12, 4));
            wrapper.setOpaque(false);
            JLabel label = new JLabel(labelText);
            label.setPreferredSize(new Dimension(145, 28));
            wrapper.add(label, BorderLayout.WEST);
            ShortcutField field = new ShortcutField(value);
            field.setHorizontalAlignment(SwingConstants.LEFT);
            wrapper.add(field, BorderLayout.CENTER);
            status.setPreferredSize(new Dimension(170, 28));
            wrapper.add(status, BorderLayout.EAST);
            field.onChanged = this::updateStatus;
            return field;
        }

        private void updateStatus() {
            setStatus(primaryStatus, conflictText(definition, primaryField.getStroke()));
            setStatus(alternateStatus, conflictText(definition, alternateField.getStroke()));
        }

        private void setStatus(JLabel label, String text) {
            label.setText(text);
            label.setForeground(text.startsWith("Currently")
                    ? ModernTheme.DANGER : ModernTheme.mutedForeground());
        }
    }

    private static class ShortcutField extends JTextField {
        private KeyStroke stroke;
        private Runnable onChanged;

        ShortcutField(KeyStroke stroke) {
            this.stroke = stroke;
            setEditable(false);
            setText(format(stroke));
            setToolTipText("Click here, then press the shortcut combination");
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    int code = event.getKeyCode();
                    if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT
                            || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) {
                        return;
                    }
                    event.consume();
                    if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) {
                        setStroke(null);
                        return;
                    }
                    int modifiers = event.getModifiersEx()
                            & (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
                                    | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK);
                    setStroke(KeyStroke.getKeyStroke(code, modifiers));
                }
            });
        }

        KeyStroke getStroke() {
            return stroke;
        }

        void setStroke(KeyStroke stroke) {
            this.stroke = stroke;
            setText(format(stroke));
            if (onChanged != null) {
                onChanged.run();
            }
        }
    }

    private static class ShortcutDefinition {
        final Action action;
        final String id;
        final String name;
        final KeyStroke defaultPrimary;
        final KeyStroke defaultAlternate;
        KeyStroke primary;
        KeyStroke alternate;

        ShortcutDefinition(Action action, String id, String name, KeyStroke defaultPrimary,
                KeyStroke defaultAlternate) {
            this.action = action;
            this.id = id;
            this.name = name;
            this.defaultPrimary = defaultPrimary;
            this.defaultAlternate = defaultAlternate;
        }
    }
}
