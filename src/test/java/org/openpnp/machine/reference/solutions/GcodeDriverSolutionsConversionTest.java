package org.openpnp.machine.reference.solutions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.driver.GcodeAsyncDriver;
import org.openpnp.machine.reference.driver.GcodeDriver;

class GcodeDriverSolutionsConversionTest {
    @Test
    void convertsPlainDriverToAsyncAndBackWithoutLosingIdentity() throws Exception {
        GcodeDriver plainDriver = new GcodeDriver();
        plainDriver.setId("mks-gen-l");
        plainDriver.setName("MKS GEN L");
        plainDriver.setTimeoutMilliseconds(1500);
        plainDriver.setMaxFeedRate(12345);

        GcodeAsyncDriver asyncDriver = GcodeDriverSolutions.createAsyncDriver(plainDriver);

        assertEquals(GcodeAsyncDriver.class, asyncDriver.getClass());
        assertEquals(plainDriver.getId(), asyncDriver.getId());
        assertEquals(plainDriver.getName(), asyncDriver.getName());
        assertEquals(plainDriver.getMaxFeedRate(), asyncDriver.getMaxFeedRate());
        assertEquals(plainDriver.getTimeoutMilliseconds() * 3,
                asyncDriver.getTimeoutMilliseconds());

        GcodeDriver restoredDriver = GcodeDriverSolutions.createPlainDriver(asyncDriver);

        assertEquals(GcodeDriver.class, restoredDriver.getClass());
        assertEquals(plainDriver.getId(), restoredDriver.getId());
        assertEquals(plainDriver.getName(), restoredDriver.getName());
        assertEquals(plainDriver.getMaxFeedRate(), restoredDriver.getMaxFeedRate());
        assertEquals(plainDriver.getTimeoutMilliseconds(), restoredDriver.getTimeoutMilliseconds());
    }
}