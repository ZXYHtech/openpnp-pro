import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.spi.base.AbstractMachine;

class BottomVisionRepickJobTest {
    @Test
    void directRepickDoesNotDiscardOrPlaceAndResumesVisionAndPlacement() throws Exception {
        runDirectRecovery(0);
    }

    @Test
    void failedDirectRepickDoesNotDiscardAndCanBeRetriedWhilePaused() throws Exception {
        runDirectRecovery(1);
    }

    @Test
    void directRepickKeepsPartOffDetectionAndCanBeRetriedAfterCorrection() throws Exception {
        runDirectRecovery(2);
    }

    @Test
    void directRepickLeavesOtherNozzlesInTheCurrentCycleIntact() throws Exception {
        runDirectRecovery(3);
    }

    @Test
    void failedVisionIsDiscardedRepickedAndPlacedThroughTheNormalJobPipeline() throws Exception {
        runRecovery(false);
    }

    @Test
    void discardAndSkipContinuesOtherPlacementsWithoutRepickingTheFailedOne() throws Exception {
        runRecovery(true);
    }

    @SuppressWarnings("unchecked")
    private void runDirectRecovery(int mode) throws Exception {
        Configuration.initialize();
        ReferenceJobProcessorRetryTests.MachineBuilder machineBuilder = new ReferenceJobProcessorRetryTests.MachineBuilder()
                .head("H1").nozzleTip("NT1").nozzle("N1", "NT1")
                .topCamera("TOP").bottomCamera("BOTTOM");
        if (mode == 3) {
            machineBuilder.nozzleTip("NT2").nozzle("N2", "NT2");
        }
        Machine machine = machineBuilder.build();
        ReferenceJobProcessorRetryTests.JobBuilder jobBuilder = new ReferenceJobProcessorRetryTests.JobBuilder()
                .board("B1", 10, 10, 10, -10).packag("R0402", mode == 3
                        ? new String[] {"NT1", "NT2"} : new String[] {"NT1"})
                .part("R0402-1k", "R0402").feeder("F1", "R0402-1k", 100, 20, -5, 0)
                .placement("R1", "R0402-1k", 10, 10, 0);
        if (mode == 3) {
            jobBuilder.placement("R2", "R0402-1k", 20, 20, 0);
        }
        Job job = jobBuilder.build();
        Feeder feeder = machine.getFeederByName("F1");
        ((ReferenceJobProcessorRetryTests.TestFeeder) feeder).setPartCount(10);
        ((ReferenceJobProcessorRetryTests.TestFeeder) feeder).setPickRetryCount(0);
        ReferenceJobProcessorRetryTests.TestNozzle original =
                (ReferenceJobProcessorRetryTests.TestNozzle) machine.getDefaultHead().getDefaultNozzle();
        ReferenceJobProcessorRetryTests.TestNozzle nozzle = spy(original);
        machine.getDefaultHead().removeNozzle(original);
        machine.getDefaultHead().addNozzle(nozzle);
        ReferenceBottomVision vision = mock(ReferenceBottomVision.class);
        when(vision.isEnabled()).thenReturn(true);
        when(vision.canHandle(any(), anyBoolean())).thenReturn(true);
        when(vision.getLocation(any(), any(), any(), any())).thenReturn(new Location(LengthUnit.Millimeters));
        when(vision.findOffsets(any(), any(), any(), any()))
                .thenThrow(new Exception("No results found in vision pipeline"))
                .thenReturn(new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false));
        Field alignments = AbstractMachine.class.getDeclaredField("partAlignments");
        alignments.setAccessible(true);
        List<PartAlignment> fixtureAlignments = (List<PartAlignment>) alignments.get(machine);
        fixtureAlignments.clear();
        fixtureAlignments.add(vision);
        ReferencePnpJobProcessor processor = (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        processor.setMaxVisionRetries(1);
        machine.setEnabled(true);
        machine.home();
        processor.initialize(job);
        boolean recovered = false;
        boolean finished = false;
        try {
            for (int step = 0; step < 500; step++) {
                try {
                    if (!processor.next()) {
                        finished = true;
                        break;
                    }
                }
                catch (JobProcessorException failure) {
                    assertFalse(recovered);
                    assertTrue(processor.canRecoverFailedAlignment(nozzle));
                    Nozzle other = mode == 3 ? machine.getDefaultHead().getNozzleByName("N2") : null;
                    Part otherPart = other == null ? null : other.getPart();
                    if (mode == 3) {
                        assertNotNull(otherPart, "The second nozzle must already be carrying its part");
                    }
                    clearInvocations(nozzle);
                    if (mode == 1) {
                        nozzle.pickSuccessRatio = 0;
                        assertThrows(JobProcessorException.class, () -> processor.repickFailedAlignment(nozzle));
                        assertTrue(processor.canRecoverFailedAlignment(nozzle));
                        verify(nozzle, never()).place();
                        verify(nozzle, never()).moveToPlacementLocation(any(), isNull());
                        nozzle.pickSuccessRatio = 1;
                    }
                    else if (mode == 2) {
                        doReturn(true).when(nozzle).isPartOffEnabled(Nozzle.PartOffStep.BeforePick);
                        doReturn(false).when(nozzle).isPartOff();
                        assertThrows(JobProcessorException.class, () -> processor.repickFailedAlignment(nozzle));
                        assertTrue(processor.canRecoverFailedAlignment(nozzle));
                        verify(nozzle, never()).pick(any(), any());
                        verify(nozzle, never()).place();
                        doReturn(true).when(nozzle).isPartOff();
                    }
                    int picksBefore = nozzle.getPickCount();
                    assertTrue(processor.repickFailedAlignment(nozzle));
                    assertEquals(picksBefore + 1, nozzle.getPickCount(), "Re-pick happens immediately");
                    assertFalse(processor.repickFailedAlignment(nozzle), "Do not duplicate an accepted recovery");
                    if (other != null) {
                        assertSame(otherPart, other.getPart());
                    }
                    verify(nozzle, never()).place();
                    verify(nozzle, never()).moveToPlacementLocation(any(), isNull());
                    recovered = true;
                }
            }
            assertTrue(recovered);
            assertTrue(finished);
            assertEquals(0, job.getActivePlacements(job.getRootPanelLocation()));
            assertNull(nozzle.getPart());
            verify(nozzle, times(1)).place();
            verify(nozzle, never()).moveToPlacementLocation(any(), isNull());
            verify(vision, times(mode == 3 ? 3 : 2)).findOffsets(any(), any(), any(), any());
        }
        finally {
            machine.setEnabled(false);
            machine.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void runRecovery(boolean skip) throws Exception {
        Configuration.initialize();
        Machine machine = new ReferenceJobProcessorRetryTests.MachineBuilder()
                .head("H1").nozzleTip("NT1").nozzle("N1", "NT1")
                .topCamera("TOP").bottomCamera("BOTTOM").build();
        ReferenceJobProcessorRetryTests.JobBuilder builder = new ReferenceJobProcessorRetryTests.JobBuilder()
                .board("B1", 10, 10, 10, -10).packag("R0402", "NT1")
                .part("R0402-1k", "R0402").feeder("F1", "R0402-1k", 100, 20, -5, 0)
                .placement("R1", "R0402-1k", 10, 10, 0);
        if (skip) {
            builder.placement("R2", "R0402-1k", 20, 20, 0);
        }
        Job job = builder.build();
        ((ReferenceJobProcessorRetryTests.TestFeeder) machine.getFeederByName("F1")).setPartCount(10);
        ReferenceBottomVision vision = mock(ReferenceBottomVision.class);
        when(vision.isEnabled()).thenReturn(true);
        when(vision.canHandle(any(), anyBoolean())).thenReturn(true);
        when(vision.getLocation(any(), any(), any(), any())).thenReturn(new Location(LengthUnit.Millimeters));
        when(vision.findOffsets(any(), any(), any(), any()))
                .thenThrow(new Exception("No results found in vision pipeline"))
                .thenReturn(new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false));
        Field alignments = AbstractMachine.class.getDeclaredField("partAlignments");
        alignments.setAccessible(true);
        List<PartAlignment> fixtureAlignments = (List<PartAlignment>) alignments.get(machine);
        fixtureAlignments.clear();
        fixtureAlignments.add(vision);
        ReferencePnpJobProcessor processor = (ReferencePnpJobProcessor) machine.getPnpJobProcessor();
        processor.setMaxVisionRetries(1);
        machine.setEnabled(true);
        machine.home();
        processor.initialize(job);
        Nozzle nozzle = machine.getDefaultHead().getDefaultNozzle();
        boolean recovered = false;
        boolean finished = false;
        try {
            for (int step = 0; step < 500; step++) {
                try {
                    if (!processor.next()) {
                        finished = true;
                        break;
                    }
                }
                catch (JobProcessorException failure) {
                    assertFalse(recovered, "Only the first recognition may fail");
                    assertTrue(processor.canRecoverFailedAlignment(nozzle));
                    assertTrue(skip ? processor.discardAndSkipFailedAlignment(nozzle)
                            : processor.discardAndRequeueFailedAlignment(nozzle));
                    recovered = true;
                }
            }
            assertTrue(recovered);
            assertTrue(finished, "Recovery must allow the task to finish");
            assertEquals(2, ((ReferenceJobProcessorRetryTests.TestNozzle) nozzle).getPickCount());
            assertEquals(skip ? 1 : 0, job.getActivePlacements(job.getRootPanelLocation()));
            assertNull(nozzle.getPart());
        }
        finally {
            machine.setEnabled(false);
            machine.close();
        }
    }
}
