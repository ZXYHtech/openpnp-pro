/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.util.OpenCvUtils;

/** Locates the exact point selected before a screen jog in the settled post-jog frame. */
final class CameraViewPrecisionLocator {
    static final double MIN_CONFIDENCE = 0.78;
    private static final int PATCH_SIZE = 64;
    private static final int MIN_PATCH_SIZE = 20;

    static final class Target {
        final BufferedImage patch;
        final double anchorX;
        final double anchorY;

        Target(BufferedImage patch, double anchorX, double anchorY) {
            this.patch = patch;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }
    }

    static final class Result {
        final double sourceX;
        final double sourceY;
        final double confidence;

        Result(double sourceX, double sourceY, double confidence) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.confidence = confidence;
        }
    }

    private CameraViewPrecisionLocator() {
    }

    static Target capture(BufferedImage frame, double sourceX, double sourceY) {
        if (frame == null || sourceX < 0 || sourceY < 0
                || sourceX >= frame.getWidth() || sourceY >= frame.getHeight()) {
            return null;
        }
        int left = Math.max(0, (int) Math.round(sourceX) - PATCH_SIZE / 2);
        int top = Math.max(0, (int) Math.round(sourceY) - PATCH_SIZE / 2);
        int right = Math.min(frame.getWidth(), left + PATCH_SIZE);
        int bottom = Math.min(frame.getHeight(), top + PATCH_SIZE);
        left = Math.max(0, right - PATCH_SIZE);
        top = Math.max(0, bottom - PATCH_SIZE);
        int width = right - left;
        int height = bottom - top;
        if (width < MIN_PATCH_SIZE || height < MIN_PATCH_SIZE) {
            return null;
        }
        BufferedImage patch = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = patch.createGraphics();
        try {
            graphics.drawImage(frame, 0, 0, width, height,
                    left, top, right, bottom, null);
        }
        finally {
            graphics.dispose();
        }
        return new Target(patch, sourceX - left, sourceY - top);
    }

    static Result locate(BufferedImage frame, Target target) {
        if (frame == null || target == null || target.patch == null) {
            return null;
        }
        if (!hasEnoughTexture(target.patch)) {
            return null;
        }
        Mat frameMat = null;
        Mat patchMat = null;
        Mat frameGray = null;
        Mat patchGray = null;
        Mat search = null;
        Mat scores = null;
        try {
            frameMat = OpenCvUtils.toMat(frame);
            patchMat = OpenCvUtils.toMat(target.patch);
            frameGray = toGray(frameMat);
            patchGray = toGray(patchMat);
            int marginX = Math.max(0, frameGray.cols() / 10);
            int marginY = Math.max(0, frameGray.rows() / 10);
            int searchWidth = frameGray.cols() - 2 * marginX;
            int searchHeight = frameGray.rows() - 2 * marginY;
            if (searchWidth < patchGray.cols() || searchHeight < patchGray.rows()) {
                marginX = 0;
                marginY = 0;
                searchWidth = frameGray.cols();
                searchHeight = frameGray.rows();
            }
            if (searchWidth < patchGray.cols() || searchHeight < patchGray.rows()) {
                return null;
            }
            search = frameGray.submat(new Rect(marginX, marginY, searchWidth, searchHeight));
            scores = new Mat(search.rows() - patchGray.rows() + 1,
                    search.cols() - patchGray.cols() + 1, CvType.CV_32FC1);
            Imgproc.matchTemplate(search, patchGray, scores, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult match = Core.minMaxLoc(scores);
            if (!Double.isFinite(match.maxVal)) {
                return null;
            }
            return new Result(marginX + match.maxLoc.x + target.anchorX,
                    marginY + match.maxLoc.y + target.anchorY, match.maxVal);
        }
        finally {
            if (scores != null) {
                scores.release();
            }
            if (search != null) {
                search.release();
            }
            if (patchGray != null) {
                patchGray.release();
            }
            if (frameGray != null) {
                frameGray.release();
            }
            if (patchMat != null) {
                patchMat.release();
            }
            if (frameMat != null) {
                frameMat.release();
            }
        }
    }

    static Result locateNear(BufferedImage frame, Target target, double expectedX,
            double expectedY, int radiusX, int radiusY) {
        if (frame == null || target == null || target.patch == null
                || radiusX <= 0 || radiusY <= 0) {
            return null;
        }
        int paddingX = target.patch.getWidth() / 2;
        int paddingY = target.patch.getHeight() / 2;
        int left = Math.max(0, (int) Math.floor(expectedX - radiusX - paddingX));
        int top = Math.max(0, (int) Math.floor(expectedY - radiusY - paddingY));
        int right = Math.min(frame.getWidth(),
                (int) Math.ceil(expectedX + radiusX + paddingX));
        int bottom = Math.min(frame.getHeight(),
                (int) Math.ceil(expectedY + radiusY + paddingY));
        if (right - left < target.patch.getWidth()
                || bottom - top < target.patch.getHeight()) {
            return null;
        }
        int localWidth = right - left;
        int localHeight = bottom - top;
        BufferedImage localFrame = new BufferedImage(localWidth, localHeight,
                BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = localFrame.createGraphics();
        try {
            graphics.drawImage(frame, 0, 0, localWidth, localHeight,
                    left, top, right, bottom, null);
        }
        finally {
            graphics.dispose();
        }
        Result local = locate(localFrame, target);
        if (local == null) {
            return null;
        }
        double sourceX = left + local.sourceX;
        double sourceY = top + local.sourceY;
        if (Math.abs(sourceX - expectedX) > radiusX
                || Math.abs(sourceY - expectedY) > radiusY) {
            return null;
        }
        return new Result(sourceX, sourceY, local.confidence);
    }

    private static Mat toGray(Mat source) {
        if (source.channels() == 1) {
            return source.clone();
        }
        Mat gray = new Mat();
        Imgproc.cvtColor(source, gray,
                source.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
        return gray;
    }

    private static boolean hasEnoughTexture(BufferedImage image) {
        long sum = 0;
        long sumOfSquares = 0;
        int minimum = 255;
        int maximum = 0;
        int count = image.getWidth() * image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int luminance = (77 * ((rgb >>> 16) & 0xff)
                        + 150 * ((rgb >>> 8) & 0xff)
                        + 29 * (rgb & 0xff)) >>> 8;
                sum += luminance;
                sumOfSquares += (long) luminance * luminance;
                minimum = Math.min(minimum, luminance);
                maximum = Math.max(maximum, luminance);
            }
        }
        double mean = sum / (double) count;
        double variance = sumOfSquares / (double) count - mean * mean;
        return maximum - minimum >= 12 && variance >= 9.0;
    }
}
