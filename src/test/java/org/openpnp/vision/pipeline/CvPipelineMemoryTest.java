package org.openpnp.vision.pipeline;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class CvPipelineMemoryTest {
    @Test
    void releaseDropsLastCapturedHeapImage() {
        CvPipeline pipeline = new CvPipeline();
        pipeline.setLastCapturedImage(
                new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR));

        pipeline.release();

        assertNull(pipeline.getLastCapturedImage());
    }
}
