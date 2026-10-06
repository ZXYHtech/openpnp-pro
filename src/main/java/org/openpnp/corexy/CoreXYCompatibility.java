package org.openpnp.corexy;

import java.lang.reflect.Method;

import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.HeadMountable;

/** Performs a no-hardware API compatibility check before OpenPnP starts. */
public final class CoreXYCompatibility {
    private CoreXYCompatibility() {
    }

    public static void main(String[] args) throws Exception {
        Method compensationHook = AbstractMotionPlanner.class.getDeclaredMethod(
                "createBacklashCompensatedMotion",
                HeadMountable.class,
                double.class,
                AxesLocation.class,
                AxesLocation.class,
                MotionOption[].class);
        if (!AxesLocation.class.equals(compensationHook.getReturnType())) {
            throw new IllegalStateException("Unsupported OpenPnP compensation hook return type.");
        }
        Class.forName("org.openpnp.corexy.CoreXYAdvancedMotionPlanner", true,
                CoreXYCompatibility.class.getClassLoader());

        Package openPnpPackage = AbstractMotionPlanner.class.getPackage();
        String version = openPnpPackage.getImplementationVersion();
        System.out.println("CoreXY extension compatibility check passed. OpenPnP version: "
                + (version == null ? "unknown" : version));
    }
}
