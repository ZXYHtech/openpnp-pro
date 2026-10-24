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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.Category;
import org.openpnp.machine.reference.safety.SafetyWorkbenchImportService.FeederType;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

class SafetyWorkbenchImportServiceTest {
    @Test void measuredElectricRowsAndCameraSurviveRepeatedImport() throws Exception {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.setMaxY(454);
        model.setMeasuredImportGeometry(40, 370, -7, -13);
        Feeder front = feeder(org.openpnp.machine.reference.feeder.ReferenceAutoFeeder.class,
                "F", "front", true, location(50, 30, 0));
        Feeder rear = feeder(org.openpnp.machine.reference.feeder.ReferenceAutoFeeder.class,
                "R", "rear", true, location(50, 380, 0));
        Camera camera = mock(Camera.class);
        when(camera.getId()).thenReturn("C");
        when(camera.getName()).thenReturn("Bottom");
        when(camera.getLooking()).thenReturn(Camera.Looking.Up);
        when(camera.getLocation()).thenReturn(location(100, 150, 5));
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Arrays.asList(front, rear));
        when(machine.getCameras()).thenReturn(Collections.singletonList(camera));
        for (int i = 0; i < 2; i++) {
            SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                    EnumSet.of(Category.FEEDER_PICK_POINTS, Category.BOTTOM_CAMERAS));
            if (i == 0) {
                for (var object : model.getObjects()) {
                    if (object.type == SafetyWorkbenchModel.ObjectType.FEEDER) { object.depth = 60; }
                    else { object.apertureWidth = 10; }
                }
            }
        }
        assertEquals(3, model.getObjects().size());
        for (var object : model.getObjects()) {
            if (object.type == SafetyWorkbenchModel.ObjectType.FEEDER) {
                assertEquals(-7, object.z + object.height, 1e-8);
                assertEquals(2, object.clearance, 1e-8);
                assertEquals(60, object.depth, 1e-8);
                assertEquals(object.rearFacing ? 370 : 40,
                        object.rearFacing ? object.y : object.y + object.depth, 1e-8);
            } else {
                assertEquals(-13, object.z + object.height, 1e-8);
                assertEquals(10, object.apertureWidth, 1e-8);
            }
        }
    }
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
        SafetyWorkbenchModel.SafetyObject imported = model.getObjects().get(0);
        assertEquals(SafetyWorkbenchModel.ObjectType.FEEDER, imported.type);
        assertEquals(-1, imported.x, 0.0001);
        assertEquals(10, imported.y, 0.0001);
        assertEquals(-22, imported.z, 0.0001);
        assertEquals(24, imported.width, 0.0001);
        assertEquals(120, imported.depth, 0.0001);
        assertEquals(18, imported.height, 0.0001);
        assertTrue(imported.obstacle);
        assertTrue(imported.locked);
    }

    @Test
    void groupsAndImportsOnlySelectedFeederTypes() throws Exception {
        ReferenceStripFeeder strip1 = feeder(ReferenceStripFeeder.class,
                "S1", "条带 1", true, location(10, 20, -3));
        ReferenceStripFeeder strip2 = feeder(ReferenceStripFeeder.class,
                "S2", "条带 2", false, location(30, 20, -3));
        ReferenceTrayFeeder tray = feeder(ReferenceTrayFeeder.class,
                "T1", "托盘 1", true, location(50, 60, -2));
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Arrays.asList(strip1, strip2, tray));

        List<FeederType> types = SafetyWorkbenchImportService.feederTypes(machine);
        assertEquals(2, types.size());
        FeederType stripType = types.stream()
                .filter(type -> type.getKey().equals(ReferenceStripFeeder.class.getName()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("条带飞达（ReferenceStripFeeder）", stripType.getLabel());
        assertEquals(2, stripType.getCount());
        assertEquals(1, stripType.getEnabledCount());

        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyWorkbenchImportService.Summary summary =
                SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                        EnumSet.of(Category.FEEDER_PICK_POINTS),
                        Collections.singleton(stripType.getKey()));

        assertEquals(2, model.getObjects().size());
        assertTrue(model.getObjects().stream()
                .allMatch(object -> object.sourceKey.startsWith("feeder:S")));
        assertTrue(summary.getDetails().stream()
                .anyMatch(detail -> detail.contains("条带飞达") && detail.contains("2 个")));
    }

    @Test
    void rearFeederIsMirroredTowardTheWorkArea() throws Exception {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.setMinY(0);
        model.setMaxY(300);
        Feeder feeder = feeder(ReferenceStripFeeder.class,
                "R1", "后侧飞达", true, location(80, 250, -3));
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Collections.singletonList(feeder));

        SafetyWorkbenchImportService.Summary summary =
                SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                        EnumSet.of(Category.FEEDER_PICK_POINTS));

        SafetyWorkbenchModel.SafetyObject imported = model.getObjects().get(0);
        assertTrue(imported.rearFacing);
        assertEquals(142, imported.y, 0.0001);
        assertEquals(250, imported.y + imported.depth * 0.90, 0.0001);
        assertTrue(summary.getDetails().stream()
                .anyMatch(detail -> detail.contains("翻转后侧飞达 1 个")));
    }

    @Test
    void discardLocationImportsAsAVisiblePhysicalStation() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        Machine machine = mock(Machine.class);
        when(machine.getDiscardLocation()).thenReturn(location(100, 200, -5));

        SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                EnumSet.of(Category.DISCARD));

        SafetyWorkbenchModel.SafetyObject discard = model.getObjects().get(0);
        assertEquals(SafetyWorkbenchModel.ObjectType.DISCARD_POINT, discard.type);
        assertEquals(52, discard.width, 0.0001);
        assertEquals(52, discard.depth, 0.0001);
        assertEquals(24, discard.height, 0.0001);
        assertEquals(26, discard.apertureWidth, 0.0001);
        assertTrue(!discard.obstacle);
    }

    @Test
    void emptyFeederTypeSelectionSkipsImport() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        Machine machine = mock(Machine.class);
        when(machine.getFeeders()).thenReturn(Collections.emptyList());

        SafetyWorkbenchImportService.Summary summary =
                SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                        EnumSet.of(Category.FEEDER_PICK_POINTS), Collections.emptySet());

        assertTrue(model.getObjects().isEmpty());
        assertTrue(summary.getWarnings().stream()
                .anyMatch(warning -> warning.contains("没有选择任何飞达类型")));
    }

    @Test
    void importsUpLookingCameraAsPhysicalBody() throws Exception {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        Machine machine = mock(Machine.class);
        Camera camera = mock(Camera.class);
        when(camera.getId()).thenReturn("C1");
        when(camera.getName()).thenReturn("底部相机");
        when(camera.getLooking()).thenReturn(Camera.Looking.Up);
        when(camera.getLocation()).thenReturn(location(100, 50, 5));
        when(machine.getCameras()).thenReturn(Collections.singletonList(camera));

        SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                EnumSet.of(Category.BOTTOM_CAMERAS));

        assertEquals(1, model.getObjects().size());
        SafetyWorkbenchModel.SafetyObject imported = model.getObjects().get(0);
        assertEquals(SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA, imported.type);
        assertEquals(65, imported.x, 0.0001);
        assertEquals(15, imported.y, 0.0001);
        assertEquals(-20, imported.z, 0.0001);
        assertEquals(70, imported.width, 0.0001);
        assertEquals(70, imported.depth, 0.0001);
        assertEquals(25, imported.height, 0.0001);
        assertEquals(28, imported.apertureWidth, 0.0001);
        assertTrue(imported.obstacle);
        assertTrue(imported.locked);
    }

    @Test
    void importsPhysicalNozzleChangerAndMigratesPointMarkers() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyWorkbenchModel.SafetyObject legacy =
                model.createObject(SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER_POINT);
        legacy.sourceKey = "nozzle-tip:legacy:changer:0";
        model.addObject(legacy);

        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        tip.setName("JUKI-502");
        tip.setChangerStartLocation(location(10, 20, -8));
        tip.setChangerMidLocation(location(10, 35, -10));
        tip.setChangerMidLocation2(location(10, 45, -12));
        tip.setChangerEndLocation(location(10, 50, -12));
        Machine machine = mock(Machine.class);
        when(machine.getNozzleTips()).thenReturn(Collections.singletonList(tip));

        SafetyWorkbenchImportService.importConfiguration(model, machine, null,
                EnumSet.of(Category.NOZZLE_CHANGER));

        assertEquals(1, model.getObjects().size());
        SafetyWorkbenchModel.SafetyObject dock = model.getObjects().get(0);
        assertEquals(SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER, dock.type);
        assertEquals(-5, dock.x, 0.0001);
        assertEquals(29, dock.y, 0.0001);
        assertEquals(-44, dock.z, 0.0001);
        assertEquals(-12, dock.z + dock.height, 0.0001);
        assertEquals(30, dock.width, 0.0001);
        assertEquals(42, dock.depth, 0.0001);
        assertEquals(12, dock.apertureWidth, 0.0001);
        assertEquals(26, dock.apertureDepth, 0.0001);
        assertTrue(dock.obstacle);
        assertTrue(dock.locked);
    }

    @Test
    void workSurfaceUsesMedianPhysicalTopInsteadOfZSoftLimit() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        addPhysicalObject(model, SafetyWorkbenchModel.ObjectType.FEEDER, -49, 18);
        addPhysicalObject(model, SafetyWorkbenchModel.ObjectType.BOTTOM_CAMERA, -56, 25);
        addPhysicalObject(model, SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER, -42, 32);

        assertEquals(-31, SafetyWorkbenchGeometry.workSurfaceZ(model), 0.0001);
    }

    private static <T extends Feeder> T feeder(Class<T> type, String id,
            String name, boolean enabled, Location location) throws Exception {
        T feeder = mock(type);
        when(feeder.getId()).thenReturn(id);
        when(feeder.getName()).thenReturn(name);
        when(feeder.isEnabled()).thenReturn(enabled);
        when(feeder.getPickLocation()).thenReturn(location);
        return feeder;
    }

    private static void addPhysicalObject(SafetyWorkbenchModel model,
            SafetyWorkbenchModel.ObjectType type, double z, double height) {
        SafetyWorkbenchModel.SafetyObject object = model.createObject(type);
        object.z = z;
        object.height = height;
        model.addObject(object);
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
