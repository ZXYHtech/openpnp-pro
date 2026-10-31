package org.openpnp.machine.reference;

import org.openpnp.machine.reference.camera.ImageCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;

/** Production uses real devices; legacy simulation types remain readable for configuration migration. */
public final class ProductionFeaturePolicy {
    public static final String HARDWARE_ONLY = "openpnp.pro.hardwareOnly";
    private ProductionFeaturePolicy() { }

    public static boolean hardwareOnly() { return Boolean.parseBoolean(System.getProperty(HARDWARE_ONLY, "true")); }

    public static void requireHardware(Machine machine) throws Exception {
        if (!hardwareOnly()) { return; }
        for (var driver : machine.getDrivers()) {
            if (driver instanceof NullDriver) { throw new Exception("本版本已移除模拟机器，请配置真实控制器后启用。"); }
        }
        for (var camera : machine.getCameras()) { requireRealCamera(camera); }
        for (var head : machine.getHeads()) {
            for (var camera : head.getCameras()) { requireRealCamera(camera); }
        }
    }

    public static void requireRealCamera(Camera camera) {
        if (hardwareOnly() && (camera instanceof ImageCamera || camera instanceof SimulatedUpCamera)) {
            throw new IllegalStateException("本版本已移除模拟相机，请配置真实相机。");
        }
    }
}
