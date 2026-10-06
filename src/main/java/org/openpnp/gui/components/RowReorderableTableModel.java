package org.openpnp.gui.components;

import java.util.List;

/**
 * A table model whose underlying order can be changed and persisted.
 */
public interface RowReorderableTableModel {
    boolean reorderRows(List<Integer> modelRows, int targetModelRow);
}
