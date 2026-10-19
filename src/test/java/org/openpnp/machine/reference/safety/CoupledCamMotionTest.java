package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.axis.ReferenceCamClockwiseAxis;
import org.openpnp.machine.reference.axis.ReferenceCamCounterClockwiseAxis;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.Plan;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Locatable.LocationOption;

class CoupledCamMotionTest {
    @Test
    void twoLoadedNozzlesSwitchCameraWithXyAndCamZInTheSameCommand() throws Exception {
        setup(180);
        org.openpnp.model.Package packag = new org.openpnp.model.Package("0603");
        packag.getFootprint().setBodyWidth(1.6);
        packag.getFootprint().setBodyHeight(.8);
        java.lang.reflect.Field carried = org.openpnp.spi.base.AbstractNozzle.class.getDeclaredField("part");
        carried.setAccessible(true);
        for (ReferenceNozzle nozzle : new ReferenceNozzle[] {fixture.nozzle, clockwiseNozzle}) {
            org.openpnp.model.Part part = new org.openpnp.model.Part(nozzle.getId());
            part.setPackage(packag);
            part.setHeight(new Length(.8, LengthUnit.Millimeters));
            carried.set(nozzle, part);
        }
        SafetyWorkbenchModel.SafetyObject camera = fixture.model.createObject(SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA);
        camera.x = 280;
        camera.y = 80;
        camera.z = -60;
        camera.width = camera.depth = 40;
        camera.height = 20;
        camera.apertureWidth = camera.apertureDepth = 0;
        fixture.model.addObject(camera);
        fixture.model.certify("Both vision planes clear camera shell");
        DynamicSafeMotionSettings.setEnabled(true);
        Location cameraTarget = new Location(LengthUnit.Millimeters, 300, 100, -12, 0);
        for (ReferenceNozzle nozzle : new ReferenceNozzle[] {fixture.nozzle, clockwiseNozzle}) {
            Plan plan = DynamicSafeMotionPlanner.previewBottomVision(nozzle, cameraTarget, 1);
            assertTrue(plan.isOptimized(), plan.getReason());
            assertEquals(1, plan.getLocations().size());
            int before = fixture.commands.size();
            Location origin = nozzle.getLocation();
            if (!DynamicSafeMotionPlanner.tryMoveToBottomVision(nozzle, cameraTarget, 1)) {
                // Runtime may decline for its time/cost budget. Verify that rejection is atomic,
                // then exercise the independently certified plan through the native NullDriver fixture.
                assertEquals(before, fixture.commands.size());
                assertEquals(origin, nozzle.getLocation());
                String reason = DynamicSafeMotionPlanner.getRuntimeSummary();
                assertTrue(reason.contains("40 ms") || reason.contains("预计节省不足"), reason);
                nozzle.moveTo(plan.getLocations().get(0), 1, org.openpnp.model.Motion.MotionOption.CoordinatedMotion);
            }
            assertEquals(before + 1, fixture.commands.size());
            assertEquals(-12, nozzle.getLocation().getZ(), 1e-7);
            assertEquals(100, nozzle.getLocation().getY(), 1e-7);
        }
        assertEquals(2, fixture.commands.size());
        assertTrue(fixture.options.stream().allMatch(flags -> flags.contains(org.openpnp.model.Motion.MotionOption.CoordinatedMotion)));
    }
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest fixture;
    ReferenceCamCounterClockwiseAxis counter;
    ReferenceNozzle clockwiseNozzle;

    void setup(double arms) throws Exception {
        fixture = new DynamicSafeMotionPlannerTest();
        fixture.temporary = temporary.resolve("arms-" + arms);
        java.nio.file.Files.createDirectories(fixture.temporary);
        fixture.fixture();
        fixture.z.setLengthCoordinate(new Length(0, LengthUnit.Millimeters));
        double safeRange = arms == 0 ? 80 : 10;
        fixture.z.setSafeZoneLow(new Length(-safeRange, LengthUnit.Millimeters));
        fixture.z.setSafeZoneHigh(new Length(safeRange, LengthUnit.Millimeters));
        counter = new ReferenceCamCounterClockwiseAxis();
        counter.setType(Axis.Type.Z);
        counter.setInputAxis(fixture.z);
        counter.setCamRadius(new Length(15, LengthUnit.Millimeters));
        counter.setCamArmsAngle(arms);
        fixture.machine.addAxis(counter);
        ReferenceCamClockwiseAxis clockwise = new ReferenceCamClockwiseAxis();
        clockwise.setType(Axis.Type.Z);
        clockwise.setInputAxis(counter);
        fixture.machine.addAxis(clockwise);
        fixture.nozzle.setAxisZ(counter);
        clockwiseNozzle = new ReferenceNozzle();
        clockwiseNozzle.setAxisX(fixture.x);
        clockwiseNozzle.setAxisY(fixture.y);
        clockwiseNozzle.setAxisZ(clockwise);
        clockwiseNozzle.setHeadOffsets(new Location(LengthUnit.Millimeters, 0, 30, 0, 0));
        fixture.head.addNozzle(clockwiseNozzle);
        ToolProfile profile = fixture.model.ensureToolProfile(fixture.head.getId(), clockwiseNozzle.getId(), "N2");
        for (ToolProfile tool : fixture.model.getToolProfiles()) {
            tool.bodyRadiusX = tool.bodyRadiusY = 1;
            tool.shaftRadius = .5;
            tool.clearance = .2;
            tool.retractedZ = 30;
            tool.extendedZ = -15;
            tool.retractedTipLength = tool.extendedTipLength = 1;
            tool.bodyMinZOffset = 4;
            tool.bodyMaxZOffset = 8;
        }
        fixture.model.certify("isolated coupled cam fixture");
        SafetyWorkbenchStore.save(fixture.model);
    }

    @Test
    void oneArmAndSeesawCanOptimizeBothNozzlesWithoutAnyMotion() throws Exception {
        for (double arms : new double[] {0, 180}) {
            setup(arms);
            for (ReferenceNozzle nozzle : new ReferenceNozzle[] {fixture.nozzle, clockwiseNozzle}) {
                Location origin = nozzle.getLocation();
                Location destination = origin.derive(origin.getX() + 280, null, arms == 0 ? 0. : -12., null);
                Plan plan = DynamicSafeMotionPlanner.preview(nozzle, destination, 1);
                assertTrue(plan.isOptimized(), "arms=" + arms + ": " + plan.getReason());
                assertTrue(plan.getCoordinatedSeconds() < plan.getOriginalSeconds());
                assertEquals(destination.getZ(), plan.getLocations().get(plan.getLocations().size() - 1).getZ(), 1e-8);
                System.out.printf("CAM XYZ ESTIMATE arms=%.0f: original=%.6f coordinated=%.6f%n",
                        arms, plan.getOriginalSeconds(), plan.getCoordinatedSeconds());
            }
            assertTrue(fixture.commands.isEmpty());
        }
    }

    @Test
    void coupledSiblingZMatchesTheRealAxisTransformInBothDirections() throws Exception {
        for (double arms : new double[] {0, 180}) {
            setup(arms);
            for (ReferenceNozzle selected : new ReferenceNozzle[] {fixture.nozzle, clockwiseNozzle}) {
                ReferenceNozzle other = selected == fixture.nozzle ? clockwiseNozzle : fixture.nozzle;
                Location origin = selected.getLocation();
                List<DynamicTool> tools = DynamicSafetyCollisionEngine.snapshot(fixture.model, fixture.machine, selected);
                DynamicTool sibling = tools.stream().filter(tool -> tool.toolId.equals(other.getId())).findFirst().get();
                assertTrue(sibling.followsZ);
                assertFalse(sibling.selectedTool);
                assertTrue(sibling.conservativeSegmentZ);
                for (double targetZ : new double[] {arms == 0 ? 0 : -12, 5, 12}) {
                    Location desired = origin.derive(null, null, targetZ, null);
                    AxesLocation raw = selected.toRaw(selected.toHeadLocation(desired, LocationOption.Quiet), LocationOption.Quiet);
                    Location expected = other.toHeadMountableLocation(other.toTransformed(
                            new AxesLocation(fixture.machine).put(raw), LocationOption.Quiet), LocationOption.Quiet);
                    Waypoint actual = sibling.at(new Waypoint(origin.getX(), origin.getY(), origin.getZ()),
                            new Waypoint(origin.getX(), origin.getY(), targetZ));
                    assertEquals(expected.getZ(), actual.z, 1e-8);
                }
            }
        }
    }

    @Test
    void tinyCamPlateauChangeMustNotDropALargeMotorMovement() throws Exception {
        setup(0);
        Location origin = fixture.nozzle.getLocation();
        AxesLocation raw = new AxesLocation(fixture.machine).put(new AxesLocation(fixture.z, 45));
        Location desired = fixture.nozzle.toHeadMountableLocation(fixture.nozzle.toTransformed(raw, LocationOption.Quiet), LocationOption.Quiet);
        assertTrue(Math.abs(desired.getZ() - origin.getZ()) < .001);
        Method compact = DynamicSafeMotionPlanner.class.getDeclaredMethod("compact", HeadMountable.class, Location.class, List.class);
        compact.setAccessible(true);
        List<?> retained = (List<?>) compact.invoke(null, fixture.nozzle, origin, Collections.singletonList(desired));
        assertEquals(1, retained.size(), "The other nozzle moves significantly across the cam plateau.");
        assertTrue(fixture.commands.isEmpty());
    }

    @Test
    void impossibleCamHeightIsRejectedBeforeAnyCommand() throws Exception {
        setup(0);
        Location destination = fixture.nozzle.getLocation().derive(300., null, -30., null);
        assertFalse(DynamicSafeMotionPlanner.preview(fixture.nozzle, destination, 1).isOptimized());
        assertTrue(fixture.commands.isEmpty());
    }
}
