package org.openpnp.corexy.distribution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreXYDistributionTest {
    @TempDir
    Path temporary;

    @Test
    void installPreservesFormalConfigAndScriptsAndProvidesPremainLauncher() throws Exception {
        Path application = temporary.resolve("OpenPnP app"), configuration = temporary.resolve("配置 100%"), bundle = temporary.resolve("插件工具");
        Files.createDirectories(application);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(application.resolve("gui.jar")))) {
            jar.putNextEntry(new JarEntry("org/openpnp/Main.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        Files.createDirectories(bundle.resolve("runtime/bin"));
        Files.write(bundle.resolve("runtime/bin/java.exe"), new byte[] {1});
        Files.write(bundle.resolve("openpnp-corexy-agent.jar"), new byte[] {2});
        Path custom = configuration.resolve("scripts/TornadoSMT/A_CoreXY_Repeatability_Tuner.py");
        Files.createDirectories(custom.getParent());
        Files.write(custom, "custom script".getBytes(StandardCharsets.UTF_8));
        Path formal = configuration.resolve("scripts/TornadoSMT/OpenPnP-CoreXY/config/corexy.properties");
        Files.createDirectories(formal.getParent());
        Files.write(formal, "enabled=true\ncustom=yes".getBytes(StandardCharsets.UTF_8));
        Files.write(configuration.resolve("machine.xml"), "original machine".getBytes(StandardCharsets.UTF_8));
        Path launcher = CoreXYInstaller.install(application, configuration, bundle);
        String content = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
        assertTrue(content.contains("-javaagent:"));
        assertTrue(content.contains("配置 100%%"));
        assertTrue(content.contains("runtime\\bin\\java.exe"));
        assertEquals("custom script", new String(Files.readAllBytes(custom), StandardCharsets.UTF_8));
        assertEquals("enabled=true\ncustom=yes", new String(Files.readAllBytes(formal), StandardCharsets.UTF_8));
        assertEquals("original machine", new String(Files.readAllBytes(configuration.resolve("machine.xml")), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(configuration.resolve("scripts/TornadoSMT/A_CoreXY_Auto_Calibration.py")));
        assertTrue(Files.isRegularFile(configuration.resolve("scripts/Events/Startup.01.CoreXYPrecision.py")));
        assertNotNull(CoreXYInstaller.findApplicationJar(application));
    }

    @Test
    void missingApplicationIsRejectedBeforeAnyConfigWrites() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CoreXYInstaller.install(temporary,
                temporary.resolve("new-config"), temporary.resolve("missing-bundle")));
        assertFalse(Files.exists(temporary.resolve("new-config")));
    }
}
