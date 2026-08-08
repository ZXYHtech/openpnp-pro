package org.openpnp.gui.support;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileFilter;

/**
 * Common OpenPnP Pro file dialog with an editable path field. Users can paste a full path instead
 * of navigating the native chooser every time, while the browse button and recent paths remain
 * available. Multiple paths are separated with semicolons or line breaks.
 */
public final class PathSelectionDialog {
    private static final int RECENT_LIMIT = 5;
    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(PathSelectionDialog.class);

    public enum Mode {
        FILES(JFileChooser.FILES_ONLY),
        DIRECTORIES(JFileChooser.DIRECTORIES_ONLY),
        FILES_AND_DIRECTORIES(JFileChooser.FILES_AND_DIRECTORIES);

        private final int chooserMode;

        Mode(int chooserMode) {
            this.chooserMode = chooserMode;
        }
    }

    private PathSelectionDialog() {
    }

    public static File[] chooseOpen(Component parent, String preferenceKey, String title,
            Mode mode, boolean multiple, File initialLocation, FileFilter filter) {
        SelectionUi ui = new SelectionUi(parent, preferenceKey, title, mode, multiple,
                initialLocation, null, filter, false);
        while (true) {
            if (!ui.confirm()) {
                return null;
            }
            List<File> files = parsePaths(ui.getText());
            String validation = validateOpen(files, mode, multiple, filter);
            if (validation == null) {
                remember(preferenceKey, ui.getText(), files);
                return files.toArray(new File[0]);
            }
            JOptionPane.showMessageDialog(parent, validation, "地址不可用",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    public static File chooseSave(Component parent, String preferenceKey, String title,
            File suggestedFile, File initialDirectory, FileFilter filter) {
        SelectionUi ui = new SelectionUi(parent, preferenceKey, title, Mode.FILES, false,
                initialDirectory, suggestedFile, filter, true);
        while (true) {
            if (!ui.confirm()) {
                return null;
            }
            List<File> files = parsePaths(ui.getText());
            if (files.size() != 1) {
                JOptionPane.showMessageDialog(parent, "请输入一个保存地址。", "地址不可用",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            File file = files.get(0).getAbsoluteFile();
            File parentDirectory = file.getParentFile();
            if (parentDirectory != null && !parentDirectory.isDirectory()) {
                JOptionPane.showMessageDialog(parent,
                        "保存目录不存在：\n" + parentDirectory,
                        "地址不可用", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            remember(preferenceKey, file.getAbsolutePath(), files);
            return file;
        }
    }

    static List<File> parsePaths(String text) {
        List<File> files = new ArrayList<>();
        if (text == null) {
            return files;
        }
        String[] parts = text.replace('\r', '\n').split("[;\\n]+");
        for (String part : parts) {
            String value = stripQuotes(part.trim());
            if (!value.isEmpty()) {
                files.add(new File(value).getAbsoluteFile());
            }
        }
        return files;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String validateOpen(List<File> files, Mode mode, boolean multiple,
            FileFilter filter) {
        if (files.isEmpty()) {
            return "请输入文件或目录地址。";
        }
        if (!multiple && files.size() != 1) {
            return "此功能只能选择一个地址。";
        }
        for (File file : files) {
            if (!file.exists()) {
                return "地址不存在：\n" + file;
            }
            if (mode == Mode.FILES && !file.isFile()) {
                return "请选择文件：\n" + file;
            }
            if (mode == Mode.DIRECTORIES && !file.isDirectory()) {
                return "请选择目录：\n" + file;
            }
            if (file.isFile() && filter != null && !filter.accept(file)) {
                return "文件类型不受支持：\n" + file;
            }
        }
        return null;
    }

    private static String key(String preferenceKey, String suffix) {
        String safe = preferenceKey == null ? "default"
                : preferenceKey.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        return "path." + safe + "." + suffix;
    }

    private static String preference(String preferenceKey, String suffix) {
        try {
            return PREFERENCES.get(key(preferenceKey, suffix), "");
        }
        catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void remember(String preferenceKey, String text, List<File> files) {
        if (text == null || text.length() > Preferences.MAX_VALUE_LENGTH || files.isEmpty()) {
            return;
        }
        try {
            PREFERENCES.put(key(preferenceKey, "last"), text.trim());
            File first = files.get(0).getAbsoluteFile();
            File directory = first.isDirectory() ? first : first.getParentFile();
            if (directory != null) {
                PREFERENCES.put(key(preferenceKey, "directory"), directory.getAbsolutePath());
            }
            List<String> recent = new ArrayList<>();
            recent.add(text.trim());
            for (int index = 0; index < RECENT_LIMIT && recent.size() < RECENT_LIMIT; index++) {
                String old = preference(preferenceKey, "recent" + index);
                if (!old.isEmpty() && !recent.contains(old)) {
                    recent.add(old);
                }
            }
            for (int index = 0; index < recent.size(); index++) {
                PREFERENCES.put(key(preferenceKey, "recent" + index), recent.get(index));
            }
        }
        catch (RuntimeException ignored) {
            // Read-only preferences must not prevent file import or export.
        }
    }

    private static final class SelectionUi {
        private final Component parent;
        private final String title;
        private final Mode mode;
        private final boolean multiple;
        private final FileFilter filter;
        private final boolean save;
        private final File initialLocation;
        private final JComboBox<String> address = new JComboBox<>();
        private final JPanel content = new JPanel(new BorderLayout(0, 7));

        SelectionUi(Component parent, String preferenceKey, String title, Mode mode,
                boolean multiple, File initialLocation, File suggestedFile, FileFilter filter,
                boolean save) {
            this.parent = parent;
            this.title = title;
            this.mode = mode;
            this.multiple = multiple;
            this.filter = filter;
            this.save = save;
            this.initialLocation = initialLocation;

            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (int index = 0; index < RECENT_LIMIT; index++) {
                String recent = preference(preferenceKey, "recent" + index);
                if (!recent.isEmpty()) {
                    model.addElement(recent);
                }
            }
            address.setModel(model);
            address.setEditable(true);
            address.setPreferredSize(new Dimension(610, address.getPreferredSize().height));
            address.setToolTipText("可直接粘贴完整地址；多个地址用分号或换行分隔");

            String initial = initialText(preferenceKey, suggestedFile, initialLocation, save);
            address.getEditor().setItem(initial);
            JButton browse = new JButton("浏览...");
            browse.addActionListener(event -> browse());
            JPanel row = new JPanel(new BorderLayout(7, 0));
            row.add(address, BorderLayout.CENTER);
            row.add(browse, BorderLayout.EAST);
            content.add(new JLabel(multiple
                    ? "地址（可直接粘贴；多个地址用分号或换行分隔）："
                    : "地址（可直接粘贴完整路径）："), BorderLayout.NORTH);
            content.add(row, BorderLayout.CENTER);
        }

        boolean confirm() {
            return JOptionPane.showConfirmDialog(parent, content, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                            == JOptionPane.OK_OPTION;
        }

        String getText() {
            Object value = address.getEditor().getItem();
            return value == null ? "" : value.toString().trim();
        }

        private void browse() {
            JFileChooser chooser = new JFileChooser(resolveDirectory(getText(), initialLocation));
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(mode.chooserMode);
            chooser.setMultiSelectionEnabled(multiple && !save);
            if (filter != null) {
                chooser.setFileFilter(filter);
            }
            List<File> entered = parsePaths(getText());
            if (!entered.isEmpty()) {
                chooser.setSelectedFile(entered.get(0));
            }
            int result = save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File[] selected = chooser.getSelectedFiles();
            if (selected.length == 0 && chooser.getSelectedFile() != null) {
                selected = new File[] { chooser.getSelectedFile() };
            }
            address.getEditor().setItem(join(selected));
        }
    }

    private static String initialText(String preferenceKey, File suggestedFile,
            File initialLocation, boolean save) {
        String last = preference(preferenceKey, "last");
        if (!save && !last.isEmpty()) {
            return last;
        }
        File directory = new File(preference(preferenceKey, "directory"));
        if (!directory.isDirectory()) {
            directory = resolveDirectory("", initialLocation);
        }
        if (suggestedFile != null) {
            if (suggestedFile.isAbsolute()) {
                return suggestedFile.getAbsolutePath();
            }
            return new File(directory, suggestedFile.getPath()).getAbsolutePath();
        }
        return initialLocation == null ? "" : initialLocation.getAbsolutePath();
    }

    static File resolveDirectory(String text, File fallback) {
        List<File> files = parsePaths(text);
        File candidate = files.isEmpty() ? fallback : files.get(0);
        if (candidate != null && candidate.isFile()) {
            candidate = candidate.getParentFile();
        }
        while (candidate != null && !candidate.isDirectory()) {
            candidate = candidate.getParentFile();
        }
        if (candidate != null) {
            return candidate;
        }
        return new File(System.getProperty("user.dir", "."));
    }

    private static String join(File[] files) {
        StringBuilder joined = new StringBuilder();
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("; ");
            }
            joined.append(file.getAbsolutePath());
        }
        return joined.toString();
    }
}
