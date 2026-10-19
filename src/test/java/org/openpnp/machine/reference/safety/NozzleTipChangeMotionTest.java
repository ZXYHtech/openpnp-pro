package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner.NozzleTipChangeScope;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion.MotionOption;

class NozzleTipChangeMotionTest {
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest fixture;
    ReferenceNozzleTip tip;

    @BeforeEach void setup() throws Exception {
        fixture = new DynamicSafeMotionPlannerTest();
        fixture.temporary = temporary;
        fixture.fixture();
        tip = new ReferenceNozzleTip();
        tip.setName("Test tip");
        tip.setChangerStartLocation(location(300, 100, 0));
        tip.setChangerMidLocation(location(300, 105, -2));
        tip.setChangerMidLocation2(location(300, 110, -2));
        tip.setChangerEndLocation(location(300, 115, 0));
        tip.getCalibration().setRecalibrationTrigger(
                org.openpnp.machine.reference.ReferenceNozzleTipCalibration.RecalibrationTrigger.Manual);
        fixture.machine.addNozzleTip(tip);
        fixture.nozzle.addCompatibleNozzleTip(tip);
        fixture.nozzle.setChangerEnabled(true);
        fixture.nozzle.setManualNozzleTipChangeLocation(fixture.target());
        // The same geometry must permit normal coordinated travel outside a change operation.
        assertTrue(DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1).isOptimized());
    }

    @Test void automaticLoadingKeepsExactlyTheOriginalSafeZAndTaughtSequence() throws Exception {
        assertSameRouteWithCoordinationEnabled(true, false);
        assertSame(tip, fixture.nozzle.getNozzleTip());
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
    }

    @Test void automaticUnloadingKeepsExactlyTheOriginalSafeZAndTaughtSequence() throws Exception {
        assertSameRouteWithCoordinationEnabled(false, false);
        assertNull(fixture.nozzle.getNozzleTip());
        assertEquals(20, fixture.nozzle.getLocation().getZ(), 1e-8);
    }

    @Test void manualLoadingKeepsOriginalRouteAndRestoresCoordinationAfterItsInterruption() throws Exception {
        assertSameRouteWithCoordinationEnabled(true, true);
        assertCoordinationResumed();
    }

    @Test void manualUnloadingKeepsOriginalRouteAndRestoresCoordinationAfterItsInterruption() throws Exception {
        assertSameRouteWithCoordinationEnabled(false, true);
        assertCoordinationResumed();
    }

    @Test void nestedChangerScopesCannotReenableCoordinationBeforeTheOuterOperationFinishes() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        try (NozzleTipChangeScope outer = DynamicSafeMotionPlanner.suspendForNozzleTipChange()) {
            try (NozzleTipChangeScope inner = DynamicSafeMotionPlanner.suspendForNozzleTipChange()) {
                assertAllRuntimeEntryPointsSuspended();
            }
            assertAllRuntimeEntryPointsSuspended();
            // Read-only previews and the user's saved opt-in remain available.
            assertTrue(DynamicSafeMotionSettings.isEnabled());
            assertTrue(DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1).isOptimized());
        }
        assertCoordinationResumed();
    }

    @Test void failedAutomaticChangePropagatesTheErrorAndRestoresCoordinationWithoutRetryingMotion() throws Exception {
        DynamicSafeMotionSettings.setEnabled(true);
        doThrow(new Exception("changer motion rejected")).when(fixture.planner)
                .moveTo(any(), any(), anyDouble(), any());
        Exception failure = assertThrows(Exception.class, () -> fixture.nozzle.loadNozzleTip(tip, false));
        assertEquals("changer motion rejected", failure.getMessage());
        verify(fixture.planner, times(1)).moveTo(any(), any(), anyDouble(), any());
        assertTrue(DynamicSafeMotionSettings.isEnabled());
        // Observe the coordinated option on the first attempted normal move, without executing hardware.
        doAnswer(invocation -> {
            assertTrue(Arrays.asList(invocation.getArguments()).contains(MotionOption.CoordinatedMotion));
            throw new Exception("normal coordinated motion resumed");
        }).when(fixture.planner).moveTo(any(), any(), anyDouble(), any());
        Exception resumed = assertThrows(Exception.class, () ->
                DynamicSafeMotionPlanner.tryMoveToBottomVision(fixture.nozzle, fixture.target(), 1));
        assertEquals("normal coordinated motion resumed", resumed.getMessage());
    }

    private void assertAllRuntimeEntryPointsSuspended() throws Exception {
        assertFalse(DynamicSafeMotionPlanner.tryMoveToLocationAtSafeZ(fixture.nozzle, fixture.target(), 1));
        assertFalse(DynamicSafeMotionPlanner.tryMoveToBottomVision(fixture.nozzle, fixture.target(), 1));
        // The exclusion must occur before reading a feeder or any calibration data.
        assertFalse(DynamicSafeMotionPlanner.tryMoveToPick(fixture.nozzle, fixture.target(), 1, null));
        assertTrue(fixture.commands.isEmpty());
    }

    private void assertSameRouteWithCoordinationEnabled(boolean loading, boolean manual) throws Exception {
        fixture.nozzle.setChangerEnabled(!manual);
        List<List<Double>> original = route(loading, manual, false);
        List<List<Double>> enabled = route(loading, manual, true);
        assertEquals(original, enabled, "A changer operation must not be optimized, even without modeled obstructions");
        assertTrue(enabled.size() >= (manual ? 2 : 6));
        assertFalse(fixture.options.stream().anyMatch(flags -> flags.contains(MotionOption.CoordinatedMotion)));
        assertTrue(DynamicSafeMotionSettings.isEnabled(), "The saved setting is not disabled by a change operation");
    }

    private List<List<Double>> route(boolean loading, boolean manual, boolean enabled) throws Exception {
        fixture.nozzle.setNozzleTip(loading ? null : tip);
        resetStart();
        DynamicSafeMotionSettings.setEnabled(enabled);
        if (loading && manual) {
            assertThrows(ReferenceNozzle.ManualLoadException.class, () -> fixture.nozzle.loadNozzleTip(tip, false));
        }
        else if (!loading && manual) {
            assertThrows(ReferenceNozzle.ManualUnloadException.class, () -> fixture.nozzle.unloadNozzleTip());
        }
        else if (loading) {
            fixture.nozzle.loadNozzleTip(tip, false);
        }
        else {
            fixture.nozzle.unloadNozzleTip();
        }
        List<List<Double>> result = new ArrayList<>();
        for (AxesLocation command : fixture.commands) {
            result.add(Arrays.asList(command.getCoordinate(fixture.x), command.getCoordinate(fixture.y),
                    command.getCoordinate(fixture.z)));
        }
        return result;
    }

    private void assertCoordinationResumed() throws Exception {
        resetStart();
        DynamicSafeMotionSettings.setEnabled(true);
        assertTrue(DynamicSafeMotionPlanner.tryMoveToBottomVision(fixture.nozzle, fixture.target(), 1));
        assertTrue(fixture.options.stream().anyMatch(flags -> flags.contains(MotionOption.CoordinatedMotion)));
    }

    private void resetStart() throws Exception {
        fixture.x.setLengthCoordinate(new Length(20, LengthUnit.Millimeters));
        fixture.y.setLengthCoordinate(new Length(100, LengthUnit.Millimeters));
        fixture.z.setLengthCoordinate(new Length(20, LengthUnit.Millimeters));
        fixture.commands.clear();
        fixture.options.clear();
    }

    private Location location(double x, double y, double z) {
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }
}
