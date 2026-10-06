package org.openpnp.events;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Placement;

public class JobPlacementProcessingEventTest {
    @Test
    public void preservesConcreteBoardPlacementStageAndSource() {
        BoardLocation boardLocation = new BoardLocation(new Board());
        Placement placement = new Placement("R1");
        Object source = new Object();

        JobPlacementProcessingEvent event = new JobPlacementProcessingEvent(boardLocation,
                placement, JobPlacementProcessingEvent.Stage.Align, source);

        assertSame(boardLocation, event.boardLocation);
        assertSame(placement, event.placement);
        assertSame(JobPlacementProcessingEvent.Stage.Align, event.stage);
        assertSame(source, event.source);
    }
}
