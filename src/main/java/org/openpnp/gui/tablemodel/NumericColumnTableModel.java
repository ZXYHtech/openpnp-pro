package org.openpnp.gui.tablemodel;

import javax.swing.table.TableModel;

/** Identifies legacy numeric columns which are formatted and stored as strings by the table. */
public interface NumericColumnTableModel extends TableModel {
    boolean isNumericColumn(int column);

    /** Full precision in display units, before the renderer rounds it. */
    default Object getNumericValueAt(int row, int column) {
        return getValueAt(row, column);
    }
}
