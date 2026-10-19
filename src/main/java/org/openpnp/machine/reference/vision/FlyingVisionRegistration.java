package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.openpnp.corexy.precision.ToolPointRuntime;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/** Same-frame fixture registration; camera/transport latency never enters the position calculation. */
public final class FlyingVisionRegistration {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int ROI = 384;

    public static final class Model {
        String signature, imageFile;
        double z, angle;
        double xx, xy, yx, yy;
        double validationError;
        transient BufferedImage reference;
    }

    public static final class RegisteredFrame {
        public final BufferedImage image;
        public final double x, y, correlation;
        RegisteredFrame(BufferedImage image, double x, double y, double correlation) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.correlation = correlation;
        }
    }

    private FlyingVisionRegistration() { }

    private static Path root() throws Exception {
        Path root = Configuration.get().getConfigurationDirectory().toPath().resolve("pro-flying-reference");
        Files.createDirectories(root);
        return root;
    }

    private static String id(Nozzle nozzle, Camera camera) {
        return (camera.getId() + "-" + nozzle.getId()).replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    static String signature(Nozzle nozzle, Camera camera) {
        ReferenceNozzle n = (ReferenceNozzle)nozzle;
        return n.getNozzleTip().getId() + "|" + n.getHeadOffsets() + "|" + camera.getLocation()
                + "|" + camera.getUnitsPerPixel() + "|" + n.getCameraToolCalibratedOffset(camera)
                + "|" + ToolPointRuntime.nativeSignature(n) + "|" + GSON.toJson(ToolPointRuntime.snapshot().get(n.getId()));
    }

    public static Model load(Nozzle nozzle, Camera camera, Location target) throws Exception {
        List<Model> models = new ArrayList<>();
        String prefix = id(nozzle, camera);
        try (var files = Files.list(root())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().equals(prefix + ".json")
                    || p.getFileName().toString().startsWith(prefix + "-pose-")
                    && p.getFileName().toString().endsWith(".json"))::iterator) {
                try { models.add(GSON.fromJson(Files.readString(file), Model.class)); }
                catch (Exception damagedReference) { /* Other independently valid poses remain usable. */ }
            }
        }
        Model model = select(models, signature(nozzle, camera), target);
        if (model == null) {
            throw new Exception("当前吸嘴、拍摄高度或角度缺少有效飞拍参考，请在自动评估页准备空吸嘴参考。");
        }
        model.reference = ImageIO.read(root().resolve(model.imageFile).toFile());
        if (model.reference == null) {
            throw new Exception("无法读取飞拍参考图像。");
        }
        return model;
    }

    static Model select(List<Model> models, String signature, Location target) {
        Location mm = target.convertToUnits(LengthUnit.Millimeters);
        return models.stream().filter(model -> model != null && signature.equals(model.signature)
                && Double.isFinite(mm.getZ() + mm.getRotation() + model.z + model.angle)
                && Math.abs(mm.getZ() - model.z) <= .02
                && Math.abs(Utils2D.angleNorm(mm.getRotation() - model.angle, 180)) <= 1
                && Double.isFinite(model.validationError) && model.validationError <= .03
                && Double.isFinite(model.xx + model.xy + model.yx + model.yy)
                && Math.abs(model.xx * model.yy - model.xy * model.yx) >= 1e-7
                && model.imageFile != null && model.imageFile.matches("[a-zA-Z0-9_-]+\\.png"))
                .min(Comparator.comparingDouble(model -> Math.abs(mm.getZ() - model.z)
                        + Math.abs(Utils2D.angleNorm(mm.getRotation() - model.angle, 180))))
                .orElse(null);
    }

    private static Mat gray(BufferedImage image) {
        if (image.getWidth() < ROI || image.getHeight() < ROI) {
            throw new IllegalArgumentException("飞拍同帧配准需要至少384×384像素。");
        }
        Mat input = OpenCvUtils.toMat(image), gray = new Mat();
        Mat roi = input.submat(new Rect(image.getWidth() / 2 - ROI / 2,
                image.getHeight() / 2 - ROI / 2, ROI, ROI));
        try {
            if (roi.channels() == 1) {
                roi.copyTo(gray);
            } else {
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            }
            Imgproc.resize(gray, gray, new Size(ROI / 2, ROI / 2), 0, 0, Imgproc.INTER_AREA);
            return gray;
        } catch (RuntimeException failure) {
            gray.release();
            throw failure;
        } finally {
            roi.release();
            input.release();
        }
    }

    /** Package-visible for synthetic translation, occlusion and brightness regression tests. */
    static double[] translation(BufferedImage reference, BufferedImage frame) {
        Mat template = gray(reference), input = gray(frame);
        Mat mask = new Mat(ROI / 2, ROI / 2, CvType.CV_8UC1, new Scalar(255));
        Mat warp = Mat.eye(2, 3, CvType.CV_32F);
        Mat first = new Mat(), second = new Mat(), window = new Mat();
        try {
            template.convertTo(first, CvType.CV_32F);
            input.convertTo(second, CvType.CV_32F);
            Imgproc.createHanningWindow(window, first.size(), CvType.CV_32F);
            Point coarse = Imgproc.phaseCorrelate(first, second, window);
            if (Math.hypot(coarse.x, coarse.y) > 30) {
                throw new IllegalArgumentException("吸嘴外周尚未进入飞拍窗口。");
            }
            warp.put(0, 2, coarse.x);
            warp.put(1, 2, coarse.y);
            // Exclude the component, tip and its reflections. Match the fixed surrounding fixture.
            Imgproc.circle(mask, new Point(ROI / 4, ROI / 4), 40, new Scalar(0), -1);
            double score = Video.findTransformECC(template, input, warp, Video.MOTION_TRANSLATION,
                    new TermCriteria(TermCriteria.COUNT | TermCriteria.EPS, 25, .0001), mask, 5);
            double x = warp.get(0, 2)[0] * 2, y = warp.get(1, 2)[0] * 2;
            if (!Double.isFinite(score + x + y) || score < .98 || Math.hypot(x, y) > 45) {
                throw new IllegalArgumentException("吸嘴外周配准不可靠，已拒绝该帧。");
            }
            return new double[] {x, y, score};
        } finally {
            template.release();
            input.release();
            mask.release();
            warp.release();
            first.release();
            second.release();
            window.release();
        }
    }

    public static RegisteredFrame register(Model model, BufferedImage frame, Camera camera) throws Exception {
        double[] shift = translation(model.reference, frame);
        double x = model.xx * shift[0] + model.xy * shift[1];
        double y = model.yx * shift[0] + model.yy * shift[1];
        if (Math.hypot(x, y) > .8) {
            throw new Exception("飞拍帧超出已验证的0.8mm位移范围。");
        }
        Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        Mat source = OpenCvUtils.toMat(frame), result = new Mat(), affine = Mat.eye(2, 3, CvType.CV_64F);
        try {
            affine.put(0, 2, -x / upp.getX());
            affine.put(1, 2, y / upp.getY());
            Imgproc.warpAffine(source, result, affine, source.size(), Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT, new Scalar(0, 0, 0));
            return new RegisteredFrame(OpenCvUtils.toBufferedImage(result), x, y, shift[2]);
        } finally {
            source.release();
            result.release();
            affine.release();
        }
    }

    private static BufferedImage capture(Nozzle nozzle, Camera camera, Location target) throws Exception {
        var live = SafetyWorkbenchStore.get();
        long revision = live.getRevision();
        if (!live.isCertified()) {
            throw new Exception("飞拍标定需要已认证的实机包络。");
        }
        var model = GSON.fromJson(GSON.toJson(live),
                org.openpnp.machine.reference.safety.SafetyWorkbenchModel.class);
        Location from = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location to = target.convertToUnits(LengthUnit.Millimeters);
        var validation = DynamicSafetyCollisionEngine.validateTransit(model, java.util.Arrays.asList(
                new Waypoint(from.getX(), from.getY(), from.getZ()),
                new Waypoint(to.getX(), to.getY(), to.getZ())),
                DynamicSafetyCollisionEngine.snapshot(model, nozzle.getHead().getMachine(), nozzle));
        if (!validation.isSafe() || !live.isCertified() || live.getRevision() != revision) {
            throw new Exception("飞拍标定路线未通过连续包络认证：" + validation.getIssues());
        }
        MovableUtils.moveToBottomVisionLocation(nozzle, target, .20);
        nozzle.waitForCompletion(CompletionType.WaitForStillstand);
        return camera.lightSettleAndCapture();
    }

    public static void calibrate(Nozzle nozzle, Part part, double angle, Consumer<String> progress) throws Exception {
        for (Nozzle n : nozzle.getHead().getNozzles()) {
            if (n.getPart() != null) {
                throw new Exception("同帧飞拍标定需要所有吸嘴为空。");
            }
        }
        Camera camera = org.openpnp.util.VisionUtils.getBottomVisionCamera();
        Location center = ReferenceBottomVision.getDefault().getCameraLocationAtPartHeight(part, camera, nozzle, angle)
                .convertToUnits(LengthUnit.Millimeters);
        Model model = new Model();
        model.signature = signature(nozzle, camera);
        model.z = center.getZ();
        model.angle = angle;
        camera.actuateLightBeforeCapture();
        try {
            if (nozzle.getEffectiveSafeZ() == null) {
                throw new Exception("吸嘴未配置安全高度，无法标定。");
            }
            Location overhead = center.derive(null, null,
                    nozzle.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue(), null);
            capture(nozzle, camera, overhead);
            progress.accept(nozzle.getName() + " · 拍摄外周参考，排除中心器件区域");
            model.reference = capture(nozzle, camera, center);
            Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
            double tipPixels = ((ReferenceNozzle)nozzle).getCalibrationNozzleTip().getCalibration()
                    .getCalibrationTipDiameter().convertToUnits(LengthUnit.Millimeters).getValue() / upp.getX();
            Point expected = new Point(model.reference.getWidth() / 2.0, model.reference.getHeight() / 2.0);
            Mat referenceMat = OpenCvUtils.toMat(model.reference);
            Point bore;
            try { bore = NozzleBoreDetector.detect(referenceMat, expected, 1 / upp.getX(), tipPixels).center; }
            finally { referenceMat.release(); }
            double[][] samples = new double[4][];
            double[][] offsets = {{.6, 0}, {-.6, 0}, {0, .6}, {0, -.6}};
            for (int i = 0; i < offsets.length; i++) {
                progress.accept(nozzle.getName() + " · 标定位移 " + (i + 1) + "/4");
                BufferedImage frame = capture(nozzle, camera,
                        center.derive(center.getX() + offsets[i][0], center.getY() + offsets[i][1], null, null));
                samples[i] = java.util.Arrays.copyOf(translation(model.reference, frame), 5);
                Mat mat = OpenCvUtils.toMat(frame);
                try {
                    Point actual = NozzleBoreDetector.detect(mat, expected, 1 / upp.getX(), tipPixels).center;
                    samples[i][3] = (actual.x - bore.x) * upp.getX();
                    samples[i][4] = -(actual.y - bore.y) * upp.getY();
                } finally { mat.release(); }
                org.pmw.tinylog.Logger.info("Flying reference {} requested={} observed={}", nozzle.getName(),
                        java.util.Arrays.toString(offsets[i]), java.util.Arrays.toString(samples[i]));
            }
            double a = 0, b = 0, d = 0, ux = 0, uy = 0, vx = 0, vy = 0;
            for (double[] sample : samples) {
                a += sample[0] * sample[0];
                b += sample[0] * sample[1];
                d += sample[1] * sample[1];
                ux += sample[0] * sample[3];
                uy += sample[1] * sample[3];
                vx += sample[0] * sample[4];
                vy += sample[1] * sample[4];
            }
            double determinant = a * d - b * b;
            if (!Double.isFinite(determinant) || Math.abs(determinant) < 100) {
                throw new Exception("外周位移比例标定退化，未保存。");
            }
            model.xx = (ux * d - uy * b) / determinant;
            model.xy = (uy * a - ux * b) / determinant;
            model.yx = (vx * d - vy * b) / determinant;
            model.yy = (vy * a - vx * b) / determinant;
            for (double[] check : new double[][] {{.4, .3}, {-.35, .45}, {0, 0}}) {
                progress.accept(nozzle.getName() + " · 独立复核位移比例");
                BufferedImage frame = capture(nozzle, camera,
                        center.derive(center.getX() + check[0], center.getY() + check[1], null, null));
                double[] measured = translation(model.reference, frame);
                double x = model.xx * measured[0] + model.xy * measured[1];
                double y = model.yx * measured[0] + model.yy * measured[1];
                Mat mat = OpenCvUtils.toMat(frame);
                try {
                    Point actual = NozzleBoreDetector.detect(mat, expected, 1 / upp.getX(), tipPixels).center;
                    double actualX = (actual.x - bore.x) * upp.getX();
                    double actualY = -(actual.y - bore.y) * upp.getY();
                    double error = Math.hypot(x - actualX, y - actualY);
                    model.validationError = Math.max(model.validationError, error);
                    org.pmw.tinylog.Logger.info("Flying reference check {} requested={} bore=({}, {}) predicted=({}, {}) error={}",
                            nozzle.getName(), java.util.Arrays.toString(check), actualX, actualY, x, y, error);
                } finally { mat.release(); }
            }
            if (model.validationError > .03 || !model.signature.equals(signature(nozzle, camera))) {
                throw new Exception("同帧位移独立复核失败或配置改变，未保存：" + model.validationError + " mm");
            }
            model.imageFile = id(nozzle, camera) + "-" + java.util.UUID.randomUUID() + ".png";
            ImageIO.write(model.reference, "png", root().resolve(model.imageFile).toFile());
            Path file = root().resolve(id(nozzle, camera) + "-pose-"
                    + Math.round(model.z * 1000) + "-" + Math.round(model.angle * 1000) + ".json");
            Path temp = Files.createTempFile(root(), "registration-", ".tmp");
            try {
                Files.writeString(temp, GSON.toJson(model));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
            progress.accept(nozzle.getName() + " · 已保存；独立复核最大差 " + model.validationError + " mm");
        } finally {
            try { camera.actuateLightAfterCapture(); }
            finally { nozzle.moveToSafeZ(); }
        }
    }

    static PartAlignmentOffset consensus(List<PartAlignmentOffset> values) throws Exception {
        List<PartAlignmentOffset> best = new ArrayList<>();
        for (PartAlignmentOffset center : values) {
            List<PartAlignmentOffset> cluster = new ArrayList<>();
            for (PartAlignmentOffset value : values) {
                if (center.getPreRotated() == value.getPreRotated()
                        && center.getLocation().getLinearDistanceTo(value.getLocation()) <= .04
                        && Math.abs(Utils2D.angleNorm(center.getLocation().getRotation()
                                - value.getLocation().getRotation(), 180)) <= .75) {
                    cluster.add(value);
                }
            }
            if (cluster.size() > best.size()) {
                best = cluster;
            }
        }
        if (best.size() < 3) {
            throw new Exception("飞拍不足3帧一致：位置需相差不超过0.04mm，角度不超过0.75°；已回退普通视觉。");
        }
        List<Double> x = new ArrayList<>(), y = new ArrayList<>(), angle = new ArrayList<>();
        for (PartAlignmentOffset value : best) {
            Location mm = value.getLocation().convertToUnits(LengthUnit.Millimeters);
            x.add(mm.getX());
            y.add(mm.getY());
            angle.add(mm.getRotation());
        }
        return new PartAlignmentOffset(new Location(LengthUnit.Millimeters,
                median(x), median(y), 0, median(angle)), best.get(0).getPreRotated());
    }

    private static double median(List<Double> values) {
        values.sort(Comparator.naturalOrder());
        int n = values.size();
        return (values.get(n / 2) + values.get((n - 1) / 2)) / 2;
    }
}
