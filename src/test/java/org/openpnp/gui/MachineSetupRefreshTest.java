package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;
import org.junit.jupiter.api.*;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.spi.PropertySheetHolder;

class MachineSetupRefreshTest {

    private Fixture fixture;

    @FunctionalInterface
    private interface CheckedAction {

        void run() throws Exception;
    }

    private void edt(CheckedAction action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @AfterEach
    void closeWindow() throws Exception {
        edt(() -> {
            if (fixture != null) {
                fixture.window.dispose();
            }
        });
    }

    @Test
    void ordinaryUpdatesKeepExpansionSelectionScrollAndUnappliedEdits() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        edt(() -> {
            Holder group = new Holder("相机");
            for (int i = 0; i < 55; i++) {
                group.children.add(new Holder("相机 " + i));
            }
            fixture = new Fixture(new Holder("机器", group, new Holder("飞达")));
            fixture.tree.expandPath(fixture.node("相机").getTreePath());
            fixture.tree.setSelectionPath(fixture.node("相机 39").getTreePath());
            Holder selected = (Holder) fixture.node("相机 39").getPropertySheetHolder();
            selected.editor.setText("尚未应用的编辑");
            fixture.viewport.setViewPosition(new Point(0, 280));
            fixture.position = fixture.viewport.getViewPosition();
            assertEquals(1, selected.sheetReads);
            for (int i = 0; i < 25; i++) {
                selected.scalarChange(i);
            }
        });
        edt(() -> {
            assertTrue(fixture.tree.isExpanded(fixture.node("相机").getTreePath()));
            assertEquals(fixture.node("相机 39").getTreePath(), fixture.tree.getSelectionPath());
            assertEquals(fixture.position, fixture.viewport.getViewPosition());
            Holder selected = (Holder) fixture.node("相机 39").getPropertySheetHolder();
            assertEquals("尚未应用的编辑", selected.editor.getText());
            assertEquals(1, selected.sheetReads, "Refresh must not rebuild the active settings page");
        });
    }

    @Test
    void addingAndRemovingNodesKeepsOtherBranchesAndDetachesOldListeners() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Holder[] deleted = { null };
        edt(() -> {
            Holder cameras = new Holder("相机", new Holder("顶部相机"));
            Holder feeders = new Holder("飞达", new Holder("飞达 1"), new Holder("飞达 2"));
            fixture = new Fixture(new Holder("机器", cameras, feeders, new Holder("轴", new Holder("X"))));
            fixture.tree.expandPath(fixture.node("相机").getTreePath());
            fixture.tree.expandPath(fixture.node("飞达").getTreePath());
            fixture.tree.setSelectionPath(fixture.node("顶部相机").getTreePath());
            deleted[0] = feeders.children.remove(0);
            assertEquals(1, deleted[0].listeners());
            feeders.children.add(new Holder("飞达 3"));
            feeders.structuralChange();
        });
        edt(() -> {
            assertTrue(fixture.tree.isExpanded(fixture.node("相机").getTreePath()));
            assertTrue(fixture.tree.isExpanded(fixture.node("飞达").getTreePath()));
            assertFalse(fixture.tree.isExpanded(fixture.node("轴").getTreePath()));
            assertEquals("顶部相机", fixture.tree.getSelectionPath().getLastPathComponent().toString());
            assertNotNull(fixture.node("飞达 3"));
            assertEquals(0, deleted[0].listeners());
            deleted[0].scalarChange(1);
        });
        edt(() -> assertEquals("顶部相机", fixture.tree.getSelectionPath().getLastPathComponent().toString()));
    }

    @Test
    void syntheticCategoryRecreationAndFilteredUpdatesPreserveNavigation() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Holder[] oldCategory = { null };
        FreshCategories root = new FreshCategories();
        edt(() -> {
            fixture = new Fixture(root);
            oldCategory[0] = root.category;
            fixture.tree.expandPath(fixture.node("相机").getTreePath());
            fixture.tree.setSelectionPath(fixture.node("底部相机").getTreePath());
            root.structuralChange();
        });
        edt(() -> {
            assertTrue(fixture.tree.isExpanded(fixture.node("相机").getTreePath()));
            assertEquals(0, oldCategory[0].listeners());
            assertEquals(1, root.camera.listeners(), "Reconciliation must reuse the existing camera node");
            fixture.search.setText("底部");
            root.camera.scalarChange(1);
        });
        edt(() -> {
            assertEquals("底部相机", fixture.tree.getSelectionPath().getLastPathComponent().toString());
            fixture.search.setText("");
            assertTrue(fixture.tree.isExpanded(fixture.node("相机").getTreePath()));
            assertFalse(fixture.tree.isExpanded(fixture.node("飞达").getTreePath()));
            assertEquals(1, root.camera.sheetReads);
        });
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Fixture {

        final MachineSetupPanel panel;

        final JFrame window = new JFrame("配置树刷新测试");

        final JTree tree;

        final JViewport viewport;

        final JTextField search;

        Point position;

        Fixture(Holder root) throws Exception {
            Configuration.initialize();
            Configuration.get().setMachine(new org.openpnp.machine.reference.ReferenceMachine());
            panel = new MachineSetupPanel();
            tree = (JTree) field(panel, "tree");
            DefaultTreeModel model = new DefaultTreeModel(panel.new PropertySheetHolderTreeNode(root, null));
            Field modelField = MachineSetupPanel.class.getDeclaredField("treeModel");
            modelField.setAccessible(true);
            modelField.set(panel, model);
            tree.setModel(model);
            search = (JTextField) field(panel, "searchTextField");
            viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, tree);
            window.setContentPane(panel);
            window.setSize(1000, 650);
            window.setVisible(true);
        }

        MachineSetupPanel.PropertySheetHolderTreeNode node(String name) {
            return find((MachineSetupPanel.PropertySheetHolderTreeNode) tree.getModel().getRoot(), name);
        }

        private MachineSetupPanel.PropertySheetHolderTreeNode find(MachineSetupPanel.PropertySheetHolderTreeNode node, String name) {
            if (node.toString().equals(name)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                MachineSetupPanel.PropertySheetHolderTreeNode found = find((MachineSetupPanel.PropertySheetHolderTreeNode) node.getChildAt(i), name);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    public static class Holder extends AbstractModelObject implements PropertySheetHolder {

        final String title;

        final List<Holder> children = new ArrayList<>();

        JTextField editor;

        int sheetReads;

        Holder(String title, Holder... children) {
            this.title = title;
            java.util.Collections.addAll(this.children, children);
        }

        void scalarChange(int value) {
            firePropertyChange("value", -1, value);
        }

        void structuralChange() {
            fireIndexedPropertyChange("children", 0, null, new Object());
        }

        int listeners() {
            return propertyChangeSupport.getPropertyChangeListeners().length;
        }

        @Override
        public String getPropertySheetHolderTitle() {
            return title;
        }

        @Override
        public PropertySheetHolder[] getChildPropertySheetHolders() {
            return children.toArray(new PropertySheetHolder[0]);
        }

        @Override
        public javax.swing.Action[] getPropertySheetHolderActions() {
            return null;
        }

        @Override
        public Icon getPropertySheetHolderIcon() {
            return null;
        }

        @Override
        public PropertySheet[] getPropertySheets() {
            sheetReads++;
            editor = new JTextField();
            JPanel panel = new JPanel();
            panel.add(editor);
            return new PropertySheet[] { new PropertySheet() {

                @Override
                public String getPropertySheetTitle() {
                    return "配置";
                }

                @Override
                public JPanel getPropertySheetPanel() {
                    return panel;
                }
            } };
        }
    }

    public static final class FreshCategories extends Holder {

        final Holder camera = new Holder("底部相机");

        Holder category;

        FreshCategories() {
            super("机器");
        }

        @Override
        public PropertySheetHolder[] getChildPropertySheetHolders() {
            category = new Holder("相机", camera);
            return new PropertySheetHolder[] { category, new Holder("飞达", new Holder("飞达 1")) };
        }
    }
}
