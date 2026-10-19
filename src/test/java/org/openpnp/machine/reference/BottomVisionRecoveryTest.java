package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.vision.ReferenceBottomVision;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment;
import org.openpnp.spi.base.AbstractMachine;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.spi.PnpJobPlanner.PlannedPlacement;
import org.openpnp.spi.PnpJobProcessor.JobPlacement;
import org.openpnp.spi.PnpJobProcessor.JobPlacement.Status;

class BottomVisionRecoveryTest {
    private ReferencePnpJobProcessor processor;
    private ReferenceBottomVision vision;
    private Nozzle failedNozzle;
    private Nozzle otherNozzle;
    private PlannedPlacement failed;
    private PlannedPlacement other;
    private AtomicReference<Part> carried;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        Configuration.initialize();
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        processor = new ReferencePnpJobProcessor();
        processor.initialize(new Job());
        processor.setMaxVisionRetries(1);
        vision = mock(ReferenceBottomVision.class);
        when(vision.isEnabled()).thenReturn(true);
        Field alignments = AbstractMachine.class.getDeclaredField("partAlignments");
        alignments.setAccessible(true);
        List<PartAlignment> fixtureAlignments = (List<PartAlignment>) alignments.get(machine);
        fixtureAlignments.clear();
        fixtureAlignments.add(vision);
        failedNozzle = mock(Nozzle.class);
        otherNozzle = mock(Nozzle.class);
        when(failedNozzle.getName()).thenReturn("N1");
        when(otherNozzle.getName()).thenReturn("N2");
        failed = planned("R1", failedNozzle);
        other = planned("R2", otherNozzle);
        carried = new AtomicReference<>(failed.jobPlacement.getPlacement().getPart());
        when(failedNozzle.getPart()).thenAnswer(call -> carried.get());
        doAnswer(call -> {
            carried.set(null);
            return null;
        }).when(failedNozzle).place();
        when(vision.findOffsets(any(), any(), any(), eq(failedNozzle)))
                .thenThrow(new Exception("No results found in vision pipeline"));
        when(vision.findOffsets(any(), any(), any(), eq(otherNozzle)))
                .thenReturn(new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false));
        Field step = ReferencePnpJobProcessor.class.getDeclaredField("currentStep");
        step.setAccessible(true);
        step.set(processor, processor.new Align(Arrays.asList(failed, other)));
    }

    private PlannedPlacement planned(String id, Nozzle nozzle) {
        Placement placement = new Placement(id);
        placement.setPart(new Part("TEST-" + id));
        placement.setErrorHandling(Placement.ErrorHandling.Alert);
        JobPlacement jobPlacement = new JobPlacement(new BoardLocation(new Board()), placement);
        jobPlacement.setStatus(Status.Processing);
        PlannedPlacement planned = new PlannedPlacement(nozzle, null, jobPlacement, null);
        planned.alignmentOffsets = new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false);
        return planned;
    }

    @Test
    void onlyTheActualFailedNozzleCanBeRequeued() throws Exception {
        assertFalse(processor.canRecoverFailedAlignment(failedNozzle));
        assertFalse(processor.discardAndRequeueFailedAlignment(failedNozzle));
        assertThrows(JobProcessorException.class, processor::next);
        assertTrue(processor.canRecoverFailedAlignment(failedNozzle));
        assertSame(carried.get(), processor.getFailedAlignmentPart(failedNozzle));
        assertFalse(processor.canRecoverFailedAlignment(otherNozzle));
        assertFalse(processor.discardAndRequeueFailedAlignment(otherNozzle));
        assertTrue(processor.discardAndRequeueFailedAlignment(failedNozzle));
        assertNull(carried.get());
        assertEquals(Status.Pending, failed.jobPlacement.getStatus());
        assertNull(failed.alignmentOffsets);
        assertEquals(Status.Processing, other.jobPlacement.getStatus());
        assertFalse(processor.discardAndRequeueFailedAlignment(failedNozzle));
        verify(failedNozzle, times(1)).place();
        verify(failedNozzle).moveToSafeZ();
        verify(otherNozzle, never()).place();
        assertTrue(processor.next());
        verify(vision).findOffsets(any(), any(), any(), eq(otherNozzle));
    }

    @Test
    void discardAndSkipMarksErrorWithoutRequeuingOrMarkingPlaced() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        assertFalse(processor.discardAndSkipFailedAlignment(otherNozzle));
        assertTrue(processor.discardAndSkipFailedAlignment(failedNozzle));
        assertNull(carried.get());
        assertEquals(Status.Errored, failed.jobPlacement.getStatus());
        assertTrue(failed.jobPlacement.getError().getMessage().contains("尚未贴装"));
        assertNull(failed.alignmentOffsets);
        assertEquals(Status.Processing, other.jobPlacement.getStatus());
        assertFalse(processor.discardAndSkipFailedAlignment(failedNozzle));
        assertFalse(processor.discardAndRequeueFailedAlignment(failedNozzle));
        verify(failedNozzle, times(1)).place();
        verify(otherNozzle, never()).place();
        assertTrue(processor.next());
        verify(vision).findOffsets(any(), any(), any(), eq(otherNozzle));
    }

    @Test
    void failedDiscardCannotSkipThePlacement() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        doThrow(new Exception("Discard actuator failed")).when(failedNozzle).place();
        assertThrows(JobProcessorException.class, () -> processor.discardAndSkipFailedAlignment(failedNozzle));
        assertEquals(Status.Processing, failed.jobPlacement.getStatus());
        assertNull(failed.jobPlacement.getError());
        assertTrue(processor.canRecoverFailedAlignment(failedNozzle));
    }

    @Test
    void discardFailureKeepsThePlacementPausedAndRecoverable() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        doThrow(new Exception("Discard actuator failed")).when(failedNozzle).place();
        assertThrows(JobProcessorException.class, () -> processor.discardAndRequeueFailedAlignment(failedNozzle));
        assertEquals(Status.Processing, failed.jobPlacement.getStatus());
        assertNotNull(failed.alignmentOffsets);
        assertNotNull(carried.get());
        assertTrue(processor.canRecoverFailedAlignment(failedNozzle));
    }

    @Test
    void retryVisionPreservesThePartAndDoesNotDiscard() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        doReturn(new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false))
                .when(vision).findOffsets(any(), any(), any(), eq(failedNozzle));
        assertTrue(processor.next());
        assertNotNull(carried.get());
        verify(failedNozzle, never()).place();
        assertFalse(processor.canRecoverFailedAlignment(failedNozzle));
    }

    @Test
    void changedPartCannotCauseAnotherPartToBeDiscarded() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        carried.set(new Part("ANOTHER-PART"));
        assertThrows(JobProcessorException.class, () -> processor.discardAndRequeueFailedAlignment(failedNozzle));
        verify(failedNozzle, never()).place();
        assertEquals(Status.Processing, failed.jobPlacement.getStatus());
    }

    @Test
    void missingPartStillReturnsTheFailedPlacementToPending() throws Exception {
        assertThrows(JobProcessorException.class, processor::next);
        carried.set(null);
        assertTrue(processor.discardAndRequeueFailedAlignment(failedNozzle));
        assertEquals(Status.Pending, failed.jobPlacement.getStatus());
        verify(failedNozzle, never()).place();
    }

    @Test
    void vacuumLossAfterAlignmentUsesTheSameRecoveryPath() throws Exception {
        doReturn(new PartAlignmentOffset(new Location(LengthUnit.Millimeters), false))
                .when(vision).findOffsets(any(), any(), any(), eq(failedNozzle));
        when(failedNozzle.isPartOnEnabled(Nozzle.PartOnStep.Align)).thenReturn(true);
        when(failedNozzle.isPartOn()).thenReturn(false);
        JobProcessorException error = assertThrows(JobProcessorException.class, processor::next);
        assertSame(failedNozzle, error.getSource());
        assertTrue(processor.canRecoverFailedAlignment(failedNozzle));
        assertTrue(processor.discardAndRequeueFailedAlignment(failedNozzle));
    }
}
