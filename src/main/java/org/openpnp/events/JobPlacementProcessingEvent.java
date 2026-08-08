/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 *
 * This file is part of OpenPnP.
 */

package org.openpnp.events;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.Placement;

/**
 * Announces which placement is currently being processed. Pick/Align/Place are display-only
 * stages. Complete is emitted after release and safe-Z retraction; a one-shot operator workflow
 * may use it to queue follow-up inspection after the current machine task has finished.
 */
public class JobPlacementProcessingEvent {
    public enum Stage {
        Pick,
        Align,
        Place,
        Complete
    }

    public final BoardLocation boardLocation;
    public final Placement placement;
    public final Stage stage;
    public final Object source;

    public JobPlacementProcessingEvent(BoardLocation boardLocation, Placement placement,
            Stage stage, Object source) {
        this.boardLocation = boardLocation;
        this.placement = placement;
        this.stage = stage;
        this.source = source;
    }
}
