package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

class FeederUtilsInterchangeabilityTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void findsEnabledFeederAssignedToAnAliasPart() throws Exception {
        Part jobPart = new Part("TASK-R10K");
        jobPart.setAliases("R10K-STOCK");
        Part feederPart = new Part("r10k-stock");
        Feeder feeder = mock(Feeder.class);
        when(feeder.getPart()).thenReturn(feederPart);
        when(feeder.isEnabled()).thenReturn(true);
        when(feeder.getPriority()).thenReturn(Feeder.Priority.Normal);
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Arrays.asList(feeder));

        List<Feeder> matches = FeederUtils.findFeeders(machine, jobPart);

        assertEquals(Arrays.asList(feeder), matches);
    }

    @Test
    void keepsUnrelatedFeedersOutOfTheDispatchPool() throws Exception {
        Part jobPart = new Part("TASK-R10K");
        Part feederPart = new Part("FEEDER-C10NF");
        Feeder feeder = mock(Feeder.class);
        when(feeder.getPart()).thenReturn(feederPart);
        when(feeder.isEnabled()).thenReturn(true);
        when(feeder.getPriority()).thenReturn(Feeder.Priority.Normal);
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Arrays.asList(feeder));

        assertEquals(0, FeederUtils.findFeeders(machine, jobPart).size());
    }
}
