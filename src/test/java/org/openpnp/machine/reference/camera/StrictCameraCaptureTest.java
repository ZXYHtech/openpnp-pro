package org.openpnp.machine.reference.camera;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;

class StrictCameraCaptureTest {
    @TempDir Path temporary;
    static class Camera extends OpenPnpCaptureCamera {
        boolean available;
        BufferedImage frame;
        @Override public boolean ensureOpen() { return available;
        }
        @Override public BufferedImage internalCapture() { return frame;
        }
        @Override public BufferedImage transformImage(BufferedImage raw) { return raw;
        }
    }
    Camera camera;
    @BeforeEach void setup() throws Exception {
        Configuration.initialize(temporary.toFile());
        Configuration.get().setMachine(new ReferenceMachine());
        camera = new Camera();
        camera.setName("Top");
    }
    @Test void previewHasPlaceholderButVisionRejectsMissingDeviceInChinese() {
        assertNotNull(camera.captureTransformed());
        Exception failure = assertThrows(ReferenceCamera.CameraCaptureException.class, camera::capture);
        assertTrue(failure.getMessage().contains("Top"));
        assertTrue(failure.getMessage().contains("设备未打开"));
        assertTrue(failure.getMessage().contains(camera.getId()));
        assertNotNull(camera.captureTransformed());
    }
    @Test void noFrameIsReportedAndLaterSuccessfulCaptureRecovers() throws Exception {
        camera.available = true;
        Exception failure = assertThrows(ReferenceCamera.CameraCaptureException.class, camera::capture);
        assertTrue(failure.getMessage().contains("仍未收到图像"));
        camera.frame = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        assertSame(camera.frame, camera.capture());
    }
    @Test void previewOnOtherThreadCannotPoisonVisionCapture() throws Exception {
        Thread preview = new Thread(() -> assertNotNull(camera.captureTransformed()));
        preview.start();
        preview.join(5000);
        assertFalse(preview.isAlive());
        camera.available = true;
        camera.frame = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        assertSame(camera.frame, camera.capture());
    }
}
