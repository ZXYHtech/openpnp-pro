package org.openpnp.machine.reference.vision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.vision.DualNozzleCyclePlanner.Score;

class DualNozzleCyclePlannerTest {
    @Test void preferActualEstimatedTimeBeforeDistanceOrDirectionChanges() {
        Score shortButSlow = new Score(0, 2.0, 100, 0);
        Score longerButFast = new Score(7, 1.8, 140, 4);
        assertSame(longerButFast, DualNozzleCyclePlanner.best(List.of(shortButSlow, longerButFast)));
    }

    @Test void nearEqualTimesPreferFewerReversalsThenShorterDistance() {
        Score manyTurns = new Score(0, 2.0, 100, 4);
        Score fewerTurns = new Score(1, 2.002, 120, 2);
        Score sameTurnsShorter = new Score(2, 2.004, 110, 2);
        assertSame(sameTurnsShorter, DualNozzleCyclePlanner.best(List.of(manyTurns, fewerTurns, sameTurnsShorter)));
        assertNull(DualNozzleCyclePlanner.best(List.of(new Score(0, Double.NaN, 0, 0))));
    }
}
