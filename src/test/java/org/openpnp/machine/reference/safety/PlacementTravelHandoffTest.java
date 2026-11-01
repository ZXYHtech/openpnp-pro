package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlacementTravelHandoffTest {
    @TempDir Path temporary;

    @Test void releasedRetreatCanJoinTheNextApproachOnlyOnceAndOnlyAtTheSamePosition() throws Exception {
        var f = new DynamicSafeMotionPlannerTest();
        f.temporary = temporary;
        f.fixture();
        try {
            assertFalse(PlacementTravelHandoff.consume(f.nozzle));
            PlacementTravelHandoff.record(f.nozzle);
            assertTrue(PlacementTravelHandoff.consume(f.nozzle));
            assertFalse(PlacementTravelHandoff.consume(f.nozzle));
            PlacementTravelHandoff.record(f.nozzle);
            f.x.setCoordinate(f.x.getCoordinate() + 1);
            assertFalse(PlacementTravelHandoff.consume(f.nozzle));
            PlacementTravelHandoff.record(f.nozzle);
            var setPart = org.openpnp.spi.base.AbstractNozzle.class.getDeclaredMethod("setPart", org.openpnp.model.Part.class);
            setPart.setAccessible(true);
            setPart.invoke(f.nozzle, new org.openpnp.model.Part("held-part"));
            assertFalse(PlacementTravelHandoff.consume(f.nozzle), "Held parts retain the normal wait barrier");
        } finally { f.machine.close(); }
    }
}
