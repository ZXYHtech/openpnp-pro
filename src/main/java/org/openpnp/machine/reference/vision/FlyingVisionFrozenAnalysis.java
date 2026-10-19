package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.ImageCapture;
import org.opencv.core.RotatedRect;

/** Analysis of one existing frame. Never applies a shot or performs another capture. */
public final class FlyingVisionFrozenAnalysis {
    private FlyingVisionFrozenAnalysis() { }

    public static RotatedRect analyze(CvPipeline pipeline, Camera camera, BufferedImage image) throws Exception {
        if (pipeline.getPipelineShotsCount() != 1) {
            throw new Exception("飞拍仅支持单张完整器件图像，多视野器件使用普通视觉。");
        }
        Set<String> allowed = new HashSet<>(Arrays.asList("ImageCapture", "ParameterNumeric", "ParameterBool",
                "BlurGaussian", "MaskCircle", "MaskRectangle", "ConvertColor", "MaskHsv", "Threshold",
                "FindContours", "FilterContours", "MinAreaRect", "DetectRectlinearSymmetry", "ImageRecall",
                "DrawContours", "DrawRotatedRects", "ImageWriteDebug"));
        int captures = 0;
        for (CvStage stage : pipeline.getStages()) {
            if (!stage.isEnabled()) {
                continue;
            }
            if (!stage.getClass().getPackage().getName().equals("org.openpnp.vision.pipeline.stages")
                    || !allowed.contains(stage.getClass().getSimpleName())) {
                throw new Exception("飞拍不支持可能访问硬件的视觉阶段：" + stage.getClass().getSimpleName());
            }
            if (stage instanceof ImageCapture) {
                captures++;
                if (((ImageCapture)stage).getCount() != 1) {
                    throw new Exception("飞拍不支持在已有图像分析时重新连拍。");
                }
            }
        }
        if (captures != 1) {
            throw new Exception("飞拍流水线必须恰有一个图像输入阶段。");
        }
        pipeline.setProperty(ImageCapture.EXPERIMENTAL_CAPTURE_PROPERTY, image);
        try (FastBottomVision.CaptureScope scope = FastBottomVision.prepareCapture(pipeline, camera)) {
            FastBottomVision.process(pipeline);
        }
        CvStage.Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
        if (result == null) {
            result = pipeline.getResult("result");
        }
        if (result == null || !(result.model instanceof RotatedRect)) {
            throw new Exception("飞拍图像未找到有效器件矩形。");
        }
        String detector = pipeline.getStage(VisionUtils.PIPELINE_RESULTS_NAME) == null ? "result" : VisionUtils.PIPELINE_RESULTS_NAME;
        if (pipeline.getStage(detector) instanceof org.openpnp.vision.pipeline.stages.MinAreaRect) {
            RotatedRect detection = (RotatedRect)result.model;
            detection.angle = FlyingPartEdgeAngle.refine(image, detection);
        }
        // The sole shot already owns the prepared properties. apply() would move the nozzle.
        CvPipeline.PipelineShot shot = pipeline.getPipelineShots().get(0);
        shot.processResult(result);
        RotatedRect rect = (RotatedRect)shot.processCompositeResult().getModel();
        if (rect == null || !Double.isFinite(rect.center.x + rect.center.y + rect.angle
                + rect.size.width + rect.size.height) || rect.size.width <= 0 || rect.size.height <= 0) {
            throw new Exception("飞拍图像识别坐标无效。");
        }
        return rect;
    }
}
