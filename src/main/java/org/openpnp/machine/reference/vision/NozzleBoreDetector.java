package org.openpnp.machine.reference.vision;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;

/** Detects the enclosed air bore of an empty, circular metal nozzle. Imaging centering only. */
public final class NozzleBoreDetector {
    public static final class Detection {
        public final Point center;
        public final double diameter, edgeCoverage, edgeResidual;

        Detection(Point center, double diameter, double edgeCoverage, double edgeResidual) {
            this.center = center;
            this.diameter = diameter;
            this.edgeCoverage = edgeCoverage;
            this.edgeResidual = edgeResidual;
        }
    }

    private NozzleBoreDetector() { }

    public static Detection detect(Mat image, Point expected, double maxDistance, double tipDiameter) throws Exception {
        if (image.empty() || !Double.isFinite(maxDistance) || !Double.isFinite(tipDiameter)
                || maxDistance <= 0 || tipDiameter < 12 || tipDiameter > 300
                || !Double.isFinite(expected.x) || !Double.isFinite(expected.y)) {
            throw new Exception("吸嘴中心孔检测参数无效，或图像分辨率不足。");
        }
        int padding = (int)Math.ceil(maxDistance + tipDiameter);
        int left = Math.max(0, (int)expected.x - padding), top = Math.max(0, (int)expected.y - padding);
        int right = Math.min(image.cols(), (int)expected.x + padding + 1);
        int bottom = Math.min(image.rows(), (int)expected.y + padding + 1);
        if (right - left < tipDiameter || bottom - top < tipDiameter) {
            throw new Exception("吸嘴中心孔搜索区域超出图像。");
        }
        Mat roi = image.submat(new Rect(left, top, right - left, bottom - top));
        Mat gray = new Mat(), mask = new Mat(), hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            if (roi.channels() == 1) {
                roi.copyTo(gray);
            } else if (roi.channels() == 3) {
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            } else if (roi.channels() == 4) {
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGRA2GRAY);
            } else {
                throw new Exception("吸嘴图像通道数不受支持。");
            }
            Imgproc.threshold(gray, mask, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);
            byte[] pixels = new byte[gray.rows() * gray.cols()];
            gray.get(0, 0, pixels);
            List<Detection> candidates = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                double minDiameter = tipDiameter * .38, maxDiameter = tipDiameter * .85;
                if (area < Math.PI * minDiameter * minDiameter / 5
                        || area > Math.PI * maxDiameter * maxDiameter / 3 || contour.rows() < 16) {
                    continue;
                }
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                RotatedRect coarse;
                try { coarse = Imgproc.fitEllipseDirect(points); }
                finally { points.release(); }
                double minor = Math.min(coarse.size.width, coarse.size.height);
                double major = Math.max(coarse.size.width, coarse.size.height);
                double fullness = area / (Math.PI * minor * major / 4);
                if (minor < minDiameter || major > maxDiameter || minor / major < .78
                        || fullness < .85 || fullness > 1.15
                        || Math.hypot(coarse.center.x + left - expected.x,
                                coarse.center.y + top - expected.y) > maxDistance) {
                    continue;
                }
                Detection refined = refine(pixels, gray.cols(), gray.rows(), coarse);
                if (refined != null) {
                    Point center = new Point(refined.center.x + left, refined.center.y + top);
                    if (Math.hypot(center.x - expected.x, center.y - expected.y) <= maxDistance) {
                        candidates.add(new Detection(center, refined.diameter,
                                refined.edgeCoverage, refined.edgeResidual));
                    }
                }
            }
            if (candidates.size() != 1) {
                throw new Exception(candidates.isEmpty()
                        ? "未找到完整、对比度足够的吸嘴中心孔；请检查空吸嘴、直径、对焦和照明。"
                        : "找到多个相似中心孔，无法唯一定位；请清洁吸嘴并检查照明和相机位置。");
            }
            return candidates.get(0);
        } finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            roi.release();
            gray.release();
            mask.release();
            hierarchy.release();
        }
    }

    private static Detection refine(byte[] pixels, int width, int height, RotatedRect coarse) {
        final int count = 64;
        double radius = (coarse.size.width + coarse.size.height) / 4;
        List<Point> edges = new ArrayList<>();
        int[] sectors = new int[8];
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count, cos = Math.cos(angle), sin = Math.sin(angle);
            double best = 0, bestRadius = 0;
            for (double r = radius - 3; r <= radius + 3; r += .25) {
                double gradient = sample(pixels, width, height, coarse.center.x + (r + .5) * cos,
                        coarse.center.y + (r + .5) * sin)
                        - sample(pixels, width, height, coarse.center.x + (r - .5) * cos,
                                coarse.center.y + (r - .5) * sin);
                if (gradient > best) {
                    best = gradient;
                    bestRadius = r;
                }
            }
            double inside = sample(pixels, width, height, coarse.center.x + radius * .6 * cos,
                    coarse.center.y + radius * .6 * sin);
            double outside = sample(pixels, width, height, coarse.center.x + (bestRadius + 1.5) * cos,
                    coarse.center.y + (bestRadius + 1.5) * sin);
            if (best >= 8 && outside - inside >= 18) {
                edges.add(new Point(coarse.center.x + bestRadius * cos, coarse.center.y + bestRadius * sin));
                sectors[i / 8]++;
            }
        }
        if (edges.size() < 56) {
            return null;
        }
        for (int sector : sectors) {
            if (sector < 6) {
                return null;
            }
        }
        MatOfPoint2f points = new MatOfPoint2f();
        points.fromList(edges);
        try {
            RotatedRect ellipse = Imgproc.fitEllipseDirect(points);
            double minor = Math.min(ellipse.size.width, ellipse.size.height);
            double major = Math.max(ellipse.size.width, ellipse.size.height);
            if (minor / major < .78 || Math.hypot(ellipse.center.x - coarse.center.x,
                    ellipse.center.y - coarse.center.y) > 1.5) {
                return null;
            }
            double residual = 0, angle = Math.toRadians(ellipse.angle);
            for (Point p : edges) {
                double x = p.x - ellipse.center.x, y = p.y - ellipse.center.y;
                double u = (x * Math.cos(angle) + y * Math.sin(angle)) / (ellipse.size.width / 2);
                double v = (-x * Math.sin(angle) + y * Math.cos(angle)) / (ellipse.size.height / 2);
                double error = (Math.hypot(u, v) - 1) * minor / 2;
                residual += error * error;
            }
            residual = Math.sqrt(residual / edges.size());
            if (!Double.isFinite(residual) || residual > .8) {
                return null;
            }
            return new Detection(ellipse.center, (minor + major) / 2, edges.size() / (double)count, residual);
        } finally { points.release(); }
    }

    private static double sample(byte[] pixels, int width, int height, double x, double y) {
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
        if (ix < 0 || iy < 0 || ix + 1 >= width || iy + 1 >= height) {
            return Double.NaN;
        }
        double dx = x - ix, dy = y - iy;
        int at = iy * width + ix;
        return (pixels[at] & 255) * (1 - dx) * (1 - dy) + (pixels[at + 1] & 255) * dx * (1 - dy)
                + (pixels[at + width] & 255) * (1 - dx) * dy + (pixels[at + width + 1] & 255) * dx * dy;
    }
}
