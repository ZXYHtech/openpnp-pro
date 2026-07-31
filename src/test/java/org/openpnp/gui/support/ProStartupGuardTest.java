package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProStartupGuardTest {
    @TempDir
    Path temporaryHome;

    @Test
    void existingSharedConfigurationIsBackedUpWithoutJobBodies() throws Exception {
        String oldHome = System.getProperty("user.home");
        String oldConfig = System.getProperty("configDir");
        try {
            System.setProperty("user.home", temporaryHome.toString());
            System.clearProperty("configDir");
            Path shared = temporaryHome.resolve(".openpnp2");
            Files.createDirectories(shared);
            Files.write(shared.resolve("machine.xml"), "<machine/>".getBytes(StandardCharsets.UTF_8));
            Files.write(shared.resolve("production.job.xml"), "<job/>".getBytes(StandardCharsets.UTF_8));

            File prepared = ProStartupGuard.prepareConfigurationDirectory();

            assertEquals(shared.toFile().getCanonicalFile(), prepared.getCanonicalFile());
            assertTrue(shared.resolve(".pro-initial-backup-complete").toFile().isFile());
            File[] archives = temporaryHome.resolve("OpenPnP Pro Backups").toFile()
                    .listFiles((directory, name) -> name.endsWith(".zip"));
            assertNotNull(archives);
            assertEquals(1, archives.length);
            Set<String> entries = zipEntries(archives[0]);
            assertTrue(entries.contains("data/machine.xml"));
            assertTrue(entries.contains("PRO_BACKUP_MANIFEST.txt"));
            assertFalse(entries.contains("data/production.job.xml"));

            ProStartupGuard.prepareConfigurationDirectory();
            assertEquals(1, temporaryHome.resolve("OpenPnP Pro Backups").toFile()
                    .listFiles((directory, name) -> name.endsWith(".zip")).length);
        }
        finally {
            restoreProperty("user.home", oldHome);
            restoreProperty("configDir", oldConfig);
        }
    }

    @Test
    void boardOrPanelDataAlsoTriggersTheMandatoryBackup() throws Exception {
        String oldHome = System.getProperty("user.home");
        String oldConfig = System.getProperty("configDir");
        try {
            System.setProperty("user.home", temporaryHome.toString());
            System.clearProperty("configDir");
            Path shared = temporaryHome.resolve(".openpnp2");
            Files.createDirectories(shared);
            Files.write(shared.resolve("custom.board.xml"),
                    "<openpnp-board/>".getBytes(StandardCharsets.UTF_8));

            ProStartupGuard.prepareConfigurationDirectory();

            File[] archives = temporaryHome.resolve("OpenPnP Pro Backups").toFile()
                    .listFiles((directory, name) -> name.endsWith(".zip"));
            assertNotNull(archives);
            assertEquals(1, archives.length);
            assertTrue(zipEntries(archives[0]).contains("data/custom.board.xml"));
        }
        finally {
            restoreProperty("user.home", oldHome);
            restoreProperty("configDir", oldConfig);
        }
    }
    @Test
    void identifiesOriginalAndProLaunchCommandsButNotUnrelatedJava() {
        assertTrue(ProStartupGuard.isOpenPnpCommand("java -jar openpnp-gui-2.6.jar"));
        assertTrue(ProStartupGuard.isOpenPnpCommand("C:\\Program Files\\OpenPnP\\OpenPnP.exe"));
        assertTrue(ProStartupGuard.isOpenPnpCommand("C:\\Program Files\\OpenPnP Pro\\OpenPnP Pro.exe"));
        assertFalse(ProStartupGuard.isOpenPnpCommand("java -jar unrelated.jar"));
        assertFalse(ProStartupGuard.isOpenPnpCommand(null));
    }

    @Test
    void currentLauncherFamilyIsNotReportedAsAnotherInstance() {
        Set<Long> selfProcessIds = ProStartupGuard.collectSelfProcessIds(
                ProcessHandle.current());
        assertTrue(selfProcessIds.contains(ProcessHandle.current().pid()));
        assertFalse(ProStartupGuard.shouldReportProcess(ProcessHandle.current().pid(),
                selfProcessIds, "OpenPnP Pro.exe"));
        assertFalse(ProStartupGuard.shouldReportProcess(1234L,
                Set.of(1234L), "OpenPnP Pro.exe"));
        assertTrue(ProStartupGuard.shouldReportProcess(5678L,
                selfProcessIds, "C:\\Program Files\\OpenPnP\\OpenPnP.exe"));
    }
    private static Set<String> zipEntries(File archive) throws Exception {
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement().getName());
            }
        }
        return entries;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        }
        else {
            System.setProperty(key, value);
        }
    }
}