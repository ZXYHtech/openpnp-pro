package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;

class TableSelectionSupportTest {
    @Test void cancelsUncommittedEditAndBothSelectionsWithoutWritingModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new DefaultTableModel(new Object[][]{{"original"}}, new Object[]{"value"}));
            TableSelectionSupport.install(table);
            table.setCellSelectionEnabled(true);
            table.changeSelection(0, 0, false, false);
            assertTrue(table.editCellAt(0, 0));
            ((JTextField)table.getEditorComponent()).setText("accidental edit");
            ((JMenuItem)table.getComponentPopupMenu().getComponent(0)).doClick();
            assertFalse(table.isEditing());
            assertEquals("original", table.getValueAt(0, 0));
            assertEquals(0, table.getSelectedRowCount());
            assertEquals(0, table.getSelectedColumnCount());
            JPopupMenu replacement = new JPopupMenu();
            replacement.add(new JMenuItem("existing"));
            table.setComponentPopupMenu(replacement);
            TableSelectionSupport.install(table);
            assertEquals(3, replacement.getComponentCount());
            assertEquals("existing", ((JMenuItem)replacement.getComponent(0)).getText());
        });
    }
}
