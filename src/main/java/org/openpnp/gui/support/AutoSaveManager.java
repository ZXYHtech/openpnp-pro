package org.openpnp.gui.support;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.pmw.tinylog.Logger;

/**
 * Periodically writes recovery-only snapshots while the machine is idle.
 */
public class AutoSaveManager {
    private static final long AUTOSAVE_INTERVAL_MINUTES = 2;
    private static final int SNAPSHOT_RETENTION = 6;
    private static final DateTimeFormatter SNAPSHOT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Configuration configuration;
    private final Supplier<Job> jobSupplier;
    private final File recoveryRoot;
    private final ScheduledExecutorService executor;

    public AutoSaveManager(Configuration configuration, Supplier<Job> jobSupplier) {
        this.configuration = configuration;
        this.jobSupplier = jobSupplier;
        recoveryRoot = new File(System.getProperty("java.io.tmpdir"), "OpenPnP-Pro-Autosave");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OpenPnP-AutoSave");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::saveIfIdle, AUTOSAVE_INTERVAL_MINUTES,
                AUTOSAVE_INTERVAL_MINUTES, TimeUnit.MINUTES);
        Logger.info("Recovery autosave enabled at {}", recoveryRoot.getAbsolutePath());
    }

    public void stop() {
        executor.shutdownNow();
    }

    public File getRecoveryRoot() {
        return recoveryRoot;
    }

    private void saveIfIdle() {
        try {
            if (!configuration.isLoaded() || configuration.getMachine() == null
                    || configuration.getMachine().isBusy()) {
                return;
            }
            File snapshot = new File(recoveryRoot,
                    SNAPSHOT_FORMAT.format(LocalDateTime.now()));
            configuration.saveRecoverySnapshot(snapshot, jobSupplier.get());
            trimOldSnapshots();
            Logger.debug("Recovery autosave completed: {}", snapshot.getAbsolutePath());
        }
        catch (Exception e) {
            Logger.warn(e, "Recovery autosave failed.");
        }
    }

    private void trimOldSnapshots() {
        File[] snapshots = recoveryRoot.listFiles(File::isDirectory);
        if (snapshots == null || snapshots.length <= SNAPSHOT_RETENTION) {
            return;
        }
        Arrays.sort(snapshots, Comparator.comparing(File::getName).reversed());
        for (int index = SNAPSHOT_RETENTION; index < snapshots.length; index++) {
            deleteSnapshotDirectory(snapshots[index]);
        }
    }

    private void deleteSnapshotDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteSnapshotDirectory(child);
                }
                else if (!child.delete()) {
                    Logger.warn("Unable to remove old autosave file {}", child);
                }
            }
        }
        if (!directory.delete()) {
            Logger.warn("Unable to remove old autosave directory {}", directory);
        }
    }
}
