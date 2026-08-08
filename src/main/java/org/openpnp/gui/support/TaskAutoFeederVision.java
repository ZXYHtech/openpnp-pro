package org.openpnp.gui.support;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;

/** Conservative top-camera centering for a part already presented by an automatic feeder. */
public final class TaskAutoFeederVision {
    public static final double MAX_CORRECTION_MM = 2.0;
    private static final double MIN_CONFIDENCE = 0.55;
    private static final double AMBIGUITY_MARGIN = 0.035;
    private static final double MAX_POCKET_SCALE = 5.0;

    public static final class Result {
        public final Location measuredLocation;
        public final double confidence;
        public final double widthMm;
        public final double heightMm;
        public final boolean pocketBased;
        public final BufferedImage preview;

        private Result(Location measuredLocation, double confidence, double widthMm,
                double heightMm, boolean pocketBased, BufferedImage preview) {
            this.measuredLocation = measuredLocation;
            this.confidence = confidence;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.pocketBased = pocketBased;
            this.preview = preview;
        }
    }

    private static final class Candidate {
        private final RotatedRect rect;
        private final double widthMm;
        private final double heightMm;
        private final double shiftMm;
        private final double score;
        private final boolean pocketBased;

        private Candidate(RotatedRect rect, double widthMm, double heightMm, double shiftMm,
                double score, boolean pocketBased) {
            this.rect = rect;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.shiftMm = shiftMm;
            this.score = score;
            this.pocketBased = pocketBased;
        }
    }

    private TaskAutoFeederVision() {
    }

    public static Result locate(Camera camera, ReferenceAutoFeeder feeder) throws Exception {
        return locate(camera, feeder, MIN_CONFIDENCE);
    }

    public static Result locate(Camera camera, ReferenceAutoFeeder feeder,
            double minimumConfidence) throws Exception {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0
                || minimumConfidence > 1) {
            throw new Exception("自动纠偏最低置信度必须在 0%～100% 之间。");
        }
        if (camera == null || feeder == null || feeder.getPart() == null
                || feeder.getPart().getPackage() == null) {
            throw new Exception("自动飞达未配置元件或封装，无法确定视觉目标尺寸。");
        }
        Footprint footprint = feeder.getPart().getPackage().getFootprint();
        if (footprint == null || footprint.getUnits() == null
                || footprint.getBodyWidth() <= 0 || footprint.getBodyHeight() <= 0) {
            throw new Exception("当前元件封装缺少本体宽度/高度，请完善封装后再自动纠偏。");
        }
        double expectedWidthMm = new Length(footprint.getBodyWidth(), footprint.getUnits())
                .convertToUnits(LengthUnit.Millimeters).getValue();
        double expectedHeightMm = new Length(footprint.getBodyHeight(), footprint.getUnits())
                .convertToUnits(LengthUnit.Millimeters).getValue();
        if (expectedWidthMm < 0.15 || expectedHeightMm < 0.15) {
            throw new Exception("封装本体尺寸过小或无效，无法安全建立自动飞达视觉目标。");
        }

        BufferedImage image = camera.lightSettleAndCapture();
        if (image == null) {
            throw new Exception("顶部相机没有返回图像。");
        }
        Location unitsPerPixel = camera.getUnitsPerPixelAtZ()
                .convertToUnits(LengthUnit.Millimeters);
        double xMmPerPixel = Math.abs(unitsPerPixel.getX());
        double yMmPerPixel = Math.abs(unitsPerPixel.getY());
        if (!(xMmPerPixel > 0) || !(yMmPerPixel > 0)) {
            throw new Exception("顶部相机像素比例无效，无法把识别偏移换算为毫米。");
        }

        Mat source = OpenCvUtils.toMat(image);
        Mat gray = new Mat();
        Mat enhanced = new Mat();
        Mat blurred = new Mat();
        Mat binary = new Mat();
        Mat inverse = new Mat();
        Mat adaptive = new Mat();
        Mat adaptiveInverse = new Mat();
        Mat edges = new Mat();
        Mat gradient = new Mat();
        double meanMmPerPixel = Math.sqrt(xMmPerPixel * yMmPerPixel);
        double expectedSmallPixels = Math.min(expectedWidthMm, expectedHeightMm)
                / meanMmPerPixel;
        int closeSize = oddKernelSize(expectedSmallPixels * 0.12, 3, 15,
                Math.min(source.cols(), source.rows()));
        int adaptiveBlockSize = oddKernelSize(expectedSmallPixels * 1.5, 15, 61,
                Math.min(source.cols(), source.rows()));
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(closeSize, closeSize));
        Mat edgeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(Math.max(3, Math.min(7, closeSize)),
                        Math.max(3, Math.min(7, closeSize))));
        CLAHE clahe = Imgproc.createCLAHE(2.4, new Size(8, 8));
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            if (source.channels() == 1) {
                source.copyTo(gray);
            }
            else {
                Imgproc.cvtColor(source, gray, source.channels() == 4
                        ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
            }
            // Local contrast normalization keeps black packages, white tape and specular metal
            // usable without forcing one global brightness/contrast setting for every feeder.
            clahe.apply(gray, enhanced);
            Imgproc.GaussianBlur(enhanced, blurred, new Size(5, 5), 0);
            Imgproc.threshold(blurred, binary, 0, 255,
                    Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Core.bitwise_not(binary, inverse);
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(inverse, inverse, Imgproc.MORPH_CLOSE, kernel);

            Imgproc.adaptiveThreshold(blurred, adaptive, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                    adaptiveBlockSize, 3);
            Core.bitwise_not(adaptive, adaptiveInverse);
            Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(adaptiveInverse, adaptiveInverse,
                    Imgproc.MORPH_CLOSE, kernel);

            // Edge closure handles multi-tone parts whose body, terminals and pins do not form one
            // Otsu region. It is intentionally only a supporting strategy; the size and nominal
            // pick-point checks below remain authoritative.
            Imgproc.Canny(blurred, edges, 30, 90);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, edgeKernel);
            Imgproc.dilate(edges, edges, edgeKernel);

            // A morphological gradient is less sensitive than Canny to broad highlights on metal
            // covers and to the low-contrast outer wall of black component pockets.
            Imgproc.morphologyEx(blurred, gradient, Imgproc.MORPH_GRADIENT, edgeKernel);
            Imgproc.threshold(gradient, gradient, 0, 255,
                    Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(gradient, gradient, Imgproc.MORPH_CLOSE, edgeKernel);

            List<Candidate> candidates = new ArrayList<>();
            collectCandidates(binary, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 1.00);
            releaseContours(contours);
            collectCandidates(inverse, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 1.00);
            releaseContours(contours);
            collectCandidates(adaptive, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 0.94);
            releaseContours(contours);
            collectCandidates(adaptiveInverse, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 0.94);
            releaseContours(contours);
            collectCandidates(edges, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 0.88);
            releaseContours(contours);
            collectCandidates(gradient, contours, candidates, source.cols(), source.rows(),
                    xMmPerPixel, yMmPerPixel, expectedWidthMm, expectedHeightMm, 0.91);
            releaseContours(contours);

            candidates = consolidateCandidates(candidates, expectedSmallPixels);
            if (candidates.isEmpty() || candidates.get(0).score < minimumConfidence) {
                if (candidates.isEmpty()) {
                    throw new Exception("未找到靠近当前拾取点的目标轮廓；请检查灯光、焦距、封装本体尺寸或元件是否已出料。");
                }
                Candidate nearest = candidates.get(0);
                throw new Exception(String.format(Locale.ROOT,
                        "已找到候选，但置信度 %.0f%% 低于当前要求 %.1f%%（识别 %.2f x %.2f mm，封装 %.2f x %.2f mm）；请降低单飞达增量前先检查封装尺寸和顶部相机对比度。",
                        nearest.score * 100.0, minimumConfidence * 100.0,
                        nearest.widthMm, nearest.heightMm,
                        expectedWidthMm, expectedHeightMm));
            }
            Candidate best = candidates.get(0);
            for (int index = 1; index < candidates.size(); index++) {
                Candidate other = candidates.get(index);
                if (other.score >= best.score - AMBIGUITY_MARGIN) {
                    throw new Exception(String.format(Locale.ROOT,
                            "画面中存在两个置信度接近的目标（%.0f%% / %.0f%%），无法安全确认实际出料元件。",
                            best.score * 100.0, other.score * 100.0));
                }
            }
            if (best.shiftMm > MAX_CORRECTION_MM) {
                throw new Exception(String.format(Locale.ROOT,
                        "识别目标偏移 %.3f mm，超过自动飞达安全上限 %.1f mm。",
                        best.shiftMm, MAX_CORRECTION_MM));
            }

            Location measured = VisionUtils.getPixelLocation(camera, best.rect.center.x,
                    best.rect.center.y);
            Mat previewMat = source.clone();
            Point[] vertices = new Point[4];
            best.rect.points(vertices);
            for (int index = 0; index < vertices.length; index++) {
                Imgproc.line(previewMat, vertices[index], vertices[(index + 1) % vertices.length],
                        new Scalar(0, 255, 0), 2);
            }
            Imgproc.drawMarker(previewMat, best.rect.center, new Scalar(0, 0, 255),
                    Imgproc.MARKER_CROSS, 24, 2);
            BufferedImage preview = OpenCvUtils.toBufferedImage(previewMat);
            previewMat.release();
            return new Result(measured, best.score, best.widthMm, best.heightMm,
                    best.pocketBased, preview);
        }
        finally {
            releaseContours(contours);
            clahe.collectGarbage();
            clahe.clear();
            edgeKernel.release();
            kernel.release();
            gradient.release();
            edges.release();
            adaptiveInverse.release();
            adaptive.release();
            inverse.release();
            binary.release();
            blurred.release();
            enhanced.release();
            gray.release();
            source.release();
        }
    }

    private static void collectCandidates(Mat mask, List<MatOfPoint> contours,
            List<Candidate> candidates, int imageWidth, int imageHeight, double xMmPerPixel,
            double yMmPerPixel, double expectedWidthMm, double expectedHeightMm,
            double strategyReliability) {
        contours.clear();
        Mat hierarchy = new Mat();
        Mat workingMask = mask.clone();
        try {
            Imgproc.findContours(workingMask, contours, hierarchy, Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            double meanMmPerPixel = Math.sqrt(xMmPerPixel * yMmPerPixel);
            for (MatOfPoint contour : contours) {
                if (contour.rows() < 4) {
                    continue;
                }
                org.opencv.core.MatOfPoint2f contour2f = new org.opencv.core.MatOfPoint2f(
                        contour.toArray());
                RotatedRect rect;
                try {
                    rect = Imgproc.minAreaRect(contour2f);
                }
                finally {
                    contour2f.release();
                }
                double widthMm = Math.max(rect.size.width, rect.size.height) * meanMmPerPixel;
                double heightMm = Math.min(rect.size.width, rect.size.height) * meanMmPerPixel;
                double expectedLarge = Math.max(expectedWidthMm, expectedHeightMm);
                double expectedSmall = Math.min(expectedWidthMm, expectedHeightMm);
                double ratioLarge = widthMm / expectedLarge;
                double ratioSmall = heightMm / expectedSmall;
                if (ratioLarge < 0.25 || ratioLarge > MAX_POCKET_SCALE
                        || ratioSmall < 0.25 || ratioSmall > MAX_POCKET_SCALE) {
                    continue;
                }
                double rectArea = Math.max(1.0, rect.size.width * rect.size.height);
                double rectangularity = Math.min(1.0, Imgproc.contourArea(contour) / rectArea);
                if (rectangularity < 0.10) {
                    continue;
                }
                boolean fillsImage = rect.size.width >= imageWidth * 0.94
                        && rect.size.height >= imageHeight * 0.94;
                if (fillsImage) {
                    continue;
                }
                double dxMm = (rect.center.x - imageWidth / 2.0) * xMmPerPixel;
                double dyMm = (rect.center.y - imageHeight / 2.0) * yMmPerPixel;
                double shiftMm = Math.hypot(dxMm, dyMm);
                if (shiftMm > MAX_CORRECTION_MM) {
                    continue;
                }
                double partScore = scorePartCandidate(widthMm, heightMm, expectedWidthMm,
                        expectedHeightMm, shiftMm, rectangularity, strategyReliability);
                double pocketScore = scorePocketCandidate(widthMm, heightMm, expectedWidthMm,
                        expectedHeightMm, shiftMm, rectangularity, strategyReliability);
                boolean pocketBased = pocketScore > partScore + 0.015;
                candidates.add(new Candidate(rect, widthMm, heightMm, shiftMm,
                        Math.max(partScore, pocketScore), pocketBased));
            }
        }
        finally {
            workingMask.release();
            hierarchy.release();
        }
    }

    static double scoreCandidate(double widthMm, double heightMm, double expectedWidthMm,
            double expectedHeightMm, double shiftMm, double rectangularity) {
        return Math.max(scorePartCandidate(widthMm, heightMm, expectedWidthMm,
                        expectedHeightMm, shiftMm, rectangularity, 1.0),
                scorePocketCandidate(widthMm, heightMm, expectedWidthMm,
                        expectedHeightMm, shiftMm, rectangularity, 1.0));
    }

    private static double scorePartCandidate(double widthMm, double heightMm,
            double expectedWidthMm,
            double expectedHeightMm, double shiftMm, double rectangularity,
            double strategyReliability) {
        double large = Math.max(widthMm, heightMm);
        double small = Math.min(widthMm, heightMm);
        double expectedLarge = Math.max(expectedWidthMm, expectedHeightMm);
        double expectedSmall = Math.min(expectedWidthMm, expectedHeightMm);
        double sizeScore = Math.exp(-Math.abs(Math.log(large / expectedLarge))
                - Math.abs(Math.log(small / expectedSmall)));
        double centerScore = Math.max(0, 1.0 - shiftMm / MAX_CORRECTION_MM);
        return 0.57 * sizeScore + 0.25 * centerScore
                + 0.13 * Math.max(0, Math.min(1, rectangularity))
                + 0.05 * Math.max(0, Math.min(1, strategyReliability));
    }

    static double scorePocketCandidate(double widthMm, double heightMm, double expectedWidthMm,
            double expectedHeightMm, double shiftMm, double rectangularity) {
        return scorePocketCandidate(widthMm, heightMm, expectedWidthMm, expectedHeightMm,
                shiftMm, rectangularity, 1.0);
    }

    private static double scorePocketCandidate(double widthMm, double heightMm,
            double expectedWidthMm, double expectedHeightMm, double shiftMm,
            double rectangularity, double strategyReliability) {
        double large = Math.max(widthMm, heightMm);
        double small = Math.min(widthMm, heightMm);
        double expectedLarge = Math.max(expectedWidthMm, expectedHeightMm);
        double expectedSmall = Math.min(expectedWidthMm, expectedHeightMm);
        double scaleLarge = large / expectedLarge;
        double scaleSmall = small / expectedSmall;
        if (scaleLarge < 0.75 || scaleSmall < 0.75
                || scaleLarge > MAX_POCKET_SCALE || scaleSmall > MAX_POCKET_SCALE) {
            return 0;
        }
        double centerScore = Math.max(0, 1.0 - shiftMm / MAX_CORRECTION_MM);
        double scaleScore = Math.exp(-0.35 * (Math.abs(Math.log(scaleLarge))
                + Math.abs(Math.log(scaleSmall))));
        double aspectScore = Math.exp(-0.8 * Math.abs(Math.log(scaleLarge / scaleSmall)));
        return 0.46 * centerScore
                + 0.22 * Math.max(0, Math.min(1, rectangularity))
                + 0.17 * scaleScore + 0.10 * aspectScore
                + 0.05 * Math.max(0, Math.min(1, strategyReliability));
    }

    private static List<Candidate> consolidateCandidates(List<Candidate> candidates,
            double expectedSmallPixels) {
        candidates.sort(Comparator.comparingDouble((Candidate item) -> item.score).reversed());
        List<Candidate> consolidated = new ArrayList<>();
        double duplicateRadius = Math.max(6.0, expectedSmallPixels * 0.55);
        for (Candidate candidate : candidates) {
            boolean duplicate = false;
            for (Candidate accepted : consolidated) {
                double distance = Math.hypot(candidate.rect.center.x - accepted.rect.center.x,
                        candidate.rect.center.y - accepted.rect.center.y);
                if (distance <= duplicateRadius) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                consolidated.add(candidate);
            }
        }
        return consolidated;
    }

    static int oddKernelSize(double requested, int minimum, int maximum, int imageLimit) {
        int safeMaximum = Math.max(3, Math.min(maximum, imageLimit - 2));
        if ((safeMaximum & 1) == 0) {
            safeMaximum--;
        }
        int value = Math.max(minimum, Math.min(safeMaximum, (int) Math.round(requested)));
        if ((value & 1) == 0) {
            value++;
        }
        return Math.min(value, safeMaximum);
    }

    private static void releaseContours(List<MatOfPoint> contours) {
        for (MatOfPoint contour : contours) {
            contour.release();
        }
        contours.clear();
    }
}
