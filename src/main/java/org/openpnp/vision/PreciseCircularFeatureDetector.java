package org.openpnp.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Closed-boundary localisation, independent of texture inside a mounting hole or annulus. */
public final class PreciseCircularFeatureDetector {
    public static final String ENABLED_PROPERTY = "openpnp.pro.preciseCircularFeature";

    public static final class Feature {
        public final double x, y, diameter, rms, coverage;
        Feature(RotatedRect ellipse, double rms, double coverage) {
            x = ellipse.center.x;
            y = ellipse.center.y;
            diameter = Math.sqrt(ellipse.size.width * ellipse.size.height);
            this.rms = rms;
            this.coverage = coverage;
        }
    }

    private PreciseCircularFeatureDetector() { }

    public static List<Feature> detect(Mat image, double x, double y, double diameter,
            double searchRadius, int maxTargets) {
        if (image == null || image.empty() || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(diameter) || diameter < 12 || !Double.isFinite(searchRadius)
                || searchRadius < 0 || maxTargets < 1) {
            throw new IllegalArgumentException("圆环精定位需要有效图像、搜索范围及至少 12 像素的已知直径。");
        }
        return detect(image, x, y, diameter, searchRadius, maxTargets, searchRadius * 2, searchRadius * 2);
    }

    public static List<Feature> detect(Mat image, double x, double y, double diameter,
            double searchRadius, int maxTargets, double searchWidth, double searchHeight) {
        if (image == null || image.empty() || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(diameter) || diameter < 12 || !Double.isFinite(searchRadius)
                || searchRadius < 0 || maxTargets < 1 || !Double.isFinite(searchWidth)
                || !Double.isFinite(searchHeight) || searchWidth < 0 || searchHeight < 0) {
            throw new IllegalArgumentException("圆环精定位图像或几何参数无效。");
        }
        double extent = searchRadius + diameter * .7 + 4;
        int left = (int) Math.max(0, Math.floor(x - extent));
        int top = (int) Math.max(0, Math.floor(y - extent));
        int right = (int) Math.min(image.cols(), Math.ceil(x + extent));
        int bottom = (int) Math.min(image.rows(), Math.ceil(y + extent));
        if (left >= right || top >= bottom) { return new ArrayList<>(); }
        Mat region = image.submat(top, bottom, left, right);
        Mat gray = new Mat(), smooth = new Mat(), edges = new Mat(), hierarchy = new Mat();
        List<Feature> features = new ArrayList<>();
        try {
            if (region.channels() == 1) { region.copyTo(gray); }
            else {
                Imgproc.cvtColor(region, gray, region.channels() == 4
                        ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
            }
            Imgproc.GaussianBlur(gray, smooth, new Size(3, 3), .7);
            byte[] pixels = new byte[(int) smooth.total()];
            smooth.get(0, 0, pixels);
            for (double threshold : new double[] { 25, 55, 95 }) {
                Imgproc.Canny(smooth, edges, threshold, threshold * 2);
                List<MatOfPoint> contours = new ArrayList<>();
                try {
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);
                    for (MatOfPoint contour : contours) {
                        if (contour.rows() < Math.max(24, diameter * 1.4)) { continue; }
                        MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                        try {
                            RotatedRect ellipse = Imgproc.fitEllipse(points);
                            if (!sizeMatches(ellipse, diameter)
                                    || Math.hypot(ellipse.center.x + left - x, ellipse.center.y + top - y) > searchRadius
                                    || Math.abs(ellipse.center.x + left - x) > searchWidth / 2
                                    || Math.abs(ellipse.center.y + top - y) > searchHeight / 2
                                    || residual(contour.toArray(), ellipse) > Math.max(.8, diameter * .014)) { continue; }
                            Feature refined = refine(ellipse, pixels, smooth.cols(), smooth.rows(), diameter);
                            if (refined == null) { continue; }
                            ellipse = new RotatedRect(new Point(refined.x + left, refined.y + top),
                                    new Size(refined.diameter, refined.diameter), 0);
                            if (Math.hypot(ellipse.center.x - x, ellipse.center.y - y) > searchRadius
                                    || ellipse.center.x - refined.diameter * .57 < 2
                                    || ellipse.center.y - refined.diameter * .57 < 2
                                    || ellipse.center.x + refined.diameter * .57 > image.cols() - 3
                                    || ellipse.center.y + refined.diameter * .57 > image.rows() - 3) { continue; }
                            Feature candidate = new Feature(ellipse, refined.rms, refined.coverage);
                            boolean duplicate = false;
                            for (int i = 0; i < features.size(); i++) {
                                Feature old = features.get(i);
                                if (Math.hypot(old.x - candidate.x, old.y - candidate.y) < diameter * .05) {
                                    // Inner and outer annular boundaries are one target. Select
                                    // the boundary closest to the taught physical diameter.
                                    if (Math.abs(candidate.diameter - diameter) + candidate.rms * .1
                                            < Math.abs(old.diameter - diameter) + old.rms * .1) {
                                        features.set(i, candidate);
                                    }
                                    duplicate = true;
                                    break;
                                }
                            }
                            if (!duplicate) { features.add(candidate); }
                        }
                        finally { points.release(); }
                    }
                }
                finally { for (MatOfPoint contour : contours) { contour.release(); } }
            }
        }
        finally {
            region.release();
            gray.release();
            smooth.release();
            edges.release();
            hierarchy.release();
        }
        features.sort(Comparator.comparingDouble(f -> Math.hypot(f.x - x, f.y - y)));
        if (maxTargets == 1 && features.size() > 1) {
            double first = Math.hypot(features.get(0).x - x, features.get(0).y - y);
            double second = Math.hypot(features.get(1).x - x, features.get(1).y - y);
            if (second - first < diameter * .5) {
                throw new IllegalStateException("搜索区存在多个相近圆环/通孔，无法可靠选择目标；请缩小搜索范围。");
            }
        }
        return new ArrayList<>(features.subList(0, Math.min(maxTargets, features.size())));
    }

    private static boolean sizeMatches(RotatedRect e, double expected) {
        double small = Math.min(e.size.width, e.size.height), large = Math.max(e.size.width, e.size.height);
        double size = Math.sqrt(small * large);
        return small / large >= .86 && size >= expected * .75 && size <= expected * 1.25;
    }

    private static Feature refine(RotatedRect initial, byte[] pixels, int width, int height, double expected) {
        List<Point> points = new ArrayList<>();
        int positive = 0, negative = 0, longestGap = 0, gap = 0;
        boolean[] covered = new boolean[96];
        double rotation = Math.toRadians(initial.angle), cos = Math.cos(rotation), sin = Math.sin(rotation);
        double rx = initial.size.width / 2, ry = initial.size.height / 2;
        for (int ray = 0; ray < covered.length; ray++) {
            double angle = ray * 2 * Math.PI / covered.length, a = Math.cos(angle), b = Math.sin(angle);
            double x = initial.center.x + rx * a * cos - ry * b * sin;
            double y = initial.center.y + rx * a * sin + ry * b * cos;
            double nx = a / rx * cos - b / ry * sin, ny = a / rx * sin + b / ry * cos;
            double normal = Math.hypot(nx, ny);
            nx /= normal;
            ny /= normal;
            double best = -1, offset = 0, signed = 0;
            for (int step = -7; step <= 7; step++) {
                double distance = step * .25;
                double value = gradient(pixels, width, height, x + distance * nx, y + distance * ny, nx, ny);
                if (Math.abs(value) > best) {
                    best = Math.abs(value);
                    offset = distance;
                    signed = value;
                }
            }
            if (best < 12 || Math.abs(offset) >= 1.75) { continue; }
            double l = Math.abs(gradient(pixels, width, height, x + (offset - .25) * nx, y + (offset - .25) * ny, nx, ny));
            double r = Math.abs(gradient(pixels, width, height, x + (offset + .25) * nx, y + (offset + .25) * ny, nx, ny));
            double denominator = l - 2 * best + r;
            if (denominator < -1e-6) { offset += Math.max(-.25, Math.min(.25, .125 * (l - r) / denominator)); }
            points.add(new Point(x + offset * nx, y + offset * ny));
            covered[ray] = true;
            if (signed > 0) { positive++; }
            else { negative++; }
        }
        for (int ray = 0; ray < covered.length * 2; ray++) {
            gap = covered[ray % covered.length] ? 0 : gap + 1;
            longestGap = Math.max(longestGap, gap);
        }
        if (points.size() < covered.length * .85 || longestGap > 12
                || Math.max(positive, negative) < points.size() * .85) { return null; }
        RotatedRect fit = initial;
        for (int iteration = 0; iteration < 3; iteration++) {
            MatOfPoint2f data = new MatOfPoint2f(points.toArray(new Point[0]));
            try { fit = Imgproc.fitEllipse(data); }
            finally { data.release(); }
            if (!sizeMatches(fit, expected)) { return null; }
            double[] errors = new double[points.size()];
            for (int i = 0; i < errors.length; i++) { errors[i] = error(points.get(i), fit); }
            Arrays.sort(errors);
            double limit = Math.max(.8, errors[errors.length / 2] * 3);
            List<Point> inliers = new ArrayList<>();
            for (Point point : points) { if (error(point, fit) <= limit) { inliers.add(point); } }
            if (inliers.size() < covered.length * .8) { return null; }
            points = inliers;
        }
        double rms = residual(points.toArray(new Point[0]), fit);
        if (rms > Math.max(.6, expected * .01)
                || Math.hypot(fit.center.x - initial.center.x, fit.center.y - initial.center.y) > 1.5) { return null; }
        return new Feature(fit, rms, points.size() / (double) covered.length);
    }

    private static double residual(Point[] points, RotatedRect fit) {
        double total = 0;
        for (Point point : points) {
            double e = error(point, fit);
            total += e * e;
        }
        return Math.sqrt(total / points.length);
    }

    private static double error(Point p, RotatedRect e) {
        double a = Math.toRadians(e.angle), x = p.x - e.center.x, y = p.y - e.center.y;
        double rx = (x * Math.cos(a) + y * Math.sin(a)) * 2 / e.size.width;
        double ry = (-x * Math.sin(a) + y * Math.cos(a)) * 2 / e.size.height;
        return Math.abs(Math.hypot(rx, ry) - 1) * Math.sqrt(e.size.width * e.size.height) / 2;
    }

    private static double gradient(byte[] pixels, int width, int height, double x, double y, double nx, double ny) {
        return sample(pixels, width, height, x + nx, y + ny) - sample(pixels, width, height, x - nx, y - ny);
    }

    private static double sample(byte[] pixels, int width, int height, double x, double y) {
        if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) { return 0; }
        int ix = (int) x, iy = (int) y;
        double fx = x - ix, fy = y - iy;
        int index = iy * width + ix;
        return (1 - fy) * ((1 - fx) * Byte.toUnsignedInt(pixels[index]) + fx * Byte.toUnsignedInt(pixels[index + 1]))
                + fy * ((1 - fx) * Byte.toUnsignedInt(pixels[index + width]) + fx * Byte.toUnsignedInt(pixels[index + width + 1]));
    }
}
