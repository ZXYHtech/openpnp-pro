package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Actuator;

class FixedMarkerCaptureTest {
    @Test
    void reacquiresIlluminationAfterHomeAndUsesSettledFrames() throws Exception {
        AtomicBoolean light = new AtomicBoolean(false);
        List<String> calls = new ArrayList<>();
        BufferedImage frame = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        Actuator lamp = (Actuator) Proxy.newProxyInstance(Actuator.class.getClassLoader(), new Class<?>[] {Actuator.class},
                (proxy, method, args) -> light.get());
        Camera camera = (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[] {Camera.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLightActuator")) { return lamp; }
                    calls.add(method.getName());
                    if (method.getName().equals("actuateLightBeforeCapture")) {
                        light.set(true);
                        return null;
                    }
                    if (method.getName().equals("settleAndCapture") || method.getName().equals("capture")) {
                        assertTrue(light.get(), "A native Home may have switched this light off.");
                        return frame;
                    }
                    throw new AssertionError("Unexpected camera operation: " + method.getName());
                });
        assertSame(frame, MarkerFrameCapture.capture(camera));
        assertSame(frame, MarkerFrameCapture.capture(camera)); // SettlingOptimizer must get a raw frame once lit.
        light.set(false); // Visual Home / bottom-camera anti-glare changes shared lighting state.
        assertSame(frame, MarkerFrameCapture.capture(camera));
        assertEquals(Arrays.asList("actuateLightBeforeCapture", "settleAndCapture",
                "actuateLightBeforeCapture", "capture",
                "actuateLightBeforeCapture", "settleAndCapture"), calls);
    }

    @Test
    void lightFailureStopsBeforeCapturingAnUnlitFrame() {
        Exception failure = new Exception("Light actuator unavailable");
        Camera camera = (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(), new Class<?>[] {Camera.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLightActuator")) { return null; }
                    if (method.getName().equals("actuateLightBeforeCapture")) { throw failure; }
                    throw new AssertionError("Must not capture after light failure");
                });
        assertSame(failure, assertThrows(Exception.class, () -> MarkerFrameCapture.capture(camera)));
    }
}