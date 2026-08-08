/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.imageio.ImageIO;

import org.openpnp.spi.Camera;

/** Stores camera snapshots in dated top/bottom folders without initiating machine motion. */
public final class VisionCaptureStore {
    public enum Position {
        TOP("top", "顶部相机", OfflineVisionSample.Source.TOP_CAMERA),
        BOTTOM("bottom", "底部相机", OfflineVisionSample.Source.BOTTOM_CAMERA),
        OTHER("other", "其他相机", OfflineVisionSample.Source.OTHER_CAMERA);

        private final String folder;
        private final String displayName;
        private final OfflineVisionSample.Source sampleSource;

        Position(String folder, String displayName, OfflineVisionSample.Source sampleSource) {
            this.folder = folder;
            this.displayName = displayName;
            this.sampleSource = sampleSource;
        }

        public String getFolder() { return folder; }
        public String getDisplayName() { return displayName; }
        public OfflineVisionSample.Source getSampleSource() { return sampleSource; }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final File root;

    public VisionCaptureStore(File configurationDirectory) {
        if (configurationDirectory == null) {
            throw new IllegalArgumentException("配置目录不可为空");
        }
        root = new File(configurationDirectory, "vision-captures");
    }

    public static Position classify(Camera.Looking looking) {
        if (looking == Camera.Looking.Down) {
            return Position.TOP;
        }
        if (looking == Camera.Looking.Up) {
            return Position.BOTTOM;
        }
        return Position.OTHER;
    }

    public synchronized CaptureResult save(Camera camera, BufferedImage image) throws Exception {
        if (camera == null || image == null) {
            throw new IllegalArgumentException("相机和图像不可为空");
        }
        Position position = classify(camera.getLooking());
        LocalDateTime now = LocalDateTime.now();
        File directory = new File(new File(root, DAY.format(now)), position.getFolder());
        Files.createDirectories(directory.toPath());
        String cameraName = sanitize(camera.getName());
        File file = new File(directory, FILE_TIME.format(now) + "-" + cameraName + ".png");
        int suffix = 2;
        while (file.exists()) {
            file = new File(directory, FILE_TIME.format(now) + "-" + cameraName + "-" + suffix++ + ".png");
        }
        if (!ImageIO.write(image, "png", file)) {
            throw new IllegalStateException("系统没有可用的 PNG 编码器");
        }
        return new CaptureResult(file.getCanonicalFile(), image, position);
    }

    public File getRoot() {
        return root;
    }

    static String sanitize(String value) {
        String safe = value == null ? "camera" : value.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]+", "-");
        safe = safe.replaceAll("^[.\\-]+|[.\\-]+$", "");
        return safe.isEmpty() ? "camera" : safe.substring(0, Math.min(60, safe.length()));
    }

    public static final class CaptureResult {
        private final File file;
        private final BufferedImage image;
        private final Position position;

        CaptureResult(File file, BufferedImage image, Position position) {
            this.file = file;
            this.image = image;
            this.position = position;
        }

        public File getFile() { return file; }
        public BufferedImage getImage() { return image; }
        public Position getPosition() { return position; }
    }
}
