package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Placement;

class ReferencePnpJobProcessorTrialTargetTest {
    @Test
    void trialModeExposesOnlyTheExactBoardAndPlacementInstances() {
        BoardLocation selectedBoard = new BoardLocation(new Board());
        BoardLocation otherBoard = new BoardLocation(new Board());
        Placement selectedPlacement = new Placement("R1");
        Placement otherPlacement = new Placement("R2");

        assertTrue(ReferencePnpJobProcessor.isTrialPlacementTarget(
                selectedBoard, selectedPlacement, selectedBoard, null));
        assertTrue(ReferencePnpJobProcessor.isTrialPlacementTarget(
                selectedBoard, selectedPlacement, selectedBoard, selectedPlacement));
        assertFalse(ReferencePnpJobProcessor.isTrialPlacementTarget(
                selectedBoard, selectedPlacement, selectedBoard, otherPlacement));
        assertFalse(ReferencePnpJobProcessor.isTrialPlacementTarget(
                selectedBoard, selectedPlacement, otherBoard, selectedPlacement));
    }

    @Test
    void normalModeDoesNotFilterCandidates() {
        assertTrue(ReferencePnpJobProcessor.isTrialPlacementTarget(
                null, null, new BoardLocation(new Board()), new Placement("C1")));
    }
}
