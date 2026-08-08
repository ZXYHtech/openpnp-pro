package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CoalescingUiUpdateTest {
    @Test
    void collapsesAThousandQueuedNotificationsIntoOneUpdate() {
        Queue<Runnable> eventQueue = new ArrayDeque<>();
        AtomicInteger updates = new AtomicInteger();
        CoalescingUiUpdate coalescing = new CoalescingUiUpdate(
                updates::incrementAndGet, eventQueue::add);

        for (int i = 0; i < 1_000; i++) {
            coalescing.request();
        }

        assertEquals(1, eventQueue.size());
        eventQueue.remove().run();
        assertEquals(1, updates.get());
        assertEquals(0, eventQueue.size());
    }

    @Test
    void retainsOneFollowUpWhenStateChangesDuringUpdate() {
        Queue<Runnable> eventQueue = new ArrayDeque<>();
        AtomicInteger updates = new AtomicInteger();
        CoalescingUiUpdate[] holder = new CoalescingUiUpdate[1];
        holder[0] = new CoalescingUiUpdate(() -> {
            if (updates.incrementAndGet() == 1) {
                for (int i = 0; i < 100; i++) {
                    holder[0].request();
                }
            }
        }, eventQueue::add);

        holder[0].request();
        eventQueue.remove().run();

        assertEquals(1, eventQueue.size());
        eventQueue.remove().run();
        assertEquals(2, updates.get());
        assertEquals(0, eventQueue.size());
    }
}
