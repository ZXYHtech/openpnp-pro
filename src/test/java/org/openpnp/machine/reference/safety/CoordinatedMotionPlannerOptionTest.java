package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Motion;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;

class CoordinatedMotionPlannerOptionTest {

    static class Planner extends ReferenceAdvancedMotionPlanner {

        Motion add(AxesLocation from, AxesLocation to, int options) {
            return addMotion(null, 1, from, to, options);
        }
    }

    @Test
    void validatedLineCannotBecomeIndependentAxisMotionInSafeZone() throws Exception {
        Configuration.initialize();
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);
        ReferenceControllerAxis x = new ReferenceControllerAxis();
        x.setType(Axis.Type.X);
        x.setDriver(driver);
        machine.addAxis(x);
        Planner planner = new Planner();
        planner.setAllowUncoordinated(true);
        machine.setMotionPlanner(planner);
        AxesLocation from = new AxesLocation(x, 0);
        AxesLocation to = new AxesLocation(x, 10);
        assertTrue(planner.add(from, to, 0).hasOption(MotionOption.UncoordinatedMotion));
        Motion coordinated = planner.add(from, to, MotionOption.CoordinatedMotion.flag());
        assertTrue(coordinated.hasOption(MotionOption.CoordinatedMotion));
        assertFalse(coordinated.hasOption(MotionOption.UncoordinatedMotion));
    }
}
