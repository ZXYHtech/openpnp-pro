package org.openpnp.corexy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.corexy.precision.PrecisionRuntime;
import org.openpnp.corexy.precision.PrecisionStore;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.Configuration;

/** One owner of CoreXY compensation, including legacy scripts and transient profiles. */
public final class LegacyCompensationInterlock {
    private static volatile Session calibration;
    private static volatile Configuration exclusiveConfiguration;
    private static volatile boolean committed;
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    private LegacyCompensationInterlock() { }

    public static void expectSavedProfile(Configuration configuration) {
        exclusiveConfiguration = configuration;
        committed = true;
    }

    public static boolean isExclusive() {
        Configuration configuration = Configuration.get();
        return PrecisionRuntime.isActive()
                || (calibration != null && calibration.configuration == configuration)
                || (committed && exclusiveConfiguration == configuration);
    }

    /** Original Startup runs before Precision Startup; disable a reinstalled formal profile first. */
    public static void prepareLegacyStartup(Path requested) throws Exception {
        if (isExclusive()) { return; }
        Configuration configuration = Configuration.get();
        if (configuration.getScripting() != null
                && configuration.getScripting().getScriptsDirectory() != null
                && CoreXYIntegration.store(configuration).isEnabled()
                && requested.equals(CoreXYIntegration.legacyConfiguration(configuration))) {
            suspendForProfile(configuration);
        }
    }

    /** Called before changing any runtime state, so rejected reloads preserve the live model. */
    public static void assertLegacyLoadAllowed(boolean operational) throws Exception {
        if (!operational || RESTORING.get()) { return; }
        Configuration configuration = Configuration.get();
        boolean saved = configuration.getScripting() != null
                && configuration.getScripting().getScriptsDirectory() != null
                && CoreXYIntegration.store(configuration).isEnabled();
        if (isExclusive() || saved) {
            throw new IllegalStateException("CoreXY 校准或补偿正在使用：原插件补偿已停用，"
                    + "不能加载旧补偿或试运行参数。请先在机器菜单的自动精度校准中停用补偿。");
        }
    }

    public static void assertLegacyPlannerEnableAllowed() {
        try {
            assertLegacyLoadAllowed(true);
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    /** A damaged saved profile must not silently fall back to legacy compensation. */
    public static void assertProductionAllowed() {
        if (committed && exclusiveConfiguration == Configuration.get()
                && calibration == null && !PrecisionRuntime.isActive() && !RESTORING.get()) {
            throw new IllegalStateException("精度补偿尚未成功加载；原插件保持停用，请检查档案或在机器菜单的自动精度校准中停用补偿。");
        }
    }

    public static synchronized Session beginCalibration(Configuration configuration) throws Exception {
        if (calibration != null) { throw new IllegalStateException("已有 CoreXY 实机校准正在进行。"); }
        Session session = new Session(configuration);
        calibration = session;
        try {
            session.suspend();
            return session;
        }
        catch (Exception failure) {
            try { session.rollback(); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        }
    }

    public static synchronized void suspendForProfile(Configuration configuration) throws Exception {
        expectSavedProfile(configuration);
        Session session = new Session(configuration);
        session.suspend();
        session.commit();
    }

    public static synchronized void release(Configuration configuration) {
        if (exclusiveConfiguration == configuration) {
            committed = false;
            exclusiveConfiguration = null;
        }
    }

    public static final class Session {
        private final Configuration configuration;
        private final AbstractMotionPlanner planner;
        private final Path formal, previousRuntime, previousRemembered;
        private final byte[] formalBytes, runtimeBytes;
        private final boolean plannerEnabled, previousCommitted;
        private final Configuration previousOwner;
        private Path backup;
        private boolean restored;

        private Session(Configuration configuration) throws Exception {
            this.configuration = configuration;
            planner = (AbstractMotionPlanner) configuration.getMachine().getMotionPlanner();
            formal = CoreXYIntegration.legacyConfiguration(configuration);
            formalBytes = Files.readAllBytes(formal);
            previousRuntime = CoreXYRuntime.getLegacyConfigurationPath();
            runtimeBytes = previousRuntime != null && Files.isRegularFile(previousRuntime)
                    ? Files.readAllBytes(previousRuntime) : null;
            previousRemembered = CoreXYAgent.getConfigurationPath();
            plannerEnabled = planner instanceof CoreXYAdvancedMotionPlanner
                    && ((CoreXYAdvancedMotionPlanner) planner).isCoreXYCompensationEnabled();
            previousOwner = exclusiveConfiguration;
            previousCommitted = committed;
        }

        public Path getBackupDirectory() { return backup; }

        private void suspend() throws Exception {
            CoreXYRuntime.requireNeutralLegacy();
            if (planner instanceof CoreXYAdvancedMotionPlanner) {
                ((CoreXYAdvancedMotionPlanner) planner).requireNeutralCoreXYOffset();
            }
            Properties properties = CoreXYIntegration.loadProperties(formal);
            boolean changed = Boolean.parseBoolean(properties.getProperty("enabled"))
                    || Boolean.parseBoolean(properties.getProperty("dry-run")) || plannerEnabled
                    || CoreXYRuntime.isLegacyEnabled() || CoreXYRuntime.isLegacyDryRun();
            if (changed && backup == null) {
                backup = CoreXYIntegration.runtimeDirectory(configuration).resolve("backups")
                        .resolve("experimental-interlock-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
                Files.createDirectories(backup);
                PrecisionStore.atomicWriteBytes(backup.resolve("formal.properties"), formalBytes);
                if (runtimeBytes != null) {
                    PrecisionStore.atomicWriteBytes(backup.resolve("active.properties"), runtimeBytes);
                }
                java.io.StringWriter xml = new java.io.StringWriter();
                Configuration.createSerializer().write(planner, xml);
                PrecisionStore.atomicWrite(backup.resolve("planner.xml"), xml.toString());
                Properties metadata = new Properties();
                metadata.setProperty("formal.path", formal.toString());
                metadata.setProperty("formal.sha256", PrecisionStore.digest(formalBytes));
                metadata.setProperty("active.path", previousRuntime == null ? "" : previousRuntime.toString());
                metadata.setProperty("planner.enabled", Boolean.toString(plannerEnabled));
                CoreXYIntegration.storeProperties(backup.resolve("backup.properties"), metadata);
            }
            if (changed || !"true".equals(properties.getProperty("precision.exclusive"))) {
                properties.setProperty("enabled", "false");
                properties.setProperty("dry-run", "false");
                properties.setProperty("precision.exclusive", "true");
                CoreXYIntegration.storeProperties(formal, properties);
            }
            if (planner instanceof CoreXYAdvancedMotionPlanner) {
                CoreXYAdvancedMotionPlanner legacyPlanner = (CoreXYAdvancedMotionPlanner) planner;
                legacyPlanner.setCoreXYCompensationEnabled(false);
                legacyPlanner.resetCoreXYCompensationState();
            }
            CoreXYAgent.suspendLegacy(formal.toString());
        }

        /** Keep the snapshot for a later persistence-failure rollback. */
        public synchronized void commit() throws Exception {
            suspend();
            expectSavedProfile(configuration);
            if (calibration == this) { calibration = null; }
        }

        public synchronized void rollback() throws Exception {
            if (restored) { return; }
            RESTORING.set(true);
            try {
                CoreXYRuntime.requireNeutralLegacy();
                PrecisionStore.atomicWriteBytes(formal, formalBytes);
                Path active = previousRuntime != null ? previousRuntime : formal;
                // An old transient file may be removed while calibration is running; restore its exact snapshot.
                if (runtimeBytes != null && (!Files.isRegularFile(active)
                        || !java.util.Arrays.equals(runtimeBytes, Files.readAllBytes(active)))) {
                    if (backup == null) {
                        backup = Files.createTempDirectory(formal.getParent(), "interlock-rollback-");
                    }
                    active = backup.resolve("active.properties");
                    PrecisionStore.atomicWriteBytes(active, runtimeBytes);
                }
                CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), active.toString());
                CoreXYAgent.rememberConfiguration((previousRemembered == null ? formal : previousRemembered).toString());
                if (planner instanceof CoreXYAdvancedMotionPlanner) {
                    ((CoreXYAdvancedMotionPlanner) planner).setCoreXYCompensationEnabled(plannerEnabled);
                }
                exclusiveConfiguration = previousOwner;
                committed = previousCommitted;
                restored = true;
            }
            catch (Exception failure) {
                expectSavedProfile(configuration);
                planner.unhome();
                ((org.openpnp.machine.reference.ReferenceMachine) configuration.getMachine()).setHomed(false);
                throw failure;
            }
            finally {
                if (calibration == this) { calibration = null; }
                RESTORING.remove();
            }
        }
    }
}
