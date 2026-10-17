package org.openpnp.corexy.precision;

import java.awt.image.BufferedImage;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Actuator;

/** Restores capture prerequisites that native Home and other cameras can change. */
final class MarkerFrameCapture {
    private MarkerFrameCapture() {
    }

    static BufferedImage capture(Camera camera) throws Exception {
        // Visual Home and bottom-camera anti-glare can turn this camera's light off.
        // Re-establish illumination for every observation, including Home alignment.
        // The calibration/verification transaction owns final light cleanup.
        Actuator light = camera.getLightActuator();
        boolean wasOn = light == null || Boolean.TRUE.equals(light.isActuated());
        camera.actuateLightBeforeCapture();
        // Only a lighting transition needs camera settling. Once lit, use raw frames:
        // the calibration path and SettlingOptimizer own the measured motion delay.
        return wasOn ? camera.capture() : camera.settleAndCapture();
    }
}