/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.Category;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

class SafetyWorkbenchImportServiceTest {
    @Test
    void importsSoftLimitsAndSelectedNozzleSafeZ() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        ReferenceControllerAxis x = axis(Axis.Type.X, -12, 488, 0);
        ReferenceControllerAxis y = axis(Axis.Type.Y, 5, 305, 0);
        ReferenceControllerAxis z = axis(Axis.Type.Z, -45, 95, 10);
        Machine machine = mock(Machine.class);
        when(machine.getAxes()).thenReturn(Arrays.asList(x, y, z));
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getName()).thenReturn("N1");
        when(nozzle.getSafeZZone()).thenReturn(new Length[] {
                mm(25), mm(72)
        });

        SafetyWorkbenchImportService.importConfiguration(model, machine, nozzle,
                EnumSet.of(Category.WORK_AREA));

        assertEquals(-12, model.getMinX(), 0.0001);
        assertEquals(488, model.getMaxX(), 0.0001);
        assertEquals(5, model.getMinY(), 0.0001);
        assertEquals(305, model.getMaxY(), 0.0001);
        assertEquals(-45, model.getMinZ(), 0.0001);
        assertEquals(95, model.getMaxZ(), 0.0001);
        assertTrue(model.hasImportedSafeZ());
        assertEquals(25, model.getImportedSafeZLow(), 0.0001);
        assertEquals(72, model.getImportedSafeZHigh(), 0.0001);
    }

    @Test
    void repeatedFeederImportUpdatesStableMarker() throws Exception {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        Machine machine = mock(Machine.class);
        Feeder feeder = mock(Feeder.class);
        when(feeder.getId()).thenReturn("F1");
        when(feeder.getName()).thenReturn("左侧飞达");
        when(feeder.isEnabled()).thenReturn(true);
        when(feeder.getPickLocation()).thenReturn(
                location(10, 20, -3), location(11, 22, -4));
        when(machine.getFeeders()).thenReturn(Collections.singletonList(feeder));

        SafetyWorkbenchImportService.Summary first =
                SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                        EnumSet.of(Category.FEEDER_PICK_POINTS));
        SafetyWorkbenchImportService.Summary second =
                SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                        EnumSet.of(Category.FEEDER_PICK_POINTS));

        assertEquals(1, first.getCreated());
        assertEquals(1, second.getUpdated());
        assertEquals(1, model.getObjects().size());
        assertEquals(11, model.getObjects().get(0).x, 0.0001);
        assertEquals(22, model.getObjects().get(0).y, 0.0001);
        assertEquals(-4, model.getObjects().get(0).z, 0.0001);
    }

    private static ReferenceControllerAxis axis(Axis.Type type, double low,
            double high, double home) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setSoftLimitLow(mm(low));
        axis.setSoftLimitLowEnabled(true);
        axis.setSoftLimitHigh(mm(high));
        axis.setSoftLimitHighEnabled(true);
        axis.setHomeCoordinate(mm(home));
        return axis;
    }

    private static Length mm(double value) {
        return new Length(value, LengthUnit.Millimeters);
    }

    private static Location location(double x, double y, double z) {
        return new Location(LengthUnit.Millimeters, x, y, z, 0);
    }
}
