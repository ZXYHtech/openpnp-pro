package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import java.util.prefs.Preferences;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.test.MemoryPreferencesFactory;

class TableWidthPersistenceTest {
    private AutoSelectTextTable table() {
        AutoSelectTextTable table = new AutoSelectTextTable(new DefaultTableModel(
                new Object[][] {{"R1", "Very long material description used to allocate the page width", "0603"}},
                new Object[] {"Id", "Part", "Package"}));
        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setExtentSize(new java.awt.Dimension(780, 200));
        return table;
    }

    @Test void fittingBeforeDeferredRestoreSurvivesPageRecreationAndDeferredLayout() throws Exception {
        Preferences prefs = new MemoryPreferencesFactory().userRoot();
        int[] fitted = new int[3];
        AutoSelectTextTable[] reopened = new AutoSelectTextTable[1];
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable first = table();
            TableUtils.installColumnWidthSavers(first, prefs, "placements");
            first.autoFitColumnsToViewport();
            for (int column = 0; column < 3; column++) {
                fitted[column] = first.getColumnModel().getColumn(column).getPreferredWidth();
                assertEquals(fitted[column], prefs.getInt("placements.windowed." + column, -1));
            }
            reopened[0] = table();
            TableUtils.installColumnWidthSavers(reopened[0], prefs, "placements");
        });
        SwingUtilities.invokeAndWait(() -> {
            for (int column = 0; column < 3; column++) {
                assertEquals(fitted[column], reopened[0].getColumnModel().getColumn(column).getPreferredWidth());
            }
        });
    }

    @Test void persistedColumnsUseModelIdentityAfterColumnReordering() throws Exception {
        Preferences prefs = new MemoryPreferencesFactory().userRoot();
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable first = table();
            TableUtils.installColumnWidthSavers(first, prefs, "feeders");
            first.moveColumn(0, 2);
            first.getColumnModel().getColumn(2).setPreferredWidth(211);
            TableUtils.saveColumnWidths(first);
            AutoSelectTextTable reopened = table();
            TableUtils.installColumnWidthSavers(reopened, prefs, "feeders");
            assertEquals(211, reopened.getColumnModel().getColumn(0).getPreferredWidth());
            AutoSelectTextTable independent = table();
            TableUtils.installColumnWidthSavers(independent, prefs, "parts");
            assertNotEquals(211, independent.getColumnModel().getColumn(0).getPreferredWidth());
        });
    }

    @Test void legacyWidthsMigrateWithoutLosingIndependentMaximizedWidths() throws Exception {
        Preferences prefs = new MemoryPreferencesFactory().userRoot();
        prefs.putInt("table0", 160);
        prefs.putInt("table.maximized.0", 280);
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable first = table();
            TableUtils.installColumnWidthSavers(first, prefs, "table");
            assertEquals(160, first.getColumnModel().getColumn(0).getWidth());
            first.autoFitColumnsToViewport();
            assertEquals(280, prefs.getInt("table.maximized.0", -1));
        });
    }

    @Test void startupLayoutCannotOverwriteAnExplicitFitButHeaderDraggingIsSaved() throws Exception {
        Preferences prefs = new MemoryPreferencesFactory().userRoot();
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable first = table();
            TableUtils.installColumnWidthSavers(first, prefs, "table");
            first.autoFitColumnsToViewport();
            int fitted = prefs.getInt("table.windowed.0", -1);
            first.getColumnModel().getColumn(0).setPreferredWidth(75);
            assertEquals(fitted, prefs.getInt("table.windowed.0", -1), "Programmatic startup layout must not replace saved widths");
            first.getTableHeader().setResizingColumn(first.getColumnModel().getColumn(0));
            first.getColumnModel().getColumn(0).setPreferredWidth(225);
            first.getTableHeader().setResizingColumn(null);
            assertEquals(225, prefs.getInt("table.windowed.0", -1));
        });
    }

    @Test void rebuildingModelColumnsRestoresAndContinuesSavingUserWidths() throws Exception {
        Preferences prefs = new MemoryPreferencesFactory().userRoot();
        SwingUtilities.invokeAndWait(() -> {
            AutoSelectTextTable first = table();
            TableUtils.installColumnWidthSavers(first, prefs, "table");
            first.autoFitColumnsToViewport();
            int fitted = prefs.getInt("table.windowed.0", -1);
            first.setModel(new DefaultTableModel(new Object[] {"Id", "Part", "Package"}, 0));
            assertEquals(fitted, first.getColumnModel().getColumn(0).getPreferredWidth());
            first.getTableHeader().setResizingColumn(first.getColumnModel().getColumn(0));
            first.getColumnModel().getColumn(0).setPreferredWidth(247);
            first.getTableHeader().setResizingColumn(null);
            assertEquals(247, prefs.getInt("table.windowed.0", -1));
        });
    }
}
