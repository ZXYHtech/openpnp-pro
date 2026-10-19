package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.*;
import org.openpnp.spi.*;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.*;

/**
 * Job-wide qualification, performed on the machine thread before ordinary placement alignment.
 */
public final class FlyingVisionEvaluation {

    public enum Status {
        PENDING ,
        READY ,
        RUNNING ,
        PASSED ,
        NO_GAIN ,
        FAILED ,
        BLOCKED ;
    }

    public static final class Entry {
        public String key;
        public String partId;
        public String packageId;
        public String nozzleId;
        public String nozzleName;
        public String tipId;
        public String signature;
        public List<String> partIds = new ArrayList<>();
        public String reason = "尚未验证";
        public String next = "准备参考后，随正常贴片自动验证";
        public Status status = Status.PENDING;
        public boolean selected = true;
        public boolean enabled;
        public boolean managed;
        public int remaining;
        public int trials;
        public double z;
        public double angle;
        public double maxPositionMm;
        public double maxAngleDeg;
        public double driftMm;
        public double staticMs;
        public double flyingMs;
        public long testedAt;
        transient Part part;
        transient Nozzle nozzle;
        transient BoardLocation board;
        transient Placement placement;
    }

    public static final class Policy {
        public int repeats = 3;
        public double maxPositionMm = 0.05;
        public double maxAngleDeg = 0.5;
        public double maxDriftMm = 0.03;
        public double minimumSavingPercent = 5;
        public boolean autoEnable = true;
    }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Entry> entries = new LinkedHashMap<>();
    private static boolean loaded;
    private static volatile boolean armed;
    private static volatile boolean cancelled;
    private static Policy policy = new Policy();
    private static final Map<String, String> lastModes = new LinkedHashMap<>();
    private static volatile String progress = "按封装选择一个代表器件验证，同条件料号共享结果。";

    private FlyingVisionEvaluation() {
    }

    private static Path file() {
        return Configuration.get().getConfigurationDirectory().toPath().resolve("pro-flying-evaluation.json");
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(file())) {
                for (Entry row : JSON.fromJson(Files.readString(file()), Entry[].class)) {
                    if (row.key == null || row.status == null) {
                        continue;
                    }
                    if (row.status == Status.READY || row.status == Status.RUNNING) {
                        row.status = Status.PENDING;
                        row.reason = "上次评估已结束，需重新开始";
                    }
                    entries.put(row.key, row);
                }
            }
        } catch (Exception failure) {
            progress = "评估记录无法读取，保持普通视觉：" + failure.getMessage();
        }
    }

    private static synchronized void save() throws Exception {
        Path temp = Files.createTempFile(file().getParent(), "flying-evaluation-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(entries.values()));
            Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String key(String packageId, String nozzle, String tip, double z, double angle) {
        return packageId + "|" + nozzle + "|" + tip + "|" + Math.round(z * 1000) + "|" + Math.round(Utils2D.angleNorm(angle, 180));
    }

    private static double angle(ReferenceBottomVision vision, Part part, Nozzle nozzle, BoardLocation board, Placement placement) {
        return FlyingBottomVisionService.calculateWantedAngle(FlyingBottomVisionService.usesPreRotate(vision, vision.getInheritedVisionSettings(part)), board, placement, nozzle.getLocation().getRotation());
    }

    public static synchronized List<Entry> scan(Job job) throws Exception {
        load();
        if (armed) {
            throw new Exception("请先停止评估，再刷新任务清单。");
        }
        Map<String, Entry> found = new LinkedHashMap<>();
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
        if (job == null || vision == null) {
            throw new Exception("请先加载贴片任务并配置底部视觉。");
        }
        Camera camera = VisionUtils.getBottomVisionCamera();
        for (BoardLocation board : job.getBoardLocations()) {
            if (!board.isEnabled()) {
                continue;
            }
            for (Placement placement : board.getBoard().getPlacements()) {
                Part part = placement.getPart();
                if (!placement.isEnabled() || placement.getType() != Placement.Type.Placement || placement.getSide() != board.getGlobalSide() || part == null) {
                    continue;
                }
                for (Nozzle nozzle : Configuration.get().getMachine().getDefaultHead().getNozzles()) {
                    double a = angle(vision, part, nozzle, board, placement);
                    String tip = nozzle.getNozzleTip() == null ? "none" : nozzle.getNozzleTip().getId();
                    double z = part.getHeight().convertToUnits(LengthUnit.Millimeters).getValue();
                    String blocked = null;
                    if (!vision.isEnabled() || !vision.getInheritedVisionSettings(part).isEnabled()) {
                        blocked = "该器件的底部视觉已停用";
                    }
                    try {
                        if (part.isPartHeightUnknown()) {
                            throw new Exception("器件高度未知，请先填写高度");
                        }
                        z = vision.getCameraLocationAtPartHeight(part, camera, nozzle, a).convertToUnits(LengthUnit.Millimeters).getZ();
                    } catch (Exception failure) {
                        blocked = failure.getMessage();
                    }
                    if (nozzle.getNozzleTip() == null || !nozzle.getCompatibleNozzleTips(part).contains(nozzle.getNozzleTip())) {
                        blocked = "当前吸嘴尖不适合该器件";
                    }
                    String key = key(part.getPackage().getId(), nozzle.getId(), tip, z, a);
                    Entry row = found.get(key);
                    if (row == null) {
                        row = entries.getOrDefault(key, new Entry());
                        row.key = key;
                        row.partId = part.getId();
                        row.packageId = part.getPackage().getId();
                        row.nozzleId = nozzle.getId();
                        row.nozzleName = nozzle.getName();
                        row.tipId = tip;
                        row.z = z;
                        row.angle = a;
                        row.part = part;
                        row.nozzle = nozzle;
                        row.board = board;
                        row.placement = placement;
                        row.remaining = 0;
                        row.partIds = new ArrayList<>();
                        if (row.status == Status.PASSED && !Objects.equals(row.signature, fingerprint(row))) {
                            row.status = Status.PENDING;
                            row.enabled = false;
                            row.reason = "配置已变化，原结果失效";
                            row.next = "重新准备参考并评估";
                        }
                        if (blocked != null) {
                            row.status = Status.BLOCKED;
                            row.enabled = false;
                            row.reason = blocked;
                            row.next = "修正器件高度或更换合适吸嘴尖，然后刷新并重试";
                        } else if (row.status == Status.BLOCKED) {
                            row.status = Status.PENDING;
                            row.reason = "条件已刷新，可重新准备参考";
                            row.next = "选中本行，重新准备参考并评估";
                        }
                        found.put(key, row);
                    }
                    if (!row.partIds.contains(part.getId())) {
                        row.partIds.add(part.getId());
                    }
                    if (!job.retrievePlacedStatus(board, placement.getId())) {
                        if (row.remaining == 0) {
                            row.part = part;
                            row.partId = part.getId();
                            row.board = board;
                            row.placement = placement;
                        }
                        row.remaining++;
                    }
                }
            }
        }
        entries.putAll(found);
        save();
        return new ArrayList<>(found.values());
    }

    public static synchronized List<Entry> snapshot() {
        load();
        return new ArrayList<>(entries.values());
    }

    public static String progress() {
        return (FlyingVisionMotion.direction() == 1 ? "飞拍 +X · " : "飞拍 -X · ") + progress;
    }

    public static boolean isArmed() {
        return armed;
    }

    public static synchronized void recordMode(Nozzle nozzle, String mode) {
        lastModes.put(nozzle.getName(), mode);
    }

    public static synchronized String modeSummary() {
        List<String> modes = new ArrayList<>();
        lastModes.forEach((name,mode)->modes.add(name + "：" + mode));
        return modes.isEmpty() ? "运行任务后，这里显示每只吸嘴实际采用的视觉方式。" : String.join("    |    ", modes);
    }

    public static synchronized String ordinaryReason(Part part, BoardLocation board, Placement placement, Nozzle nozzle) {
        Entry row = match(part, board, placement, nozzle);
        if (row != null && row.managed) {
            return row.status == Status.PASSED ? "该角度、配置或启用状态不匹配" : row.reason;
        }
        if (!FlyingVisionSettings.getEnabledPartIds().contains(part.getId())) {
            return "该料号尚未通过飞拍验证";
        }
        return "使用普通视觉流程";
    }

    static String fingerprint(Entry row) throws Exception {
        ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
        Camera camera = VisionUtils.getBottomVisionCamera();
        BottomVisionSettings settings = vision.getInheritedVisionSettings(row.part);
        java.io.StringWriter footprint = new java.io.StringWriter();
        new org.simpleframework.xml.core.Persister().write(row.part.getPackage().getFootprint(), footprint);
        String raw = FlyingVisionRegistration.signature(row.nozzle, camera) + "|" + row.part.getHeight()
                + "|" + settings.getPipeline().toXmlStringPreservingRuntimeStageValues()
                + "|" + (settings.getPipelineParameterAssignments() == null ? "{}" : new TreeMap<>(settings.getPipelineParameterAssignments()))
                + "|" + settings.isEnabled() + "|" + vision.isEnabled()
                + "|" + settings.getVisionOffset() + "|" + settings.getPreRotateUsage() + "|" + vision.isPreRotate()
                + "|" + footprint + "|" + FlyingVisionSettings.getSpeed()
                + "|" + FlyingVisionSettings.getApproachDistanceMm() + "|" + FlyingVisionSettings.getExitDistanceMm()
                + "|" + FlyingVisionSettings.getFrameCount() + "|" + FlyingVisionSettings.getMinimumSharpness()
                + "|" + FlyingVisionMotion.direction()
                + "|" + SafetyWorkbenchStore.get().getRevision();
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 255));
        }
        return hex.toString();
    }

    private static void ready() throws Exception {
        Machine machine = Configuration.get().getMachine();
        if (!machine.isEnabled() || !machine.isHomed() || !SafetyWorkbenchStore.get().isCertified()) {
            throw new Exception("机器须已启用、复位，且三维包络已认证。");
        }
    }

    /**
     * Called only by a submitted machine task with empty nozzles. Does not pick or reset job status.
     */
    public static void prepareAndArm(List<Entry> selected, Policy requested, Consumer<String> update) throws Exception {
        ready();
        validatePolicy(requested);
        for (Nozzle nozzle : Configuration.get().getMachine().getDefaultHead().getNozzles()) {
            if (nozzle.getPart() != null) {
                throw new Exception("准备参考前请清空两只吸嘴，页面不会自动抛料。");
            }
        }
        synchronized (FlyingVisionEvaluation.class) {
            for (Entry row : entries.values()) {
                if (row.status == Status.READY) {
                    row.status = Status.PENDING;
                }
            }
        }
        cancelled = false;
        armed = false;
        policy = JSON.fromJson(JSON.toJson(requested), Policy.class);
        int count = 0;
        for (Entry row : selected) {
            if (cancelled) {
                break;
            }
            if (row.status == Status.BLOCKED || row.remaining == 0) {
                continue;
            }
            row.enabled = false;
            row.managed = true;
            try {
                ready();
                Camera camera = VisionUtils.getBottomVisionCamera();
                Location target = ReferenceBottomVision.getDefault().getCameraLocationAtPartHeight(row.part, camera, row.nozzle, row.angle);
                try {
                    FlyingVisionRegistration.load(row.nozzle, camera, target);
                } catch (Exception missing) {
                    FlyingVisionRegistration.calibrate(row.nozzle, row.part, row.angle, (message)->{
                        progress = message;
                        update.accept(message);
                    });
                }
                row.signature = fingerprint(row);
                row.status = Status.READY;
                row.reason = "已排队：下一颗匹配器件取料后验证";
                row.next = "继续当前任务，自动进行三轮静拍/飞拍对比";
                count++;
            } catch (Exception failure) {
                row.status = Status.BLOCKED;
                row.reason = failure.getMessage();
                row.next = "检查照明、吸嘴和包络，刷新当前任务后重试";
                throw failure;
            } finally {
                synchronized (FlyingVisionEvaluation.class) {
                    save();
                }
            }
            update.accept(row.nozzleName + " · " + row.partId + " · " + row.reason);
        }
        if (cancelled) {
            for (Entry row : selected) {
                if (row.status == Status.READY) {
                    row.status = Status.PENDING;
                }
            }
        }
        armed = !cancelled && count > 0;
        synchronized (FlyingVisionEvaluation.class) {
            save();
        }
        progress = armed ? "参考已准备，继续当前任务即可逐项评估；未通过项保持普通视觉。" : "未启动评估，请查看各行原因。";
        update.accept(progress);
    }

    static void validatePolicy(Policy p) {
        if (p == null || p.repeats < 3 || p.repeats > 10 || !Double.isFinite(p.maxPositionMm + p.maxAngleDeg + p.maxDriftMm + p.minimumSavingPercent) || p.maxPositionMm <= 0 || p.maxPositionMm > 0.1 || p.maxAngleDeg <= 0 || p.maxAngleDeg > 1 || p.maxDriftMm <= 0 || p.maxDriftMm > 0.05 || p.minimumSavingPercent < 0 || p.minimumSavingPercent > 90) {
            throw new IllegalArgumentException("验证参数超出范围。");
        }
    }

    public static synchronized void cancel() {
        cancelled = true;
        armed = false;
        for (Entry row : entries.values()) {
            if (row.status == Status.READY) {
                row.status = Status.PENDING;
                row.reason = "用户停止评估";
            }
        }
        progress = "已停止排队；正在执行的安全动作完成后退出评估，正常贴片仍使用有效视觉结果。";
        try {
            save();
        } catch (Exception failure) {
            progress = failure.getMessage();
        }
    }

    private static Entry match(Part part, BoardLocation board, Placement placement, Nozzle nozzle) {
        load();
        if (part == null || part.isPartHeightUnknown() || nozzle.getNozzleTip() == null) {
            return null;
        }
        try {
            ReferenceBottomVision vision = ReferenceBottomVision.getDefault();
            double a = angle(vision, part, nozzle, board, placement);
            double z = vision.getCameraLocationAtPartHeight(part, VisionUtils.getBottomVisionCamera(), nozzle, a).convertToUnits(LengthUnit.Millimeters).getZ();
            Entry row = entries.get(key(part.getPackage().getId(), nozzle.getId(), nozzle.getNozzleTip().getId(), z, a));
            if (row != null) {
                row.part = part;
                row.nozzle = nozzle;
                row.board = board;
                row.placement = placement;
            }
            return row;
        } catch (Exception failure) {
            return null;
        }
    }

    public static synchronized boolean pending(Part part, BoardLocation board, Placement placement, Nozzle nozzle) {
        Entry row = match(part, board, placement, nozzle);
        if (!armed || cancelled || row == null) {
            return false;
        }
        for (Entry candidate : entries.values()) {
            if (candidate.status == Status.READY && sameRepresentativeGroup(row, candidate)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean managed(Part part) {
        load();
        return part != null && part.getPackage() != null && entries.values().stream().anyMatch((r)->part.getPackage().getId().equals(r.packageId) && r.managed);
    }

    public static synchronized boolean enabled(Part part) {
        load();
        return part != null && part.getPackage() != null && entries.values().stream().anyMatch((r)->part.getPackage().getId().equals(r.packageId) && r.enabled && r.status == Status.PASSED);
    }

    public static synchronized boolean approved(Part part, BoardLocation board, Placement placement, Nozzle nozzle) {
        Entry row = match(part, board, placement, nozzle);
        try {
            return row != null && row.enabled && row.status == Status.PASSED && row.signature.equals(fingerprint(row));
        } catch (Exception stale) {
            return false;
        }
    }

    public static synchronized void setEnabled(List<Entry> selected, boolean value) throws Exception {
        for (Entry row : selected) {
            if (value && (row.status != Status.PASSED || !Objects.equals(row.signature, fingerprint(row)))) {
                throw new Exception(row.partId + " 尚未通过当前配置验证，请先重试评估。");
            }
        }
        for (Entry row : selected) {
            row.enabled = value;
            row.managed = true;
        }
        if (value) {
            FlyingVisionSettings.setEnabled(true);
            FlyingVisionSettings.setProductionEnabled(true);
        }
        save();
    }

    static double angularDifference(double first, double second) {
        return Math.abs(Utils2D.angleNorm(first - second, 180));
    }

    static void compare(Entry row, Location before, Location flying, Location after) throws Exception {
        Location b = before.convertToUnits(LengthUnit.Millimeters);
        Location f = flying.convertToUnits(LengthUnit.Millimeters);
        Location a = after.convertToUnits(LengthUnit.Millimeters);
        if (!Double.isFinite(b.getX() + b.getY() + b.getRotation() + f.getX() + f.getY() + f.getRotation() + a.getX() + a.getY() + a.getRotation())) {
            throw new Exception("视觉坐标不是有效数字");
        }
        row.maxPositionMm = Math.max(row.maxPositionMm, Math.max(b.getLinearDistanceTo(f), a.getLinearDistanceTo(f)));
        row.maxAngleDeg = Math.max(row.maxAngleDeg, Math.max(angularDifference(b.getRotation(), f.getRotation()), angularDifference(a.getRotation(), f.getRotation())));
        row.driftMm = Math.max(row.driftMm, b.getLinearDistanceTo(a));
        if (angularDifference(b.getRotation(), a.getRotation()) > 0.5) {
            throw new Exception("静拍前后角度变化，器件可能滑动");
        }
    }

    static void decide(Entry row, Policy p) {
        row.enabled = false;
        if (row.trials < p.repeats || !Double.isFinite(row.staticMs + row.flyingMs + row.maxPositionMm + row.maxAngleDeg + row.driftMm) || row.staticMs <= 0 || row.flyingMs <= 0 || row.maxPositionMm > p.maxPositionMm || row.maxAngleDeg > p.maxAngleDeg || row.driftMm > p.maxDriftMm) {
            row.status = Status.FAILED;
            row.reason = "位置、角度、静拍漂移或有效轮数未通过";
            row.next = "检查取料、照明、对焦和速度，再重新评估";
        } else if ((1 - row.flyingMs / row.staticMs) * 100 < p.minimumSavingPercent) {
            row.status = Status.NO_GAIN;
            row.reason = "精度对比通过，但相机局部耗时未改善";
            row.next = "保留快速静拍；提高有效帧率或调整飞拍参数后重试";
        } else {
            row.status = Status.PASSED;
            row.enabled = p.autoEnable;
            row.reason = "位置、角度、稳定性与局部耗时通过";
            row.next = row.enabled ? "已用于匹配的吸嘴、高度和角度，其余情况使用普通视觉" : "可点击启用通过项";
        }
    }

    private static final class StaticSample {
        PartAlignmentOffset offsets;
        double ms;
    }

    private static StaticSample stationary(Entry row, ReferenceBottomVision vision) throws Exception {
        Camera camera = VisionUtils.getBottomVisionCamera();
        Location target = vision.getCameraLocationAtPartHeight(row.part, camera, row.nozzle, row.angle);
        MovableUtils.moveToBottomVisionLocation(row.nozzle, target, Configuration.get().getMachine().getSpeed());
        row.nozzle.waitForCompletion(MotionPlanner.CompletionType.WaitForStillstand);
        long start = System.nanoTime();
        BufferedImage image = camera.lightSettleAndCapture();
        FlyingVisionRegistration.Model reference = FlyingVisionRegistration.load(row.nozzle, camera, target);
        FlyingVisionRegistration.RegisteredFrame registered = FlyingVisionRegistration.register(reference, image, camera);
        StaticSample sample = new StaticSample();
        sample.offsets = vision.analyzeExperimentalFrame(row.part, row.nozzle, camera, registered.image, row.angle, FlyingBottomVisionService.usesPreRotate(vision, vision.getInheritedVisionSettings(row.part)));
        sample.ms = (System.nanoTime() - start) / 1000000.0;
        return sample;
    }

    static boolean sameRepresentativeGroup(Entry first, Entry second) {
        return Objects.equals(first.packageId, second.packageId)
                && Objects.equals(first.nozzleId, second.nozzleId)
                && Objects.equals(first.tipId, second.tipId) && Math.abs(first.z - second.z) < .001;
    }

    /** Use the same held representative for every queued angle on this nozzle. */
    public static void evaluate(ReferenceBottomVision vision, Part part, BoardLocation board, Placement placement, Nozzle nozzle) throws Exception {
        Entry current;
        List<Entry> batch = new ArrayList<>();
        synchronized (FlyingVisionEvaluation.class) {
            current = match(part, board, placement, nozzle);
            if (!pending(part, board, placement, nozzle)) {
                return;
            }
            for (Entry candidate : entries.values()) {
                if (candidate.status == Status.READY && sameRepresentativeGroup(current, candidate)) {
                    batch.add(candidate);
                }
            }
        }
        for (Entry row : batch) {
            if (cancelled) {
                break;
            }
            row.part = part;
            row.partId = part.getId();
            row.nozzle = nozzle;
            Placement probe = new Placement("flying-package-evaluation");
            probe.setPart(part);
            probe.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, row.angle));
            evaluateOne(vision, part, null, probe, nozzle);
        }
    }

    /** The normal job performs its own final alignment; evaluation does not place parts. */
    private static void evaluateOne(ReferenceBottomVision vision, Part part, BoardLocation board, Placement placement, Nozzle nozzle) throws Exception {
        Entry row;
        synchronized (FlyingVisionEvaluation.class) {
            row = match(part, board, placement, nozzle);
            if (!pending(part, board, placement, nozzle)) {
                return;
            }
            row.status = Status.RUNNING;
            row.enabled = false;
        }
        row.trials = 0;
        row.maxPositionMm = row.maxAngleDeg = row.driftMm = row.staticMs = row.flyingMs = 0;
        try {
            ready();
            if (!Objects.equals(row.signature, fingerprint(row))) {
                throw new Exception("配置已变化，请重新准备参考");
            }
            for (int trial = 0; trial < policy.repeats; trial++) {
                if (cancelled) {
                    throw new Exception("用户停止评估");
                }
                progress = row.nozzleName + " · " + row.partId + " · 第 " + (trial + 1) + "/" + policy.repeats + " 轮：静拍 → 飞拍 → 静拍复核";
                StaticSample before = stationary(row, vision);
                FlyingBottomVisionService.Result flying = FlyingBottomVisionService.execute(vision, part, board, placement, nozzle);
                StaticSample after = stationary(row, vision);
                compare(row, before.offsets.getLocation(), flying.getOffsets().getLocation(), after.offsets.getLocation());
                row.staticMs += before.ms;
                row.flyingMs += flying.getElapsedMs();
                row.trials++;
                if (nozzle.isPartOnEnabled(Nozzle.PartOnStep.Align) && !nozzle.isPartOn()) {
                    throw new Exception("验证中器件真空检测未通过");
                }
                if (!Objects.equals(row.signature, fingerprint(row))) {
                    throw new Exception("验证期间配置变化");
                }
            }
            row.staticMs /= row.trials;
            row.flyingMs /= row.trials;
            decide(row, policy);
        } catch (Exception failure) {
            row.status = Status.FAILED;
            row.enabled = false;
            StringBuilder reason = new StringBuilder();
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                String message = cause.getMessage();
                if (message != null && reason.indexOf(message) < 0) {
                    if (reason.length() > 0) {
                        reason.append("；");
                    }
                    reason.append(message);
                }
            }
            row.reason = reason.length() == 0 ? failure.toString() : reason.toString();
            row.next = "评估异常按原流程暂停；重试任务使用普通视觉，检查后可重新评估";
            throw failure;
        } finally {
            row.testedAt = System.currentTimeMillis();
            synchronized (FlyingVisionEvaluation.class) {
                save();
                if (row.enabled) {
                    FlyingVisionSettings.setEnabled(true);
                    FlyingVisionSettings.setProductionEnabled(true);
                }
                if (entries.values().stream().noneMatch((r)->r.status == Status.READY)) {
                    armed = false;
                }
            }
            progress = row.nozzleName + " · " + row.partId + " · " + row.reason;
        }
    }
}
