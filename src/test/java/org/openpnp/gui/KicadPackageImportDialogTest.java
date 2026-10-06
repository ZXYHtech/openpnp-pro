package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;

public class KicadPackageImportDialogTest {
    @Test
    public void convertsAllSelectedViewRowsForBatchOperations() {
        DefaultTableModel model = new DefaultTableModel(
                new Object[][] {{"B"}, {"A"}, {"C"}}, new Object[] {"name"});
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.getRowSorter().toggleSortOrder(0);
        table.addRowSelectionInterval(0, 1);

        assertArrayEquals(new int[] {1, 0},
                KicadPackageImportDialog.selectedModelRows(table));
    }
}
