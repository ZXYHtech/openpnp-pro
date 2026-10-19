/*
 * Copyright (C) 2022 Jason von Nieda <jason@vonnieda.org>, Tony Luken <tonyluken62+openpnp@gmail.com>
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package org.openpnp.gui.support;

import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.prefs.Preferences;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;

import org.openpnp.gui.tablemodel.ColumnAlignable;

public class TableUtils {
    public static final int MIN_COLUMN_WIDTH = 10;
    private static final String COLUMN_WIDTH_STATE = "openpnp.columnWidthState";

    public static void setColumnAlignment(ColumnAlignable tableModel, JTable table) {
        int[] alignments = tableModel.getColumnAlignments();
        for (int iCol = 0; iCol < table.getColumnCount(); iCol++) {
            table.getColumnModel().getColumn(iCol).setHeaderRenderer(new CustomAlignmentRenderer<>(
                    table.getTableHeader().getDefaultRenderer(), alignments[iCol]));
            table.getColumnModel().getColumn(iCol).setCellRenderer(new CustomAlignmentRenderer<>(
                    table.getCellRenderer(0, iCol), alignments[iCol]));
        }
    }

    /**
     * Saves exact column widths and restores an independent set for maximized and windowed modes.
     * Legacy single-layout preferences are used as the initial value for both modes.
     */
    public static void installColumnWidthSavers(JTable table, Preferences prefs, String prefKey) {
        if (table.getClientProperty(COLUMN_WIDTH_STATE) != null) {
            return;
        }
        ColumnWidthState state = new ColumnWidthState(table, prefs, prefKey);
        table.putClientProperty(COLUMN_WIDTH_STATE, state);
        // Restore before layout or a user fit can emit preferredWidth changes. A deferred first
        // restore used to overwrite a fit made while opening/rebuilding a page.
        state.restoreIfModeChanged();
        watchColumns(state);
        table.addPropertyChangeListener("columnModel", event -> watchColumns(state));
        table.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                state.restoreIfModeChanged();
            }
        });
        table.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && table.isShowing()) {
                state.mode = null;
                state.restoreIfModeChanged();
            }
        });
        SwingUtilities.invokeLater(state::restoreIfModeChanged);
    }

    private static void watchColumns(ColumnWidthState state) {
        for (int index = 0; index < state.table.getColumnCount(); index++) {
            watchColumn(state, state.table.getColumnModel().getColumn(index));
        }
        state.table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
            @Override public void columnAdded(javax.swing.event.TableColumnModelEvent event) {
                watchColumn(state, state.table.getColumnModel().getColumn(event.getToIndex()));
                state.mode = null;
                state.restoreIfModeChanged();
            }
            @Override public void columnRemoved(javax.swing.event.TableColumnModelEvent event) { }
            @Override public void columnMoved(javax.swing.event.TableColumnModelEvent event) { }
            @Override public void columnMarginChanged(javax.swing.event.ChangeEvent event) { }
            @Override public void columnSelectionChanged(javax.swing.event.ListSelectionEvent event) { }
        });
    }

    private static void watchColumn(ColumnWidthState state, TableColumn column) {
        if (!state.observed.add(column)) { return; }
        column.addPropertyChangeListener(event -> {
            JTable table = state.table;
            if (("preferredWidth".equals(event.getPropertyName()) || "width".equals(event.getPropertyName()))
                    && !state.restoring && getWindowMode(table).equals(state.mode)
                    && table.getTableHeader() != null && table.getTableHeader().getResizingColumn() == column) {
                state.prefs.putInt(state.key(column.getModelIndex()), Math.max(MIN_COLUMN_WIDTH, (int) event.getNewValue()));
            }
        });
    }

    /** Persist the resulting widths of an explicit auto-fit, using this table's existing key. */
    public static void saveColumnWidths(JTable table) {
        Object value = table.getClientProperty(COLUMN_WIDTH_STATE);
        if (!(value instanceof ColumnWidthState)) { return; }
        ColumnWidthState state = (ColumnWidthState) value;
        state.mode = getWindowMode(table);
        for (int index = 0; index < table.getColumnCount(); index++) {
            TableColumn column = table.getColumnModel().getColumn(index);
            state.prefs.putInt(state.key(column.getModelIndex()), column.getPreferredWidth());
        }
    }

    private static class ColumnWidthState {
        private final JTable table;
        private final Preferences prefs;
        private final String prefKey;
        private String mode;
        private boolean restoring;
        private final java.util.Set<TableColumn> observed = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

        ColumnWidthState(JTable table, Preferences prefs, String prefKey) {
            this.table = table;
            this.prefs = prefs;
            this.prefKey = prefKey;
        }

        String key(int modelIndex) {
            return prefKey + "." + mode + "." + modelIndex;
        }

        void restoreIfModeChanged() {
            String currentMode = getWindowMode(table);
            if (currentMode.equals(mode)) {
                return;
            }
            mode = currentMode;
            restoring = true;
            try {
                for (int viewColumn = 0; viewColumn < table.getColumnCount(); viewColumn++) {
                    TableColumn column = table.getColumnModel().getColumn(viewColumn);
                    int modelIndex = column.getModelIndex();
                    int legacyWidth = prefs.getInt(prefKey + modelIndex,
                            column.getPreferredWidth());
                    int width = Math.max(MIN_COLUMN_WIDTH,
                            prefs.getInt(key(modelIndex), legacyWidth));
                    column.setPreferredWidth(width);
                    column.setWidth(width);
                    prefs.putInt(key(modelIndex), width);
                }
            }
            finally {
                restoring = false;
            }
        }
    }

    private static String getWindowMode(JTable table) {
        Window window = SwingUtilities.getWindowAncestor(table);
        if (window instanceof Frame
                && ((((Frame) window).getExtendedState() & Frame.MAXIMIZED_BOTH)
                        == Frame.MAXIMIZED_BOTH)) {
            return "maximized";
        }
        return "windowed";
    }
}
