package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.base.AbstractHead;

class MachineHomeReferenceTest {
    @TempDir Path temporary;

    @Test void readsConfiguredOpticalHomeWithoutConnectionOrMotion() throws Exception {
        Configuration.initialize(temporary.toFile());
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        assertThrows(IllegalStateException.class, () -> MachineHomeReference.read(Configuration.get()));
        head.setVisualHomingMethod(AbstractHead.VisualHomingMethod.ResetToFiducialLocation);
        head.setHomingFiducialLocation(new Location(LengthUnit.Millimeters, 434.9, 335.8, 0, 0));
        Location home = MachineHomeReference.read(Configuration.get());
        assertEquals(434.9, home.getX());
        assertEquals(335.8, home.getY());
        assertFalse(machine.isEnabled());
        assertFalse(machine.isHomed());
        head.setHomingFiducialLocation(new Location(LengthUnit.Millimeters, Double.NaN, 0, 0, 0));
        assertThrows(IllegalStateException.class, () -> MachineHomeReference.read(Configuration.get()));
        machine.close();
    }
}
