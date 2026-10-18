package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.gui.tablemodel.FootprintTableModel;
import org.openpnp.gui.tablemodel.PackagesTableModel;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

class LibraryCellEditingTest {
    @Test
    void partsPanelFillsHeightDescriptionSpeedAndPackage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            initialize();
            Package firstPackage = new Package("PKG-A");
            Package secondPackage = new Package("PKG-B");
            Configuration.get().addPackage(firstPackage);
            Configuration.get().addPackage(secondPackage);
            Part first = part("A", 1, firstPackage);
            Part second = part("B", 2, firstPackage);
            PartsPanel panel = new PartsPanel(Configuration.get(), null);
            AutoSelectTextTable table = table(panel);
            selectAll(table);
            enter(table, 2, "+ = 0. 2");
            assertEquals(1.2, first.getHeight().getValue(), 1e-12);
            assertEquals(2.2, second.getHeight().getValue(), 1e-12);
            assertNotSame(first.getHeight(), second.getHeight());
            enter(table, 1, "保留 文字 空格");
            assertEquals("保留 文字 空格", first.getName());
            assertEquals(first.getName(), second.getName());
            enter(table, 5, " 1 0 0 - 2 0 %");
            assertEquals(0.8, first.getSpeed(), 1e-12);
            enter(table, 5, "-= 5 %");
            assertEquals(0.75, first.getSpeed(), 1e-12);
            assertEquals(0.75, second.getSpeed(), 1e-12);
            assertTrue(table.editCellAt(0, 4));
            ((JComboBox<?>) table.getEditorComponent()).setSelectedItem(secondPackage);
            assertEquals(secondPackage, first.getPackage());
            assertEquals(secondPackage, second.getPackage());
            assertEquals(2, table.getSelectedRowCount());
        });
    }

    @Test
    void packagesPanelFillsDescriptionCategoryAndReviewState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            initialize();
            Package first = new Package("A");
            Package second = new Package("B");
            Configuration.get().addPackage(first);
            Configuration.get().addPackage(second);
            PackagesPanel panel = new PackagesPanel(Configuration.get(), null);
            AutoSelectTextTable table = table(panel);
            selectAll(table);
            enter(table, 1, "相同 描述");
            enter(table, 2, "8 mm");
            assertEquals("相同 描述", first.getDescription());
            assertEquals(first.getDescription(), second.getDescription());
            assertEquals("8 mm", first.getTapeSpecification());
            assertEquals(first.getTapeSpecification(), second.getTapeSpecification());
            assertTrue(table.editCellAt(0, 7));
            JComboBox<?> category = (JComboBox<?>) table.getEditorComponent();
            category.setSelectedIndex(1);
            assertEquals(first.getCategoryId(), second.getCategoryId());
            assertTrue(table.editCellAt(0, 8));
            ((JComboBox<?>) table.getEditorComponent()).setSelectedItem(Package.ReviewState.VERIFIED);
            assertEquals(Package.ReviewState.VERIFIED, first.getReviewState());
            assertEquals(Package.ReviewState.VERIFIED, second.getReviewState());
            assertFalse(table.getModel().isCellEditable(0, 0));
            assertEquals(2, table.getSelectedRowCount());
        });
    }

    @Test
    void packageEditsEmitOnlyRowUpdatesAndKeepSortAndFilterTargetsStable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            initialize();
            Package pkg = new Package("A");
            Configuration.get().addPackage(pkg);
            PackagesTableModel model = new PackagesTableModel(Configuration.get());
            java.util.List<javax.swing.event.TableModelEvent> events = new java.util.ArrayList<>();
            model.addTableModelListener(events::add);
            pkg.setDescription("new description");
            assertEquals(1, events.size());
            assertEquals(0, events.get(0).getFirstRow());
            assertEquals(0, events.get(0).getLastRow());
            Configuration.get().removePackage(pkg);
            events.clear();
            pkg.setDescription("removed");
            assertEquals(0, events.size());
        });
    }

    @Test
    void numericStringColumnsInFootprintAcceptArithmeticWithoutChangingPadNames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            initialize();
            Package pkg = new Package("A");
            Footprint footprint = pkg.getFootprint();
            footprint.setUnits(LengthUnit.Millimeters);
            Footprint.Pad pad = new Footprint.Pad();
            pad.setName("01");
            pad.setRotation(90);
            footprint.addPad(pad);
            AutoSelectTextTable table = new AutoSelectTextTable(new FootprintTableModel(footprint, pkg));
            selectAll(table);
            enter(table, 6, "9 0 + 0. 1");
            assertEquals(90.1, pad.getRotation(), 1e-12);
            enter(table, 7, "50 - 10");
            assertEquals(40, pad.getRoundness(), 1e-12);
            pad.setRotation(1.23456);
            enter(table, 6, "+= 0.00001");
            assertEquals(1.23457, pad.getRotation(), 1e-12);
            enter(table, 0, "0 1");
            assertEquals("0 1", pad.getName());
        });
    }

    private static void initialize() {
        Configuration.initialize();
        Configuration.get().setMachine(new ReferenceMachine());
        Configuration.get().setSystemUnits(LengthUnit.Millimeters);
        LengthCellValue.setConfiguration(Configuration.get());
    }

    private static Part part(String id, double height, Package pkg) {
        Part part = new Part(id);
        part.setHeight(new Length(height, LengthUnit.Millimeters));
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        return part;
    }

    private static AutoSelectTextTable table(Object panel) {
        try {
            Field field = panel.getClass().getDeclaredField("table");
            field.setAccessible(true);
            return (AutoSelectTextTable) field.get(panel);
        }
        catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void selectAll(AutoSelectTextTable table) {
        table.getSelectionModel().setValueIsAdjusting(true);
        table.selectAll();
        table.getSelectionModel().setValueIsAdjusting(false);
    }

    private static void enter(AutoSelectTextTable table, int modelColumn, String text) {
        assertTrue(table.editCellAt(0, table.convertColumnIndexToView(modelColumn)));
        JTextField field = (JTextField) table.getEditorComponent();
        field.setText(text);
        field.postActionEvent();
        assertFalse(table.isEditing());
    }
}
