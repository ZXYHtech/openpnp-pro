/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.gui.support.DynamicSafeMotionPanel;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.Plan;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ToolProfile;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.util.MovableUtils;

class DynamicSafeMotionPlannerTest {
    @Test
    void savedCertificationSurvivesReloadAndEditsRequireRecertification() throws Exception {
        SafetyWorkbenchStore.save(model);
        SafetyWorkbenchModel loaded = SafetyWorkbenchStore.reload();
        assertNotSame(model, loaded);
        assertTrue(loaded.isCertified());
        assertEquals(model.getRevision(), loaded.getCertifiedRevision());
        assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
        loaded.touch();
        assertFalse(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
        loaded.certify("Reviewed again");
        assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
        assertTrue(commands.isEmpty());
    }

    @Test
    void repeatedPreviewsUseFrozenGeometryButInvalidateItWhenAnObstacleChanges() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
        }
        SafetyObject object = model.createObject(ObjectType.KEEP_OUT);
        object.x = 130;
        object.y = 80;
        object.z = -10;
        object.width = 50;
        object.depth = 50;
        object.height = 100;
        model.addObject(object);
        model.certify("New obstruction");
        assertFalse(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
        assertTrue(commands.isEmpty());
    }

    @Test
    void changingConfigurationDoesNotReuseAnotherMachinesModel() throws Exception {
        assertSame(model, SafetyWorkbenchStore.get());
        Configuration.initialize(temporary.resolve("fresh-profile").toFile());
        assertNotSame(model, SafetyWorkbenchStore.get());
        assertFalse(SafetyWorkbenchStore.get().isCertified());
        Configuration.initialize(temporary.toFile());
        assertTrue(SafetyWorkbenchStore.get().isCertified());
    }

    @Test
    void pureZIsNotReportedAsAnEvaluatedFallback() throws Exception {
        DynamicMotionDiagnostics.clear();
        DynamicSafeMotionSettings.setEnabled(true);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle,
                nozzle.getLocation().derive(null, null, 0., null), 1));
        assertTrue(DynamicMotionDiagnostics.summary().contains("不适用 1"));
        assertTrue(DynamicMotionDiagnostics.summary().contains("已评估回退 0"));
    }

    @Test
    void certifiedOptInCanBeSavedWhileBusyWithoutIssuingMotion() throws Exception {
        ReferenceMachine busyMachine = spy(machine);
        when(busyMachine.isBusy()).thenReturn(true);
        SwingUtilities.invokeAndWait(() -> {
            DynamicSafeMotionPanel panel = new DynamicSafeMotionPanel(model, busyMachine, message -> { });
            ((JToggleButton) panel.getComponent(0)).doClick();
            assertTrue(DynamicSafeMotionSettings.isEnabled());
        });
        assertTrue(commands.isEmpty());
    }

    @TempDir
    Path temporary;

    ReferenceMachine machine;

    ReferenceHead head;

    ReferenceNozzle nozzle;

    NullDriver driver;

    MotionPlanner planner;

    ReferenceControllerAxis x, y, z;

    SafetyWorkbenchModel model;

    List<AxesLocation> commands;

    List<List<MotionOption>> options;

    @BeforeEach
    void fixture() throws Exception {
        Configuration.initialize(temporary.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        driver = new NullDriver();
        machine.addDriver(driver);
        x = axis(Axis.Type.X, 20);
        y = axis(Axis.Type.Y, 100);
        z = axis(Axis.Type.Z, 20);
        z.setSafeZoneLowEnabled(true);
        z.setSafeZoneLow(new Length(20, LengthUnit.Millimeters));
        z.setSafeZoneHighEnabled(true);
        z.setSafeZoneHigh(new Length(40, LengthUnit.Millimeters));
        head = new ReferenceHead();
        machine.addHead(head);
        nozzle = new ReferenceNozzle();
        nozzle.setAxisX(x);
        nozzle.setAxisY(y);
        nozzle.setAxisZ(z);
        head.addNozzle(nozzle);
        planner = mock(MotionPlanner.class);
        when(planner.isValidLocation(any(), any())).thenReturn(true);
        machine.setMotionPlanner(planner);
        machine.setEnabled(true);
        machine.setHomed(true);
        commands = new ArrayList<>();
        options = new ArrayList<>();
        doAnswer(invocation -> {
            AxesLocation target = invocation.getArgument(1);
            commands.add(target);
            List<MotionOption> flags = new ArrayList<>();
            for (Object argument : Arrays.copyOfRange(invocation.getArguments(), 3, invocation.getArguments().length)) {
                flags.add((MotionOption) argument);
            }
            options.add(flags);
            for (ControllerAxis axis : target.getControllerAxes()) {
                axis.setLengthCoordinate(target.getLengthCoordinate(axis));
            }
            return null;
        }).when(planner).moveTo(any(), any(), anyDouble(), any());
        model = new SafetyWorkbenchModel();
        model.setMinX(-100);
        model.setMaxX(500);
        model.setMinY(-100);
        model.setMaxY(500);
        model.setMinZ(-100);
        model.setMaxZ(200);
        ToolProfile profile = model.ensureToolProfile(head.getId(), nozzle.getId(), "N1");
        profile.bodyRadiusX = profile.bodyRadiusY = 1;
        profile.shaftRadius = .5;
        profile.clearance = .2;
        profile.retractedZ = 20;
        profile.extendedZ = 0;
        profile.retractedTipLength = profile.extendedTipLength = 1;
        profile.bodyMinZOffset = 4;
        profile.bodyMaxZOffset = 8;
        model.certify("Software-only fixture");
        SafetyWorkbenchStore.save(model);
        DynamicSafeMotionSettings.setEnabled(false);
    }

    ReferenceControllerAxis axis(Axis.Type type, double position) throws Exception {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        axis.setFeedratePerSecond(new Length(type == Axis.Type.Z ? 40 : 200, LengthUnit.Millimeters));
        axis.setAccelerationPerSecond2(new Length(1000, LengthUnit.Millimeters));
        axis.setJerkPerSecond3(new Length(0, LengthUnit.Millimeters));
        axis.setLengthCoordinate(new Length(position, LengthUnit.Millimeters));
        machine.addAxis(axis);
        return axis;
    }

    Location target() {
        return new Location(LengthUnit.Millimeters, 300, 100, 0, 0);
    }

    @Test
    void previewHasXyAndZInOneSegmentAndSavesEstimatedTimeWithoutMoving() throws Exception {
        Plan plan = DynamicSafeMotionPlanner.preview(nozzle, target(), 1);
        assertTrue(plan.isOptimized(), plan.getReason());
        assertTrue(plan.getCoordinatedSeconds() < plan.getOriginalSeconds());
        assertEquals(target(), plan.getLocations().get(plan.getLocations().size() - 1));
        boolean mixed = false;
        Location previous = nozzle.getLocation();
        for (Location point : plan.getLocations()) {
            mixed |= point.getX() != previous.getX() && point.getZ() != previous.getZ();
            previous = point;
        }
        assertTrue(mixed);
        assertTrue(commands.isEmpty());
        assertFalse(DynamicSafeMotionSettings.isEnabled());
        System.out.printf("XYZ SOFTWARE ESTIMATE: original=%.6fs, coordinated=%.6fs%n", plan.getOriginalSeconds(), plan.getCoordinatedSeconds());
    }

    @Test
    void enabledUtilitySendsCoordinatedCommandsAtOriginalSpeed() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        MovableUtils.moveToLocationAtSafeZ(nozzle, target(), .8);
        assertFalse(commands.isEmpty());
        assertTrue(options.stream().allMatch(flags -> flags.contains(MotionOption.CoordinatedMotion)));
        assertEquals(300, nozzle.getLocation().getX(), 1e-8);
        assertEquals(0, nozzle.getLocation().getZ(), 1e-8);
        verify(planner, atLeastOnce()).moveTo(eq(nozzle), any(), eq(.8), eq(MotionOption.CoordinatedMotion));
        verify(planner, never()).waitForCompletion(any(), any());
    }

    @Test
    void sourceLiftAlsoOverlapsXyWhenAlreadyClear() throws Exception {
        z.setLengthCoordinate(new Length(0, LengthUnit.Millimeters));
        SafetyObject obstacle = model.createObject(ObjectType.KEEP_OUT);
        obstacle.x = 140;
        obstacle.y = 90;
        obstacle.width = 20;
        obstacle.depth = 20;
        obstacle.height = 8;
        obstacle.clearance = 0;
        model.addObject(obstacle);
        model.certify("flight over a low obstacle");
        Plan plan = DynamicSafeMotionPlanner.preview(nozzle, target(), 1);
        assertTrue(plan.isOptimized(), plan.getReason());
        Location first = plan.getLocations().get(0);
        Location second = plan.getLocations().get(1);
        assertEquals(20, first.getX(), 1e-8);
        assertTrue(second.getX() > first.getX());
        assertTrue(second.getZ() > first.getZ());
    }

    @Test
    void lowClearCorridorAvoidsTheFullGlobalZLift() throws Exception {
        z.setLengthCoordinate(new Length(0, LengthUnit.Millimeters));
        Plan plan = DynamicSafeMotionPlanner.preview(nozzle, target(), 1);
        assertTrue(plan.isOptimized(), plan.getReason());
        assertTrue(plan.getLocations().stream().allMatch(location -> location.getZ() <= 1.01));
        assertTrue(plan.getCoordinatedSeconds() < plan.getOriginalSeconds());
        assertTrue(commands.isEmpty());
        System.out.printf("LOW CORRIDOR SOFTWARE ESTIMATE: original=%.6fs, optimized=%.6fs%n",
                plan.getOriginalSeconds(), plan.getCoordinatedSeconds());
    }

    @Test
    void disabledSwitchKeepsOriginalUtilityPath() throws Exception {
        MovableUtils.moveToLocationAtSafeZ(nozzle, target(), 1);
        assertTrue(options.stream().allMatch(List::isEmpty));
        assertEquals(2, commands.size());
    }

    @Test
    void certificationChangesImmediatelyPauseWithoutLosingSavedRequest() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        model.touch();
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle, target(), 1));
        assertTrue(DynamicSafeMotionSettings.isEnabled());
        assertTrue(commands.isEmpty());
    }

    @Test
    void noHomingOrUnspecifiedCoordinatesOrPureZAreOptimized() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        machine.setHomed(false);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle, target(), 1));
        assertFalse(DynamicSafeMotionPlanner.preview(nozzle, target().derive(null, null, Double.NaN, null), 1).isOptimized());
        assertFalse(DynamicSafeMotionPlanner.preview(nozzle, nozzle.getLocation().derive(null, null, 0., null), 1).isOptimized());
        assertTrue(commands.isEmpty());
    }

    @Test
    void obstacleRejectsWholePlanBeforeAnyCommand() throws Exception {
        SafetyObject obstacle = model.createObject(ObjectType.KEEP_OUT);
        obstacle.x = 160;
        obstacle.y = 90;
        obstacle.width = 30;
        obstacle.depth = 20;
        obstacle.height = 60;
        model.addObject(obstacle);
        model.certify("obstacle");
        DynamicSafeMotionSettings.setEnabled(true);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle, target(), 1));
        assertTrue(commands.isEmpty());
    }

    @Test
    void everyWaypointIsCheckedAgainstSoftLimitsBeforeAnyCommand() throws Exception {
        when(planner.isValidLocation(any(), any())).thenAnswer(call -> ((AxesLocation) call.getArgument(1)).getCoordinate(x) < 250);
        DynamicSafeMotionSettings.setEnabled(true);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle, target(), 1));
        assertTrue(commands.isEmpty());
    }

    @Test
    void executionFailurePropagatesInsteadOfRetryingOriginalPath() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new Exception("driver failure");
        }).when(planner).moveTo(any(), any(), anyDouble(), any());
        DynamicSafeMotionSettings.setEnabled(true);
        assertThrows(Exception.class, () -> MovableUtils.moveToLocationAtSafeZ(nozzle, target(), 1));
        assertEquals(1, attempts.get(), "Never issue a fallback path after motion has started.");
    }

    @Test
    void replacingModelDuringExecutionCannotContinueOnTheOldCertification() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            SafetyWorkbenchModel replacement = new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(model), SafetyWorkbenchModel.class);
            SafetyWorkbenchStore.save(replacement);
            return null;
        }).when(planner).moveTo(any(), any(), anyDouble(), any());
        DynamicSafeMotionSettings.setEnabled(true);
        assertThrows(Exception.class, () -> MovableUtils.moveToLocationAtSafeZ(nozzle, target(), 1));
        assertEquals(1, attempts.get());
    }

    @Test
    void sharedLinearZIsMappedAndSeparateControllersUseOriginalPath() throws Exception {
        ReferenceNozzle sibling = new ReferenceNozzle();
        sibling.setAxisX(x);
        sibling.setAxisY(y);
        sibling.setAxisZ(z);
        head.addNozzle(sibling);
        model.ensureToolProfile(head.getId(), sibling.getId(), "N2");
        model.certify("shared Z");
        Plan shared = DynamicSafeMotionPlanner.preview(nozzle, target(), 1);
        assertTrue(shared.isOptimized(), shared.getReason());
        DynamicSafetyCollisionEngine.DynamicTool mapped = DynamicSafetyCollisionEngine.snapshot(model, machine, nozzle).get(1);
        assertEquals(10, mapped.at(new SafetyCollisionEngine.Waypoint(20, 100, 20),
                new SafetyCollisionEngine.Waypoint(20, 100, 10)).z, 1e-8);
        head.removeNozzle(sibling);
        NullDriver second = new NullDriver();
        machine.addDriver(second);
        z.setDriver(second);
        assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).getReason().contains("同一协调"));
    }

    @Test
    void axisBacklashIsPreservedThroughFallback() {
        x.setBacklashCompensationMethod(BacklashCompensationMethod.OneSidedPositioning);
        x.setBacklashOffset(new Length(.1, LengthUnit.Millimeters));
        assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).getReason().contains("回差"));
    }

    @Test
    void boardContactKeepsOnlyTheFinalVerticalApproach() {
        SafetyObject board = model.createObject(ObjectType.BOARD);
        board.x = 280;
        board.y = 80;
        board.z = -1;
        board.width = 40;
        board.depth = 40;
        board.height = 1;
        board.clearance = .2;
        model.addObject(board);
        model.certify("board");
        Plan plan = DynamicSafeMotionPlanner.preview(nozzle, target(), 1);
        assertTrue(plan.isOptimized(), plan.getReason());
        Location approach = plan.getLocations().get(plan.getLocations().size() - 2);
        assertEquals(target().getX(), approach.getX(), 1e-8);
        assertEquals(target().getY(), approach.getY(), 1e-8);
        assertTrue(approach.getZ() > target().getZ());
    }

    @Test
    void independentSiblingAtLowZCannotCrossAnObstacle() throws Exception {
        ReferenceNozzle sibling = new ReferenceNozzle();
        sibling.setAxisX(x);
        sibling.setAxisY(y);
        sibling.setAxisZ(axis(Axis.Type.Z, 1));
        sibling.setHeadOffsets(new Location(LengthUnit.Millimeters, 0, 30, 0, 0));
        head.addNozzle(sibling);
        ToolProfile profile = model.ensureToolProfile(head.getId(), sibling.getId(), "N2");
        profile.bodyFollowsNozzleZ = true;
        profile.retractedTipLength = profile.extendedTipLength = 1;
        profile.bodyRadiusX = profile.bodyRadiusY = 2;
        SafetyObject obstacle = model.createObject(ObjectType.KEEP_OUT);
        obstacle.x = 160;
        obstacle.y = 120;
        obstacle.width = 20;
        obstacle.depth = 20;
        obstacle.height = 10;
        model.addObject(obstacle);
        model.certify("sibling");
        DynamicSafeMotionSettings.setEnabled(true);
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(nozzle, target(), 1));
        assertTrue(commands.isEmpty());
    }

    @Test
    void rotationTargetAndInputUnitsArePreserved() throws Exception {
        nozzle.setAxisRotation(axis(Axis.Type.Rotation, 0));
        Location inches = new Location(LengthUnit.Inches, 10, 4, 0, 90);
        Plan plan = DynamicSafeMotionPlanner.preview(nozzle, inches, 1);
        assertTrue(plan.isOptimized(), plan.getReason());
        if (plan.isOptimized()) {
            Location last = plan.getLocations().get(plan.getLocations().size() - 1);
            assertEquals(254, last.getX(), 1e-8);
            assertEquals(101.6, last.getY(), 1e-8);
            assertEquals(90, last.getRotation(), 1e-8);
            assertTrue(plan.getCoordinatedSeconds() < plan.getOriginalSeconds());
        }
        assertTrue(commands.isEmpty());
    }

    @Test
    void shortOrHighTargetMovesNeverSelectAnEstimatedSlowerPath() {
        for (Location destination : Arrays.asList(new Location(LengthUnit.Millimeters, 21, 100, 0, 0), new Location(LengthUnit.Millimeters, 50, 100, 30, 0))) {
            Plan plan = DynamicSafeMotionPlanner.preview(nozzle, destination, 1);
            assertTrue(!plan.isOptimized() || plan.getCoordinatedSeconds() < plan.getOriginalSeconds());
        }
    }

    @Test
    void invalidGeometryCannotBeCertifiedForMotion() {
        model.getToolProfiles().get(0).clearance = Double.NaN;
        assertFalse(SafetyModelValidator.validate(model).isEmpty());
        assertFalse(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).isOptimized());
    }

    @Test
    void gcodeMustContainAllAxesOnOneCoordinatedLine() throws Exception {
        GcodeDriver gcode = new GcodeDriver();
        machine.addDriver(gcode);
        x.setDriver(gcode);
        y.setDriver(gcode);
        z.setDriver(gcode);
        gcode.setCommand(null, CommandType.MOVE_TO_COMMAND, "G0 {X:%.4f}\nG0 {Y:%.4f}\nG0 {Z:%.4f}");
        assertTrue(DynamicSafeMotionPlanner.preview(nozzle, target(), 1).getReason().contains("同一行"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void preferencesPersistAndAreIsolatedByConfigurationDirectory() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        Configuration.initialize(temporary.resolve("another-machine").toFile());
        assertFalse(DynamicSafeMotionSettings.isEnabled());
        Configuration.initialize(temporary.toFile());
        assertTrue(DynamicSafeMotionSettings.isEnabled());
    }

    @Test
    void oneClickEnablesPersistsAndClosesAgainWithoutMotion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> messages = new ArrayList<>();
            DynamicSafeMotionPanel panel = new DynamicSafeMotionPanel(model, machine, messages::add);
            JToggleButton toggle = (JToggleButton) panel.getComponent(0);
            assertEquals("一键启用 XY/Z 联动", toggle.getText());
            toggle.doClick();
            assertTrue(DynamicSafeMotionSettings.isEnabled());
            assertEquals("关闭 XY/Z 联动", toggle.getText());
            model.touch();
            panel.refresh();
            assertTrue(((JLabel) panel.getComponent(1)).getText().contains("已暂停"));
            toggle.doClick();
            assertFalse(DynamicSafeMotionSettings.isEnabled());
        });
        assertTrue(commands.isEmpty());
    }

    @Test
    void oneClickCannotAutoCertifyUnverifiedModel() throws Exception {
        model.touch();
        SwingUtilities.invokeAndWait(() -> {
            List<String> messages = new ArrayList<>();
            DynamicSafeMotionPanel panel = new DynamicSafeMotionPanel(model, machine, messages::add);
            ((JToggleButton) panel.getComponent(0)).doClick();
            assertFalse(DynamicSafeMotionSettings.isEnabled());
            assertFalse(model.isCertified());
            assertTrue(messages.get(0).contains("认证"));
        });
    }
}
