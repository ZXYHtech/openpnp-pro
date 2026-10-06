/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/** Geometry-only support for selecting and re-teaching workbench vertices. */
public final class SafetyWorkbenchVertexEditor {
    public static final class Vertex {
        public final SafetyObject object;
        public final int polygonIndex;
        public final boolean maximumX;
        public final boolean maximumY;
        public final boolean maximumZ;

        private Vertex(SafetyObject object, int polygonIndex, boolean maximumX,
                boolean maximumY, boolean maximumZ) {
            this.object = object;
            this.polygonIndex = polygonIndex;
            this.maximumX = maximumX;
            this.maximumY = maximumY;
            this.maximumZ = maximumZ;
        }

        public Location getLocation() {
            if (object.type.isReferencePoint()) {
                return location(object.x, object.y, object.z);
            }
            if (polygonIndex >= 0 && polygonIndex < object.polygon.size()) {
                Point2 point = object.polygon.get(polygonIndex);
                return location(object.x + point.x, object.y + point.y,
                        object.z + (maximumZ ? object.height : 0));
            }
            return location(object.x + (maximumX ? object.width : 0),
                    object.y + (maximumY ? object.depth : 0),
                    object.z + (maximumZ ? object.height : 0));
        }

        public String getLabel() {
            if (object.type.isReferencePoint()) {
                return object.name + " · 参考点";
            }
            if (polygonIndex >= 0) {
                return object.name + " · 轮廓点 " + (polygonIndex + 1)
                        + (maximumZ ? "（顶部）" : "（底部）");
            }
            return object.name + " · " + (maximumX ? "X+" : "X−") + " / "
                    + (maximumY ? "Y+" : "Y−") + " / "
                    + (maximumZ ? "Z+" : "Z−");
        }
    }

    private SafetyWorkbenchVertexEditor() {
    }

    public static List<Vertex> vertices(SafetyObject object) {
        if (object == null) {
            return Collections.emptyList();
        }
        if (object.type.isReferencePoint()) {
            return Collections.singletonList(new Vertex(object, -1, false, false, false));
        }
        List<Vertex> vertices = new ArrayList<>();
        if (object.shape == Shape.POLYGON && !object.polygon.isEmpty()) {
            for (int index = 0; index < object.polygon.size(); index++) {
                vertices.add(new Vertex(object, index, false, false, false));
                vertices.add(new Vertex(object, index, false, false, true));
            }
            return vertices;
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    vertices.add(new Vertex(object, -1, x == 1, y == 1, z == 1));
                }
            }
        }
        return vertices;
    }

    public static void apply(Vertex vertex, Location taughtLocation) {
        if (vertex == null || taughtLocation == null) {
            throw new IllegalArgumentException("顶点和示教坐标不能为空。");
        }
        SafetyObject object = vertex.object;
        Location taught = taughtLocation.convertToUnits(LengthUnit.Millimeters);
        if (object.type.isReferencePoint()) {
            object.x = taught.getX();
            object.y = taught.getY();
            object.z = taught.getZ();
            return;
        }
        if (vertex.polygonIndex >= 0 && vertex.polygonIndex < object.polygon.size()) {
            Point2 point = object.polygon.get(vertex.polygonIndex);
            point.x = taught.getX() - object.x;
            point.y = taught.getY() - object.y;
            applyZ(object, vertex.maximumZ, taught.getZ());
            return;
        }
        double oppositeX = object.x + (vertex.maximumX ? 0 : object.width);
        double oppositeY = object.y + (vertex.maximumY ? 0 : object.depth);
        double oppositeZ = object.z + (vertex.maximumZ ? 0 : object.height);
        object.x = Math.min(taught.getX(), oppositeX);
        object.y = Math.min(taught.getY(), oppositeY);
        object.z = Math.min(taught.getZ(), oppositeZ);
        object.width = Math.max(0.01, Math.abs(taught.getX() - oppositeX));
        object.depth = Math.max(0.01, Math.abs(taught.getY() - oppositeY));
        object.height = Math.max(0.01, Math.abs(taught.getZ() - oppositeZ));
    }

    private static void applyZ(SafetyObject object, boolean maximumZ, double value) {
        double opposite = object.z + (maximumZ ? 0 : object.height);
        object.z = Math.min(value, opposite);
        object.height = Math.max(0.01, Math.abs(value - opposite));
    }

    private static Location location(double x, double y, double z) {
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }
}
