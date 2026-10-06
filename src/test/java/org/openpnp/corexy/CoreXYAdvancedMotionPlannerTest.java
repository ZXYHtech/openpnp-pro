package org.openpnp.corexy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Axis;

class CoreXYAdvancedMotionPlannerTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    private static final class TestPlanner extends CoreXYAdvancedMotionPlanner {
        private final ReferenceMachine machine;

        private TestPlanner(ReferenceMachine machine) {
            this.machine = machine;
        }

        @Override
        public ReferenceMachine getMachine() {
            return machine;
        }

        private void plan(AxesLocation current, AxesLocation target) {
            motionCommands.clear();
            createBacklashCompensatedMotion(null, 1.0, current, target);
        }

        private Motion motion(int index) {
            return motionCommands.get(index);
        }

        private int motionCount() {
            return motionCommands.size();
        }
    }

    @Test
    void appliesStateChangeOnceAndCarriesPhysicalOffsetAcrossShortMoves() throws Exception {
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        NullDriver driver = new NullDriver();
        machine.addDriver(driver);

        ReferenceControllerAxis x = axis(Axis.Type.X, driver);
        ReferenceControllerAxis y = axis(Axis.Type.Y, driver);
        machine.addAxis(x);
        machine.addAxis(y);

        TestPlanner planner = configuredPlanner(machine, x, y);
        AxesLocation origin = location(x, 0, y, 0);
        AxesLocation xPositive = location(x, 20, y, 0);

        planner.plan(origin, xPositive);
        assertEquals(2, planner.motionCount());
        assertEquals(20.0, planner.motion(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(0.0, planner.motion(0).getLocation1().getCoordinate(y), 1e-9);
        assertEquals(20.04, planner.motion(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, planner.motion(1).getLocation1().getCoordinate(y), 1e-9);
        assertEquals(0.2, planner.motion(1).getNominalSpeed(), 1e-9);

        AxesLocation shortMove = location(x, 21, y, 0);
        planner.plan(xPositive, shortMove);
        assertEquals(1, planner.motionCount());
        assertEquals(20.04, planner.motion(0).getLocation0().getCoordinate(x), 1e-9);
        assertEquals(21.04, planner.motion(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, planner.motion(0).getLocation1().getCoordinate(y), 1e-9);

        planner.plan(shortMove, origin);
        assertEquals(2, planner.motionCount());
        assertEquals(0.04, planner.motion(0).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(-0.02, planner.motion(0).getLocation1().getCoordinate(y), 1e-9);
        assertEquals(0.0, planner.motion(1).getLocation1().getCoordinate(x), 1e-9);
        assertEquals(0.0, planner.motion(1).getLocation1().getCoordinate(y), 1e-9);
    }

    private static TestPlanner configuredPlanner(ReferenceMachine machine,
            ReferenceControllerAxis x, ReferenceControllerAxis y) {
        TestPlanner planner = new TestPlanner(machine);
        planner.setCoreXYCompensationXAxisId(x.getId());
        planner.setCoreXYCompensationYAxisId(y.getId());
        planner.setCoreXYCompensationMinMoveA(new Length(15, LengthUnit.Millimeters));
        planner.setCoreXYCompensationMinMoveB(new Length(15, LengthUnit.Millimeters));
        planner.setCoreXYCompensationAPositiveOffset(new Location(
                LengthUnit.Millimeters, 0.01, 0.02, 0, 0));
        planner.setCoreXYCompensationANegativeOffset(new Location(LengthUnit.Millimeters));
        planner.setCoreXYCompensationBPositiveOffset(new Location(
                LengthUnit.Millimeters, 0.03, -0.04, 0, 0));
        planner.setCoreXYCompensationBNegativeOffset(new Location(LengthUnit.Millimeters));
        planner.setCoreXYCompensationSpeedFactor(0.2);
        planner.setCoreXYCompensationEnabled(true);
        return planner;
    }

    private static ReferenceControllerAxis axis(Axis.Type type, NullDriver driver) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        return axis;
    }

    private static AxesLocation location(ReferenceControllerAxis x, double xValue,
            ReferenceControllerAxis y, double yValue) {
        return new AxesLocation(x, xValue).put(new AxesLocation(y, yValue));
    }
}
