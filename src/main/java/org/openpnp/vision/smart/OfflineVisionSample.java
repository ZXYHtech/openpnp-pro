/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/** A disk-backed historical vision sample. Full images are loaded only on demand. */
public final class OfflineVisionSample {
    public enum Source {
        IMPORTED("历史导入"), TOP_CAMERA("顶部相机"), BOTTOM_CAMERA("底部相机"), OTHER_CAMERA("其他相机");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final File file;
    private final Source source;
    private final int width;
    private final int height;
    private final RoiAnalysis analysis;
    private boolean included = true;

    private OfflineVisionSample(File file, Source source, int width, int height, RoiAnalysis analysis) {
        this.file = file;
        this.source = source;
        this.width = width;
        this.height = height;
        this.analysis = analysis;
    }

    public static OfflineVisionSample analyze(File file, Source source) throws Exception {
        BufferedImage image = readImage(file);
        return new OfflineVisionSample(file.getCanonicalFile(), source, image.getWidth(), image.getHeight(),
                SmartVisionAnalyzer.analyze(image));
    }

    public BufferedImage loadImage() throws Exception {
        return readImage(file);
    }

    private static BufferedImage readImage(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IllegalArgumentException(file.getName() + " 不是受支持的图片格式");
        }
        return image;
    }

    public File getFile() { return file; }
    public Source getSource() { return source; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public RoiAnalysis getAnalysis() { return analysis; }
    public boolean isIncluded() { return included; }
    public void setIncluded(boolean included) { this.included = included; }
}
