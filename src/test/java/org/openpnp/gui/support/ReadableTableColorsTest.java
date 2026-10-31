package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Component;
import java.util.List;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.openpnp.gui.tablemodel.PlacementsHolderLocationsTableModel;
import org.openpnp.model.Configuration;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.PlacementsHolderLocation.PlacementsTransformStatus;

class ReadableTableColorsTest {
    @TempDir Path directory;

    @Test void correctedPanelCoordinatesRemainLegibleAcrossStatusSelectionAndDisabledStates() throws Exception {
        Configuration.initialize(directory.toFile());
        SwingUtilities.invokeAndWait(() -> {
            var model = new PlacementsHolderLocationsTableModel(Configuration.get());
            var first = new PanelLocation(new Panel());
            var second = new PanelLocation(new Panel());
            model.setPlacementsHolderLocations(List.of(first, second));
            JTable table = new JTable(model);
            table.setBackground(new Color(60, 63, 65));
            table.setForeground(new Color(180, 185, 195));
            table.setSelectionForeground(new Color(180, 185, 195));
            table.setSelectionBackground(new Color(73, 109, 169));
            var renderer = new MonospacedFontWithAffineStatusTableCellRenderer();
            for (var status : PlacementsTransformStatus.values()) {
                first.setPlacementsTransformStatus(status);
                second.setPlacementsTransformStatus(status);
                for (boolean enabled : new boolean[] { true, false }) {
                    table.setEnabled(enabled);
                    for (boolean selected : new boolean[] { true, false }) {
                        for (int row = 0; row < 2; row++) {
                            for (int column = 5; column <= 8; column++) {
                                Component cell = renderer.getTableCellRendererComponent(table, "200.447", selected, false, row, column);
                                assertTrue(ReadableTableColors.contrast(cell.getBackground(), cell.getForeground()) >= 4.5,
                                        status + " selected=" + selected + " enabled=" + enabled);
                            }
                        }
                    }
                }
            }
        });
    }

    @Test void ordinaryPanelEditorHandlesMissingAlternateColorAndDarkSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object previous = UIManager.get("Table.alternateRowColor");
            try {
                UIManager.put("Table.alternateRowColor", null);
                JTable table = new JTable(2, 2);
                table.setBackground(new Color(157, 188, 255));
                table.setForeground(new Color(180, 185, 195));
                table.setSelectionBackground(new Color(55, 80, 120));
                table.setSelectionForeground(new Color(85, 100, 120));
                var renderer = new MonospacedFontTableCellRenderer();
                for (boolean selected : new boolean[] { true, false }) {
                    for (int row = 0; row < 2; row++) {
                        Component cell = renderer.getTableCellRendererComponent(table, "138.814", selected, false, row, 0);
                        assertTrue(ReadableTableColors.contrast(cell.getBackground(), cell.getForeground()) >= 4.5);
                    }
                }
            }
            finally { UIManager.put("Table.alternateRowColor", previous); }
        });
    }
}
