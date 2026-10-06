package org.openpnp.corexy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Configuration;
import org.simpleframework.xml.Serializer;

class CoreXYSerializationTest {
    @Test
    void readsAndWritesExistingMachineXmlFieldNames() throws Exception {
        String xml = "<planner"
                + " core-XY-compensation-enabled=\"true\""
                + " core-XY-compensation-X-axis-id=\"x-axis\""
                + " core-XY-compensation-Y-axis-id=\"y-axis\""
                + " core-XY-compensation-speed-factor=\"0.2\">"
                + "<core-XY-compensation-min-move-a value=\"12.5\" units=\"Millimeters\"/>"
                + "<core-XY-compensation-min-move-b value=\"17.5\" units=\"Millimeters\"/>"
                + "<core-XY-compensation-A-positive-offset units=\"Millimeters\" x=\"0.01\" y=\"0.02\"/>"
                + "<core-XY-compensation-A-negative-offset units=\"Millimeters\" x=\"0.0\" y=\"0.0\"/>"
                + "<core-XY-compensation-B-positive-offset units=\"Millimeters\" x=\"0.03\" y=\"-0.04\"/>"
                + "<core-XY-compensation-B-negative-offset units=\"Millimeters\" x=\"0.0\" y=\"0.0\"/>"
                + "</planner>";

        Serializer persister = Configuration.createSerializer();
        CoreXYAdvancedMotionPlanner planner = persister.read(
                CoreXYAdvancedMotionPlanner.class, xml, false);

        assertTrue(planner.isCoreXYCompensationEnabled());
        assertEquals("x-axis", planner.getCoreXYCompensationXAxisId());
        assertEquals("y-axis", planner.getCoreXYCompensationYAxisId());
        assertEquals(12.5, planner.getCoreXYCompensationMinMoveA()
                .convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(-0.04, planner.getCoreXYCompensationBPositiveOffset()
                .convertToUnits(LengthUnit.Millimeters).getY(), 1e-9);

        StringWriter writer = new StringWriter();
        persister.write(planner, writer);
        String saved = writer.toString();
        assertTrue(saved.contains("core-XY-compensation-enabled=\"true\""));
        assertTrue(saved.contains("core-XY-compensation-A-positive-offset"));
        assertTrue(saved.contains("core-XY-compensation-B-positive-offset"));
    }
}
