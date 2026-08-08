/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps high-frequency state notifications from flooding the Swing event queue.
 * A burst is reduced to one update, while a notification arriving during that
 * update is retained as one follow-up update so the latest state is not lost.
 */
public final class CoalescingUiUpdate {
    private final Runnable update;
    private final Consumer<Runnable> scheduler;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public CoalescingUiUpdate(Runnable update) {
        this(update, EventQueue::invokeLater);
    }

    CoalescingUiUpdate(Runnable update, Consumer<Runnable> scheduler) {
        this.update = update;
        this.scheduler = scheduler;
    }

    public void request() {
        dirty.set(true);
        scheduleIfNeeded();
    }

    private void scheduleIfNeeded() {
        if (pending.compareAndSet(false, true)) {
            scheduler.accept(this::runUpdate);
        }
    }

    private void runUpdate() {
        dirty.set(false);
        try {
            update.run();
        }
        finally {
            pending.set(false);
            if (dirty.get()) {
                scheduleIfNeeded();
            }
        }
    }
}
