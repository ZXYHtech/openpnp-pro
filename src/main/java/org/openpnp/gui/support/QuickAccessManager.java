/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 */
package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * User configurable quick-access actions for the main command bar.
 *
 * Right-click any Action-backed menu item or button to pin or unpin it. The original Action is
 * reused, so enabled state, icons and machine safety guards remain identical to the source button.
 */
public class QuickAccessManager {
    private static final String PREF_ORDER = "pinned-actions";
    private static final int MAX_DIRECT_ACTIONS = 8;

    private final Window owner;
    private final JPanel target;
    private final Preferences preferences =
            Preferences.userNodeForPackage(QuickAccessManager.class).node("quick-access");
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final List<String> pinnedIds = new ArrayList<>();
    private AWTEventListener mouseListener;

    public QuickAccessManager(Window owner, JPanel target) {
        this.owner = owner;
        this.target = target;
        String stored = preferences.get(PREF_ORDER, "");
        if (!stored.trim().isEmpty()) {
            pinnedIds.addAll(Arrays.asList(stored.split(",")));
        }
    }

    public void install(Container menuBar, Container content) {
        registerTree(menuBar);
        registerTree(content);
        rebuild();
        installPopupListener();
    }

    public void registerTree(Component component) {
        if (component instanceof AbstractButton) {
            register(((AbstractButton) component).getAction());
        }
        if (component instanceof JMenu) {
            for (Component child : ((JMenu) component).getMenuComponents()) {
                registerTree(child);
            }
        }
        else if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                registerTree(child);
            }
        }
    }

    public void register(Action action) {
        if (action == null) {
            return;
        }
        String id = Objects.toString(action.getValue(ShortcutManager.ACTION_ID_KEY), null);
        if (id == null || id.trim().isEmpty()) {
            String name = Objects.toString(action.getValue(Action.NAME), action.getClass().getSimpleName());
            id = Integer.toHexString((action.getClass().getName() + "|" + name).hashCode());
            action.putValue(ShortcutManager.ACTION_ID_KEY, id);
        }
        actions.put(id, action);
    }

    private void installPopupListener() {
        mouseListener = event -> {
            if (!(event instanceof MouseEvent)) {
                return;
            }
            MouseEvent mouseEvent = (MouseEvent) event;
            if (!mouseEvent.isPopupTrigger()) {
                return;
            }
            AbstractButton button = findButton(mouseEvent.getComponent());
            if (button == null || button.getAction() == null
                    || !SwingUtilities.isDescendingFrom(button, owner)) {
                return;
            }
            register(button.getAction());
            if (SwingUtilities.isDescendingFrom(button, target)) {
                remove(button.getAction());
                mouseEvent.consume();
                return;
            }
            showPopup(button, mouseEvent);
            mouseEvent.consume();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    private void showPopup(AbstractButton button, MouseEvent event) {
        Action action = button.getAction();
        String id = Objects.toString(action.getValue(ShortcutManager.ACTION_ID_KEY), "");
        boolean pinned = pinnedIds.contains(id);
        JPopupMenu popup = new JPopupMenu();
        JMenuItem item = new JMenuItem(pinned ? "Remove from Quick Access" : "Pin to Quick Access");
        item.addActionListener(e -> {
            if (pinned) {
                pinnedIds.remove(id);
            }
            else {
                pinnedIds.remove(id);
                pinnedIds.add(id);
            }
            persist();
            rebuild();
        });
        popup.add(item);
        popup.show(button, event.getX(), event.getY());
    }

    private AbstractButton findButton(Component component) {
        Component current = component;
        while (current != null && !(current instanceof Window)) {
            if (current instanceof AbstractButton) {
                return (AbstractButton) current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void persist() {
        preferences.put(PREF_ORDER, pinnedIds.stream().collect(Collectors.joining(",")));
    }

    private void remove(Action action) {
        String id = Objects.toString(action.getValue(ShortcutManager.ACTION_ID_KEY), "");
        if (pinnedIds.remove(id)) {
            persist();
            rebuild();
        }
    }
    private void rebuild() {
        target.removeAll();
        target.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        List<Action> pinnedActions = pinnedIds.stream().map(actions::get).filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (pinnedActions.isEmpty()) {
            JLabel hint = new JLabel("Right-click a command to pin it here");
            hint.setForeground(ModernTheme.mutedForeground());
            target.add(hint);
        }
        else {
            int directCount = Math.min(MAX_DIRECT_ACTIONS, pinnedActions.size());
            for (int i = 0; i < directCount; i++) {
                target.add(createButton(pinnedActions.get(i)));
            }
            if (pinnedActions.size() > MAX_DIRECT_ACTIONS) {
                JButton more = new JButton("More");
                more.setFocusable(false);
                more.addActionListener(e -> {
                    JPopupMenu menu = new JPopupMenu();
                    for (int i = MAX_DIRECT_ACTIONS; i < pinnedActions.size(); i++) {
                        menu.add(new JMenuItem(pinnedActions.get(i)));
                    }
                    menu.show(more, 0, more.getHeight());
                });
                target.add(more);
            }
        }
        target.revalidate();
        target.repaint();
    }

    private JButton createButton(Action action) {
        JButton button = new JButton(action);
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(5, 8, 5, 8));
        button.putClientProperty("JButton.buttonType", "roundRect");
        if (button.getIcon() != null) {
            button.setText(null);
        }
        String name = Objects.toString(action.getValue(Action.NAME), "");
        button.setToolTipText(name + " | Right-click or middle-click to remove");
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    remove(action);
                    event.consume();
                }
            }
        });
        return button;
    }
}
