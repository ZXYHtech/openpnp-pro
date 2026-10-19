package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;

class AutoSelectTextTableEditingTest {
    @Test
    void enterFillsOnlySelectedRowsInTheEditedReorderedColumn() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(String.class, new Object[][] {{"a", "A"}, {"b", "B"}, {"c", "C"}});
            table.moveColumn(1, 0);
            select(table, 0, 2);
            edit(table, 0, 0, "shared description").postActionEvent();
            assertEquals("shared description", table.getModel().getValueAt(0, 1));
            assertEquals("B", table.getModel().getValueAt(1, 1));
            assertEquals("shared description", table.getModel().getValueAt(2, 1));
            assertEquals("a", table.getModel().getValueAt(0, 0));
            assertEquals(2, table.getSelectedRowCount());
            assertFalse(table.isEditing());
        });
    }

    @Test
    void relativeOperationsUseEveryOriginalRowEvenWhenSorterReordersAfterEachUpdate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(Integer.class, new Object[][] {{1}, {10}, {2}, {20}});
            table.setAutoCreateRowSorter(true);
            TableRowSorter<?> sorter = (TableRowSorter<?>) table.getRowSorter();
            sorter.setSortsOnUpdates(true);
            sorter.toggleSortOrder(0);
            select(table, 0, 1, 2);
            edit(table, 0, 0, "+= 1 0").postActionEvent();
            assertEquals(11, table.getModel().getValueAt(0, 0));
            assertEquals(20, table.getModel().getValueAt(1, 0));
            assertEquals(12, table.getModel().getValueAt(2, 0));
            assertEquals(20, table.getModel().getValueAt(3, 0));
            assertEquals(Arrays.asList(0, 2, 1), selectedModelRows(table));
        });
    }

    @Test
    void filteredRowsAreNotUpdatedAndReadOnlyRowsAreSkipped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][] {{1}, {2}, {3}, {4}}, new Object[] {"Value"}) {
                @Override
                public Class<?> getColumnClass(int column) {
                    return Integer.class;
                }

                @Override
                public boolean isCellEditable(int row, int column) {
                    return row != 2;
                }
            };
            AutoSelectTextTable table = new AutoSelectTextTable(model);
            table.setMultiRowEditEnabled(true);
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
            table.setRowSorter(sorter);
            sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    return entry.getIdentifier() != 1;
                }
            });
            table.selectAll();
            edit(table, 0, 0, "= 1 + 9").postActionEvent();
            assertEquals(10, model.getValueAt(0, 0));
            assertEquals(2, model.getValueAt(1, 0));
            assertEquals(3, model.getValueAt(2, 0));
            assertEquals(10, model.getValueAt(3, 0));
        });
    }

    @Test
    void invalidBatchDoesNotChangeAnyRowAndCanBeCorrectedOrCancelled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(Integer.class, new Object[][] {{1}, {Integer.MAX_VALUE}});
            select(table, 0, 1);
            JTextField input = edit(table, 0, 0, "+= 1");
            assertFalse(table.getCellEditor().stopCellEditing());
            assertEquals(1, table.getValueAt(0, 0));
            assertEquals(Integer.MAX_VALUE, table.getValueAt(1, 0));
            assertTrue(input.getToolTipText().contains("未修改任何"));
            input.setText("1 / 2");
            assertFalse(table.getCellEditor().stopCellEditing());
            table.getCellEditor().cancelCellEditing();
            assertFalse(table.isEditing());
            edit(table, 0, 0, "2 + 3").postActionEvent();
            assertEquals(5, table.getValueAt(0, 0));
            assertEquals(5, table.getValueAt(1, 0));
        });
    }

    @Test
    void lengthBatchUsesSeparateValuesWithNativeUnitsPerRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Configuration.initialize();
            Configuration.get().setSystemUnits(LengthUnit.Millimeters);
            LengthCellValue.setConfiguration(Configuration.get());
            AutoSelectTextTable table = table(LengthCellValue.class, new Object[][] {
                    {new LengthCellValue(new Length(1, LengthUnit.Inches), true)},
                    {new LengthCellValue(new Length(1, LengthUnit.Millimeters), true)}});
            select(table, 0, 1);
            edit(table, 0, 0, "+= 2.54 mm").postActionEvent();
            Length first = ((LengthCellValue) table.getValueAt(0, 0)).getLength();
            Length second = ((LengthCellValue) table.getValueAt(1, 0)).getLength();
            assertEquals(1.1, first.getValue(), 1e-12);
            assertEquals(LengthUnit.Inches, first.getUnits());
            assertEquals(3.54, second.getValue(), 1e-12);
            assertEquals(LengthUnit.Millimeters, second.getUnits());
        });
    }

    @Test
    void singleCellArithmeticWorksWhenBatchEditingIsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(Double.class, new Object[][] {{1.0}, {2.0}});
            table.setMultiRowEditEnabled(false);
            select(table, 0, 1);
            edit(table, 0, 0, "1 2 . 5 - 0. 5").postActionEvent();
            assertEquals(12.0, table.getValueAt(0, 0));
            assertEquals(2.0, table.getValueAt(1, 0));
        });
    }

    @Test
    void doubleClickOnASelectedRowKeepsTheMultiSelectionForEnter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(String.class, new Object[][] {{"A"}, {"B"}, {"C"}});
            table.setSize(300, 120);
            select(table, 0, 2);
            for (int click = 1; click <= 2; click++) {
                table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                        10, 10, click, false, MouseEvent.BUTTON1));
                table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, 10, 10, click, false, MouseEvent.BUTTON1));
            }
            assertTrue(table.isEditing());
            assertEquals(2, table.getSelectedRowCount());
            JTextField input = (JTextField) table.getEditorComponent();
            input.setText("shared");
            input.postActionEvent();
            assertEquals("shared", table.getValueAt(0, 0));
            assertEquals("B", table.getValueAt(1, 0));
            assertEquals("shared", table.getValueAt(2, 0));
            assertEquals(2, table.getSelectedRowCount());
        });
    }

    @Test
    void comboEditorStillFillsSelectedRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable table = table(String.class, new Object[][] {{"A"}, {"B"}, {"C"}});
            JComboBox<String> combo = new JComboBox<>(new String[] {"A", "B", "C"});
            table.setDefaultEditor(String.class, new DefaultCellEditor(combo));
            select(table, 0, 2);
            assertTrue(table.editCellAt(0, 0));
            combo.setSelectedItem("C");
            assertEquals("C", table.getValueAt(0, 0));
            assertEquals("B", table.getValueAt(1, 0));
            assertEquals("C", table.getValueAt(2, 0));
        });
    }

    @Test
    void committingDoesNotScrollToAnOffscreenLeadRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object[][] data = new Object[100][1];
            Arrays.stream(data).forEach(row -> row[0] = 1);
            AutoSelectTextTable table = table(Integer.class, data);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setSize(300, 160);
            scroll.doLayout();
            table.setSize(280, table.getRowHeight() * 100);
            select(table, 1, 80);
            Point before = new Point(0, 640);
            scroll.getViewport().setViewPosition(before);
            edit(table, 1, 0, "+= 1").postActionEvent();
            assertEquals(before, scroll.getViewport().getViewPosition());
            assertEquals(2, table.getSelectedRowCount());
        });
    }

    private static AutoSelectTextTable table(Class<?> type, Object[][] values) {
        DefaultTableModel model = new DefaultTableModel(values, new Object[values[0].length]) {
            @Override
            public Class<?> getColumnClass(int column) {
                return type;
            }
        };
        AutoSelectTextTable table = new AutoSelectTextTable(model);
        table.setMultiRowEditEnabled(true);
        return table;
    }

    static void select(AutoSelectTextTable table, int... rows) {
        table.getSelectionModel().setValueIsAdjusting(true);
        table.clearSelection();
        for (int row : rows) {
            table.addRowSelectionInterval(row, row);
        }
        table.getSelectionModel().setValueIsAdjusting(false);
        table.setColumnSelectionInterval(0, 0);
    }

    static JTextField edit(AutoSelectTextTable table, int row, int column, String value) {
        assertTrue(table.editCellAt(row, column, new ActionEvent(table, 0, "F2")));
        JTextField input = (JTextField) table.getEditorComponent();
        input.setText(value);
        return input;
    }

    private static java.util.List<Integer> selectedModelRows(AutoSelectTextTable table) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        for (int row : table.getSelectedRows()) {
            result.add(table.convertRowIndexToModel(row));
        }
        return result;
    }
}
