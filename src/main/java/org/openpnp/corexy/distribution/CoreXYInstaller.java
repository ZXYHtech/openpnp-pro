package org.openpnp.corexy.distribution;

import java.awt.BorderLayout;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Standalone installer intentionally has no OpenPnP API dependency. */
public final class CoreXYInstaller {
    private CoreXYInstaller() {
    }

    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("安装独立 CoreXY 工具");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 8, 12));
            panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
            JTextField application = new JTextField("C:\\Program Files\\OpenPnP");
            JTextField configuration = new JTextField(Paths.get(System.getProperty("user.home"), ".openpnp2").toString());
            JButton choose = new JButton("选择原版 OpenPnP 安装目录");
            JButton install = new JButton("安装脚本并创建带运行时的启动入口");
            panel.add(new JLabel("<html>插件包已包含运行时，不需要另行安装 Java。<br>关闭 OpenPnP 后选择安装目录与机器配置目录，安装后通过新入口启动。</html>"));
            panel.add(application);
            panel.add(choose);
            panel.add(new JLabel("OpenPnP 配置目录（保留已有参数和自定义脚本）"));
            panel.add(configuration);
            panel.add(install);
            choose.addActionListener(event -> {
                JFileChooser dialog = new JFileChooser(application.getText());
                dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (dialog.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    application.setText(dialog.getSelectedFile().toString());
                }
            });
            install.addActionListener(event -> {
                try {
                    Path bundle = Paths.get(CoreXYInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                    Path launcher = install(Paths.get(application.getText()), Paths.get(configuration.getText()), bundle);
                    JOptionPane.showMessageDialog(frame, "安装完成。请通过这个入口启动：\n" + launcher
                            + "\n然后打开 脚本 → TornadoSMT → A_CoreXY_Auto_Calibration。");
                }
                catch (Exception failure) {
                    JOptionPane.showMessageDialog(frame, failure.getMessage(), "安装未完成", JOptionPane.ERROR_MESSAGE);
                }
            });
            frame.add(panel, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    public static Path install(Path application, Path configuration, Path bundle) throws Exception {
        application = application.toAbsolutePath().normalize();
        configuration = configuration.toAbsolutePath().normalize();
        bundle = bundle.toAbsolutePath().normalize();
        Path agent = bundle.resolve("openpnp-corexy-agent.jar");
        Path java = bundle.resolve("runtime/bin/java.exe");
        if (!Files.isRegularFile(agent) || !Files.isRegularFile(java)) {
            throw new IllegalArgumentException("插件包缺少 Agent 或内置运行时，请完整解压后重试。");
        }
        Path gui = findApplicationJar(application);
        if (gui == null) {
            throw new IllegalArgumentException("所选目录中没有找到 OpenPnP 主程序 JAR。");
        }
        try (InstallTransaction transaction = new InstallTransaction()) {
        Path scripts = configuration.resolve("scripts");
        // Back up configuration before adding files. Formal machine parameters are never changed.
        Path backup = configuration.resolve("corexy-install-backups").resolve(Long.toString(System.currentTimeMillis()));
        Files.createDirectories(backup);
        if (Files.isRegularFile(configuration.resolve("machine.xml"))) {
            Files.copy(configuration.resolve("machine.xml"), backup.resolve("machine.xml"));
        }
        try (InputStream stream = resource("/corexy/scripts-manifest.txt")) {
            for (String name : new String(readAll(stream), StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (name.trim().isEmpty()) {
                    continue;
                }
                Path target = scripts.resolve(name).normalize();
                if (!target.startsWith(scripts)) {
                    throw new IllegalArgumentException("Invalid script path.");
                }
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    try (InputStream source = resource("/corexy/scripts/" + name)) {
                        transaction.beforeWrite(target);
                        Files.copy(source, target);
                    }
                }
            }
        }
        Path runtime = scripts.resolve("TornadoSMT/OpenPnP-CoreXY");
        for (String name : new String[] {"corexy.properties", "calibration-policy.properties"}) {
            Path target = runtime.resolve("config").resolve(name);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                try (InputStream source = resource("/corexy/config/" + name)) {
                    transaction.beforeWrite(target);
                    Files.copy(source, target);
                }
            }
        }
        Path installedAgent = runtime.resolve("lib/openpnp-corexy-agent.jar");
        Files.createDirectories(installedAgent.getParent());
        if (Files.exists(installedAgent)) {
            Files.copy(installedAgent, backup.resolve("openpnp-corexy-agent.jar"));
        }
        transaction.beforeWrite(installedAgent);
        Files.copy(agent, installedAgent, StandardCopyOption.REPLACE_EXISTING);
        // Launch with premain instrumentation: no Attach API prompt and no external JDK.
        String classpath = installedAgent + ";" + gui + ";" + application.resolve("lib") + "\\*;"
                + gui.getParent().resolve("lib") + "\\*";
        String script = "@echo off\r\nchcp 65001 >nul\r\nsetlocal DisableDelayedExpansion\r\ncd /d \"" + batchPath(application) + "\"\r\n\""
                + batchPath(java) + "\" -Xmx1536m --add-opens=java.base/java.lang=ALL-UNNAMED"
                + " --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED"
                + " \"-DconfigDir=" + batchPath(configuration) + "\" \"-javaagent:" + batchPath(installedAgent)
                + "\" -cp \"" + classpath.replace("%", "%%") + "\" org.openpnp.Main\r\n"
                + "if errorlevel 1 pause\r\n";
        Path launcher = bundle.resolve("启动 OpenPnP 与 CoreXY.bat");
        transaction.beforeWrite(launcher);
        Files.write(launcher, script.getBytes(StandardCharsets.UTF_8));
        transaction.committed = true;
        return launcher;
        }
    }

    private static final class InstallTransaction implements AutoCloseable {
        private final java.util.Map<Path, byte[]> previous = new java.util.LinkedHashMap<>();
        boolean committed;
        void beforeWrite(Path file) throws Exception {
            if (Files.exists(file) && !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("安装目标不是普通文件：" + file);
            }
            previous.put(file, Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
        }
        @Override
        public void close() throws Exception {
            if (committed) {
                return;
            }
            Exception failure = null;
            for (java.util.Map.Entry<Path, byte[]> file : previous.entrySet()) {
                try {
                    if (file.getValue() == null) {
                        Files.deleteIfExists(file.getKey());
                    }
                    else {
                        Files.write(file.getKey(), file.getValue());
                    }
                }
                catch (Exception rejected) {
                    if (failure == null) {
                        failure = rejected;
                    }
                    else {
                        failure.addSuppressed(rejected);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public static Path findApplicationJar(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        List<Path> jars = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(directory, 3)) {
            files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")).forEach(jars::add);
        }
        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getEntry("org/openpnp/Main.class") != null) {
                    return path;
                }
            }
            catch (java.io.IOException notAJar) {
                // Ignore damaged unrelated dependency files; only the real GUI JAR qualifies.
            }
        }
        return null;
    }

    private static String batchPath(Path path) {
        String text = path.toString();
        if (text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid launcher path.");
        }
        return text.replace("%", "%%");
    }

    private static InputStream resource(String name) {
        InputStream stream = CoreXYInstaller.class.getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException("Missing installer resource: " + name);
        }
        return stream;
    }
    private static byte[] readAll(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
