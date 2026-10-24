package org.openpnp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class MainVersionTest {
    @Test
    public void readsProVersionFromPackagedResource() {
        assertEquals("2.6P3-beta66-xyz", Main.getVersionString());
    }
}
