package org.openpnp.corexy.precision;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.Configuration;

/** Ordinary Home checks only its permanent datum; full workspace checks are explicitly requested. */
public final class FixedMarkerLifecycle {
    interface Measurement extends FixedMarkerVerifier.Backend {
        void prepare(PrecisionProfile profile) throws Exception;
        default void prepare(PrecisionProfile profile, boolean full) throws Exception { prepare(profile); }
        void finish() throws Exception;
        void setCancellationGuard(BooleanSupplier cancelled);
    }
    private static final String SESSION = UUID.randomUUID().toString();
    private static final ThreadLocal<Boolean> MEASURING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<ManualCheck> MANUAL = new ThreadLocal<>();
    private static volatile FixedMarkerLifecycle current;

    private final Configuration configuration;
    private final PrecisionProfile profile;
    private final Measurement backend;
    private final AbstractMotionPlanner planner;
    private boolean attempted, accepted, fullRejected;
    private Path lastReport;

    private static final class ManualCheck {
        final CalibrationController.Progress progress;
        final BooleanSupplier cancelled;
        final boolean full;
        ManualCheck(CalibrationController.Progress progress, BooleanSupplier cancelled, boolean full) {
            this.progress = progress;
            this.cancelled = cancelled;
            this.full = full;
        }
    }

    private FixedMarkerLifecycle(Configuration configuration, PrecisionProfile profile,
            Measurement backend, boolean freshlyCalibrated) {
        this.configuration = configuration;
        this.profile = profile;
        this.backend = backend;
        planner = (AbstractMotionPlanner) configuration.getMachine().getMotionPlanner();
        try {
            PrecisionStore store = CoreXYIntegration.store(configuration);
            java.util.List<Path> history = store.listVerificationDirectories();
            for (int index = history.size() - 1; index >= 0; index--) {
                StartupVerificationReport previous = store.loadVerification(history.get(index));
                if (("manual".equals(previous.kind) || "startup".equals(previous.kind))
                        && profile.solutionId.equals(previous.profileId)) {
                    fullRejected = !previous.passed;
                    break;
                }
            }
        }
        catch (Exception unreadable) {
            fullRejected = true;
            org.pmw.tinylog.Logger.warn(unreadable, "完整验证历史不可读，需要手动完整验证。");
        }
        attempted = freshlyCalibrated;
        accepted = freshlyCalibrated && !fullRejected;
    }

    public static synchronized void install(Configuration configuration, PrecisionProfile profile,
            OpenPnpCalibrationMachine backend, boolean freshlyCalibrated) {
        install(configuration, profile, new Measurement() {
            @Override public void prepare(PrecisionProfile profile) throws Exception { backend.prepareVerification(profile); }
            @Override public void prepare(PrecisionProfile profile, boolean full) throws Exception { backend.prepareVerification(profile, full); }
            @Override public void finish() throws Exception { backend.finishVerification(); }
            @Override public void setCancellationGuard(BooleanSupplier cancelled) { backend.setCancellationGuard(cancelled); }
            @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception { return backend.observe(path); }
            @Override public void subtractDatum(double x, double y) throws Exception { backend.subtractDatum(x, y); }
        }, freshlyCalibrated);
    }

    static synchronized void install(Configuration configuration, PrecisionProfile profile,
            Measurement backend, boolean freshlyCalibrated) {
        current = profile.fixedMarkers == null ? null
                : new FixedMarkerLifecycle(configuration, profile, backend, freshlyCalibrated);
    }

    public static void clear() {
        current = null;
    }

    public static void assertReady(PrecisionProfile profile) {
        FixedMarkerLifecycle lifecycle = current;
        if (lifecycle != null && lifecycle.profile == profile
                && !lifecycle.accepted && !MEASURING.get()) {
            throw new IllegalStateException(lifecycle.attempted
                    ? "Home 或完整验证未通过，请按结果提示重试。"
                    : "请先复位并通过 Home 基准检查；完整验证由按钮手动启动。");
        }
    }

    public static void beforeHomeCompletion(AbstractMotionPlanner planner) throws Exception {
        FixedMarkerLifecycle lifecycle = current;
        if (lifecycle == null || lifecycle.planner != planner || !PrecisionRuntime.isActive()
                || PrecisionRuntime.getModel() != lifecycle.profile.model || MEASURING.get()) {
            return;
        }
        // Calibration and disabling perform their own transactions and must not call the saved profile recursively.
        ManualCheck manual = MANUAL.get();
        if (manual == null && !CoreXYIntegration.acquireWorkflow()) {
            return;
        }
        try (PrecisionRuntime.CompensatedHomingScope scope = PrecisionRuntime.compensatedHoming(planner)) {
            boolean full = manual != null && manual.full;
            lifecycle.measure(full, full ? "manual" : "home",
                    manual != null ? manual.progress : (p, message) -> CoreXYIntegration.setStartupStatus(message),
                    manual != null ? manual.cancelled : () -> false);
            if (!lifecycle.accepted) {
                throw new IllegalStateException("上次完整验证未通过，请使用‘完整验证’重试。");
            }
            scope.complete();
        }
        finally {
            if (manual == null) { CoreXYIntegration.releaseWorkflow(); }
        }
    }

    public static Path verifyNow(Configuration configuration, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        return homeAndCheck(configuration, progress, cancelled, true);
    }

    public static Path homeOnly(Configuration configuration, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        return homeAndCheck(configuration, progress, cancelled, false);
    }

    private static Path homeAndCheck(Configuration configuration, CalibrationController.Progress progress,
            BooleanSupplier cancelled, boolean full) throws Exception {
        FixedMarkerLifecycle lifecycle = current;
        if (lifecycle == null || lifecycle.configuration != configuration || !PrecisionRuntime.isActive()
                || PrecisionRuntime.getModel() != lifecycle.profile.model) {
            throw new IllegalStateException("请先完成并启用 5 个固定 marker 的自动校准。");
        }
        if (!configuration.getMachine().isEnabled()) {
            throw new IllegalStateException("请先连接机器，再执行 Home 和固定 marker 校验。");
        }
        if (!CoreXYIntegration.acquireWorkflow()) {
            throw new IllegalStateException("请等待当前 CoreXY 校准或校验完成。");
        }
        try {
            if (cancelled.getAsBoolean()) { throw new CancellationException("已取消固定 marker 校验"); }
            // The caller owns the transaction, while the same Home callback performs the visual phase before Park.
            Path previousReport = lifecycle.lastReport;
            lifecycle.accepted = false;
            MANUAL.set(new ManualCheck(progress, cancelled, full));
            configuration.getMachine().home();
            if (lifecycle.lastReport == null || lifecycle.lastReport.equals(previousReport) || !lifecycle.accepted) {
                throw new IllegalStateException("Home 没有执行固定 marker 校验，请检查原生或 Agent 接入。");
            }
            return lifecycle.lastReport;
        }
        catch (Exception failure) {
            lifecycle.accepted = false;
            plannerUnhome(lifecycle);
            throw failure;
        }
        finally {
            MEASURING.remove();
            MANUAL.remove();
            CoreXYIntegration.releaseWorkflow();
        }
    }

    private static void plannerUnhome(FixedMarkerLifecycle lifecycle) {
        lifecycle.planner.unhome();
        ((org.openpnp.machine.reference.ReferenceMachine) lifecycle.configuration.getMachine()).setHomed(false);
    }

    private Path measure(boolean full, String kind, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
        StartupVerificationReport report = new StartupVerificationReport();
        report.kind = kind;
        report.sessionId = SESSION;
        report.profileId = profile.solutionId;
        report.fingerprint = profile.fingerprint;
        report.target = profile.target;
        Exception failure = null;
        boolean prepared = false;
        attempted = true;
        accepted = false;
        MEASURING.set(true);
        try {
            backend.setCancellationGuard(cancelled);
            prepared = true;
            backend.prepare(profile, full);
            FixedMarkerVerifier verifier = new FixedMarkerVerifier();
            FixedMarkerVerifier.Backend adapter = new FixedMarkerVerifier.Backend() {
                @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
                    return backend.observe(path);
                }
                @Override public void subtractDatum(double dx, double dy) throws Exception {
                    backend.subtractDatum(dx, dy);
                }
            };
            if (!profile.modelDatumMarkerId.isEmpty() && profile.homeReference == null) {
                if (!full) {
                    throw new IllegalStateException("旧方案尚未保存 Home 参考。请保留四个临时 marker，手动执行一次‘完整验证’；通过后普通复位只检查 Home，四点可移除。");
                }
                ModelReferencedDatum.alignDuringHome(adapter, profile, report, progress, cancelled);
            }
            else {
                verifier.alignHome(adapter, profile, report, progress, cancelled);
            }
            if (full) {
                verifier.verify(adapter, profile, report, progress, cancelled);
                if (!profile.modelDatumMarkerId.isEmpty() && profile.homeReference == null) {
                    double[] reference = verifier.captureHomeReference(adapter, profile, report, cancelled);
                    PrecisionStore store = CoreXYIntegration.store(configuration);
                    PrecisionStore.Snapshot previous = store.snapshot();
                    String previousId = profile.solutionId;
                    profile.homeReference = reference;
                    try {
                        store.saveProfile(profile, store.isEnabled());
                        report.profileId = profile.solutionId;
                        report.notes.add("已保存 Home 参考；普通复位不再依赖四个临时覆盖点。");
                    }
                    catch (Exception persistence) {
                        profile.homeReference = null;
                        profile.solutionId = previousId;
                        try { store.restore(previous); }
                        catch (Exception rollback) { persistence.addSuppressed(rollback); }
                        throw persistence;
                    }
                }
            }
            else {
                int lastCount = 5;
                report.metrics = PrecisionMetrics.evaluate(report.homeSamples.subList(report.homeSamples.size() - lastCount,
                        report.homeSamples.size()), null);
                report.passed = !fullRejected;
                report.outcome = fullRejected ? "Home 基准通过，但上次完整验证未通过；请手动完整验证后恢复生产。"
                        : "Home 基准检查通过；未访问四个临时覆盖点。完整误差验证可手动执行。";
                report.notes.add("仅验证 Home 坐标基准；不代表工作区完整精度。残差见 afterHomeResidual。");
            }
        }
        catch (Exception rejected) {
            failure = rejected;
            report.passed = false;
            report.cancelled = rejected instanceof CancellationException;
            report.outcome = "固定 marker " + (report.cancelled ? "校验已取消：" : "校验失败：") + rejected.getMessage();
        }
        finally {
            try {
                if (prepared) {
                    backend.finish();
                }
            }
            catch (Exception cleanup) {
                report.passed = false;
                if (failure == null) { failure = cleanup; }
                else { failure.addSuppressed(cleanup); }
                report.outcome = "校验清理失败：" + cleanup.getMessage();
            }
            MEASURING.remove();
            report.completedAt = System.currentTimeMillis();
            try {
                lastReport = CoreXYIntegration.store(configuration).saveVerification(report);
            }
            catch (Exception persistence) {
                report.passed = false;
                if (failure == null) { failure = persistence; }
                else { failure.addSuppressed(persistence); }
                report.outcome = "无法保存校验数据，已阻止生产：" + persistence.getMessage();
            }
            if (full) { fullRejected = !report.passed || failure != null; }
            accepted = report.passed && failure == null && !fullRejected;
            CoreXYIntegration.setStartupStatus(report.outcome + (lastReport == null ? "" : " 记录：" + lastReport));
        }
        if (failure != null) {
            throw failure;
        }
        return lastReport;
    }
}
