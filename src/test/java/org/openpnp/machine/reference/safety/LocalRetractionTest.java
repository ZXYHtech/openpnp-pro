package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.base.AbstractNozzle;
import org.openpnp.util.MovableUtils;

class LocalRetractionTest {
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest fixture;
    ReferenceAutoFeeder feeder;
    Part part;
    SafetyWorkbenchModel.SafetyObject body;

    @BeforeEach void setup() throws Exception {
        fixture = new DynamicSafeMotionPlannerTest();
        fixture.temporary = temporary;
        fixture.fixture();
        org.openpnp.model.Package packag = new org.openpnp.model.Package("0603");
        packag.getFootprint().setBodyWidth(1.6);
        packag.getFootprint().setBodyHeight(.8);
        part = new Part("C0603");
        part.setPackage(packag);
        part.setHeight(new Length(.8, LengthUnit.Millimeters));
        feeder = new ReferenceAutoFeeder();
        feeder.setPart(part);
        feeder.setLocation(new Location(LengthUnit.Millimeters, 20, 100, 0, 0));
        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        tip.setMaxPartHeight(new Length(2, LengthUnit.Millimeters));
        fixture.nozzle.setNozzleTip(tip);
        Field carried = AbstractNozzle.class.getDeclaredField("part");
        carried.setAccessible(true);
        carried.set(fixture.nozzle, part);
        double pickZ = feeder.isPartHeightAbovePickLocation() ? .8 : 0;
        fixture.z.setLengthCoordinate(new Length(pickZ, LengthUnit.Millimeters));
        body = fixture.model.createObject(SafetyWorkbenchModel.ObjectType.FEEDER);
        body.sourceKey = "feeder:" + feeder.getId() + ":body";
        body.x = 15;
        body.y = 95;
        body.z = -5;
        body.width = body.depth = 10;
        body.height = 8;
        body.clearance = 0;
        fixture.model.addObject(body);
        fixture.model.certify("Feeder calibrated around the taught pickup recess");
        org.openpnp.model.Configuration.get().getScripting().on("Nozzle.AfterPick", null);
        org.openpnp.model.Configuration.get().getScripting().on("Job.Placement.Complete", null);
        DynamicSafeMotionSettings.setEnabled(true);
        // Warm read-only geometry and Motion classes before measuring a runtime budget.
        DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1);
    }

    @Test void retractsOnlyToLocalClearanceThenAdjustsRemainingZWhileXyMovesToVision() throws Exception {
        DynamicSafeMotionPlanner.retractAfterPick(fixture.nozzle, feeder);
        double exitZ = fixture.nozzle.getLocation().getZ();
        assertTrue(exitZ > 3 && exitZ < 10, "Local exit must clear the calibrated feeder, without full Z20");
        assertEquals(1, fixture.commands.size());
        Location target = new Location(LengthUnit.Millimeters, 300, 100, -12, 0);
        MovableUtils.moveToBottomVisionLocation(fixture.nozzle, target, 1);
        assertEquals(-12, fixture.nozzle.getLocation().getZ(), 1e-8);
        assertTrue(fixture.commands.size() > 1);
        org.openpnp.model.AxesLocation last = fixture.commands.get(fixture.commands.size() - 1);
        org.openpnp.model.AxesLocation previous = fixture.commands.get(fixture.commands.size() - 2);
        assertNotEquals(previous.getCoordinate(fixture.x), last.getCoordinate(fixture.x),
                "Final imaging Z must be reached while XY is moving, without a vertical tail");
        assertNotEquals(previous.getCoordinate(fixture.z), last.getCoordinate(fixture.z));
        assertTrue(DynamicMotionDiagnostics.summary().contains("局部安全退出"));
    }

    @Test void unrelatedOrMissingFeederGeometryRetainsFullSafeZ() throws Exception {
        body.sourceKey = "feeder:another:body";
        fixture.model.touch();
        fixture.model.certify("Different feeder");
        DynamicSafeMotionPlanner.retractAfterPick(fixture.nozzle, feeder);
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
    }

    @Test void aChangerHookCannotUseTheShortPickupRetraction() throws Exception {
        try (DynamicSafeMotionPlanner.NozzleTipChangeScope ignored = DynamicSafeMotionPlanner.suspendForNozzleTipChange()) {
            DynamicSafeMotionPlanner.retractAfterPick(fixture.nozzle, feeder);
        }
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
        assertEquals(1, fixture.commands.size());
    }

    @Test void aChangerHookCannotUseTheShortPlacementRetraction() throws Exception {
        prepareBoard();
        try (DynamicSafeMotionPlanner.NozzleTipChangeScope ignored = DynamicSafeMotionPlanner.suspendForNozzleTipChange()) {
            DynamicSafeMotionPlanner.retractAfterPlace(fixture.nozzle);
        }
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
        assertEquals(1, fixture.commands.size());
    }

    @Test void placementCanExitToLocalClearanceWhenNoFullSafeZCheckIsRequired() throws Exception {
        prepareBoard();
        DynamicSafeMotionPlanner.retractAfterPlace(fixture.nozzle);
        assertTrue(fixture.nozzle.getLocation().getZ() < 10);
        assertTrue(fixture.nozzle.getLocation().getZ() > .8);
    }

    @Test void enabledPartOffCheckStillGetsTheFullSafeZRetraction() throws Exception {
        prepareBoard();
        ReferenceNozzleTip tip = (ReferenceNozzleTip) fixture.nozzle.getNozzleTip();
        tip.setPartOffCheckAfterPlace(true);
        tip.setMethodPartOff(ReferenceNozzleTip.VacuumMeasurementMethod.Absolute);
        fixture.nozzle.setVacuumSenseActuator(org.mockito.Mockito.mock(org.openpnp.spi.Actuator.class));
        assertTrue(fixture.nozzle.isPartOffEnabled(org.openpnp.spi.Nozzle.PartOffStep.AfterPlace));
        DynamicSafeMotionPlanner.retractAfterPlace(fixture.nozzle);
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
    }

    private void prepareBoard() throws Exception {
        fixture.nozzle.clearPartForRepick();
        fixture.z.setLengthCoordinate(new Length(.8, LengthUnit.Millimeters));
        body.type = SafetyWorkbenchModel.ObjectType.BOARD;
        body.height = 5;
        fixture.model.touch();
        fixture.model.certify("Clear board departure");
        DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1);
    }

    @Test void aWrongPickCoordinateCannotAuthorizeARecessChannel() throws Exception {
        Location unrelated = new Location(LengthUnit.Millimeters, 300, 100, 0, 0);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToPick(fixture.nozzle, unrelated, 1, feeder));
        assertTrue(fixture.commands.isEmpty());
    }

    @Test void partialRetreatExecutionFailureMustNotIssueASecondFallback() throws Exception {
        org.mockito.Mockito.doThrow(new Exception("simulated driver rejection")).when(fixture.planner)
                .moveTo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());
        Exception failure = assertThrows(Exception.class, () -> DynamicSafeMotionPlanner.retractAfterPick(fixture.nozzle, feeder));
        assertTrue(failure.getMessage().contains("simulated driver rejection"));
        org.mockito.Mockito.verify(fixture.planner, org.mockito.Mockito.times(1))
                .moveTo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());
    }

    @Test void taughtPickBelowTheFeederTopCanUseAVerticalChannelButNeverADiagonalContact() throws Exception {
        fixture.z.setLengthCoordinate(new Length(20, LengthUnit.Millimeters));
        feeder.setLocation(feeder.getPickLocation().derive(300., null, null, null));
        org.openpnp.model.Location target = feeder.getPickLocation().derive(null, null,
                feeder.isPartHeightAbovePickLocation() ? .8 : 0., null);
        body.x = 295;
        fixture.model.touch();
        fixture.model.certify("Pickup inside a calibrated recess");
        assertTrue(DynamicSafeMotionPlanner.tryMoveToPick(fixture.nozzle, target, 1, feeder));
        assertTrue(fixture.commands.size() >= 2);
        org.openpnp.model.AxesLocation last = fixture.commands.get(fixture.commands.size() - 1);
        org.openpnp.model.AxesLocation previous = fixture.commands.get(fixture.commands.size() - 2);
        assertEquals(last.getCoordinate(fixture.x), previous.getCoordinate(fixture.x), 1e-8);
        assertEquals(target.getZ(), fixture.nozzle.getLocation().getZ(), 1e-8);
        assertTrue(previous.getCoordinate(fixture.z) < 20, "Most Z descent should already occur with XY");
    }
}
