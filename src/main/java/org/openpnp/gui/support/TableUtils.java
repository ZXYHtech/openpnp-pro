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
        for (int iCol = 0; iCol < table.getColumnCount(); iCol++) {
            table.getColumnModel().getColumn(iCol).addPropertyChangeListener(
                    new PropertyChangeListener() {
                        @Override
                        public void propertyChange(PropertyChangeEvent event) {
                            if ("preferredWidth".equals(event.getPropertyName())
                                    && !state.restoring) {
                                TableColumn column = (TableColumn) event.getSource();
                                int width = Math.max(MIN_COLUMN_WIDTH,
                                        (int) event.getNewValue());
                                prefs.putInt(state.key(column.getModelIndex()), width);
                            }
                        }
                    });
        }
        table.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                state.restoreIfModeChanged();
            }
        });
        SwingUtilities.invokeLater(state::restoreIfModeChanged);
    }

    private static class ColumnWidthState {
        private final JTable table;
        private final Preferences prefs;
        private final String prefKey;
        private String mode;
        private boolean restoring;

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