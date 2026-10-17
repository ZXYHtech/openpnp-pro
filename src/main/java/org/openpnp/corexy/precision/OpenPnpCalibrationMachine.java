package org.openpnp.corexy.precision;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.agent.CoreXYRuntime;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;

/** Real adapter. Nothing moves until run() is explicitly started in an OpenPnP machine task. */
public final class OpenPnpCalibrationMachine implements CalibrationMachine {
    private final Configuration configuration;
    private final ReferenceMachine machine;
    private final ReferenceCamera camera;
    private final ReferenceControllerAxis x, y;
    private final AbstractMotionPlanner planner;
    private final MarkerDetector detector = new MarkerDetector();
    private final Map<ReferenceNozzleTip, String> toolBackups = new LinkedHashMap<>();
    private Map<String, ToolPointModel> originalToolModels = new LinkedHashMap<>();
    private final Map<String, ToolPointModel> calibratedTools = new LinkedHashMap<>();
    private final Path legacy;
    private CalibrationRequest request;
    private PrecisionRuntime.Settings originalSettings;
    private byte[] originalLegacyBytes;
    private BacklashCompensationMethod originalBacklashX, originalBacklashY;
    private Length originalAccelerationX, originalAccelerationY;
    private Location originalBottomOffsets;
    private double originalBottomRotation;
    private ReferenceCamera bottom;
    private Path disabledConfig;
    private boolean started;
    private long startedAt;
    private double datumX, datumY;
    private double noise;
    private boolean verificationOnly;
    private ControllerShaping.Session shaping;
    private PrecisionProfile previousProfile;
    private boolean originalPlannerCompensation;
    private org.openpnp.corexy.LegacyCompensationInterlock.Session legacySession;
    private final List<PrecisionSample> priorObservations = new ArrayList<>();
    private final Map<String, CalibrationPlan.Path> fittedPaths = new LinkedHashMap<>();
    private java.util.function.BooleanSupplier cancellation = () -> false;

    @Override
    public void setCancellationGuard(java.util.function.BooleanSupplier cancelled) {
        cancellation = cancelled;
    }

    @Override
    public List<PrecisionSample> previousObservations() {
        return new ArrayList<>(priorObservations);
    }

    @Override
    public List<String> preparationNotes() {
        List<String> notes = new ArrayList<>();
        if (legacySession != null) {
            Path backup = legacySession.getBackupDirectory();
            notes.add("已自动停用原插件 Agent、试运行及旧规划器补偿；"
                    + (backup == null ? "旧方案原本已停用。" : "原始参数备份：" + backup));
        }
        if (!fittedPaths.isEmpty()) {
            double shortest = Double.POSITIVE_INFINITY;
            for (CalibrationPlan.Path path : fittedPaths.values()) {
                shortest = Math.min(shortest, path.conditioningTravel);
            }
            notes.add(String.format(java.util.Locale.ROOT,
                    "已按 X/Y 软限位和补偿余量调整 %d 条边缘 marker 路径；最短预行程 %.3f mm。"
                    + "采样 CSV 使用实际 A/B 行程，marker 坐标及换向方向保持不变。",
                    fittedPaths.size(), shortest));
        }
        return notes;
    }

    private void checkCancelled() {
        if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("用户取消校准");
        }
    }

    public OpenPnpCalibrationMachine(Configuration configuration) throws Exception {
        this.configuration = configuration;
        if (!(configuration.getMachine() instanceof ReferenceMachine)
                || !(configuration.getMachine().getMotionPlanner() instanceof AbstractMotionPlanner)) {
            throw new IllegalStateException("仅支持 ReferenceMachine 和标准运动规划器。");
        }
        machine = (ReferenceMachine) configuration.getMachine();
        Camera top = machine.getDefaultHead().getDefaultCamera();
        if (!(top instanceof ReferenceCamera) || !(top.getAxisX() instanceof ReferenceControllerAxis)
                || !(top.getAxisY() instanceof ReferenceControllerAxis)) {
            throw new IllegalStateException("顶部相机必须直接映射到笛卡尔 X/Y 控制器轴。");
        }
        camera = (ReferenceCamera) top;
        x = (ReferenceControllerAxis) camera.getAxisX();
        y = (ReferenceControllerAxis) camera.getAxisY();
        planner = (AbstractMotionPlanner) machine.getMotionPlanner();
        if (x == y || x.getType() != Axis.Type.X || y.getType() != Axis.Type.Y
                || x.getDriver() == null || x.getDriver() != y.getDriver()) {
            throw new IllegalStateException("X/Y 必须是同一驱动器上的两个独立控制器轴。");
        }
        legacy = CoreXYIntegration.legacyConfiguration(configuration);
    }

    public ReferenceCamera getCamera() {
        return camera;
    }

    public List<PlateSelfCalibration.Observation> collectPlateView(CalibrationRequest settings, int view,
            java.util.function.BooleanSupplier cancelled) throws Exception {
        setCancellationGuard(cancelled);
        List<PlateSelfCalibration.Observation> observations = new ArrayList<>();
        List<CalibrationPlan.Path> paths = new ArrayList<>();
        for (int row = 0; row < settings.gridSize; row++) {
            for (int column = 0; column < settings.gridSize; column++) {
                double angle = Math.toRadians(settings.plateAngle);
                double u = column * settings.pitch, v = row * settings.pitch;
                for (int a : new int[] {-1, 1}) {
                    for (int b : new int[] {-1, 1}) {
                        paths.add(new CalibrationPlan.Path("plate-view-" + view,
                                settings.originX + u * Math.cos(angle) - v * Math.sin(angle),
                                settings.originY + u * Math.sin(angle) + v * Math.cos(angle),
                                settings.conditioningTravel, settings.conditioningTravel, .5, .5,
                                a, b, 1, settings.settleMillis));
                    }
                }
            }
        }
        try {
            prepare(settings, new CalibrationPlan(paths));
            measureNoise();
            for (int row = 0; row < settings.gridSize; row++) {
                for (int column = 0; column < settings.gridSize; column++) {
                    if (cancelled.getAsBoolean()) {
                        throw new java.util.concurrent.CancellationException("用户取消标定板采集");
                    }
                    double angle = Math.toRadians(settings.plateAngle);
                    double u = column * settings.pitch, v = row * settings.pitch;
                    double px = settings.originX + u * Math.cos(angle) - v * Math.sin(angle);
                    double py = settings.originY + u * Math.sin(angle) + v * Math.cos(angle);
                    double dx = 0, dy = 0, rawX = 0, rawY = 0;
                    for (int a : new int[] {-1, 1}) {
                        for (int b : new int[] {-1, 1}) {
                            PrecisionSample sample = observe(paths.get((row * settings.gridSize + column) * 4
                                    + (a == -1 ? 0 : 2) + (b == -1 ? 0 : 1)));
                            dx += sample.correctionX / 4;
                            dy += sample.correctionY / 4;
                            rawX = sample.x;
                            rawY = sample.y;
                        }
                    }
                    observations.add(new PlateSelfCalibration.Observation(view, row * settings.gridSize + column,
                            rawX, rawY, settings.plateAngle, dx, dy));
                }
            }
            return observations;
        }
        finally {
            finish(false, null, settings.settleMillis);
            if (!cancelled.getAsBoolean() && machine.isEnabled() && planner.isHomed()) {
                MovableUtils.moveToLocationAtSafeZ(camera,
                        location(settings.originX, settings.originY, settings.planeZ), .35 * machine.getSpeed());
                camera.waitForCompletion(CompletionType.WaitForStillstand);
            }
        }
    }

    @Override
    public boolean isSimulation() {
        return false;
    }

    @Override
    public String fingerprint() throws Exception {
        if (machine.getDefaultHead().getDefaultCamera() != camera || camera.getAxisX() != x || camera.getAxisY() != y) {
            throw new IllegalStateException("顶部相机或 X/Y 轴映射已改变，请重新校准。");
        }
        StringBuilder text = new StringBuilder("CoreXY-profile-v1|");
        for (ReferenceControllerAxis axis : new ReferenceControllerAxis[] {x, y}) {
            text.append(axis.getId()).append('|').append(axis.getType()).append('|')
                    .append(axis.getDriver().getId()).append('|').append(axis.getDriver().getClass().getName())
                    .append('|').append(axis.isSoftLimitLowEnabled()).append('|').append(axis.getSoftLimitLow())
                    .append('|').append(axis.isSoftLimitHighEnabled()).append('|').append(axis.getSoftLimitHigh())
                    .append('|').append(axis.getFeedratePerSecond()).append('|')
                    .append(axis.getAccelerationPerSecond2()).append('|');
            if (axis.getDriver() instanceof GcodeDriver) {
                text.append(((GcodeDriver) axis.getDriver()).getFirmwareConfiguration()).append('|');
            }
        }
        appendCameraFingerprint(text, camera);
        for (Camera fixed : machine.getCameras()) {
            if (fixed instanceof ReferenceCamera) {
                appendCameraFingerprint(text, (ReferenceCamera) fixed);
            }
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                text.append(nozzle.getId()).append('|').append(nozzle.getHeadOffsets()).append('|');
                text.append(nozzle.getAxisX() == null ? "" : nozzle.getAxisX().getId()).append('|')
                        .append(nozzle.getAxisY() == null ? "" : nozzle.getAxisY().getId()).append('|')
                        .append(nozzle.getAxisZ() == null ? "" : nozzle.getAxisZ().getId()).append('|')
                        .append(nozzle.getAxisRotation() == null ? "" : nozzle.getAxisRotation().getId()).append('|');
            }
        }
        return PrecisionStore.digest(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendCameraFingerprint(StringBuilder text, ReferenceCamera item) throws Exception {
        text.append(item.getId()).append('|').append(item.getHeadOffsets()).append('|')
                .append(item.getUnitsPerPixel()).append('|').append(item.getUnitsPerPixelSecondary())
                .append('|').append(item.getRotation()).append('|').append(item.isFlipX()).append('|')
                .append(item.isFlipY()).append('|').append(item.getOffsetX()).append('|').append(item.getOffsetY())
                .append('|').append(item.getCropWidth()).append('|').append(item.getCropHeight()).append('|')
                .append(item.getScaleWidth()).append('|').append(item.getScaleHeight()).append('|')
                .append(item.getCameraPrimaryZ()).append('|').append(item.getCameraSecondaryZ()).append('|')
                .append(item.isEnableUnitsPerPixel3D()).append('|');
        StringWriter lens = new StringWriter(), advanced = new StringWriter();
        Configuration.createSerializer().write(item.getCalibration(), lens);
        Configuration.createSerializer().write(item.getAdvancedCalibration(), advanced);
        text.append(lens).append('|').append(advanced).append('|');
    }

    @Override
    public void prepare(CalibrationRequest settings, CalibrationPlan plan) throws Exception {
        if (!machine.isEnabled() || !machine.isHomed()) {
            throw new IllegalStateException("请先连接机器并完成 Home。");
        }
        if (x.getDriver() instanceof org.openpnp.machine.reference.driver.NullDriver
                || camera.getClass().getSimpleName().startsWith("Simulated")) {
            throw new IllegalStateException("虚拟驱动或模拟相机不能生成生产补偿档案，请使用离线演练。");
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    throw new IllegalStateException("请移除吸嘴上的元件后开始校准。");
                }
            }
        }
        settings.validate();
        request = settings;
        fittedPaths.clear();
        validatePlan(plan);
        originalSettings = PrecisionRuntime.settings();
        originalPlannerCompensation = planner instanceof org.openpnp.corexy.CoreXYAdvancedMotionPlanner
                && ((org.openpnp.corexy.CoreXYAdvancedMotionPlanner) planner).isCoreXYCompensationEnabled();
        if (originalSettings != null) {
            previousProfile = CoreXYIntegration.store(configuration).loadForRestore();
        }
        originalLegacyBytes = Files.readAllBytes(legacy);
        originalToolModels = ToolPointRuntime.snapshot();
        originalBacklashX = x.getBacklashCompensationMethod();
        originalBacklashY = y.getBacklashCompensationMethod();
        originalAccelerationX = x.getAccelerationPerSecond2();
        originalAccelerationY = y.getAccelerationPerSecond2();
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getNozzleTip() instanceof ReferenceNozzleTip) {
                    ReferenceNozzleTip tip = (ReferenceNozzleTip) nozzle.getNozzleTip();
                    StringWriter backup = new StringWriter();
                    Configuration.createSerializer().write(tip.getCalibration(), backup);
                    toolBackups.put(tip, backup.toString());
                }
            }
        }
        started = true;
        startedAt = System.nanoTime();
        camera.actuateLightBeforeCapture();
        // Recording five points can leave the camera over the last marker, rather than the datum.
        if (settings.fixedMarkers != null) {
            MovableUtils.moveToLocationAtSafeZ(camera, homeViewingLocation(settings), .35 * machine.getSpeed());
            camera.waitForCompletion(CompletionType.WaitForStillstand);
        }
        Location initial = detect(location(settings.originX, settings.originY, settings.planeZ));
        datumX = initial.getX() - settings.originX;
        datumY = initial.getY() - settings.originY;
        if (originalSettings != null || CoreXYRuntime.isLegacyEnabled() || originalPlannerCompensation) {
            PrecisionRuntime.setProductionGuards(null, null);
            for (CalibrationPlan.Path path : plan.finalCheck) {
                priorObservations.add(observe(path));
            }
        }
        machine.home();
        PrecisionRuntime.deactivate();
        legacySession = org.openpnp.corexy.LegacyCompensationInterlock.beginCalibration(configuration);
        ToolPointRuntime.setModels(java.util.Collections.emptyMap());
        x.setBacklashCompensationMethod(BacklashCompensationMethod.None);
        y.setBacklashCompensationMethod(BacklashCompensationMethod.None);
        MovableUtils.moveToLocationAtSafeZ(camera, settings.fixedMarkers == null
                ? location(settings.originX, settings.originY, settings.planeZ) : homeViewingLocation(settings), .35 * machine.getSpeed());
        camera.waitForCompletion(CompletionType.WaitForStillstand);
    }

    @Override
    public void validatePlan(CalibrationPlan plan) throws Exception {
        CalibrationRequest settings = request;
        for (ReferenceControllerAxis axis : new ReferenceControllerAxis[] {x, y}) {
            if (!axis.isSoftLimitLowEnabled() || !axis.isSoftLimitHighEnabled()
                    || axis.getSoftLimitLow().getValue() >= axis.getSoftLimitHigh().getValue()) {
                throw new IllegalStateException("自动校准需要已验证的 X/Y 双向软限位。");
            }
        }
        List<CalibrationPlan.Path> paths = new ArrayList<>(plan.training);
        paths.addAll(plan.validation);
        paths.addAll(plan.finalCheck);
        for (CalibrationPlan.Path planned : paths) {
            CalibrationPlan.Path path = safeFixedPath(planned);
            for (double[] point : path.points(settings.conditioningTravel)) {
                Location target = location(point[0], point[1], settings.planeZ);
                boolean fixedHome = settings.fixedMarkers != null
                        && Math.hypot(path.x - settings.originX, path.y - settings.originY) < 1e-8
                        && (path.homeStart || path.direct || (path.a == 0 && path.b == 0));
                if (fixedHome) { target = homeViewingLocation(settings); }
                AxesLocation raw = camera.toRaw(camera.toHeadLocation(target));
                if (!camera.isReachable(target) || !planner.isValidLocation(camera, raw)) {
                    throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                            "校准路径超出软限位：%s；目标 X=%.3f Y=%.3f mm，原始轴 X=%.3f Y=%.3f mm。",
                            path.id, target.getX(), target.getY(), raw.getCoordinate(x), raw.getCoordinate(y)));
                }
                for (ReferenceControllerAxis axis : new ReferenceControllerAxis[] {x, y}) {
                    double coordinate = raw.getCoordinate(axis);
                    double margin = fixedHome ? 0 : settings.maximumOffset + 1;
                    if (coordinate < axis.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue() + margin
                            || coordinate > axis.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue() - margin) {
                        throw new IllegalStateException("校准轨迹缺少补偿安全余量，请缩小范围或换向预行程。");
                    }
                }
            }
        }
    }

    @Override
    public double conditioningLimit(CalibrationRequest settings) {
        double limit = settings.conditioningTravel;
        for (CalibrationPlan.Path path : fittedPaths.values()) {
            limit = Math.min(limit, path.conditioningTravel);
        }
        return limit;
    }

    /** Geometry only. The same fitted path is preflighted and executed, including startup checks. */
    CalibrationPlan.Path safeFixedPath(CalibrationPlan.Path path) throws Exception {
        if (request.fixedMarkers == null || path.homeStart || path.direct) {
            return path;
        }
        AxesLocation zero = camera.toRaw(camera.toHeadLocation(location(0, 0, request.planeZ)));
        double margin = request.maximumOffset + 1 + 1e-6;
        CalibrationPlan.Path fitted = FixedMarkerTravel.fit(path, request.conditioningTravel,
                x.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue() + margin - zero.getCoordinate(x),
                x.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue() - margin - zero.getCoordinate(x),
                y.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue() + margin - zero.getCoordinate(y),
                y.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue() - margin - zero.getCoordinate(y));
        if (fitted != path) {
            fittedPaths.put(path.id, fitted);
        }
        return fitted;
    }

    @Override
    public double measureNoise() throws Exception {
        List<Location> observations = new ArrayList<>();
        Location expected = expected(request.originX, request.originY);
        for (int i = 0; i < 30; i++) {
            checkCancelled();
            observations.add(detect(expected));
            Thread.sleep(50);
        }
        double meanX = 0, meanY = 0;
        for (Location observation : observations) {
            meanX += observation.getX() / observations.size();
            meanY += observation.getY() / observations.size();
        }
        double sum = 0;
        for (Location observation : observations) {
            sum += Math.pow(observation.getX() - meanX, 2) + Math.pow(observation.getY() - meanY, 2);
        }
        Location scale = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        noise = Math.max(Math.sqrt(sum / (observations.size() * 2)),
                Math.max(Math.abs(scale.getX()), Math.abs(scale.getY())) * .1);
        return noise;
    }

    @Override
    public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
        checkCancelled();
        path = safeFixedPath(path);
        if (path.homeStart) {
            machine.home();
            if (request.fixedMarkers != null) {
                alignCalibrationDatum();
            }
        }
        if (!machine.isEnabled() || !planner.isHomed()) {
            throw new IllegalStateException("机器已停止或失去 Home，终止采样。");
        }
        if (!verificationOnly) {
            x.setAccelerationPerSecond2(originalAccelerationX.multiply(path.accelerationFactor));
            y.setAccelerationPerSecond2(originalAccelerationY.multiply(path.accelerationFactor));
        }
        double[][] points = path.points(request.conditioningTravel);
        AxesLocation start = new AxesLocation(machine);
        double effectiveSpeed = path.speed * machine.getSpeed();
        boolean fixedHome = request.fixedMarkers != null && (path.homeStart || path.direct)
                && Math.hypot(path.x - request.originX, path.y - request.originY) < 1e-8;
        Location measuredPose = fixedHome ? homeViewingLocation(request)
                : location(points[0][0], points[0][1], request.planeZ);
        MovableUtils.moveToLocationAtSafeZ(camera, measuredPose, effectiveSpeed);
        // Flush conditioning moves separately so blending cannot erase the direction reversal.
        camera.waitForCompletion(CompletionType.WaitForStillstand);
        if (!path.homeStart && !path.direct) {
            camera.moveTo(location(points[1][0], points[1][1], request.planeZ), effectiveSpeed);
            camera.waitForCompletion(CompletionType.WaitForStillstand);
            for (int i = 2; i < points.length; i++) {
                checkCancelled();
                camera.moveTo(location(points[i][0], points[i][1], request.planeZ), effectiveSpeed);
            }
        }
        camera.waitForCompletion(CompletionType.WaitForStillstand);
        if ((!PrecisionRuntime.isActive() || ModelReferencedDatum.isRawStage()) && request.settleMillis > 0) {
            Thread.sleep(request.settleMillis);
        }
        if (verificationOnly) {
            // Separate stationary reads so the Home noise check does not repeatedly sample a cached video frame.
            Thread.sleep(50);
        }
        Location expected = expected(path.x, path.y);
        Location measured = detect(expected);
        AxesLocation rawExpected = camera.toRaw(camera.toHeadLocation(expected));
        AxesLocation rawMeasured = camera.toRaw(camera.toHeadLocation(measured));
        AxesLocation rawTarget = camera.toRaw(camera.toHeadLocation(fixedHome ? measuredPose
                : location(path.x, path.y, request.planeZ)));
        double dx = rawTarget.getCoordinate(x) - start.getCoordinate(x);
        double dy = rawTarget.getCoordinate(y) - start.getCoordinate(y);
        double temperature = readTemperature(request.temperatureActuator);
        if (Double.isFinite(temperature)) {
            PrecisionRuntime.setTemperature(temperature);
        }
        return new PrecisionSample(path.id, camera.getId(), rawTarget.getCoordinate(x), rawTarget.getCoordinate(y),
                request.planeZ, 0, path.speed * machine.getSpeed(),
                x.getAccelerationPerSecond2().convertToUnits(LengthUnit.Millimeters).getValue(),
                temperature, (System.nanoTime() - startedAt) / 1e9,
                path.homeStart ? dx + dy : path.signA * path.a, path.homeStart ? dx - dy : path.signB * path.b,
                path.homeStart ? Math.abs(dx + dy) : path.a, path.homeStart ? Math.abs(dx - dy) : path.b,
                request.settleMillis, path.homeStart ? (int) Math.signum(dx + dy) : path.signA,
                path.homeStart ? (int) Math.signum(dx - dy) : path.signB,
                rawMeasured.getCoordinate(x) - rawExpected.getCoordinate(x),
                rawMeasured.getCoordinate(y) - rawExpected.getCoordinate(y));
    }

    @Override
    public void calibrateHomeState(PrecisionModel model) throws Exception {
        List<HomeStateFitter.Observation> observations = new ArrayList<>();
        for (int repetition = 0; repetition < 3; repetition++) {
            CalibrationPlan.Path path = new CalibrationPlan.Path("home-fitting-" + repetition,
                    request.originX, request.originY, 0, 0, .6, 1, 0, 0, 1, request.settleMillis);
            path.homeStart = true;
            PrecisionSample sample = observe(path);
            observations.add(new HomeStateFitter.Observation(sample, PrecisionRuntime.homeTrace(planner),
                    (sample.deltaA + sample.deltaB) / 2, (sample.deltaA - sample.deltaB) / 2));
        }
        new HomeStateFitter().fit(model, observations);
    }

    private Location detect(Location expected) throws Exception {
        java.awt.image.BufferedImage image = MarkerFrameCapture.capture(camera);
        org.openpnp.model.Point pixel = VisionUtils.getLocationPixels(camera, camera, expected);
        Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        double scale = Math.sqrt(Math.abs(upp.getX() * upp.getY()));
        if (!Double.isFinite(scale) || scale <= 0 || Math.abs(Math.abs(upp.getX() / upp.getY()) - 1) > .3) {
            throw new IllegalStateException("需要有效且接近等比例的相机像素标定。");
        }
        double search = request.fixedMarkers == null ? Math.max(request.markerDiameter * 2, request.maximumOffset * 3)
                : Math.max(request.markerDiameter * 2, request.fixedMarkers.homeSearchRadius);
        double radius = Math.min(Math.min(image.getWidth(), image.getHeight()) * .35, search / scale);
        try {
            MarkerDetector.Detection detection = detector.detect(image, pixel.getX(), pixel.getY(),
                    request.markerDiameter / scale, radius);
            return VisionUtils.getPixelLocation(camera, camera, detection.x, detection.y).convertToUnits(LengthUnit.Millimeters);
        }
        catch (MarkerDetectionException failure) {
            // Preserve the exact rejected frame, before rollback homes and changes the camera view.
            try {
                Path directory = CoreXYIntegration.store(configuration).getRoot().resolve("diagnostics")
                        .resolve("marker-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
                Files.createDirectories(directory);
                javax.imageio.ImageIO.write(image, "png", directory.resolve("frame.png").toFile());
                Properties detail = new Properties();
                detail.setProperty("expected.location", expected.toString());
                detail.setProperty("camera.location", camera.getLocation().toString());
                detail.setProperty("expected.pixel", pixel.toString());
                detail.setProperty("expected.diameter.pixels", Double.toString(request.markerDiameter / scale));
                detail.setProperty("search.radius.pixels", Double.toString(radius));
                detail.setProperty("units.per.pixel", upp.toString());
                detail.setProperty("legacy.enabled", Boolean.toString(CoreXYRuntime.isLegacyEnabled()));
                detail.setProperty("precision.enabled", Boolean.toString(PrecisionRuntime.isActive()));
                detail.setProperty("failure", failure.getMessage());
                try (java.io.Writer writer = Files.newBufferedWriter(directory.resolve("capture.properties"), StandardCharsets.UTF_8)) {
                    detail.store(writer, "Rejected marker frame");
                }
                throw new MarkerDetectionException(failure.getMessage() + " [" + directory + "]", failure);
            }
            catch (java.io.IOException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
                throw failure;
            }
        }
    }

    @Override
    public void apply(PrecisionModel model, int settling) throws Exception {
        machine.home();
        PrecisionRuntime.deactivate();
        if (model != null) {
            // Runtime and calibration must use the same production acceleration settings.
            x.setAccelerationPerSecond2(originalAccelerationX);
            y.setAccelerationPerSecond2(originalAccelerationY);
            PrecisionRuntime.activate(planner, model, x.getId(), y.getId(), settling, .25, 1);
            if (model.getLayers().contains(PrecisionModel.Layer.THERMAL_DRIFT)) {
                PrecisionRuntime.setTemperatureSource(() -> readTemperature(request.temperatureActuator));
                PrecisionRuntime.setTemperature(readTemperature(request.temperatureActuator));
            }
        }
        if (request.fixedMarkers != null) {
            alignCalibrationDatum();
        }
    }

    @Override
    public List<String> optimizeControllerAndTools(CalibrationRequest settings) throws Exception {
        checkCancelled();
        List<String> notes = new ArrayList<>();
        if (settings.optimizeShaping) {
            if (x.getDriver() instanceof GcodeDriver
                    && ((GcodeDriver) x.getDriver()).getFirmwareConfiguration().toLowerCase(java.util.Locale.ROOT).contains("klipper")) {
                try {
                    camera.moveToSafeZ();
                    camera.waitForCompletion(CompletionType.WaitForStillstand);
                    shaping = new ControllerShaping().calibrate(transport());
                    notes.add("控制器加速度计校准已完成；输入整形还需通过最终端点验证。不重启固件。");
                }
                catch (UnsupportedOperationException unavailable) {
                    notes.add("输入整形不可用：" + unavailable.getMessage());
                }
                finally {
                    // Firmware resonance tests can move independently of the host coordinates.
                    machine.home();
                }
            }
            else {
                notes.add("输入整形不可用：当前控制器没有受支持的 Klipper 共振校准接口。");
            }
        }
        if (settings.calibrateTools) {
            calibrateTools(notes);
        }
        // Assess delay with an identical approach each time, using raw camera frames.
        FixedMarkerSetup.Marker settlingMarker = settings.fixedMarkers == null ? null
                : settings.fixedMarkers.markers.get(settings.fixedMarkers.homeIndex == 0 ? 1 : 0);
        CalibrationPlan.Path probe = new CalibrationPlan.Path("settling",
                settlingMarker == null ? settings.originX : settlingMarker.x,
                settlingMarker == null ? settings.originY : settlingMarker.y,
                24, 18, .8, 1, 1, -1, 1, settings.settleMillis);
        if (settlingMarker != null) { probe.markerId = settlingMarker.id; }
        validatePlan(new CalibrationPlan(java.util.Collections.singletonList(probe)));
        settings.settleMillis = new SettlingOptimizer().select(delay -> {
            settings.settleMillis = delay;
            PrecisionSample sample = observe(probe);
            return new double[] {sample.correctionX, sample.correctionY};
        }, noise, settings.target);
        notes.add("自动选择等待时间：" + settings.settleMillis + " ms；使用原始图像，不包含相机内置 settle 延迟。");
        notes.add("公共偏置相对于本次 marker 基准；未使用外部绝对长度基准时，不代表绝对坐标精度。");
        if (settings.spatial && settings.fixedMarkers == null) {
            registerBoard(notes);
        }
        if (settings.fixedMarkers != null) {
            notes.add("使用机器 Home 与四个临时覆盖点；校准后覆盖点可移除，完整验证时按保存位置放回。Home 基准需保留。");
            notes.add("5 点只能评估覆盖区的低阶空间误差；不能证明未采样位置的高频误差已消除。");
        }
        return notes;
    }

    private void registerBoard(List<String> notes) throws Exception {
        double angle = Math.toRadians(request.plateAngle), span = request.pitch * (request.gridSize - 1);
        List<CalibrationPlan.Path> paths = new ArrayList<>();
        for (double[] local : new double[][] {{0, 0}, {span, 0}, {0, span}}) {
            for (int a : new int[] {-1, 1}) {
                for (int b : new int[] {-1, 1}) {
                    paths.add(new CalibrationPlan.Path("plate-registration",
                            request.originX + local[0] * Math.cos(angle) - local[1] * Math.sin(angle),
                            request.originY + local[0] * Math.sin(angle) + local[1] * Math.cos(angle),
                            request.conditioningTravel, request.conditioningTravel, .5, .5, a, b, 1, request.settleMillis));
                }
            }
        }
        validatePlan(new CalibrationPlan(paths));
        double[][] measured = new double[3][2];
        for (int index = 0; index < paths.size(); index++) {
            CalibrationPlan.Path path = paths.get(index);
            PrecisionSample sample = observe(path);
            Location nominal = expected(path.x, path.y);
            measured[index / 4][0] += (nominal.getX() + sample.correctionX) / 4;
            measured[index / 4][1] += (nominal.getY() + sample.correctionY) / 4;
        }
        double hx = measured[1][0] - measured[0][0], hy = measured[1][1] - measured[0][1];
        double vx = measured[2][0] - measured[0][0], vy = measured[2][1] - measured[0][1];
        double correctedAngle = Math.atan2(hy - vx, hx + vy);
        double difference = Math.atan2(Math.sin(correctedAngle - angle), Math.cos(correctedAngle - angle));
        if (Math.abs(difference) > Math.toRadians(5)
                || Math.abs(Math.hypot(hx, hy) / span - 1) > .02
                || Math.abs(Math.hypot(vx, vy) / span - 1) > .02) {
            throw new IllegalStateException("标定板方向或点间距不匹配，请重新放置并确认设置。");
        }
        request.plateAngle = Math.toDegrees(correctedAngle);
        datumX = measured[0][0] - request.originX;
        datumY = measured[0][1] - request.originY;
        notes.add(String.format(java.util.Locale.ROOT, "已自动登记标定板旋转角 %.3f°；仅去除刚体摆放误差，不拟合比例和剪切。", request.plateAngle));
    }

    private void calibrateTools(List<String> notes) throws Exception {
        try {
            Camera lower = VisionUtils.getBottomVisionCamera();
            if (!(lower instanceof ReferenceCamera)) {
                notes.add("工具点校准不可用：缺少标准底部相机。");
                return;
            }
            bottom = (ReferenceCamera) lower;
        }
        catch (Exception absent) {
            notes.add("工具点校准不可用：" + absent.getMessage());
            return;
        }
        originalBottomOffsets = bottom.getHeadOffsets();
        originalBottomRotation = bottom.getRotation();
        boolean cameraAligned = false;
        for (Head head : machine.getHeads()) {
            for (Nozzle mounted : head.getNozzles()) {
                if (!(mounted instanceof ReferenceNozzle) || !(mounted.getNozzleTip() instanceof ReferenceNozzleTip)) {
                    notes.add("吸嘴 " + mounted.getName() + " 未装载标准吸嘴尖，跳过。");
                    continue;
                }
                ReferenceNozzle nozzle = (ReferenceNozzle) mounted;
                checkCancelled();
                ReferenceNozzleTip tip = (ReferenceNozzleTip) mounted.getNozzleTip();
                ReferenceNozzleTipCalibration calibration = tip.getCalibration();
                if (!calibration.isEnabled()) {
                    notes.add("吸嘴 " + mounted.getName() + " 未启用并配置视觉校准，跳过。");
                    continue;
                }
                StringWriter xml = new StringWriter();
                Configuration.createSerializer().write(calibration, xml);
                toolBackups.putIfAbsent(tip, xml.toString());
                calibration.calibrate(nozzle);
                if (!calibration.isCalibrated(nozzle)) {
                    throw new IllegalStateException("吸嘴校准没有生成有效模型：" + nozzle.getName());
                }
                if (!cameraAligned) {
                    calibration.calibrateCamera(nozzle);
                    cameraAligned = true;
                }
                notes.add("吸嘴 " + nozzle.getName() + " 已校准旋转偏心和相机工具偏置；沿用原生校准，避免重复叠加。");
                calibrateToolHeight(nozzle, tip, notes);
            }
        }
    }

    private void calibrateToolHeight(ReferenceNozzle nozzle, ReferenceNozzleTip tip, List<String> notes) throws Exception {
        if (!bottom.isUnitsPerPixelAtZCalibrated()) {
            notes.add("吸嘴 " + nozzle.getName() + " 的多高度校准不可用：底部相机还没有两高度像素标定。");
            return;
        }
        ReferenceNozzleTipCalibration calibration = tip.getCalibration();
        Location base = calibration.getCalibrationLocation(bottom, null).convertToUnits(LengthUnit.Millimeters);
        for (double z : new double[] {base.getZ() - request.toolHeightSpan / 2, base.getZ() + request.toolHeightSpan / 2}) {
            if (!nozzle.isReachable(base.derive(null, null, z, null))) {
                throw new IllegalStateException("吸嘴多高度校准超出 Z 软限位：" + nozzle.getName());
            }
        }
        java.lang.reflect.Method findCircle = ReferenceNozzleTipCalibration.class
                .getDeclaredMethod("findCircle", ReferenceNozzle.class, Location.class, boolean.class);
        findCircle.setAccessible(true);
        ToolPointModel model = new ToolPointOptimizer().calibrate(new ToolPointOptimizer.Backend() {
            @Override
            public double noiseFloor() {
                Location scale = bottom.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
                return .1 * Math.max(Math.abs(scale.getX()), Math.abs(scale.getY()));
            }
            @Override
            public ToolPointModel.Observation observe(double z, double angle) throws Exception {
                return measure(z, angle, false);
            }
            @Override
            public ToolPointModel.Observation observeRaw(double z, double angle) throws Exception {
                return measure(z, angle, true);
            }
            private ToolPointModel.Observation measure(double z, double angle, boolean raw) throws Exception {
                checkCancelled();
                Location target = base.derive(null, null, z, angle);
                MovableUtils.moveToLocationAtSafeZ(nozzle, target, .35);
                if (raw) {
                    planner.moveTo(nozzle, nozzle.toRaw(nozzle.toHeadLocation(target,
                            org.openpnp.spi.Locatable.LocationOption.SuppressDynamicCompensation)), .35);
                }
                nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                Location measured;
                try {
                    measured = (Location) findCircle.invoke(calibration, nozzle, target, false);
                }
                catch (java.lang.reflect.InvocationTargetException failure) {
                    throw new Exception("吸嘴视觉测量失败", failure.getCause());
                }
                if (measured == null) {
                    throw new IllegalStateException("吸嘴视觉没有唯一有效目标。");
                }
                measured = measured.convertToUnits(LengthUnit.Millimeters);
                Location normalScale = bottom.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
                Location heightScale = bottom.getUnitsPerPixel(new Length(z, LengthUnit.Millimeters)).convertToUnits(LengthUnit.Millimeters);
                Location expected = target.subtract(bottom.getLocation().convertToUnits(LengthUnit.Millimeters));
                return new ToolPointModel.Observation(z, angle,
                        measured.getX() * heightScale.getX() / normalScale.getX() - expected.getX(),
                        measured.getY() * heightScale.getY() / normalScale.getY() - expected.getY());
            }
            @Override
            public void apply(ToolPointModel candidate) {
                if (candidate == null) {
                    calibratedTools.remove(nozzle.getId());
                }
                else {
                    candidate.nativeSignature = ToolPointRuntime.nativeSignature(nozzle);
                    calibratedTools.put(nozzle.getId(), candidate);
                }
                ToolPointRuntime.setModels(calibratedTools);
            }
        }, nozzle.getId(), tip.getId(), base.getZ(), request.toolHeightSpan, request.target, request.maximumOffset);
        notes.add(model == null ? "吸嘴 " + nozzle.getName() + " 的附加高度模型没有改善独立测量，保持原生补偿。"
                : "吸嘴 " + nozzle.getName() + " 的多高度/角度模型已通过第三组角度与高度实测；超出 Z 范围时使用边界值。");
    }

    public ControllerShaping.Transport transport() {
        return (command, timeout) -> {
            GcodeDriver driver = (GcodeDriver) x.getDriver();
            driver.receiveResponses();
            driver.sendCommand(command, timeout);
            StringBuilder response = new StringBuilder();
            for (GcodeDriver.Line line : driver.receiveResponses()) {
                response.append(line.getLine()).append('\n');
            }
            return response.toString();
        };
    }

    public double readTemperature(String id) throws Exception {
        if (id == null || id.isEmpty()) {
            return Double.NaN;
        }
        Actuator actuator = machine.getActuator(id);
        if (actuator == null) {
            for (Head head : machine.getHeads()) {
                actuator = head.getActuator(id);
                if (actuator != null) {
                    break;
                }
            }
        }
        if (actuator == null) {
            throw new IllegalStateException("温度传感器不存在：" + id);
        }
        double value = Double.parseDouble(actuator.read().trim());
        if (!Double.isFinite(value) || value < -50 || value > 150) {
            throw new IllegalStateException("温度读取无效。");
        }
        return value;
    }

    @Override
    public void finish(boolean keep, PrecisionModel model, int settling) throws Exception {
        if (!started) {
            return;
        }
        Cleanup cleanup = new Cleanup();
        x.setAccelerationPerSecond2(originalAccelerationX);
        y.setAccelerationPerSecond2(originalAccelerationY);
        if (keep && model != null) {
            try {
                if (legacySession != null) {
                    legacySession.commit();
                }
                else {
                    Properties properties = CoreXYIntegration.loadProperties(legacy);
                    properties.setProperty("enabled", "false");
                    properties.setProperty("dry-run", "false");
                    CoreXYIntegration.storeProperties(legacy, properties);
                    CoreXYAgent.rememberConfiguration(legacy.toString());
                }
            }
            catch (Exception failedCommit) {
                cleanup.failure = failedCommit;
                keep = false;
            }
        }
        if (!keep) {
            cleanup.run(() -> {
                try {
                if (machine.isEnabled()) {
                    machine.home();
                }
                else {
                    planner.unhome();
                }
                }
                catch (Exception failure) {
                    planner.unhome();
                    throw failure;
                }
            });
            cleanup.run(PrecisionRuntime::deactivate);
            x.setBacklashCompensationMethod(originalBacklashX);
            y.setBacklashCompensationMethod(originalBacklashY);
            if (legacySession == null && planner instanceof org.openpnp.corexy.CoreXYAdvancedMotionPlanner) {
                ((org.openpnp.corexy.CoreXYAdvancedMotionPlanner) planner).setCoreXYCompensationEnabled(originalPlannerCompensation);
            }
            cleanup.run(this::restoreTools);
            cleanup.run(() -> ToolPointRuntime.setModels(originalToolModels));
            if (shaping != null && machine.isEnabled()) {
                cleanup.run(() -> transport().query(shaping.previous, 10000));
            }
            if (legacySession != null) {
                cleanup.run(legacySession::rollback);
            }
            else {
                cleanup.run(() -> PrecisionStore.atomicWriteBytes(legacy, originalLegacyBytes));
                cleanup.run(() -> CoreXYAgent.install(CoreXYAgent.getStartupInstrumentation(), legacy.toString()));
            }
            cleanup.run(() -> PrecisionRuntime.restore(originalSettings));
            started = false;
        }
        cleanup.run(() -> {
            camera.actuateLightAfterCapture();
            if (machine.isEnabled()) {
                camera.moveToSafeZ();
                camera.waitForCompletion(CompletionType.WaitForStillstand);
            }
        });
        if (disabledConfig != null) {
            cleanup.run(() -> Files.deleteIfExists(disabledConfig));
        }
        if (keep && cleanup.failure != null) {
            cleanup.run(() -> finish(false, null, settling));
        }
        cleanup.check();
    }

    private static final class Cleanup {
        interface Action {
            void run() throws Exception;
        }
        Exception failure;
        void run(Action action) {
            try {
                action.run();
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
        void check() throws Exception {
            if (failure != null) {
                throw new IllegalStateException("恢复或保存有步骤失败；其余恢复步骤已执行，请检查配置并重新 Home。", failure);
            }
        }
    }

    public PrecisionProfile profile(CalibrationController.Report report) throws Exception {
        PrecisionProfile profile = new PrecisionProfile();
        profile.createdAt = report.createdAt;
        profile.fingerprint = fingerprint();
        profile.axisX = x.getId();
        profile.axisY = y.getId();
        profile.previousBacklashX = previousProfile == null ? originalBacklashX.name() : previousProfile.previousBacklashX;
        profile.previousBacklashY = previousProfile == null ? originalBacklashY.name() : previousProfile.previousBacklashY;
        profile.model = report.chosen;
        profile.noise = report.noise;
        profile.target = request.target;
        profile.baseline = report.baselineFinal;
        profile.corrected = report.correctedFinal;
        profile.settlingMillis = report.settlingMillis;
        profile.temperatureActuator = request.temperatureActuator;
        profile.controllerRestoreCommand = shaping == null ? previousProfile == null ? "" : previousProfile.controllerRestoreCommand
                : previousProfile != null && !previousProfile.controllerRestoreCommand.isEmpty()
                    ? previousProfile.controllerRestoreCommand : shaping.previous;
        profile.controllerApplyCommand = shaping == null ? previousProfile == null ? "" : previousProfile.controllerApplyCommand : shaping.calibrated;
        profile.toolModels = new LinkedHashMap<>(calibratedTools);
        profile.fixedMarkers = request.fixedMarkers == null ? null : request.fixedMarkers.copy();
        profile.referenceOffsetX = datumX;
        profile.referenceOffsetY = datumY;
        profile.validate(profile.fingerprint);
        return profile;
    }

    private void restoreTools() throws Exception {
        Field field = ReferenceNozzleTip.class.getDeclaredField("calibration");
        field.setAccessible(true);
        Cleanup cleanup = new Cleanup();
        for (Map.Entry<ReferenceNozzleTip, String> backup : toolBackups.entrySet()) {
            cleanup.run(() -> field.set(backup.getKey(), Configuration.createSerializer()
                    .read(ReferenceNozzleTipCalibration.class, backup.getValue())));
        }
        if (bottom != null && originalBottomOffsets != null) {
            cleanup.run(() -> bottom.setHeadOffsets(originalBottomOffsets));
            cleanup.run(() -> bottom.setRotation(originalBottomRotation));
        }
        cleanup.check();
    }

    private Location location(double px, double py, double pz) {
        return new Location(LengthUnit.Millimeters, px, py, pz, 0);
    }

    /** A datum at the travel boundary can be observed off-center, without aiming beyond the end stop. */
    private Location homeViewingLocation(CalibrationRequest settings) throws Exception {
        Location nominal = location(settings.originX, settings.originY, settings.planeZ);
        AxesLocation raw = camera.toRaw(camera.toHeadLocation(nominal));
        double reserve = settings.maximumOffset + Math.max(1, settings.fixedMarkers.maximumHomeOffset);
        AxesLocation safe = new AxesLocation();
        for (ReferenceControllerAxis axis : new ReferenceControllerAxis[] {x, y}) {
            double low = axis.getSoftLimitLow().convertToUnits(LengthUnit.Millimeters).getValue() + reserve;
            double high = axis.getSoftLimitHigh().convertToUnits(LengthUnit.Millimeters).getValue() - reserve;
            if (!axis.isSoftLimitLowEnabled() || !axis.isSoftLimitHighEnabled() || low > high) {
                throw new IllegalStateException("Home marker 拍摄需要有效的双向软限位及漂移/补偿余量。");
            }
            safe = safe.add(new AxesLocation(axis, Math.max(low, Math.min(high, raw.getCoordinate(axis)))));
        }
        AxesLocation adjusted = raw.put(safe);
        Location view = camera.toHeadMountableLocation(camera.toTransformed(adjusted)).convertToUnits(LengthUnit.Millimeters);
        view = view.derive(null, null, settings.planeZ, 0.0);
        Location upp = camera.getUnitsPerPixel(new Length(settings.planeZ, LengthUnit.Millimeters))
                .convertToUnits(LengthUnit.Millimeters);
        double scaleX = Math.abs(upp.getX()), scaleY = Math.abs(upp.getY());
        double width = camera.getWidth(), height = camera.getHeight();
        double clearance = settings.fixedMarkers.maximumHomeOffset + settings.maximumOffset + settings.markerDiameter * .65;
        double centerX = width / 2 + (settings.originX + datumX - view.getX()) / upp.getX();
        double centerY = height / 2 - (settings.originY + datumY - view.getY()) / upp.getY();
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || scaleX <= 0 || scaleY <= 0
                || settings.markerDiameter / Math.sqrt(scaleX * scaleY) < 8
                || centerX < clearance / scaleX + 3 || centerX > width - clearance / scaleX - 3
                || centerY < clearance / scaleY + 3 || centerY > height - clearance / scaleY - 3) {
            throw new IllegalStateException("Home marker 在安全拍摄位置的视野不足，请增大视野、调整 marker 或降低已确认的 Home 偏移上限。");
        }
        return view;
    }


    private Location expected(double px, double py) {
        if (request.fixedMarkers != null) {
            boolean known = false;
            for (FixedMarkerSetup.Marker marker : request.fixedMarkers.markers) {
                if (Math.hypot(marker.x - px, marker.y - py) < 1e-8) { known = true; }
            }
            if (!known) { throw new IllegalArgumentException("固定 marker 采样目标不在已保存的 5 点中。"); }
            return location(px + datumX, py + datumY, request.planeZ);
        }
        double angle = Math.toRadians(request.plateAngle);
        double dx = px - request.originX, dy = py - request.originY;
        int column = (int) Math.round((dx * Math.cos(angle) + dy * Math.sin(angle)) / request.pitch);
        int row = (int) Math.round((-dx * Math.sin(angle) + dy * Math.cos(angle)) / request.pitch);
        double[] artifact = column >= 0 && column < request.gridSize && row >= 0 && row < request.gridSize
                ? request.artifactOffsets.get(Integer.toString(row * request.gridSize + column)) : null;
        return location(px + datumX + (artifact == null ? 0 : artifact[0] * Math.cos(angle) - artifact[1] * Math.sin(angle)),
                py + datumY + (artifact == null ? 0 : artifact[0] * Math.sin(angle) + artifact[1] * Math.cos(angle)), request.planeZ);
    }

    /** Read-only measurement setup, also usable before machine.setHomed(true) in the Home tail. */
    public void prepareVerification(PrecisionProfile profile) throws Exception {
        prepareVerification(profile, true);
    }

    public void prepareVerification(PrecisionProfile profile, boolean full) throws Exception {
        profile.validate(fingerprint());
        if (profile.fixedMarkers == null || !machine.isEnabled() || !planner.isHomed()) {
            throw new IllegalStateException("需要已连接、完成机械寻零并已保存的 5 marker 补偿方案。");
        }
        if (x.getDriver() instanceof org.openpnp.machine.reference.driver.NullDriver
                || camera.getClass().getSimpleName().startsWith("Simulated")) {
            throw new IllegalStateException("模拟驱动和相机不能进行生产启动校验。");
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    throw new IllegalStateException("请移除吸嘴上的元件，再执行 Home marker 校验。");
                }
            }
        }
        request = profile.fixedMarkers.toRequest();
        request.temperatureActuator = profile.temperatureActuator;
        request.settleMillis = profile.settlingMillis;
        datumX = profile.referenceOffsetX;
        datumY = profile.referenceOffsetY;
        noise = profile.noise;
        startedAt = System.nanoTime();
        verificationOnly = true;
        originalAccelerationX = x.getAccelerationPerSecond2();
        originalAccelerationY = y.getAccelerationPerSecond2();
        if (full) {
            validatePlan(new CalibrationPlan(profile.modelDatumMarkerId.isEmpty()
                    ? FixedMarkerVerifier.verificationPaths(profile.fixedMarkers, profile.settlingMillis)
                    : ModelReferencedDatum.verificationPaths(profile)));
        }
        else {
            Location view = homeViewingLocation(request);
            if (!camera.isReachable(view) || !planner.isValidLocation(camera, camera.toRaw(camera.toHeadLocation(view)))) {
                throw new IllegalStateException("Home 基准拍摄位置不可达。");
            }
        }
        camera.actuateLightBeforeCapture();
    }

    public void finishVerification() throws Exception {
        try {
            camera.waitForCompletion(CompletionType.WaitForStillstand);
        }
        finally {
            camera.actuateLightAfterCapture();
            verificationOnly = false;
        }
    }

    /** Inspect an existing marker at safe Z; never changes the calibrated point or compensation. */
    public void viewFixedMarker(FixedMarkerSetup setup, int index) throws Exception {
        setup.validate();
        if (!machine.isEnabled() || !machine.isHomed() || index < 0 || index >= setup.markers.size()) {
            throw new IllegalStateException("请先连接机器并完成 Home，再移动查看固定 marker。");
        }
        for (Head head : machine.getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    throw new IllegalStateException("请先移除吸嘴上的元件，再移动查看 marker。");
                }
            }
        }
        FixedMarkerSetup.Marker marker = setup.markers.get(index);
        Location target = index == setup.homeIndex ? homeViewingLocation(setup.toRequest())
                : location(marker.x, marker.y, setup.planeZ);
        AxesLocation raw = camera.toRaw(camera.toHeadLocation(target));
        if (!camera.isReachable(target) || !planner.isValidLocation(camera, raw)) {
            throw new IllegalStateException("marker 拍摄位置超出机器软限位。");
        }
        camera.actuateLightBeforeCapture();
        MovableUtils.moveToLocationAtSafeZ(camera, target, .35 * machine.getSpeed());
        camera.waitForCompletion(CompletionType.WaitForStillstand);
        // Keep illumination on while the operator inspects the live view.
    }

    public void subtractDatum(double measuredX, double measuredY) throws Exception {
        camera.waitForCompletion(CompletionType.WaitForStillstand);
        AxesLocation current = new AxesLocation(machine);
        AxesLocation coordinates = new AxesLocation(x, current.getCoordinate(x) - measuredX)
                .add(new AxesLocation(y, current.getCoordinate(y) - measuredY));
        planner.setGlobalOffsets(coordinates);
        camera.waitForCompletion(CompletionType.WaitForStillstand);
    }

    private void alignCalibrationDatum() throws Exception {
        PrecisionProfile provisional = new PrecisionProfile();
        provisional.fixedMarkers = request.fixedMarkers;
        // Preparation can re-home before measureNoise() runs. Use the same optical
        // resolution floor here so that this stage does not demand fictitious precision.
        Location scale = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        provisional.noise = Math.max(noise, Math.max(Math.abs(scale.getX()), Math.abs(scale.getY())) * .1);
        provisional.target = request.target;
        provisional.settlingMillis = request.settleMillis;
        FixedMarkerVerifier.Backend adapter = new FixedMarkerVerifier.Backend() {
            @Override public PrecisionSample observe(CalibrationPlan.Path path) throws Exception {
                return OpenPnpCalibrationMachine.this.observe(path);
            }
            @Override public void subtractDatum(double dx, double dy) throws Exception {
                OpenPnpCalibrationMachine.this.subtractDatum(dx, dy);
            }
        };
        if (PrecisionRuntime.isActive()) {
            try (PrecisionRuntime.ReferenceAlignmentScope scope = PrecisionRuntime.referenceAlignment(planner)) {
                new FixedMarkerVerifier().alignHome(adapter, provisional, new StartupVerificationReport(),
                        (p, message) -> {}, cancellation);
            }
        }
        else {
            new FixedMarkerVerifier().alignHome(adapter, provisional, new StartupVerificationReport(),
                    (p, message) -> {}, cancellation);
        }
    }
}
