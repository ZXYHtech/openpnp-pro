package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TaskAutoFeederVisionTest {
    @Test
    public void correctSizedCenteredTargetScoresAboveMismatchedTarget() {
        double exact = TaskAutoFeederVision.scoreCandidate(1.6, 0.8, 1.6, 0.8, 0.2, 0.9);
        double wrongSize = TaskAutoFeederVision.scoreCandidate(3.0, 1.8, 1.6, 0.8, 0.2, 0.9);
        double farAway = TaskAutoFeederVision.scoreCandidate(1.6, 0.8, 1.6, 0.8, 1.8, 0.9);

        assertTrue(exact > 0.8);
        assertTrue(exact > wrongSize);
        assertTrue(exact > farAway);
    }

    @Test
    public void toleratesVisiblePinsAndCanUseACenteredPocketForLargeBlackParts() {
        double bodyWithPins = TaskAutoFeederVision.scoreCandidate(
                3.2, 0.8, 1.6, 0.8, 0.1, 0.55);
        double centeredPocket = TaskAutoFeederVision.scorePocketCandidate(
                4.2, 2.1, 1.6, 0.8, 0.1, 0.95);
        double entireLane = TaskAutoFeederVision.scoreCandidate(
                10.0, 5.0, 1.6, 0.8, 0.1, 0.95);

        assertTrue(bodyWithPins > 0.55);
        assertTrue(centeredPocket > 0.55);
        assertTrue(entireLane < 0.55);
    }

    @Test
    public void morphologyKernelsRemainOddAndBounded() {
        assertEquals(3, TaskAutoFeederVision.oddKernelSize(1, 3, 15, 100));
        assertEquals(9, TaskAutoFeederVision.oddKernelSize(8, 3, 15, 100));
        assertEquals(13, TaskAutoFeederVision.oddKernelSize(20, 3, 15, 15));
    }
}
