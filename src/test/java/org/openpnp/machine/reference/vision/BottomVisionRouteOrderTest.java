package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.axis.*;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.model.*;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Nozzle;

class BottomVisionRouteOrderTest {
    @TempDir Path temporary;
    ReferenceMachine machine;
    NullDriver driver;
    ReferenceControllerAxis motor;
    Nozzle[] nozzles;
    Location[] centers, ends;

    ReferenceControllerAxis axis(Axis.Type type, double position, double speed) throws Exception {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        axis.setFeedratePerSecond(new Length(speed, LengthUnit.Millimeters));
        axis.setAccelerationPerSecond2(new Length(1000, LengthUnit.Millimeters));
        axis.setJerkPerSecond3(new Length(10000, LengthUnit.Millimeters));
        axis.setLengthCoordinate(new Length(position, LengthUnit.Millimeters));
        machine.addAxis(axis);
        return axis;
    }

    void fixture(double camPosition) throws Exception {
        Configuration.initialize(temporary.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        driver = new NullDriver();
        machine.addDriver(driver);
        var x = axis(Axis.Type.X, 230, 200);
        var y = axis(Axis.Type.Y, 100, 200);
        motor = axis(Axis.Type.Z, camPosition, 100);
        motor.setSafeZoneLowEnabled(true);
        motor.setSafeZoneLow(new Length(0, LengthUnit.Millimeters));
        motor.setSafeZoneHighEnabled(true);
        motor.setSafeZoneHigh(new Length(0, LengthUnit.Millimeters));
        var left = new ReferenceCamCounterClockwiseAxis();
        left.setType(Axis.Type.Z);
        left.setInputAxis(motor);
        left.setCamRadius(new Length(15, LengthUnit.Millimeters));
        left.setCamArmsAngle(0);
        machine.addAxis(left);
        var right = new ReferenceCamClockwiseAxis();
        right.setType(Axis.Type.Z);
        right.setInputAxis(left);
        machine.addAxis(right);
        var head = new ReferenceHead();
        machine.addHead(head);
        nozzles = new Nozzle[2];
        var packag = new org.openpnp.model.Package("0603");
        var part = new Part("test");
        part.setPackage(packag);
        part.setHeight(new Length(.8, LengthUnit.Millimeters));
        var carried = org.openpnp.spi.base.AbstractNozzle.class.getDeclaredField("part");
        carried.setAccessible(true);
        for (int i = 0; i < 2; i++) {
            var nozzle = new ReferenceNozzle();
            nozzle.setAxisX(x);
            nozzle.setAxisY(y);
            nozzle.setAxisZ(i == 0 ? left : right);
            nozzle.setHeadOffsets(new Location(LengthUnit.Millimeters, i * 22, 0, 0, 0));
            head.addNozzle(nozzle);
            carried.set(nozzle, part);
            nozzles[i] = nozzle;
        }
        centers = new Location[] {new Location(LengthUnit.Millimeters, 226, 100, 5.8, 0),
                new Location(LengthUnit.Millimeters, 226, 100, 5.8, 0)};
        ends = new Location[] {new Location(LengthUnit.Millimeters, 150, 200, -13, 0),
                new Location(LengthUnit.Millimeters, 172, 200, -13, 0)};
    }

    @Test void lastPickedN2AvoidsSharedCamReversalEvenWhenN1IsCloserInXy() throws Exception {
        fixture(100);
        assertTrue(nozzles[0].getLocation().getLinearDistanceTo(centers[0])
                < nozzles[1].getLocation().getLinearDistanceTo(centers[1]));
        double normal = BottomVisionRouteOrder.routeSeconds(nozzles, centers, new boolean[2], ends, false, 1);
        double reversed = BottomVisionRouteOrder.routeSeconds(nozzles, centers, new boolean[2], ends, true, 1);
        assertTrue(BottomVisionRouteOrder.reverse(normal, reversed), normal + " / " + reversed);
        assertEquals(100, motor.getLengthCoordinate().getValue(), "Read-only ordering must never move an axis");
    }

    @Test void lastPickedN1CanRemainFirstAndInvalidEstimatesNeverReorder() throws Exception {
        fixture(-100);
        double normal = BottomVisionRouteOrder.routeSeconds(nozzles, centers, new boolean[2], ends, false, 1);
        double reversed = BottomVisionRouteOrder.routeSeconds(nozzles, centers, new boolean[2], ends, true, 1);
        assertFalse(BottomVisionRouteOrder.reverse(normal, reversed), normal + " / " + reversed);
        assertFalse(BottomVisionRouteOrder.reverse(Double.NaN, 0));
        assertFalse(BottomVisionRouteOrder.reverse(1, Double.NaN));
        assertFalse(BottomVisionRouteOrder.reverse(1, .999));
    }
}
