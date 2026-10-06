package org.openpnp.corexy;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.openpnp.Main;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.precision.CalibrationController;
import org.openpnp.corexy.precision.OpenPnpCalibrationMachine;
import org.openpnp.corexy.precision.PrecisionProfile;
import org.openpnp.corexy.precision.PrecisionRuntime;
import org.openpnp.corexy.precision.PrecisionStore;
import org.openpnp.corexy.precision.FixedMarkerLifecycle;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.pmw.tinylog.Logger;

/** Native and standalone entry points share the same classes, resources and profile schema. */
public final class CoreXYIntegration {
    private static volatile String startupStatus = "尚未加载 CoreXY 补偿档案";
    private static final java.util.concurrent.atomic.AtomicBoolean WORKFLOW = new java.util.concurrent.atomic.AtomicBoolean();

    public static boolean acquireWorkflow() {
        return WORKFLOW.compareAndSet(false, true);
    }

    public static void releaseWorkflow() {
        WORKFLOW.set(false);
    }

    private CoreXYIntegration() {
    }

    public static Path runtimeDirectory(Configuration configuration) {
        return configuration.getScripting().getScriptsDirectory().toPath()
                .resolve("TornadoSMT/OpenPnP-CoreXY").toAbsolutePath().normalize();
    }

    public static PrecisionStore store(Configuration configuration) {
        return new PrecisionStore(runtimeDirectory(configuration).resolve("precision"));
    }

    public static Path legacyConfiguration(Configuration configuration) throws Exception {
        Path path = runtimeDirectory(configuration).resolve("config/corexy.properties");
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (InputStream stream = CoreXYIntegration.class.getResourceAsStream("/corexy/config/corexy.properties")) {
                if (stream == null) {
                    throw new IllegalStateException("CoreXY 默认配置资源缺失。");
                }
                Files.copy(stream, path);
            }
        }
        Properties properties = loadProperties(path);
        if ("uninitialized".equals(properties.getProperty("onboarding.state"))) {
            Camera camera = configuration.getMachine().getDefaultHead().getDefaultCamera();
            if (!(camera.getAxisX() instanceof ReferenceControllerAxis)
                    || !(camera.getAxisY() instanceof ReferenceControllerAxis)) {
                throw new IllegalStateException("新机配置需要顶部相机直接映射 X/Y 控制器轴。");
            }
            properties.setProperty("axis.x.id", camera.getAxisX().getId());
            properties.setProperty("axis.y.id", camera.getAxisY().getId());
            properties.setProperty("onboarding.state", "ready-for-calibration");
            properties.setProperty("openpnp.implementation-version", Main.getBuildString());
            storeProperties(path, properties);
        }
        return path;
    }

    /** Called after optional Startup scripts. This performs configuration reads, never motion. */
    public static void initialize(Configuration configuration) {
        try {
            if (!CoreXYAgent.hasNativeHooks() && !CoreXYAgent.isInstalled()) {
                startupStatus = "原版 OpenPnP：通过独立插件的 Startup 安装 Agent 后使用。";
                return;
            }
            if (PrecisionRuntime.isActive()) {
                return;
            }
            PrecisionStore store = store(configuration);
            if (!store.isEnabled()) {
                LegacyCompensationInterlock.release(configuration);
                CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), legacyConfiguration(configuration).toString());
                startupStatus = "CoreXY 原生模块已就绪，自动补偿未启用。";
                return;
            }
            LegacyCompensationInterlock.expectSavedProfile(configuration);
            LegacyCompensationInterlock.suspendForProfile(configuration);
            OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
            PrecisionProfile profile = store.loadProfile(backend.fingerprint());
            if (profile == null) {
                throw new IllegalStateException("启用标志存在，但没有完整的补偿档案。");
            }
            activateProfile(configuration, profile, backend);
            startupStatus = (profile.fixedMarkers == null ? "已加载通过独立验证的补偿档案；Home 后生效。"
                    : "已预载 5 点补偿方案；Home 完成前自动修正视觉基准，并执行本次启动校验。")
                    + " 原插件补偿已自动停用。";
        }
        catch (Exception failure) {
            startupStatus = "CoreXY 补偿未加载：" + failure.getMessage();
            if (LegacyCompensationInterlock.isExclusive()) {
                configuration.getMachine().getMotionPlanner().unhome();
                if (configuration.getMachine() instanceof org.openpnp.machine.reference.ReferenceMachine) {
                    ((org.openpnp.machine.reference.ReferenceMachine) configuration.getMachine()).setHomed(false);
                }
            }
            Logger.warn(failure, startupStatus);
        }
    }

    public static String getStartupStatus() {
        return startupStatus;
    }

    public static void setStartupStatus(String message) {
        startupStatus = message;
    }

    /** Native and standalone Agent call this before publishing a successful machine Home. */
    public static void beforeHomeCompletion(AbstractMotionPlanner planner) throws Exception {
        FixedMarkerLifecycle.beforeHomeCompletion(planner);
    }

    public static Path verifyFixedMarkers(Configuration configuration, CalibrationController.Progress progress,
            java.util.function.BooleanSupplier cancelled) throws Exception {
        return FixedMarkerLifecycle.verifyNow(configuration, progress, cancelled);
    }

    public static void activateProfile(Configuration configuration, PrecisionProfile profile,
            OpenPnpCalibrationMachine backend) throws Exception {
        profile.validate(backend.fingerprint());
        AbstractMotionPlanner planner = (AbstractMotionPlanner) configuration.getMachine().getMotionPlanner();
        LegacyCompensationInterlock.suspendForProfile(configuration);
        ((ReferenceControllerAxis) configuration.getMachine().getAxis(profile.axisX))
                .setBacklashCompensationMethod(BacklashCompensationMethod.None);
        ((ReferenceControllerAxis) configuration.getMachine().getAxis(profile.axisY))
                .setBacklashCompensationMethod(BacklashCompensationMethod.None);
        PrecisionRuntime.activate(planner, profile.model, profile.axisX, profile.axisY,
                profile.settlingMillis, .25, 1);
        org.openpnp.corexy.precision.ToolPointRuntime.setModels(profile.toolModels);
        if (profile.model.getLayers().contains(org.openpnp.corexy.precision.PrecisionModel.Layer.THERMAL_DRIFT)) {
            PrecisionRuntime.setTemperatureSource(() -> backend.readTemperature(profile.temperatureActuator));
        }
        FixedMarkerLifecycle.install(configuration, profile, backend, false);
        PrecisionRuntime.setProductionGuards(() -> {
            profile.validate(backend.fingerprint());
            FixedMarkerLifecycle.assertReady(profile);
        }, () -> {
            if (!profile.controllerApplyCommand.isEmpty()) {
                backend.transport().query(profile.controllerApplyCommand, 10000);
            }
        });
    }

    public static Path persistResult(Configuration configuration, OpenPnpCalibrationMachine backend,
            CalibrationController.Report report) throws Exception {
        PrecisionStore store = store(configuration);
        PrecisionStore.Snapshot previous = store.snapshot();
        boolean enableRequested = report.enabled;
        String measuredOutcome = report.outcome;
        Path directory = null;
        report.enabled = false;
        if (enableRequested) { report.outcome = "方案已通过测量验证，等待保存配置和启用标志。"; }
        try {
            directory = store.saveReport(report);
            if (enableRequested && report.chosen != null && !report.simulation) {
                PrecisionProfile profile = backend.profile(report);
                profile.calibrationDirectory = directory.toString();
                store.saveProfile(profile, false);
                configuration.save();
                store.setEnabled(true);
                report.enabled = true;
                report.outcome = measuredOutcome;
                store.updateReportCommit(directory, true, report.outcome);
                FixedMarkerLifecycle.install(configuration, profile, backend, true);
                PrecisionRuntime.setProductionGuards(() -> {
                    profile.validate(backend.fingerprint());
                    FixedMarkerLifecycle.assertReady(profile);
                }, () -> {
                    if (!profile.controllerApplyCommand.isEmpty()) {
                        backend.transport().query(profile.controllerApplyCommand, 10000);
                    }
                });
            }
            return directory;
        }
        catch (Exception persistenceFailure) {
            report.enabled = false;
            report.outcome = "补偿方案保存或启用失败，已请求恢复原配置：" + persistenceFailure.getMessage();
            try {
                backend.finish(false, null, report.settlingMillis);
                store.restore(previous);
                configuration.save();
            }
            catch (Exception rollbackFailure) {
                persistenceFailure.addSuppressed(rollbackFailure);
                store.setEnabled(false);
            }
            if (directory != null) {
                try {
                    store.updateReportCommit(directory, false, report.outcome);
                }
                catch (Exception historyFailure) {
                    persistenceFailure.addSuppressed(historyFailure);
                }
            }
            throw persistenceFailure;
        }
    }

    public static void disable(Configuration configuration) throws Exception {
        if (!acquireWorkflow()) {
            throw new IllegalStateException("请等待当前 CoreXY 校准完成后再停用。");
        }
        try {
            disableInternal(configuration);
        }
        finally {
            releaseWorkflow();
        }
    }

    private static void disableInternal(Configuration configuration) throws Exception {
        PrecisionStore store = store(configuration);
        PrecisionProfile profile = null;
        try {
            profile = store.loadForRestore();
        }
        catch (Exception damaged) {
            Logger.warn(damaged, "档案损坏，仍将停用运行时补偿；轴设置需要人工核对。");
        }
        Exception homeFailure = null;
        try {
            if (configuration.getMachine().isEnabled()) {
                configuration.getMachine().home();
            }
            else {
                configuration.getMachine().getMotionPlanner().unhome();
            }
        }
        catch (Exception failure) {
            configuration.getMachine().getMotionPlanner().unhome();
            homeFailure = failure;
        }
        PrecisionRuntime.deactivate();
        FixedMarkerLifecycle.clear();
        if (configuration.getMachine().getMotionPlanner() instanceof CoreXYAdvancedMotionPlanner) {
            ((CoreXYAdvancedMotionPlanner) configuration.getMachine().getMotionPlanner()).setCoreXYCompensationEnabled(false);
        }
        org.openpnp.corexy.precision.ToolPointRuntime.setModels(java.util.Collections.emptyMap());
        store.setEnabled(false);
        if (profile != null) {
            org.openpnp.spi.Axis axisX = configuration.getMachine().getAxis(profile.axisX);
            org.openpnp.spi.Axis axisY = configuration.getMachine().getAxis(profile.axisY);
            ReferenceControllerAxis x = axisX instanceof ReferenceControllerAxis ? (ReferenceControllerAxis) axisX : null;
            ReferenceControllerAxis y = axisY instanceof ReferenceControllerAxis ? (ReferenceControllerAxis) axisY : null;
            if (x != null) {
                x.setBacklashCompensationMethod(BacklashCompensationMethod.valueOf(profile.previousBacklashX));
            }
            if (y != null) {
                y.setBacklashCompensationMethod(BacklashCompensationMethod.valueOf(profile.previousBacklashY));
            }
            if (!profile.controllerRestoreCommand.isEmpty() && configuration.getMachine().isEnabled()) {
                try {
                    new OpenPnpCalibrationMachine(configuration).transport().query(profile.controllerRestoreCommand, 10000);
                }
                catch (Exception failure) {
                    homeFailure = failure;
                }
            }
        }
        Path legacy = legacyConfiguration(configuration);
        Properties properties = loadProperties(legacy);
        properties.setProperty("enabled", "false");
        properties.setProperty("dry-run", "false");
        storeProperties(legacy, properties);
        CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), legacy.toString());
        configuration.save();
        LegacyCompensationInterlock.release(configuration);
        startupStatus = "CoreXY 补偿已停用，轴设置已恢复。";
        if (homeFailure != null) {
            throw new IllegalStateException("补偿已停用；Home 或控制器恢复未完成，请检查连接后重新 Home。", homeFailure);
        }
    }

    /** Installs every upstream script without overwriting customized scripts or formal parameters. */
    public static int installLegacyTools(Configuration configuration) throws Exception {
        Path scripts = configuration.getScripting().getScriptsDirectory().toPath().toAbsolutePath().normalize();
        int count = 0;
        try (InputStream stream = CoreXYIntegration.class.getResourceAsStream("/corexy/scripts-manifest.txt")) {
            if (stream == null) {
                throw new IllegalStateException("原插件脚本资源清单缺失。");
            }
            String manifest = readText(stream);
            for (String name : manifest.split("\\r?\\n")) {
                if (name.trim().isEmpty()) {
                    continue;
                }
                Path target = scripts.resolve(name).normalize();
                if (!target.startsWith(scripts)) {
                    throw new IllegalStateException("Invalid bundled script path.");
                }
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    try (InputStream resource = CoreXYIntegration.class.getResourceAsStream("/corexy/scripts/" + name)) {
                        if (resource == null) {
                            throw new IllegalStateException("Missing bundled script: " + name);
                        }
                        Files.copy(resource, target);
                    }
                    count++;
                }
            }
        }
        legacyConfiguration(configuration);
        Path policy = runtimeDirectory(configuration).resolve("config/calibration-policy.properties");
        if (!Files.exists(policy)) {
            try (InputStream resource = CoreXYIntegration.class.getResourceAsStream("/corexy/config/calibration-policy.properties")) {
                if (resource == null) {
                    throw new IllegalStateException("Missing calibration policy.");
                }
                Files.copy(resource, policy);
            }
        }
        return count;
    }

    public static void launchLegacy(Configuration configuration) throws Exception {
        if (WORKFLOW.get()) {
            throw new IllegalStateException("请等待当前 CoreXY 校准完成后再打开工具箱。");
        }
        if (LegacyCompensationInterlock.isExclusive() || store(configuration).isEnabled()) {
            throw new IllegalStateException("请先停用自动补偿，再打开原插件调参器。");
        }
        installLegacyTools(configuration);
        configuration.getScripting().execute(configuration.getScripting().getScriptsDirectory().toPath()
                .resolve("TornadoSMT/A_CoreXY_Repeatability_Tuner.py").toFile(), null);
    }

    public static Properties loadProperties(Path path) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        }
        return properties;
    }

    public static void storeProperties(Path path, Properties properties) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        properties.store(bytes, "OpenPnP CoreXY configuration");
        PrecisionStore.atomicWriteBytes(path, bytes.toByteArray());
    }

    private static String readText(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
