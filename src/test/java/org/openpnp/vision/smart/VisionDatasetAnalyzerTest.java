package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisionDatasetAnalyzerTest {
    @TempDir
    File temporaryDirectory;

    @Test
    void aggregatesOnlyIncludedDiskBackedSamples() throws Exception {
        OfflineVisionSample dark = sample("dark.png", 30, 80);
        OfflineVisionSample bright = sample("bright.png", 190, 245);
        OfflineVisionSample ignored = sample("ignored.png", 100, 120);
        ignored.setIncluded(false);

        VisionDatasetAnalysis result = VisionDatasetAnalyzer.analyze(Arrays.asList(dark, bright, ignored));

        assertEquals(2, result.getSampleCount());
        assertTrue(result.getMaxBrightness() > result.getMinBrightness());
        assertTrue(result.getBrightnessSpread() > 100);
        assertTrue(result.toChineseSummary().contains("历史样本 2 张"));
        assertTrue(result.buildAdvice().contains("光照变化较大"));
    }

    private OfflineVisionSample sample(String name, int leftGray, int rightGray) throws Exception {
        BufferedImage image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int gray = x < image.getWidth() / 2 ? leftGray : rightGray;
                image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
            }
        }
        File file = new File(temporaryDirectory, name);
        ImageIO.write(image, "png", file);
        return OfflineVisionSample.analyze(file, OfflineVisionSample.Source.IMPORTED);
    }
}
