package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;

class FeederAssignmentPersistenceTest {
    @Test void saveLeavesEventThreadResponsiveAndNeverSavesDirtyBoardFiles() throws Exception {
        Configuration configuration = mock(Configuration.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean background = new AtomicBoolean(), callbackOnEdt = new AtomicBoolean();
        doAnswer(call -> {
            background.set(!SwingUtilities.isEventDispatchThread());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(configuration).saveMachineConfiguration();
        try {
            SwingUtilities.invokeAndWait(() -> FeederAssignmentSupport.saveMachineAsync(configuration, () -> {
                callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                finished.countDown();
            }, failure -> fail(failure)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            CountDownLatch responsive = new CountDownLatch(1);
            SwingUtilities.invokeLater(responsive::countDown);
            assertTrue(responsive.await(2, TimeUnit.SECONDS), "Saving must not block the EDT");
        }
        finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(background.get());
        assertTrue(callbackOnEdt.get());
        verify(configuration, never()).save();
    }

    @Test void failedSaveReturnsToEventThreadForRollback() throws Exception {
        Configuration configuration = mock(Configuration.class);
        doThrow(new java.io.IOException("disk full")).when(configuration).saveMachineConfiguration();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicBoolean restored = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> FeederAssignmentSupport.saveMachineAsync(configuration,
                () -> fail("Save must fail"), failure -> {
                    restored.set(SwingUtilities.isEventDispatchThread() && "disk full".equals(failure.getMessage()));
                    failed.countDown();
                }));
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertTrue(restored.get());
        verify(configuration, never()).save();
    }
}
