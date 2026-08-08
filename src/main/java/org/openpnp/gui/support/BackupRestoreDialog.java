package org.openpnp.gui.support;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.openpnp.gui.JobPanel;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Panel;

/** Selectable backup and guarded restore center for all OpenPnP Pro data. */
@SuppressWarnings("serial")
public class BackupRestoreDialog extends JDialog {
    private static final String MANIFEST = "OPENPNP_PRO_BACKUP.properties";
    private final Configuration configuration;
    private final BackupTableModel model = new BackupTableModel();
    private final JTable table = new JTable(model);
    private File loadedArchive;

    public BackupRestoreDialog(Frame owner, Configuration configuration) {
        super(owner, "OpenPnP Pro · 备份与恢复", true);
        this.configuration = configuration;
        buildUi();
        scanLiveData();
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        JLabel help = new JLabel("<html><b>备份中心</b>：勾选类别或具体文件。"
                + "任务文件本体不会备份，只保存最近任务路径及关联单板、拼版数据。"
                + "恢复前会自动创建保护快照。</html>");
        add(help, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setMaxWidth(55);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(230);
        table.getColumnModel().getColumn(3).setPreferredWidth(330);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(75);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton selectAll = new JButton("全选");
        selectAll.addActionListener(event -> setAllSelected(true));
        JButton clear = new JButton("清除");
        clear.addActionListener(event -> setAllSelected(false));
        JButton sameCategory = new JButton("选择同类");
        sameCategory.addActionListener(event -> selectCurrentCategory());
        JButton scan = new JButton("扫描当前数据");
        scan.addActionListener(event -> scanLiveData());
        JButton backup = new JButton("备份选中项...");
        backup.addActionListener(event -> chooseBackup());
        JButton load = new JButton("加载备份清单...");
        load.addActionListener(event -> chooseArchive());
        JButton restore = new JButton("恢复选中项");
        restore.addActionListener(event -> restoreSelected());
        actions.add(selectAll);
        actions.add(clear);
        actions.add(sameCategory);
        actions.add(scan);
        actions.add(backup);
        actions.add(load);
        actions.add(restore);
        add(actions, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1050, 610));
        pack();
        setLocationRelativeTo(getOwner());
    }

    private void scanLiveData() {
        loadedArchive = null;
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File root = configuration.getConfigurationDirectory();
        addKnown(items, seen, new File(root, "machine.xml"), "机器与设备",
                "驱动器、运动轴、相机、吸嘴、执行器、飞达和机器参数");
        addKnown(items, seen, new File(root, "parts.xml"), "物料库",
                "物料型号、别名、封装关联和视觉参数引用");
        addKnown(items, seen, new File(root, "packages.xml"), "封装库",
                "封装尺寸、焊盘、吸嘴兼容性和视觉参数");
        addKnown(items, seen, new File(root, "boards.xml"), "单板索引",
                "已加载单板文件路径和单板数据表索引");
        addKnown(items, seen, new File(root, "panels.xml"), "拼版索引",
                "已加载拼版文件路径和拼版数据表索引");
        addKnown(items, seen, new File(root, "vision-settings.xml"), "视觉配置",
                "视觉算法、管线和识别参数");
        addKnown(items, seen, new File(root, "script-state.xml"), "脚本状态",
                "脚本持久化状态和运行参数");
        addTree(items, seen, new File(root, "scripts"), "脚本文件",
                "自动化脚本及其辅助文件");
        for (Board board : configuration.getBoards()) {
            addKnown(items, seen, board.getFile(), "单板数据",
                    "单板尺寸、器件坐标、面别和贴装属性");
        }
        for (Panel panel : configuration.getPanels()) {
            addKnown(items, seen, panel.getFile(), "拼版数据",
                    "拼版结构、子板位置、原点和 Mark 点设置");
        }
        try {
            byte[] recentTasks = exportRecentTasks();
            items.add(Item.synthetic("最近任务记录", "@recent-tasks", "任务记录",
                    "仅保存最近任务文件路径，不复制用户任务文件",
                    recentTasks.length, propertyCount(recentTasks)));
            byte[] preferences = exportOpenPnpPreferences();
            items.add(Item.synthetic("界面与操作偏好", "@openpnp-preferences", "参数与偏好",
                    "快捷键、表格宽度、窗口布局、悬浮监控、主题及其他操作偏好",
                    preferences.length, preferenceEntryCount()));
        }
        catch (Exception exception) {
            // A platform may temporarily deny preference access; file backups remain available.
        }
        model.setItems(items);
    }

    private void addTree(List<Item> items, Set<String> seen, File directory,
            String category, String description) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                addTree(items, seen, file, category, description);
            }
            else if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".job.xml")) {
                addKnown(items, seen, file, category, description);
            }
        }
    }

    private void addKnown(List<Item> items, Set<String> seen, File file,
            String category, String description) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            String canonical = file.getCanonicalPath();
            if (seen.add(canonical)) {
                items.add(Item.live(file, category, description));
            }
        }
        catch (IOException ignored) {
        }
    }

    private void chooseBackup() {
        File selected = PathSelectionDialog.chooseSave(this, "pro-backup-save",
                "保存 OpenPnP Pro 备份",
                new File("OpenPnP-Pro-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now()) + ".zip"),
                configuration.getConfigurationDirectory(),
                new FileNameExtensionFilter("OpenPnP Pro 备份 (*.zip)", "zip"));
        if (selected == null) {
            return;
        }
        try {
            File output = ensureZip(selected);
            createBackup(output, model.selectedItems());
            JOptionPane.showMessageDialog(this, "备份完成：\n" + output);
        }
        catch (Exception exception) {
            MessageBoxes.errorBox(this, "备份失败", exception);
        }
    }

    private void createBackup(File output, List<Item> selected) throws Exception {
        Properties manifest = new Properties();
        manifest.setProperty("format", "OpenPnP-Pro-Backup-1");
        manifest.setProperty("created", LocalDateTime.now().toString());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            int index = 0;
            for (Item item : selected) {
                String entryName = "files/" + index + "/" + item.name;
                byte[] bytes;
                if ("@recent-tasks".equals(item.source)) {
                    bytes = exportRecentTasks();
                }
                else if ("@openpnp-preferences".equals(item.source)) {
                    bytes = exportOpenPnpPreferences();
                }
                else {
                    bytes = Files.readAllBytes(item.file.toPath());
                }
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(bytes);
                zip.closeEntry();
                String prefix = "item." + index + ".";
                manifest.setProperty(prefix + "entry", entryName);
                manifest.setProperty(prefix + "source", item.source);
                manifest.setProperty(prefix + "category", item.category);
                manifest.setProperty(prefix + "description", item.description);
                manifest.setProperty(prefix + "size", Long.toString(bytes.length));
                manifest.setProperty(prefix + "count", Integer.toString(item.count));
                index++;
            }
            manifest.setProperty("count", Integer.toString(index));
            zip.putNextEntry(new ZipEntry(MANIFEST));
            manifest.store(zip, "OpenPnP Pro backup manifest");
            zip.closeEntry();
        }
    }

    private byte[] exportRecentTasks() throws IOException {
        Properties properties = new Properties();
        Preferences prefs = Preferences.userNodeForPackage(JobPanel.class);
        for (int index = 0; index < 10; index++) {
            String value = prefs.get("JobPanel.recentFiles_" + index, null);
            if (value != null) {
                properties.setProperty(Integer.toString(index), value);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "Recent jobs: paths only, task files are not included");
        return output.toByteArray();
    }

    private byte[] exportOpenPnpPreferences() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Preferences.userRoot().node("/org/openpnp").exportSubtree(output);
        return output.toByteArray();
    }

    private int preferenceEntryCount() throws Exception {
        return countPreferenceEntries(Preferences.userRoot().node("/org/openpnp"));
    }

    private int countPreferenceEntries(Preferences node) throws Exception {
        int count = node.keys().length;
        for (String child : node.childrenNames()) {
            count += countPreferenceEntries(node.node(child));
        }
        return count;
    }

    private static int propertyCount(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties.size();
    }
    private void chooseArchive() {
        File[] selected = PathSelectionDialog.chooseOpen(this, "pro-backup-restore",
                "加载 OpenPnP Pro 备份清单", PathSelectionDialog.Mode.FILES, false,
                configuration.getConfigurationDirectory(),
                new FileNameExtensionFilter("OpenPnP Pro 备份 (*.zip)", "zip"));
        if (selected == null) {
            return;
        }
        try {
            loadedArchive = selected[0];
            model.setItems(readArchiveItems(loadedArchive));
        }
        catch (Exception exception) {
            loadedArchive = null;
            MessageBoxes.errorBox(this, "无法读取备份", exception);
        }
    }

    private List<Item> readArchiveItems(File archive) throws Exception {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) {
                throw new IOException("不是有效的 OpenPnP Pro 备份文件。");
            }
            Properties manifest = new Properties();
            try (InputStream input = zip.getInputStream(manifestEntry)) {
                manifest.load(input);
            }
            List<Item> items = new ArrayList<>();
            int count = Integer.parseInt(manifest.getProperty("count", "0"));
            for (int index = 0; index < count; index++) {
                String prefix = "item." + index + ".";
                items.add(Item.archive(manifest.getProperty(prefix + "entry"),
                        manifest.getProperty(prefix + "source"),
                        manifest.getProperty(prefix + "category"),
                        manifest.getProperty(prefix + "description"),
                        Long.parseLong(manifest.getProperty(prefix + "size", "0")),
                        Integer.parseInt(manifest.getProperty(prefix + "count", "0"))));
            }
            return items;
        }
    }

    private void restoreSelected() {
        if (loadedArchive == null) {
            JOptionPane.showMessageDialog(this, "请先加载备份清单。",
                    "恢复", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Item> selected = model.selectedItems();
        if (selected.isEmpty()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "将恢复 " + selected.size() + " 项数据。恢复前会自动备份当前对应文件。"
                        + "\n恢复完成后必须重新启动软件。是否继续？",
                "确认安全恢复", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            File safetyDir = new File(configuration.getConfigurationDirectory(), "pro-backups");
            safetyDir.mkdirs();
            List<Item> current = new ArrayList<>();
            for (Item item : selected) {
                if (!item.source.startsWith("@")) {
                    File destination = new File(item.source);
                    if (destination.isFile()) {
                        current.add(Item.live(destination, item.category, "恢复前保护副本"));
                    }
                }
            }
            createBackup(new File(safetyDir, "before-restore-"
                    + System.currentTimeMillis() + ".zip"), current);
            try (ZipFile zip = new ZipFile(loadedArchive)) {
                for (Item item : selected) {
                    ZipEntry entry = zip.getEntry(item.archiveEntry);
                    if (entry == null) {
                        throw new IOException("备份内缺少：" + item.archiveEntry);
                    }
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) {
                        bytes = readAll(input);
                    }
                    if ("@recent-tasks".equals(item.source)) {
                        restoreRecentTasks(bytes);
                    }
                    else if ("@openpnp-preferences".equals(item.source)) {
                        Preferences.importPreferences(new ByteArrayInputStream(bytes));
                    }
                    else {
                        File destination = new File(item.source);
                        File parent = destination.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        File temporary = new File(destination.getPath() + ".restore.tmp");
                        Files.write(temporary.toPath(), bytes);
                        try {
                            Files.move(temporary.toPath(), destination.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE);
                        }
                        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                            Files.move(temporary.toPath(), destination.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            JOptionPane.showMessageDialog(this, "恢复完成，请正常退出并重新启动 OpenPnP Pro。");
        }
        catch (Exception exception) {
            MessageBoxes.errorBox(this, "恢复失败", exception);
        }
    }

    private void restoreRecentTasks(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        Preferences prefs = Preferences.userNodeForPackage(JobPanel.class);
        for (int index = 0; index < 10; index++) {
            String key = "JobPanel.recentFiles_" + index;
            String value = properties.getProperty(Integer.toString(index));
            if (value == null) {
                prefs.remove(key);
            }
            else {
                prefs.put(key, value);
            }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void selectCurrentCategory() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "请先点选一个类别中的任意一行。");
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        String category = model.items.get(modelRow).category;
        for (Item item : model.items) {
            item.selected = category.equals(item.category);
        }
        model.fireTableDataChanged();
    }
    private void setAllSelected(boolean selected) {
        for (Item item : model.items) {
            item.selected = selected;
        }
        model.fireTableDataChanged();
    }

    private static File ensureZip(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? file : new File(file.getPath() + ".zip");
    }

    private static int estimateItems(File file) {
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            return 1;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int count = 0;
            for (int index = 0; index < text.length() - 2; index++) {
                if (text.charAt(index) == '<' && Character.isLetter(text.charAt(index + 1))) {
                    count++;
                }
            }
            return count;
        }
        catch (Exception ignored) {
            return 0;
        }
    }

    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        return String.format("%.1f MB", size / 1024.0 / 1024.0);
    }

    private static class Item {
        boolean selected = true;
        final File file;
        final String name;
        final String source;
        final String category;
        final String description;
        final long size;
        final int count;
        final String archiveEntry;
        final boolean recentTasks;

        private Item(File file, String name, String source, String category,
                String description, long size, int count, String archiveEntry,
                boolean recentTasks) {
            this.file = file;
            this.name = name;
            this.source = source;
            this.category = category;
            this.description = description;
            this.size = size;
            this.count = count;
            this.archiveEntry = archiveEntry;
            this.recentTasks = recentTasks;
        }

        static Item live(File file, String category, String description) {
            return new Item(file, file.getName(), file.getAbsolutePath(), category,
                    description, file.length(), estimateItems(file), null, false);
        }

        static Item synthetic(String name, String source, String category,
                String description, long size, int count) {
            return new Item(null, name, source, category, description, size, count,
                    null, "@recent-tasks".equals(source));
        }

        static Item archive(String entry, String source, String category,
                String description, long size, int count) {
            String name = "@recent-tasks".equals(source) ? "最近任务记录"
                    : "@openpnp-preferences".equals(source) ? "界面与操作偏好"
                    : new File(source).getName();
            return new Item(null, name, source, category, description, size, count,
                    entry, "@recent-tasks".equals(source));
        }
    }

    private static class BackupTableModel extends AbstractTableModel {
        private final String[] columns = {"选择", "类别", "文件", "功能与包含数据",
                "大小", "数据项"};
        private List<Item> items = new ArrayList<>();

        void setItems(List<Item> items) {
            this.items = items;
            fireTableDataChanged();
        }

        List<Item> selectedItems() {
            List<Item> selected = new ArrayList<>();
            for (Item item : items) {
                if (item.selected) {
                    selected.add(item);
                }
            }
            return selected;
        }

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Object getValueAt(int row, int column) {
            Item item = items.get(row);
            switch (column) {
                case 0: return item.selected;
                case 1: return item.category;
                case 2: return item.name;
                case 3: return item.description;
                case 4: return formatSize(item.size);
                default: return Integer.toString(item.count);
            }
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                items.get(row).selected = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }
    }
}
