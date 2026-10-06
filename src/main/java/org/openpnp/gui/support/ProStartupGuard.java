package org.openpnp.gui.support;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.JOptionPane;

/** Startup isolation, non-destructive instance warning and mandatory first backup. */
public final class ProStartupGuard {
    private static final String MARKER = ".pro-initial-backup-complete";

    private ProStartupGuard() {
    }

    public static boolean checkOtherOpenPnpInstances() {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }
        while (true) {
            List<String> instances = findOtherInstances();
            if (instances.isEmpty()) {
                return true;
            }
            Object[] options = {"已正常关闭，重新检测", "退出本次启动"};
            int result = JOptionPane.showOptionDialog(null,
                    "检测到其他 OpenPnP 正在运行：\n\n"
                            + String.join("\n", instances)
                            + "\n\n原版和 Pro 共用飞达、吸嘴及机器配置，不能同时运行。"
                            + "请在上面列出的窗口中正常退出；软件不会强制结束任何进程。",
                    "OpenPnP Pro · 运行冲突检查", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (result == 0) {
                continue;
            }
            return false;
        }
    }

    static List<String> findOtherInstances() {
        List<String> results = new ArrayList<>();
        Set<Long> selfProcessIds = collectSelfProcessIds(ProcessHandle.current());
        ProcessHandle.allProcesses().forEach(process -> {
            ProcessHandle.Info info = process.info();
            String command = info.command().orElse("");
            String line = info.commandLine().orElse(command);
            if (shouldReportProcess(process.pid(), selfProcessIds, line)) {
                results.add("PID " + process.pid() + " · " + abbreviate(line, 120));
            }
        });
        return results;
    }

    static Set<Long> collectSelfProcessIds(ProcessHandle current) {
        Set<Long> processIds = new HashSet<>();
        ProcessHandle process = current;
        while (process != null && processIds.add(process.pid())) {
            process = process.parent().orElse(null);
        }
        return processIds;
    }

    static boolean shouldReportProcess(long processId, Set<Long> selfProcessIds,
            String commandLine) {
        return !selfProcessIds.contains(processId) && isOpenPnpCommand(commandLine);
    }

    public static File prepareConfigurationDirectory() throws IOException {
        String override = System.getProperty("configDir");
        File home = new File(System.getProperty("user.home"));
        File directory = override == null ? new File(home, ".openpnp2")
                : new File(override);
        if (containsProtectableData(directory) && !new File(directory, MARKER).exists()) {
            File backup = mandatoryBackup(directory);
            if (!confirmMandatoryBackup(directory, backup)) {
                throw new IOException("首次安全备份未确认，OpenPnP Pro 已停止启动。");
            }
            directory.mkdirs();
            Files.write(new File(directory, MARKER).toPath(),
                    ("backup=" + backup.getAbsolutePath() + "\n"
                            + "created=" + LocalDateTime.now()).getBytes(StandardCharsets.UTF_8));
        }
        else {
            directory.mkdirs();
            if (!new File(directory, MARKER).exists()) {
                Files.write(new File(directory, MARKER).toPath(),
                        "new-configuration=true\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        return directory;
    }

    private static boolean confirmMandatoryBackup(File source, File backup) {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }
        String detail = "检测到共享配置目录已有数据，已按要求完成首次强制备份。";
        return JOptionPane.showConfirmDialog(null,
                detail + "\n\n来源：" + source.getAbsolutePath()
                        + "\n备份：" + backup.getAbsolutePath()
                        + "\n\n确认备份位置后才能继续启动。",
                "OpenPnP Pro · 首次安全备份", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static File mandatoryBackup(File source) throws IOException {
        File root = new File(System.getProperty("user.home"), "OpenPnP Pro Backups");
        root.mkdirs();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now());
        File output = new File(root, "OpenPnP-Pro-initial-" + stamp + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            StringBuilder manifest = new StringBuilder("OpenPnP Pro mandatory backup\n");
            addDirectory(zip, source, source, manifest);
            zip.putNextEntry(new ZipEntry("PRO_BACKUP_MANIFEST.txt"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output;
    }

    private static void addDirectory(ZipOutputStream zip, File root, File current,
            StringBuilder manifest) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File file : children) {
            String relative = root.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/');
            if (shouldExclude(relative + (file.isDirectory() ? "/" : ""), file)) {
                continue;
            }
            if (file.isDirectory()) {
                addDirectory(zip, root, file, manifest);
            }
            else {
                zip.putNextEntry(new ZipEntry("data/" + relative));
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
                manifest.append(relative).append('\t').append(file.length()).append('\n');
            }
        }
    }

    static boolean isOpenPnpCommand(String commandLine) {
        String searchable = commandLine == null ? "" : commandLine.toLowerCase(Locale.ROOT);
        return searchable.contains("openpnp-gui")
                || searchable.contains("org.openpnp.main")
                || searchable.contains("openpnp.exe")
                || searchable.contains("openpnp pro.exe");
    }
    private static boolean shouldExclude(String relative, File file) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".job.xml") || lower.equals(MARKER)
                || lower.startsWith("log/") || lower.startsWith("logs/")
                || lower.startsWith("backups/") || lower.contains("/backups/")
                || lower.startsWith("openpnp-pro-autosave/");
    }

    private static boolean containsProtectableData(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            String relative = file.getName();
            if (shouldExclude(relative + (file.isDirectory() ? "/" : ""), file)) {
                continue;
            }
            if (file.isFile() || containsProtectableData(file)) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length - 1) + "…";
    }
}