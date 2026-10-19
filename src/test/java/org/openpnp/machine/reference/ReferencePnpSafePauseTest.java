package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;

class ReferencePnpSafePauseTest {
    @Test void finishingCurrentPartDoesNotStartTheNextPickUntilResumed() throws Exception {
        Configuration.initialize();
        ReferenceMachine machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        Head head = mock(Head.class);
        Nozzle nozzle = mock(Nozzle.class);
        when(head.getNozzles()).thenReturn(Collections.singletonList(nozzle));
        machine.addHead(head);
        when(nozzle.getPart()).thenReturn(mock(Part.class));
        ReferencePnpJobProcessor processor = new ReferencePnpJobProcessor();
        AtomicInteger picks = new AtomicInteger();
        ReferencePnpJobProcessor.Step nextPick = () -> {
            picks.incrementAndGet();
            return null;
        };
        ReferencePnpJobProcessor.Step place = () -> {
            when(nozzle.getPart()).thenReturn(null);
            return nextPick;
        };
        Field step = ReferencePnpJobProcessor.class.getDeclaredField("currentStep");
        step.setAccessible(true);
        step.set(processor, place);
        processor.requestPauseWhenNozzlesEmpty(true);
        assertTrue(processor.next(), "Current carried part must finish");
        assertTrue(processor.next(), "A safe pause must preserve the next step");
        assertEquals(0, picks.get(), "Do not pick another cycle after the nozzles become empty");
        processor.requestPauseWhenNozzlesEmpty(false);
        assertFalse(processor.next());
        assertEquals(1, picks.get());
    }

    @Test void pauseRequestNeverWaitsForTheMonitorHeldByTheRunningMachineStep() throws Exception {
        ReferencePnpJobProcessor processor = new ReferencePnpJobProcessor();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread motion = new Thread(() -> {
            synchronized (processor) {
                held.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
        });
        motion.start();
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> processor.requestPauseWhenNozzlesEmpty(true));
        }
        finally {
            release.countDown();
            motion.join(3000);
        }
    }
}
