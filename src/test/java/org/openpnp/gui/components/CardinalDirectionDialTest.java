package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.Icons;

public class CardinalDirectionDialTest {
    @Test
    public void fourScreenDirectionsMapToOpenPnpCardinalAngles() {
        assertEquals(0, CardinalDirectionDial.directionForPoint(0, -50));
        assertEquals(270, CardinalDirectionDial.directionForPoint(50, 0));
        assertEquals(180, CardinalDirectionDial.directionForPoint(0, 50));
        assertEquals(90, CardinalDirectionDial.directionForPoint(-50, 0));
    }

    @Test
    public void loadingFeederRotationDoesNotCreateAnOverride() {
        CardinalDirectionDial dial = new CardinalDirectionDial();
        dial.setFeederRotation(37.5);
        assertNull(dial.getRotationOverride());
    }

    @Test
    public void nearestCardinalWrapsNegativeAndHighAngles() {
        assertEquals(0, CardinalDirectionDial.nearestCardinal(359));
        assertEquals(270, CardinalDirectionDial.nearestCardinal(-91));
        assertEquals(90, CardinalDirectionDial.nearestCardinal(450));
    }

    @Test
    public void taskAndInspectionSvgIconsLoadAtToolbarSize() {
        assertEquals(24, Icons.job.getIconWidth());
        assertEquals(24, Icons.job.getIconHeight());
        assertEquals(24, Icons.feederInspect.getIconWidth());
        assertEquals(24, Icons.feederInspect.getIconHeight());
    }
}
