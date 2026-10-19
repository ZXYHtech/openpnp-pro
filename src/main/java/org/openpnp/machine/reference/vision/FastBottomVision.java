package org.openpnp.machine.reference.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.openpnp.model.Length;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvAbstractParameterStage;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.*;

/** Exact crop of an existing, central positive mask; never narrows the configured search area. */
public final class FastBottomVision {
    public static final String REGION = "openpnp.pro.bottomVisionRegion";
    public static final String RETAIN = "openpnp.pro.retainedStageImages";
    private static final ThreadLocal<Rectangle> CAPTURE_REGION = new ThreadLocal<>();

    private FastBottomVision() { }

    public static final class Region {
        final int width, height;
        final Rectangle bounds;
        Region(int width, int height, Rectangle bounds) {
            this.width = width;
            this.height = height;
            this.bounds = bounds;
        }
    }

    public static final class CaptureScope implements AutoCloseable {
        private final Rectangle previous = CAPTURE_REGION.get();
        private CaptureScope(Region region) {
            CAPTURE_REGION.set(region == null ? null : region.bounds);
        }
        @Override public void close() {
            if (previous == null) {
                CAPTURE_REGION.remove();
            }
            else {
                CAPTURE_REGION.set(previous);
            }
        }
    }

    /** Applies parameters only, without capture or movement. Unsupported pipelines retain full-frame processing. */
    public static CaptureScope prepareCapture(CvPipeline pipeline, Camera camera) throws Exception {
        Set<String> retained = new HashSet<>();
        for (CvStage stage : pipeline.getStages()) {
            if (stage.isEnabled() && stage instanceof ImageRecall) {
                retained.add(((ImageRecall) stage).getImageStageName());
            }
        }
        pipeline.setProperty(RETAIN, retained);
        Region region = findRegion(pipeline, camera);
        pipeline.setProperty(REGION, region);
        return new CaptureScope(region);
    }

    static Region findRegion(CvPipeline pipeline, Camera camera) throws Exception {
        MaskCircle firstMask = null;
        boolean captured = false;
        boolean resultSeen = false;
        int padding = 8;
        for (CvStage stage : pipeline.getStages()) {
            if (!stage.isEnabled()) {
                continue;
            }
            if (stage instanceof CvAbstractParameterStage) {
                if (captured) {
                    return null;
                }
                stage.process(pipeline);
                continue;
            }
            if (!captured) {
                if (!(stage instanceof ImageCapture)) {
                    return null;
                }
                captured = true;
                continue;
            }
            if (firstMask == null) {
                if (!(stage instanceof MaskCircle)) {
                    return null;
                }
                firstMask = (MaskCircle) stage;
            }
            // Histogram-based automatic thresholds and spatial symmetry depend on the whole image.
            if (stage instanceof Threshold && ((Threshold) stage).isAuto()
                    || stage instanceof MaskHsv && Boolean.TRUE.equals(((MaskHsv) stage).getAuto())
                    || stage instanceof DetectRectlinearSymmetry || stage instanceof MaskRectangle) {
                return null;
            }
            if (stage instanceof Threshold && (((Threshold) stage).isInvert() || ((Threshold) stage).getThreshold() <= 0)
                    || stage instanceof MinAreaRect && ((MinAreaRect) stage).getThresholdMin() <= 0) {
                return null;
            }
            if (stage instanceof MaskHsv && ((MaskHsv) stage).isBinaryMask()) {
                return null;
            }
            if (stage instanceof ConvertColor) {
                org.openpnp.vision.FluentCv.ColorCode conversion = ((ConvertColor) stage).getConversion();
                if (conversion != org.openpnp.vision.FluentCv.ColorCode.Bgr2HsvFull
                        && conversion != org.openpnp.vision.FluentCv.ColorCode.Hsv2BgrFull
                        && conversion != org.openpnp.vision.FluentCv.ColorCode.Bgr2Gray) {
                    return null;
                }
            }
            if (stage instanceof MinAreaRect && (stage.getName().equals("results") || stage.getName().equals("result"))) {
                resultSeen = true;
            }
            if (stage instanceof ImageRecall && (!resultSeen
                    || !(pipeline.getStage(((ImageRecall) stage).getImageStageName()) instanceof ImageCapture))) {
                return null;
            }
            if (stage instanceof BlurGaussian) {
                BlurGaussian blur = (BlurGaussian) stage;
                int kernel = blur.getPossiblePipelinePropertyOverride(blur.getKernelSize(), pipeline,
                        blur.getPropertyName() + ".kernelSize", Double.class, Integer.class, Length.class) | 1;
                if (kernel < 1 || kernel > 63) {
                    return null;
                }
                padding += kernel / 2;
            }
            if (stage instanceof MaskCircle) {
                MaskCircle mask = (MaskCircle) stage;
                // Explicit pixel coordinates must not silently change meaning in cropped images.
                Object center = pipeline.getProperty(mask.getPropertyName() + ".center");
                if (center != null && !(center instanceof Location)) {
                    return null;
                }
            }
        }
        if (firstMask == null || !resultSeen) {
            return null;
        }
        int diameter = firstMask.getPossiblePipelinePropertyOverride(firstMask.getDiameter(), pipeline,
                firstMask.getPropertyName() + ".diameter", Double.class, Integer.class, Length.class);
        int width = camera.getWidth(), height = camera.getHeight();
        Point center = firstMask.getPossiblePipelinePropertyOverride(new Point(width / 2.0, height / 2.0),
                pipeline, firstMask.getPropertyName() + ".center", Point.class,
                org.openpnp.model.Point.class, Location.class);
        if (diameter <= 0 || diameter > Math.min(width, height) - 2 * padding
                || Math.abs(center.x - width / 2.0) > 1e-6
                || Math.abs(center.y - height / 2.0) > 1e-6) {
            return null;
        }
        int size = Math.max(128, diameter + padding * 2 + 2);
        int cropWidth = size + ((width - size) & 1);
        int cropHeight = size + ((height - size) & 1);
        if (cropWidth > width || cropHeight > height) {
            return null;
        }
        if ((long) cropWidth * cropHeight > (long) width * height * .7) {
            return null;
        }
        return new Region(width, height, new Rectangle((width - cropWidth) / 2,
                (height - cropHeight) / 2, cropWidth, cropHeight));
    }

    /** BufferedImage subimages share a strided backing array: copy explicitly before OpenCV conversion. */
    public static BufferedImage crop(BufferedImage source, Rectangle r) {
        BufferedImage cropped = new BufferedImage(r.width, r.height, BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, r.width, r.height, r.x, r.y,
                    r.x + r.width, r.y + r.height, null);
        } finally { graphics.dispose(); }
        return cropped;
    }

    public static BufferedImage settlingImage(BufferedImage source) {
        Rectangle r = CAPTURE_REGION.get();
        if (r == null || r.x + r.width > source.getWidth() || r.y + r.height > source.getHeight()) {
            return source;
        }
        return crop(source, r);
    }

    public static void process(CvPipeline pipeline) throws Exception {
        Region region = (Region) pipeline.getProperty(REGION);
        if (region == null) {
            pipeline.process();
            return;
        }
        BufferedImage image = (BufferedImage) pipeline.getProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY);
        if (image.getWidth() != region.width || image.getHeight() != region.height) {
            throw new Exception("底部相机图像尺寸改变，请暂停后重新识别。");
        }
        Camera camera = (Camera) pipeline.getProperty("camera");
        Location upp = camera.getUnitsPerPixelAtZ();
        Camera roiCamera = (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[]{Camera.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getWidth")) {
                        return region.bounds.width;
                    }
                    if (method.getName().equals("getHeight")) {
                        return region.bounds.height;
                    }
                    if (method.getName().equals("getUnitsPerPixelAtZ")) {
                        return upp;
                    }
                    // All allowed pipeline stages are pure analysis; no hardware access from this proxy.
                    if (method.getName().startsWith("capture") || method.getName().startsWith("settle")
                            || method.getName().startsWith("actuate") || method.getName().startsWith("move")) {
                        throw new IllegalStateException("Hardware access in frozen bottom-vision analysis");
                    }
                    try { return method.invoke(camera, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        pipeline.setProperty("camera", roiCamera);
        pipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY, crop(image, region.bounds));
        try {
            pipeline.process();
            CvStage.Result result = pipeline.getResult("results");
            if (result == null) {
                result = pipeline.getResult("result");
            }
            if (result != null && result.model instanceof RotatedRect) {
                RotatedRect rect = (RotatedRect) result.model;
                Point[] points = new Point[4];
                rect.points(points);
                for (Point point : points) {
                    if (point.x <= 2 || point.y <= 2 || point.x >= region.bounds.width - 3
                            || point.y >= region.bounds.height - 3) {
                        throw new Exception("底部视觉器件接近图像边界，请检查取料或增大搜索区域。");
                    }
                }
                rect.center.x += region.bounds.x;
                rect.center.y += region.bounds.y;
            }
        } finally {
            pipeline.setProperty("camera", camera);
            pipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY, image);
        }
    }
}
