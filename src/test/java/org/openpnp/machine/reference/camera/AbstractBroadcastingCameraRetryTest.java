package org.openpnp.machine.reference.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;

import com.google.common.io.Files;

class AbstractBroadcastingCameraRetryTest {
    @Test
    void unavailableCameraIsNotReopenedForEveryPreviewFrame() throws Exception {
        Configuration.initialize(Files.createTempDir());
        Configuration.get().setMachine(new ReferenceMachine());
        FailingCamera camera = new FailingCamera();

        assertFalse(camera.tryEnsureOpen());
        for (int frame = 0; frame < 150; frame++) {
            assertFalse(camera.tryEnsureOpen());
        }
        assertEquals(1, camera.openAttempts);

        Thread.sleep(35);
        assertFalse(camera.tryEnsureOpen());
        assertEquals(2, camera.openAttempts);

        camera.forceImmediateRetry();
        assertFalse(camera.tryEnsureOpen());
        assertEquals(3, camera.openAttempts);
    }

    private static class FailingCamera extends OpenPnpCaptureCamera {
        int openAttempts;

        @Override
        public synchronized void open() {
            openAttempts++;
            // Simulate a disconnected camera backend that returns without opening a stream.
        }

        @Override
        protected long getOpenRetryDelayMillis() {
            return 25;
        }

        boolean tryEnsureOpen() {
            return ensureOpen();
        }

        void forceImmediateRetry() {
            resetOpenRetry();
        }
    }
}
