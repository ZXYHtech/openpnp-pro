package org.openpnp.corexy.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;

final class CoreXYCompatibilityCheck {
    private CoreXYCompatibilityCheck() {
    }

    static void verify() throws Exception {
        Method plan = AbstractMotionPlanner.class.getDeclaredMethod(
                "createBacklashCompensatedMotion",
                HeadMountable.class,
                double.class,
                AxesLocation.class,
                AxesLocation.class,
                MotionOption[].class);
        requireProtected(plan, AxesLocation.class);

        Method add = AbstractMotionPlanner.class.getDeclaredMethod(
                "addMotion",
                HeadMountable.class,
                double.class,
                AxesLocation.class,
                AxesLocation.class,
                int.class);
        requireProtected(add, org.openpnp.model.Motion.class);

        AbstractMotionPlanner.class.getDeclaredMethod(
                "waitForCompletion", HeadMountable.class, CompletionType.class);
        AbstractMotionPlanner.class.getDeclaredMethod("setGlobalOffsets", AxesLocation.class);
        AbstractMotionPlanner.class.getDeclaredMethod("home");
        AbstractMotionPlanner.class.getDeclaredMethod("unhome");
        ReferenceMachine.class.getDeclaredMethod("home");
        ReferenceMachine.class.getDeclaredMethod("setHomed", boolean.class);
        // Resolve advice parameter types before installing the transformer. Otherwise a
        // camera-only stock machine can load ReferenceNozzle recursively during transformation.
        org.openpnp.machine.reference.ReferenceNozzle.class.getDeclaredMethod("toHeadLocation",
                org.openpnp.model.Location.class, org.openpnp.model.Location.class,
                org.openpnp.spi.Locatable.LocationOption[].class);
        org.openpnp.machine.reference.ReferenceNozzle.class.getDeclaredMethod("toHeadMountableLocation",
                org.openpnp.model.Location.class, org.openpnp.model.Location.class,
                org.openpnp.spi.Locatable.LocationOption[].class);
        org.openpnp.machine.reference.ReferenceNozzle.class.getDeclaredMethod("getCameraToolCalibratedOffset",
                org.openpnp.spi.Camera.class);
    }

    private static void requireProtected(Method method, Class<?> returnType) {
        if (!Modifier.isProtected(method.getModifiers())
                || !returnType.equals(method.getReturnType())) {
            throw new IllegalStateException("Unsupported OpenPnP method: " + method);
        }
    }
}
