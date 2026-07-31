package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.RowReorderSupport;

class AutoSelectTextTableRowReorderTest {
    @Test
    void rightButtonDragInvokesPersistentReorderHandler() throws Exception {
        AtomicReference<List<String>> order =
                new AtomicReference<>(new ArrayList<>(Arrays.asList("A", "B", "C")));

        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = new AutoSelectTextTable(
                    new DefaultTableModel(new Object[][] {{"A"}, {"B"}, {"C"}},
                            new Object[] {"ID"}));
            table.setSize(300, table.getRowHeight() * 3);
            table.setRowSelectionInterval(0, 0);
            table.setRowReorderHandler((rows, target) -> {
                List<String> reordered = RowReorderSupport.reorder(order.get(), rows, target);
                if (reordered == null) {
                    return false;
                }
                order.set(reordered);
                return true;
            });

            long now = System.currentTimeMillis();
            table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED, now,
                    InputEvent.BUTTON3_DOWN_MASK, 10, table.getRowHeight() / 2, 1, true,
                    MouseEvent.BUTTON3));
            table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_DRAGGED, now + 1,
                    InputEvent.BUTTON3_DOWN_MASK, 10, table.getRowHeight() * 3 - 1, 0, false,
                    MouseEvent.NOBUTTON));
            table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, now + 2, 0,
                    10, table.getRowHeight() * 3 - 1, 1, true, MouseEvent.BUTTON3));
        });

        assertEquals(Arrays.asList("B", "C", "A"), order.get());
    }
    @Test
    void truncatedCellShowsFullValueButFittedCellDoesNot() throws Exception {
        String value = "A very long component or package description";
        AtomicReference<String> narrowTooltip = new AtomicReference<>();
        AtomicReference<String> wideTooltip = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = new AutoSelectTextTable(
                    new DefaultTableModel(new Object[][] {{value}}, new Object[] {"Description"}));
            table.setSize(600, table.getRowHeight());
            table.getColumnModel().getColumn(0).setMinWidth(20);
            table.getColumnModel().getColumn(0).setMaxWidth(1000);
            table.getColumnModel().getColumn(0).setWidth(60);
            MouseEvent hover = new MouseEvent(table, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, 10, table.getRowHeight() / 2, 0, false);
            narrowTooltip.set(table.getToolTipText(hover));

            table.getColumnModel().getColumn(0).setWidth(600);
            wideTooltip.set(table.getToolTipText(hover));
        });

        assertTrue(narrowTooltip.get().contains(value));
        assertNull(wideTooltip.get());
    }
}
