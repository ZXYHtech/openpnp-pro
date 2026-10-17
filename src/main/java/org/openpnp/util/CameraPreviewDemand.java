package org.openpnp.util;

import java.util.Map;
import java.util.WeakHashMap;
import org.openpnp.spi.Camera;
import org.openpnp.machine.reference.camera.AbstractBroadcastingCamera;

/** Temporary preview clients do not modify persisted camera FPS or task-suspension preferences. */
public final class CameraPreviewDemand {
    private static final Map<Camera, Integer> REQUESTS = new WeakHashMap<>();
    private CameraPreviewDemand() { }

    public static synchronized void acquire(Camera camera) {
        REQUESTS.put(camera, REQUESTS.getOrDefault(camera, 0) + 1);
        wake(camera);
    }

    public static synchronized void release(Camera camera) {
        int remaining = REQUESTS.getOrDefault(camera, 0) - 1;
        if (remaining <= 0) { REQUESTS.remove(camera); }
        else { REQUESTS.put(camera, remaining); }
        wake(camera);
    }

    public static synchronized boolean isRequested(Camera camera) { return REQUESTS.containsKey(camera); }

    private static void wake(Camera camera) {
        if (camera instanceof AbstractBroadcastingCamera) {
            AbstractBroadcastingCamera broadcasting = (AbstractBroadcastingCamera) camera;
            broadcasting.setPreviewFps(broadcasting.getPreviewFps());
        }
    }
}
