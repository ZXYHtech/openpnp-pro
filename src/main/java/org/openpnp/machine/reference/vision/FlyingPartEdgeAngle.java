package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.Utils2D;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Robust long-edge angle for small rectangular chips; excludes corner glare and hull outliers. */
public final class FlyingPartEdgeAngle {
    private FlyingPartEdgeAngle() { }

    public static double refine(BufferedImage image, RotatedRect rect) throws Exception {
        boolean swapped = rect.size.width < rect.size.height;
        double width = Math.max(rect.size.width, rect.size.height), height = Math.min(rect.size.width, rect.size.height);
        if (width < 25 || height < 12 || width / height < 1.3) {
            throw new Exception("该器件边缘不足以进行可靠的飞拍角度复核。");
        }
        double angle = Math.toRadians(rect.angle + (swapped ? 90 : 0));
        double ux = Math.cos(angle), uy = Math.sin(angle), vx = -uy, vy = ux;
        Mat source = OpenCvUtils.toMat(image), gray = new Mat();
        try {
            if (source.channels() == 1) {
                source.copyTo(gray);
            } else {
                Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
            }
            byte[] pixels = new byte[gray.rows() * gray.cols()];
            gray.get(0, 0, pixels);
            double[] directions = new double[2];
            for (int side = 0; side < 2; side++) {
                int sign = side == 0 ? 1 : -1;
                double nominal = side == 0 ? -height / 2 : height / 2;
                List<Point> edge = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    double u = width * (-.38 + .76 * i / 39), best = 8, at = Double.NaN;
                    for (double v = nominal - 3; v <= nominal + 3; v += .125) {
                        double outer = pixel(pixels, gray.cols(), gray.rows(), rect.center.x + u * ux + (v - .5) * vx,
                                rect.center.y + u * uy + (v - .5) * vy);
                        double inner = pixel(pixels, gray.cols(), gray.rows(), rect.center.x + u * ux + (v + .5) * vx,
                                rect.center.y + u * uy + (v + .5) * vy);
                        double gradient = (inner - outer) * sign;
                        double inside = pixel(pixels, gray.cols(), gray.rows(), rect.center.x + u * ux + (v + 3 * sign) * vx,
                                rect.center.y + u * uy + (v + 3 * sign) * vy);
                        double outside = pixel(pixels, gray.cols(), gray.rows(), rect.center.x + u * ux + (v - 3 * sign) * vx,
                                rect.center.y + u * uy + (v - 3 * sign) * vy);
                        if (gradient > best && inside - outside > 15) {
                            best = gradient;
                            at = v;
                        }
                    }
                    if (Double.isFinite(at)) {
                        edge.add(new Point(rect.center.x + u * ux + at * vx, rect.center.y + u * uy + at * vy));
                    }
                }
                if (edge.size() < 16) {
                    throw new Exception("飞拍器件长边可用点不足，请降低速度或检查反光。");
                }
                MatOfPoint2f points = new MatOfPoint2f();
                Mat line = new Mat();
                points.fromList(edge);
                try {
                    Imgproc.fitLine(points, line, Imgproc.DIST_HUBER, 0, .01, .01);
                    double dx = line.get(0, 0)[0], dy = line.get(1, 0)[0];
                    double cx = line.get(2, 0)[0], cy = line.get(3, 0)[0];
                    double error = 0;
                    for (Point p : edge) {
                        double residual = Math.abs((p.x - cx) * dy - (p.y - cy) * dx);
                        error += Math.min(1, residual * residual);
                    }
                    if (Math.sqrt(error / edge.size()) > .6) {
                        throw new Exception("飞拍长边拟合残差过大。");
                    }
                    directions[side] = Utils2D.angleNorm(Math.toDegrees(Math.atan2(dy, dx)) - Math.toDegrees(angle), 90);
                } finally {
                    points.release();
                    line.release();
                }
            }
            if (Math.abs(directions[0] - directions[1]) > .6
                    || Math.abs(directions[0] + directions[1]) / 2 > 4) {
                throw new Exception("飞拍两条长边角度不一致，请检查反光或运动模糊。");
            }
            return rect.angle + (directions[0] + directions[1]) / 2;
        } finally {
            source.release();
            gray.release();
        }
    }

    private static double pixel(byte[] pixels, int width, int height, double x, double y) {
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
        if (ix < 0 || iy < 0 || ix + 1 >= width || iy + 1 >= height) {
            return Double.NaN;
        }
        double a = x - ix, b = y - iy;
        int at = iy * width + ix;
        return (pixels[at] & 255) * (1 - a) * (1 - b) + (pixels[at + 1] & 255) * a * (1 - b)
                + (pixels[at + width] & 255) * (1 - a) * b + (pixels[at + width + 1] & 255) * a * b;
    }
}
