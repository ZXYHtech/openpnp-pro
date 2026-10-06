package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.spi.Camera;

class VisionCaptureStoreTest {
    @TempDir
    File temporaryDirectory;

    @Test
    void mapsOpenPnpLookingDirectionToPhysicalCameraPosition() {
        assertEquals(VisionCaptureStore.Position.TOP, VisionCaptureStore.classify(Camera.Looking.Down));
        assertEquals(VisionCaptureStore.Position.BOTTOM, VisionCaptureStore.classify(Camera.Looking.Up));
    }

    @Test
    void storesPngInDatedSafeBottomCameraFolder() throws Exception {
        Camera camera = camera("../底部 相机:*?", Camera.Looking.Up);
        BufferedImage image = new BufferedImage(16, 10, BufferedImage.TYPE_INT_RGB);

        VisionCaptureStore.CaptureResult result = new VisionCaptureStore(temporaryDirectory).save(camera, image);

        assertEquals(VisionCaptureStore.Position.BOTTOM, result.getPosition());
        assertTrue(result.getFile().isFile());
        assertTrue(result.getFile().getAbsolutePath().contains(File.separator + "bottom" + File.separator));
        assertTrue(result.getFile().getCanonicalPath().startsWith(temporaryDirectory.getCanonicalPath()));
        assertTrue(result.getFile().getName().endsWith(".png"));
        assertTrue(!result.getFile().getName().contains(".."));
    }

    private static Camera camera(String name, Camera.Looking looking) {
        return (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[] { Camera.class },
                (proxy, method, arguments) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("getLooking".equals(method.getName())) {
                        return looking;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == double.class) {
                        return 0d;
                    }
                    return null;
                });
    }
}
