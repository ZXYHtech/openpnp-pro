package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.signaler.SoundSignaler;

class TaskSoundMenuTest {
    @Test void errorAndCompletionPreferencesAreIndependentAndDoNotDuplicateSignalers() throws Exception {
        var machine = new ReferenceMachine();
        TaskSoundMenu.setEnabled(machine, true, false);
        assertTrue(machine.getSignalers().isEmpty());
        TaskSoundMenu.setEnabled(machine, true, true);
        var signaler = TaskSoundMenu.soundSignalers(machine).get(0);
        assertTrue(signaler.isEnableErrorSound());
        assertFalse(signaler.isEnableFinishedSound());
        TaskSoundMenu.setEnabled(machine, false, true);
        TaskSoundMenu.setEnabled(machine, true, false);
        assertFalse(signaler.isEnableErrorSound());
        assertTrue(signaler.isEnableFinishedSound());
        assertEquals(1, machine.getSignalers().size());
        SoundSignaler other = new SoundSignaler();
        other.setEnableFinishedSound(true);
        machine.addSignaler(other);
        TaskSoundMenu.setEnabled(machine, false, false);
        assertTrue(TaskSoundMenu.soundSignalers(machine).stream().noneMatch(SoundSignaler::isEnableFinishedSound));
    }
}
