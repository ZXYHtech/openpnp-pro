package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigurationRowReorderTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        Configuration.initialize();
        configuration = Configuration.get();
    }

    @Test
    void packageReorderChangesOrderAndFiresEvent() {
        Package first = new Package("PKG-A");
        Package second = new Package("PKG-B");
        configuration.addPackage(first);
        configuration.addPackage(second);
        AtomicInteger events = new AtomicInteger();
        configuration.addPropertyChangeListener("packages", event -> events.incrementAndGet());

        assertTrue(configuration.reorderPackages(Arrays.asList(second, first)));
        assertEquals(Arrays.asList(second, first), configuration.getPackages());
        assertEquals(1, events.get());
    }

    @Test
    void partReorderChangesOrderAndFiresEvent() {
        Part first = new Part("PART-A");
        Part second = new Part("PART-B");
        configuration.addPart(first);
        configuration.addPart(second);
        AtomicInteger events = new AtomicInteger();
        configuration.addPropertyChangeListener("parts", event -> events.incrementAndGet());

        assertTrue(configuration.reorderParts(Arrays.asList(second, first)));
        assertEquals(Arrays.asList(second, first), configuration.getParts());
        assertEquals(1, events.get());
    }
}
