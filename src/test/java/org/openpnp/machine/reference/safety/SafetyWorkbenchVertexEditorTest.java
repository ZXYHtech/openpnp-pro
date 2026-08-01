/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Point2;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.Shape;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVertexEditor.Vertex;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class SafetyWorkbenchVertexEditorTest {
    @Test
    void reteachesBoxCornerAgainstOppositeCorner() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject object = model.createObject(ObjectType.CUSTOM);
        object.x = 10;
        object.y = 20;
        object.z = 30;
        object.width = 40;
        object.depth = 50;
        object.height = 60;
        Vertex minimum = SafetyWorkbenchVertexEditor.vertices(object).stream()
                .filter(vertex -> !vertex.maximumX && !vertex.maximumY && !vertex.maximumZ)
                .findFirst().orElseThrow(AssertionError::new);

        SafetyWorkbenchVertexEditor.apply(minimum,
                new Location(LengthUnit.Millimeters, 5, 15, 25, 0));

        assertEquals(5, object.x, 0.0001);
        assertEquals(15, object.y, 0.0001);
        assertEquals(25, object.z, 0.0001);
        assertEquals(45, object.width, 0.0001);
        assertEquals(55, object.depth, 0.0001);
        assertEquals(65, object.height, 0.0001);
    }

    @Test
    void reteachesPolygonVertexAndTopPlane() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject object = model.createObject(ObjectType.CUSTOM);
        object.shape = Shape.POLYGON;
        object.x = 100;
        object.y = 50;
        object.z = 2;
        object.height = 8;
        object.polygon.add(new Point2(0, 0));
        object.polygon.add(new Point2(10, 0));
        object.polygon.add(new Point2(0, 10));
        List<Vertex> vertices = SafetyWorkbenchVertexEditor.vertices(object);

        SafetyWorkbenchVertexEditor.apply(vertices.get(1),
                new Location(LengthUnit.Millimeters, 103, 54, 14, 0));

        assertEquals(3, object.polygon.get(0).x, 0.0001);
        assertEquals(4, object.polygon.get(0).y, 0.0001);
        assertEquals(12, object.height, 0.0001);
    }

    @Test
    void referenceMarkerUsesExactCoordinate() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject marker = model.createObject(ObjectType.FEEDER_PICK_POINT);
        Vertex vertex = SafetyWorkbenchVertexEditor.vertices(marker).get(0);

        SafetyWorkbenchVertexEditor.apply(vertex,
                new Location(LengthUnit.Millimeters, 123.4, 56.7, -8.9, 0));

        assertEquals(123.4, marker.x, 0.0001);
        assertEquals(56.7, marker.y, 0.0001);
        assertEquals(-8.9, marker.z, 0.0001);
        assertEquals(1, SafetyWorkbenchVertexEditor.vertices(marker).size());
    }
}
