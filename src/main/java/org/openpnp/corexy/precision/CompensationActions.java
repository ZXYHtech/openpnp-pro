package org.openpnp.corexy.precision;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.model.Configuration;

/** UI and automation share the same persistent, verified enable transaction. */
public final class CompensationActions {
    private CompensationActions() { }

    public static Path enable(Configuration configuration, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        if (!configuration.getMachine().isEnabled()) {
            throw new IllegalStateException("请先连接机器，启用方案需要 Home 和实测校验。");
        }
        for (org.openpnp.spi.Head head : configuration.getMachine().getHeads()) {
            for (org.openpnp.spi.Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    throw new IllegalStateException("请先移除吸嘴上的元件，再启用和校验补偿。");
                }
            }
        }
        OpenPnpCalibrationMachine backend = new OpenPnpCalibrationMachine(configuration);
        PrecisionStore store = CoreXYIntegration.store(configuration);
        PrecisionProfile profile = store.loadProfile(backend.fingerprint());
        if (profile == null) { throw new IllegalStateException("尚无通过实测验证的保存方案，请先校准。"); }
        if (!CoreXYIntegration.acquireWorkflow()) {
            throw new IllegalStateException("请等待当前 CoreXY 操作完成。");
        }
        boolean changed = false;
        Exception activationFailure = null;
        try {
            if (cancelled.getAsBoolean()) { throw new CancellationException("已取消启用补偿"); }
            progress.update(0, "准备启用已保存方案 · Home 清除旧补偿状态");
            changed = true;
            configuration.getMachine().home();
            PrecisionRuntime.deactivate();
            CoreXYIntegration.activateProfile(configuration, profile, backend);
        }
        catch (Exception failure) {
            activationFailure = failure;
        }
        finally {
            CoreXYIntegration.releaseWorkflow();
            if (!changed) { CoreXYIntegration.setStartupStatus("方案启用未完成，请检查机器状态。"); }
        }
        try {
            if (activationFailure != null) { throw activationFailure; }
            Path report = FixedMarkerLifecycle.homeOnly(configuration, progress, cancelled);
            configuration.saveMachineConfiguration();
            store.setEnabled(true);
            CoreXYIntegration.setStartupStatus("新补偿已启用并保存 · Home 基准检查通过，5点精度验证可手动执行。");
            return report;
        }
        catch (Exception failure) {
            try {
                if (changed) { CoreXYIntegration.disable(configuration); }
            }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
