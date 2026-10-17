package org.openpnp.corexy.precision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.util.OpenCvUtils;

/** Ellipse centres from raw frames. Rejects ambiguous, wrong-sized and clipped markers. */
public final class MarkerDetector {
    public static final class Detection {
        public final double x, y, diameter;
        Detection(double x, double y, double diameter) {
            this.x = x;
            this.y = y;
            this.diameter = diameter;
        }
    }

    public Detection detect(BufferedImage image, double expectedX, double expectedY,
            double diameterPixels, double searchRadius) {
        if (image == null || !Double.isFinite(expectedX) || !Double.isFinite(expectedY)
                || !Double.isFinite(diameterPixels) || diameterPixels < 8
                || !Double.isFinite(searchRadius) || searchRadius < 1) {
            throw new IllegalArgumentException("Marker 图像或像素标定无效；直径至少需要 8 个像素。");
        }
        Mat source = OpenCvUtils.toMat(image), gray = new Mat(), binary = new Mat(), hierarchy = new Mat();
        Mat region = null;
        List<Detection> detections = new ArrayList<>();
        try {
            if (source.channels() == 1) {
                source.copyTo(gray);
            }
            else {
                Imgproc.cvtColor(source, gray, source.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
            }
            // Unrelated bright pads elsewhere in the camera view must not determine the
            // marker threshold. Include the whole largest accepted circle at the search edge.
            double extent = searchRadius + diameterPixels * .65 + 4;
            int left = (int) Math.max(0, Math.floor(expectedX - extent));
            int top = (int) Math.max(0, Math.floor(expectedY - extent));
            int right = (int) Math.min(image.getWidth(), Math.ceil(expectedX + extent));
            int bottom = (int) Math.min(image.getHeight(), Math.ceil(expectedY + extent));
            if (left >= right || top >= bottom) {
                throw new MarkerDetectionException("Marker search region is outside the camera image.");
            }
            region = gray.submat(top, bottom, left, right);
            for (int polarity : new int[] {Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV}) {
                Imgproc.threshold(region, binary, 0, 255, polarity | Imgproc.THRESH_OTSU);
                List<MatOfPoint> contours = new ArrayList<>();
                try {
                    Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE,
                            new Point(left, top));
                    for (MatOfPoint contour : contours) {
                        if (contour.rows() < 12) {
                            continue;
                        }
                        MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                        try {
                            RotatedRect ellipse = Imgproc.fitEllipse(points);
                            double small = Math.min(ellipse.size.width, ellipse.size.height);
                            double large = Math.max(ellipse.size.width, ellipse.size.height);
                            double diameter = Math.sqrt(small * large);
                            double area = Imgproc.contourArea(contour);
                            double perimeter = Imgproc.arcLength(points, true);
                            double circularity = 4 * Math.PI * area / (perimeter * perimeter);
                            if (small / large < .65 || circularity < .65
                                    || diameter < diameterPixels * .7 || diameter > diameterPixels * 1.3
                                    || Math.hypot(ellipse.center.x - expectedX, ellipse.center.y - expectedY) > searchRadius
                                    || ellipse.center.x - large / 2 < 2 || ellipse.center.y - large / 2 < 2
                                    || ellipse.center.x + large / 2 > image.getWidth() - 3
                                    || ellipse.center.y + large / 2 > image.getHeight() - 3) {
                                continue;
                            }
                            Detection detection = new Detection(ellipse.center.x, ellipse.center.y, diameter);
                            boolean duplicate = false;
                            for (Detection previous : detections) {
                                if (Math.hypot(previous.x - detection.x, previous.y - detection.y) < 2) {
                                    duplicate = true;
                                }
                            }
                            if (!duplicate) {
                                detections.add(detection);
                            }
                        }
                        finally {
                            points.release();
                        }
                    }
                }
                finally {
                    for (MatOfPoint contour : contours) {
                        contour.release();
                    }
                }
            }
            detections.sort(Comparator.comparingDouble(d -> Math.hypot(d.x - expectedX, d.y - expectedY)));
            if (detections.isEmpty()) {
                throw new MarkerDetectionException("未找到完整圆形 marker，请检查直径、照明、对焦和相机位置。");
            }
            if (detections.size() > 1) {
                double first = Math.hypot(detections.get(0).x - expectedX, detections.get(0).y - expectedY);
                double second = Math.hypot(detections.get(1).x - expectedX, detections.get(1).y - expectedY);
                if (second - first < diameterPixels * .5) {
                    throw new MarkerDetectionException("存在多个相近 marker，无法可靠判断目标。");
                }
            }
            return detections.get(0);
        }
        finally {
            if (region != null) {
                region.release();
            }
            source.release();
            gray.release();
            binary.release();
            hierarchy.release();
        }
    }
}
