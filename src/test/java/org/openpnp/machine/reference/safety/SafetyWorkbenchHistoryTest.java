/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;

class SafetyWorkbenchHistoryTest {
    @Test
    void undoAndRedoRestoreGeometryAndRequireRecertification() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.ensureToolProfile("H1", "N1", "N1");
        SafetyObject object = model.createObject(ObjectType.BOARD);
        object.x = 10;
        model.addObject(object);
        model.certify("checked");
        SafetyWorkbenchHistory history = new SafetyWorkbenchHistory(model);

        object.x = 25;
        model.touch();
        assertTrue(history.capture(model));
        assertTrue(history.canUndo());

        assertTrue(history.undo(model));
        assertEquals(10, model.getObjects().get(0).x, 0.000001);
        assertFalse(model.isCertified());
        assertTrue(history.canRedo());

        assertTrue(history.redo(model));
        assertEquals(25, model.getObjects().get(0).x, 0.000001);
        assertFalse(model.isCertified());
    }
}
