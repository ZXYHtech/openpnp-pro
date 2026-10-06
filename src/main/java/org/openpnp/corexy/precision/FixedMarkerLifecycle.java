package org.openpnp.corexy.precision;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.Configuration;

/** One full check per application session, plus a visual datum correction at every Home. */
public final class FixedMarkerLifecycle {
    interface Measurement extends FixedMarkerVerifier.Backend {
        void prepare(PrecisionProfile profile) throws Exception;
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
    private boolean attempted, accepted;
    private Path lastReport;

    private static final class ManualCheck {
        final CalibrationController.Progress progress;
        final BooleanSupplier cancelled;
        ManualCheck(CalibrationController.Progress progress, BooleanSupplier cancelled) {
            this.progress = progress;
            this.cancelled = cancelled;
        }
    }

    private FixedMarkerLifecycle(Configuration configuration, PrecisionProfile profile,
            Measurement backend, boolean freshlyCalibrated) {
        this.configuration = configuration;
        this.profile = profile;
        this.backend = backend;
        planner = (AbstractMotionPlanner) configuration.getMachine().getMotionPlanner();
        attempted = accepted = freshlyCalibrated;
    }

    public static synchronized void install(Configuration configuration, PrecisionProfile profile,
            OpenPnpCalibrationMachine backend, boolean freshlyCalibrated) {
        install(configuration, profile, new Measurement() {
            @Override public void prepare(PrecisionProfile profile) throws Exception { backend.prepareVerification(profile); }
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
        if (lifecycle != null && lifecycle.profile == profile && profile.fixedMarkers.verifyOnStartup
                && !lifecycle.accepted && !MEASURING.get()) {
            throw new IllegalStateException(lifecycle.attempted
                    ? "本次启动的固定 marker 校验未通过，请执行‘立即校验’或重新校准。"
                    : "请先执行 Home：完成 Home marker 视觉归零和本次启动的 5 点校验后才可生产。");
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
            boolean full = manual != null || (lifecycle.profile.fixedMarkers.verifyOnStartup && !lifecycle.attempted);
            lifecycle.measure(full, manual != null ? "manual" : full ? "startup" : "home",
                    manual != null ? manual.progress : (p, message) -> CoreXYIntegration.setStartupStatus(message),
                    manual != null ? manual.cancelled : () -> false);
            if (lifecycle.profile.fixedMarkers.verifyOnStartup && !lifecycle.accepted) {
                throw new IllegalStateException("本次启动的 5 点校验尚未通过，请使用‘立即校验’重试。");
            }
            scope.complete();
        }
        finally {
            if (manual == null) { CoreXYIntegration.releaseWorkflow(); }
        }
    }

    public static Path verifyNow(Configuration configuration, CalibrationController.Progress progress,
            BooleanSupplier cancelled) throws Exception {
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
            MANUAL.set(new ManualCheck(progress, cancelled));
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
        if (full) {
            attempted = true;
            accepted = false;
        }
        MEASURING.set(true);
        try {
            backend.setCancellationGuard(cancelled);
            prepared = true;
            backend.prepare(profile);
            FixedMarkerVerifier verifier = new FixedMarkerVerifier();
            FixedMarkerVerifier.Backend adapter = new FixedMarkerVerifier.Backend() {
                @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
                    return backend.observe(path);
                }
                @Override public void subtractDatum(double dx, double dy) throws Exception {
                    backend.subtractDatum(dx, dy);
                }
            };
            verifier.alignHome(adapter, profile, report, progress, cancelled);
            if (full) {
                verifier.verify(adapter, profile, report, progress, cancelled);
            }
            else {
                report.metrics = PrecisionMetrics.evaluate(report.homeSamples.subList(report.homeSamples.size() - 5,
                        report.homeSamples.size()), null);
                report.passed = true;
                report.outcome = "Home marker 视觉归零完成；本次仅复核 Home 基准，未重复启动 5 点校验。";
                report.notes.add("本记录仅包含 Home 基准；完整 5 点校验结果见同 sessionId 的 startup/manual 记录。");
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
            if (full) {
                accepted = report.passed && failure == null;
            }
            CoreXYIntegration.setStartupStatus(report.outcome + (lastReport == null ? "" : " 记录：" + lastReport));
        }
        if (failure != null) {
            throw failure;
        }
        return lastReport;
    }
}
