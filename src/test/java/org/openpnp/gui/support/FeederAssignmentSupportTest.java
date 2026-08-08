package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;

class FeederAssignmentSupportTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void filtersByFeederNameTypeAndLoadedPart() {
        ReferenceAutoFeeder feeder = new ReferenceAutoFeeder();
        feeder.setName("前排自动飞达 07");
        Part loadedPart = part("R10K-0603");
        feeder.setPart(loadedPart);

        assertTrue(FeederAssignmentSupport.matches(feeder, "飞达 07"));
        assertTrue(FeederAssignmentSupport.matches(feeder, "referenceauto"));
        assertTrue(FeederAssignmentSupport.matches(feeder, "r10k"));
        assertFalse(FeederAssignmentSupport.matches(feeder, "C100NF"));
    }

    @Test
    void assignsPlacementPartAndOverridesOnlyRotation() throws Exception {
        ReferenceAutoFeeder feeder = new ReferenceAutoFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 12.0, 34.0, -2.5, 45.0));
        Part placementPart = part("C100NF-0402");

        FeederAssignmentSupport.assign(feeder, placementPart, true, -90.0);

        assertSame(placementPart, feeder.getPart());
        assertEquals(12.0, feeder.getLocation().getX(), 0.0001);
        assertEquals(34.0, feeder.getLocation().getY(), 0.0001);
        assertEquals(-2.5, feeder.getLocation().getZ(), 0.0001);
        assertEquals(-90.0, feeder.getLocation().getRotation(), 0.0001);
    }

    @Test
    void leavesRotationUntouchedWhenOverrideIsOff() throws Exception {
        ReferenceAutoFeeder feeder = new ReferenceAutoFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 1.0, 2.0, 3.0, 135.0));

        FeederAssignmentSupport.assign(feeder, part("U1"), false, 0.0);

        assertEquals(135.0, feeder.getLocation().getRotation(), 0.0001);
    }

    @Test
    void rejectsUnsupportedRotationBeforeChangingTheLoadedPart() {
        Feeder specializedFeeder = mock(Feeder.class);
        Part placementPart = part("Q1");

        assertThrows(IllegalArgumentException.class, () -> FeederAssignmentSupport.assign(
                specializedFeeder, placementPart, true, 90.0));
        verify(specializedFeeder, never()).setPart(placementPart);
    }

    private static Part part(String id) {
        Part part = mock(Part.class);
        when(part.getId()).thenReturn(id);
        when(part.getName()).thenReturn(id);
        return part;
    }
}
