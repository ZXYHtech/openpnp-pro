package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.spi.PropertySheetHolder;

class MachineSetupTreeTest {
    @Test void existingSearchBoxFiltersTheTreePreservesAncestorsAndRestoresAllOptions() throws Exception {
        Configuration.initialize();
        Configuration.get().setMachine(new org.openpnp.machine.reference.ReferenceMachine());
        SwingUtilities.invokeAndWait(() -> {
            try {
                MachineSetupPanel panel = new MachineSetupPanel();
                PropertySheetHolder camera = holder("顶部相机");
                PropertySheetHolder root = holder("机器", holder("相机", camera), holder("飞达"));
                MachineSetupPanel.PropertySheetHolderTreeNode node = panel.new PropertySheetHolderTreeNode(root, null);
                DefaultTreeModel model = new DefaultTreeModel(node);
                field(panel, "treeModel").set(panel, model);
                JTree tree = (JTree) field(panel, "tree").get(panel);
                tree.setModel(model);
                JTextField search = (JTextField) field(panel, "searchTextField").get(panel);
                search.setText("顶部");
                assertEquals(1, node.getChildCount(), "Retain camera's ancestor and hide unrelated feeders");
                assertEquals(1, node.getChildAt(0).getChildCount());
                search.postActionEvent();
                assertEquals("顶部相机", tree.getSelectionPath().getLastPathComponent().toString());
                search.setText("");
                assertEquals(2, node.getChildCount(), "Clearing search restores every original child");
                tree.setSelectionPath(((MachineSetupPanel.PropertySheetHolderTreeNode) node.getChildAt(0).getChildAt(0)).getTreePath());
                ((JButton) field(panel, "favorite").get(panel)).doClick();
                ((JComboBox<?>) field(panel, "navigationScope").get(panel)).setSelectedIndex(1);
                assertEquals(1, node.getChildCount(), "Favorites keep their original ancestor context");
                ((JComboBox<?>) field(panel, "navigationScope").get(panel)).setSelectedIndex(0);
                assertEquals(2, node.getChildCount());
            }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private static PropertySheetHolder holder(String title, PropertySheetHolder... children) {
        PropertySheetHolder holder = mock(PropertySheetHolder.class);
        when(holder.getPropertySheetHolderTitle()).thenReturn(title);
        when(holder.getChildPropertySheetHolders()).thenReturn(children);
        return holder;
    }
    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
