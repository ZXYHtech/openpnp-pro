/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;

/** Visual geometry conventions shared by the 3D safety workbench and tests. */
public final class SafetyWorkbenchGeometry {
    private SafetyWorkbenchGeometry() {
    }

    /**
     * Returns a stable physical work-surface Z instead of using the Z-axis lower soft limit.
     * Imported body coordinates store their bottom Z, so their top faces are the useful samples.
     */
    public static double workSurfaceZ(SafetyWorkbenchModel model) {
        List<Double> surfaces = new ArrayList<>();
        for (SafetyObject object : model.getObjects()) {
            if (object == null || !object.enabled || !isWorkSurfaceObject(object.type)) {
                continue;
            }
            double top = object.z + object.height;
            if (Double.isFinite(top)) {
                surfaces.add(top);
            }
        }
        if (surfaces.isEmpty()) {
            return model.getMinZ() <= 0 && model.getMaxZ() >= 0
                    ? 0 : (model.getMinZ() + model.getMaxZ()) / 2.0;
        }
        Collections.sort(surfaces);
        int middle = surfaces.size() / 2;
        if ((surfaces.size() & 1) == 1) {
            return surfaces.get(middle);
        }
        return (surfaces.get(middle - 1) + surfaces.get(middle)) / 2.0;
    }

    public static boolean hasCentralOpening(SafetyObject object) {
        return (object.type == ObjectType.BOTTOM_CAMERA
                || object.type == ObjectType.NOZZLE_CHANGER)
                && object.apertureWidth > 0 && object.apertureDepth > 0;
    }

    private static boolean isWorkSurfaceObject(ObjectType type) {
        return type == ObjectType.FEEDER || type == ObjectType.BOTTOM_CAMERA
                || type == ObjectType.NOZZLE_CHANGER || type == ObjectType.BOARD
                || type == ObjectType.LOOSE_PARTS_FEEDER;
    }
}