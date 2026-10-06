package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Part;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

class JobPanelSafePauseTest {
    @Test
    void allNozzlesMustBeEmptyBeforeSafePause() {
        Machine machine = mock(Machine.class);
        Head head = mock(Head.class);
        Nozzle emptyNozzle = mock(Nozzle.class);
        Nozzle occupiedNozzle = mock(Nozzle.class);
        when(machine.getHeads()).thenReturn(Collections.singletonList(head));
        when(head.getNozzles()).thenReturn(Arrays.asList(emptyNozzle, occupiedNozzle));
        when(emptyNozzle.getPart()).thenReturn(null);
        when(occupiedNozzle.getPart()).thenReturn(mock(Part.class));

        assertFalse(JobPanel.areAllNozzlesEmpty(machine));

        when(occupiedNozzle.getPart()).thenReturn(null);
        assertTrue(JobPanel.areAllNozzlesEmpty(machine));
        assertTrue(JobPanel.shouldPauseImmediatelyWhenNozzlesEmpty(
                JobPanel.State.Running, machine));
        assertFalse(JobPanel.shouldPauseImmediatelyWhenNozzlesEmpty(
                JobPanel.State.Paused, machine));
    }
}