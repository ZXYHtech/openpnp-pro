package org.openpnp.corexy.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.model.Configuration;
import org.openpnp.util.CameraPreviewDemand;

class CalibrationPreviewDemandTest {
    @TempDir Path temporary;

    @Test
    void concurrentPreviewClientsReleaseWithoutChangingSavedCameraSettings() throws Exception {
        Configuration.initialize(temporary.toFile());
        Configuration.get().setMachine(new ReferenceMachine() {
            @Override public boolean isBusy() { return true; }
        });
        SimulatedUpCamera camera = new SimulatedUpCamera();
        camera.setPreviewFps(0);
        camera.setSuspendPreviewInTasks(true);
        assertTrue(camera.isPreviewSuspended());
        try {
            CameraPreviewDemand.acquire(camera);
            CameraPreviewDemand.acquire(camera);
            assertFalse(camera.isPreviewSuspended());
            CameraPreviewDemand.release(camera);
            assertFalse(camera.isPreviewSuspended(), "Another viewer still needs live frames.");
            CameraPreviewDemand.release(camera);
            assertTrue(camera.isPreviewSuspended());
            assertEquals(0, camera.getPreviewFps());
            assertTrue(camera.isSuspendPreviewInTasks());
        }
        finally {
            CameraPreviewDemand.release(camera);
            camera.close();
        }
    }
}
