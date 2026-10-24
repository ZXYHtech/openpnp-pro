package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;

class FastTravelClearanceTest {
    @Test void changerIsLocalRestrictionButRemainsAnObstacle() {
        SafetyObject object = new SafetyObject();
        object.type = SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER;
        object.enabled = true;
        object.obstacle = true;
        object.z = 0;
        object.height = 30;
        assertTrue(FastTravelClearance.exceeds(object, -5));
        assertFalse(FastTravelClearance.blocksGlobalPlane(object, -5));
        assertTrue(object.obstacle);
    }
    @Test void exactRequiredClearancePassesButInsufficientClearanceFails() {
        SafetyObject object = new SafetyObject();
        object.enabled = true;
        object.obstacle = true;
        object.z = -10;
        object.height = 4;
        object.clearance = 1;
        assertFalse(FastTravelClearance.exceeds(object, -5));
        object.height = 4.01;
        assertTrue(FastTravelClearance.exceeds(object, -5));
        object.height = 3.9;
        assertFalse(FastTravelClearance.exceeds(object, -5));
        object.height = Double.NaN;
        assertTrue(FastTravelClearance.exceeds(object, -5));
    }

    @Test void missingMachineGeometryCannotPassAdmission(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        org.openpnp.model.Configuration.initialize(directory.toFile());
        assertFalse(FastTravelClearance.check(new SafetyWorkbenchModel(), new ReferenceMachine(), -5).isEmpty());
    }
}
