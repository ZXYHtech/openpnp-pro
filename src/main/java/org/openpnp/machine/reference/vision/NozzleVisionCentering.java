package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.openpnp.corexy.precision.ToolPointRuntime;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTipCalibration;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.opencv.core.KeyPoint;
import org.opencv.core.RotatedRect;

/** Imaging-only center correction. Never changes head offsets or runout compensation. */
public final class NozzleVisionCentering {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path loadedFrom;
    private static State state;

    static final class State {
        int version = 1;
        Map<String, Entry> entries = new LinkedHashMap<>();
    }

    static final class Entry {
        String signature;
        double x, y;
        boolean enabled = true;
        Entry previous;
    }

    public static final class Progress {
        public final String message;
        public final BufferedImage image;
        public Progress(String message, BufferedImage image) {
            this.message = message;
            this.image = image;
        }
    }

    public static final class Report {
        public final String nozzle;
        public final Location before, after, correction;
        public final double repeatability;
        Report(String nozzle, Location before, Location after, Location correction, double repeatability) {
            this.nozzle = nozzle;
            this.before = before;
            this.after = after;
            this.correction = correction;
            this.repeatability = repeatability;
        }
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "%s：平均中心偏差 %.4f → %.4f mm；新增成像修正 X=%+.4f、Y=%+.4f mm；重复测量差 %.4f mm。",
                    nozzle, norm(before), norm(after), correction.getX(), correction.getY(), repeatability);
        }
    }

    private NozzleVisionCentering() { }

    private static String key(ReferenceNozzle nozzle, Camera camera) {
        return camera.getId() + "/" + nozzle.getHead().getId() + "/" + nozzle.getId() + "/"
                + (nozzle.getNozzleTip() == null ? "" : nozzle.getNozzleTip().getId());
    }

    private static String signature(ReferenceNozzle nozzle, Camera camera) {
        return nozzle.getHeadOffsets() + "|" + camera.getLocation() + "|" + camera.getUnitsPerPixel()
                + "|" + ToolPointRuntime.nativeSignature(nozzle) + "|"
                + GSON.toJson(ToolPointRuntime.snapshot().get(nozzle.getId()));
    }

    private static synchronized State state() throws Exception {
        Path file = Configuration.get().getConfigurationDirectory().toPath().resolve("pro-nozzle-vision-centering.json");
        if (!file.equals(loadedFrom)) {
            State next = Files.exists(file) ? GSON.fromJson(Files.readString(file), State.class) : new State();
            if (next == null || next.version != 1 || next.entries == null) {
                throw new Exception("吸嘴视觉调优文件格式无效，请恢复备份。");
            }
            for (Entry entry : next.entries.values()) {
                if (entry == null || !Double.isFinite(entry.x) || !Double.isFinite(entry.y)
                        || Math.hypot(entry.x, entry.y) > 3) {
                    throw new Exception("吸嘴视觉调优数据无效，请恢复备份。");
                }
            }
            state = next;
            loadedFrom = file;
        }
        return state;
    }

    private static synchronized void save() throws Exception {
        Path file = loadedFrom;
        Path temp = Files.createTempFile(file.getParent(), "nozzle-vision-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static synchronized Location offset(ReferenceNozzle nozzle, Camera camera) {
        try {
            if (nozzle.getNozzleTip() == null || camera.getHead() != null
                    || camera != VisionUtils.getBottomVisionCamera()) {
                return new Location(LengthUnit.Millimeters);
            }
            Entry entry = state().entries.get(key(nozzle, camera));
            if (entry != null && entry.enabled && signature(nozzle, camera).equals(entry.signature)) {
                return new Location(LengthUnit.Millimeters, entry.x, entry.y, 0, 0);
            }
            return new Location(LengthUnit.Millimeters);
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取吸嘴视觉调优数据；请检查独立调优文件。", failure);
        }
    }

    public static synchronized String status(ReferenceNozzle nozzle, Camera camera) throws Exception {
        Entry entry = state().entries.get(key(nozzle, camera));
        if (entry == null) {
            return "未调优";
        }
        return !signature(nozzle, camera).equals(entry.signature) ? "配置或原校准已改变，请重新调优"
                : String.format(java.util.Locale.ROOT, "%s · X=%+.4f Y=%+.4f mm",
                        entry.enabled ? "已启用" : "已停用", entry.x, entry.y);
    }

    public static synchronized void setEnabled(ReferenceNozzle nozzle, Camera camera, boolean enabled) throws Exception {
        Entry entry = state().entries.get(key(nozzle, camera));
        if (entry == null) {
            throw new Exception("该吸嘴尚无调优结果。");
        }
        boolean old = entry.enabled;
        entry.enabled = enabled;
        try {
            save();
        } catch (Exception failure) {
            entry.enabled = old;
            throw failure;
        }
    }

    public static synchronized void undo(ReferenceNozzle nozzle, Camera camera) throws Exception {
        String key = key(nozzle, camera);
        Entry old = state().entries.remove(key);
        if (old == null) {
            throw new Exception("没有可撤销的调优结果。");
        }
        if (old.previous != null) {
            state.entries.put(key, old.previous);
        }
        try {
            save();
        } catch (Exception failure) {
            state.entries.put(key, old);
            throw failure;
        }
    }

    static double norm(Location value) {
        Location mm = value.convertToUnits(LengthUnit.Millimeters);
        return Math.hypot(mm.getX(), mm.getY());
    }

    static Location mean(List<Location> samples) throws Exception {
        if (samples.isEmpty()) {
            throw new Exception("未取得有效测量。");
        }
        double x = 0, y = 0;
        for (Location sample : samples) {
            Location mm = sample.convertToUnits(LengthUnit.Millimeters);
            if (!Double.isFinite(mm.getX()) || !Double.isFinite(mm.getY())) {
                throw new Exception("测量坐标无效。");
            }
            x += mm.getX();
            y += mm.getY();
        }
        return new Location(LengthUnit.Millimeters, x / samples.size(), y / samples.size(), 0, 0);
    }

    static void accept(Location before, Location after, double noise) throws Exception {
        if (!Double.isFinite(norm(before)) || !Double.isFinite(norm(after)) || !Double.isFinite(noise)
                || noise > .03 || norm(after) > Math.max(.03, norm(before) * .4)
                || norm(after) > norm(before) + .005) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "复核未通过：中心 %.4f → %.4f mm，重复测量差 %.4f mm。已恢复原值；请清洁吸嘴并检查对焦、照明后重试。",
                    norm(before), norm(after), noise));
        }
    }

    private static Location measure(ReferenceNozzle nozzle, Camera camera, double angle,
            Consumer<Progress> progress, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) {
            throw new Exception("调优已取消，未应用本次结果。");
        }
        ReferenceNozzleTipCalibration calibration = nozzle.getCalibrationNozzleTip().getCalibration();
        Location target = calibration.getCalibrationLocation(camera, nozzle).derive(null, null, null, angle);
        nozzle.moveTo(target);
        try (CvPipeline pipeline = new CvPipeline(calibration.getPipeline().toXmlStringPreservingRuntimeStageValues())) {
            for (org.openpnp.vision.pipeline.CvStage stage : pipeline.getStages()) {
                if (stage instanceof org.openpnp.vision.pipeline.stages.DetectCircularSymmetry) {
                    org.openpnp.vision.pipeline.stages.DetectCircularSymmetry symmetry =
                            (org.openpnp.vision.pipeline.stages.DetectCircularSymmetry) stage;
                    symmetry.setSuperSampling(Math.max(4, symmetry.getSuperSampling()));
                    symmetry.setSubSampling(Math.min(4, symmetry.getSubSampling()));
                }
            }
            pipeline.setProperty("camera", camera);
            pipeline.setProperty("nozzleTip.diameter", calibration.getCalibrationTipDiameter());
            pipeline.setProperty("nozzleTip.maxDistance", new Length(.5, LengthUnit.Millimeters));
            pipeline.setProperty("nozzleTip.center", camera.getLocation());
            pipeline.setProperty("MaskCircle.center", new org.opencv.core.Point(camera.getWidth() / 2.0, camera.getHeight() / 2.0));
            progress.accept(new Progress(nozzle.getName() + " · " + Math.round(angle) + "° · 拍摄吸嘴中心", null));
            pipeline.process();
            List<?> detections = pipeline.getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME).getExpectedModel(List.class);
            if (detections.size() != 1) {
                throw new Exception("未找到唯一吸嘴中心；请确认吸嘴为空、照明开启，检查吸嘴识别流水线。");
            }
            Object detection = detections.get(0);
            double x, y;
            if (detection instanceof Result.Circle) {
                x = ((Result.Circle) detection).x;
                y = ((Result.Circle) detection).y;
            } else if (detection instanceof KeyPoint) {
                x = ((KeyPoint) detection).pt.x;
                y = ((KeyPoint) detection).pt.y;
            } else if (detection instanceof RotatedRect) {
                x = ((RotatedRect) detection).center.x;
                y = ((RotatedRect) detection).center.y;
            } else {
                throw new Exception("吸嘴中心检测结果类型不受支持。");
            }
            Location value = VisionUtils.getPixelCenterOffsets(camera, x, y).convertToUnits(LengthUnit.Millimeters);
            org.pmw.tinylog.Logger.info("Nozzle center {} angle={} pixel=({}, {}) frame={}x{} offset={}",
                    nozzle.getName(), angle, x, y, camera.getWidth(), camera.getHeight(), value);
            progress.accept(new Progress(nozzle.getName() + " · 中心偏差 " + String.format(java.util.Locale.ROOT, "%.4f mm", norm(value)),
                    OpenCvUtils.toBufferedImage(pipeline.getWorkingImage())));
            if (!Double.isFinite(x) || !Double.isFinite(y) || norm(value) > .5) {
                throw new Exception(String.format(java.util.Locale.ROOT,
                        "吸嘴中心测得 %.4f mm，超过精调范围 0.5 mm；请检查检测圆是否落在吸嘴孔上或先粗调位置。", norm(value)));
            }
            return value;
        }
    }

    /** Invoke on the machine executor with all nozzles empty. */
    public static Report tune(ReferenceNozzle nozzle, Consumer<Progress> progress, BooleanSupplier cancelled) throws Exception {
        if (!nozzle.getHead().getMachine().isEnabled() || !nozzle.getHead().getMachine().isHomed()) {
            throw new Exception("请先启用机器并完成复位。");
        }
        for (Nozzle candidate : nozzle.getHead().getNozzles()) {
            if (candidate.getPart() != null) {
                throw new Exception("请先移除所有吸嘴上的器件；自动调优测量空吸嘴。");
            }
        }
        if (nozzle.getCalibrationNozzleTip() == null) {
            throw new Exception("请先安装吸嘴并设置吸嘴识别流水线。");
        }
        Camera camera = VisionUtils.getBottomVisionCamera();
        if (camera.getHead() != null) {
            throw new Exception("仅支持固定底部相机。");
        }
        double[] limits = nozzle.getRotationModeLimits();
        if (limits[1] - limits[0] < 359.99) {
            throw new Exception("吸嘴旋转范围不足 360°，无法完成分角度独立复核。");
        }
        String key = key(nozzle, camera), signature = signature(nozzle, camera);
        Entry old;
        synchronized (NozzleVisionCentering.class) {
            old = state().entries.get(key);
        }
        Location original = offset(nozzle, camera);
        boolean committed = false;
        double firstAngle = (limits[0] + limits[1] - 270) / 2;
        List<Location> samples = new ArrayList<>();
        double noise = 0;
        try {
            for (Nozzle candidate : nozzle.getHead().getNozzles()) {
                candidate.moveToSafeZ();
            }
            Location base = nozzle.getCalibrationNozzleTip().getCalibration().getCalibrationLocation(camera, nozzle);
            MovableUtils.moveToLocationAtSafeZ(nozzle, base.derive(null, null, null, firstAngle));
            for (int i = 0; i < 4; i++) {
                Location a = measure(nozzle, camera, firstAngle + i * 90, progress, cancelled);
                Location b = measure(nozzle, camera, firstAngle + i * 90, progress, cancelled);
                noise = Math.max(noise, norm(a.subtract(b)));
                samples.add(a);
                samples.add(b);
            }
            Location before = mean(samples);
            if (noise > .03) {
                throw new Exception("重复图像测量差超过 0.03 mm，请检查振动、对焦和照明后重试。");
            }
            Entry proposed = new Entry();
            proposed.signature = signature;
            proposed.x = original.getX() + before.getX();
            proposed.y = original.getY() + before.getY();
            if (Math.hypot(proposed.x, proposed.y) > 3) {
                throw new Exception("累计成像修正超过 3 mm，请检查相机和吸嘴安装。");
            }
            if (old != null) {
                proposed.previous = GSON.fromJson(GSON.toJson(old), Entry.class);
                proposed.previous.previous = null;
            }
            synchronized (NozzleVisionCentering.class) {
                state.entries.put(key, proposed);
            }
            progress.accept(new Progress(nozzle.getName() + " · 临时试用修正，换角度独立复核", null));
            List<Location> checks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                double angle = firstAngle + 45 + i * 90;
                if (angle > limits[1]) {
                    angle -= 360;
                }
                Location a = measure(nozzle, camera, angle, progress, cancelled);
                Location b = measure(nozzle, camera, angle, progress, cancelled);
                noise = Math.max(noise, norm(a.subtract(b)));
                checks.add(a);
                checks.add(b);
            }
            Location after = mean(checks);
            accept(before, after, noise);
            if (cancelled.getAsBoolean() || !signature.equals(signature(nozzle, camera))) {
                throw new Exception("调优期间配置改变或操作取消，已恢复原值。");
            }
            synchronized (NozzleVisionCentering.class) {
                save();
            }
            committed = true;
            return new Report(nozzle.getName(), before, after, before, noise);
        } finally {
            if (!committed) {
                synchronized (NozzleVisionCentering.class) {
                    if (old == null) {
                        state.entries.remove(key);
                    } else {
                        state.entries.put(key, old);
                    }
                }
            }
            nozzle.moveToSafeZ();
        }
    }
}
