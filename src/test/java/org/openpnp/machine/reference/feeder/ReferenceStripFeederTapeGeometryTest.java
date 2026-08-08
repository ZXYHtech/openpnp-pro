package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;

public class ReferenceStripFeederTapeGeometryTest {
    @Test
    public void widerTapeMovesPickCenterFartherFromSprocketHoleLine() {
        assertEquals(3.5, ReferenceStripFeeder.calculateHoleToPartLateral(
                new Length(8, LengthUnit.Millimeters)).getValue(), 0.000001);
        assertEquals(5.5, ReferenceStripFeeder.calculateHoleToPartLateral(
                new Length(12, LengthUnit.Millimeters)).getValue(), 0.000001);
        assertEquals(7.5, ReferenceStripFeeder.calculateHoleToPartLateral(
                new Length(16, LengthUnit.Millimeters)).getValue(), 0.000001);
        assertEquals(11.5, ReferenceStripFeeder.calculateHoleToPartLateral(
                new Length(24, LengthUnit.Millimeters)).getValue(), 0.000001);
    }
}
