package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTabbedPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.gui.FeedersPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.MachineControlsPanel;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Actuator;

class ManualFeederPickupTest {
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest fixture;
    ReferenceAutoFeeder feeder;
    Part part;
    AtomicInteger feeds;
    Object previousFrame;
    java.lang.reflect.Field frameField;

    @BeforeEach void setup() throws Exception {
        fixture = new DynamicSafeMotionPlannerTest();
        fixture.temporary = temporary;
        fixture.fixture();
        var nozzle = fixture.nozzle;
        var tip = new ReferenceNozzleTip();
        tip.getCalibration().setEnabled(false);
        nozzle.setNozzleTip(tip);
        nozzle.setVacuumActuator(mock(Actuator.class));
        var pack = new org.openpnp.model.Package("0603-test");
        pack.addCompatibleNozzleTip(tip);
        pack.getFootprint().setBodyWidth(1.6);
        pack.getFootprint().setBodyHeight(.8);
        part = new Part("manual-pick-test");
        part.setPackage(pack);
        part.setHeight(new Length(.8, LengthUnit.Millimeters));
        var bank = fixture.model.createObject(SafetyWorkbenchModel.ObjectType.FEEDER);
        bank.x = 250;
        bank.y = 70;
        bank.z = -25;
        bank.width = 100;
        bank.depth = 60;
        bank.height = 18;
        bank.clearance = 2;
        bank.sourceKey = "feeder-bank:front";
        fixture.model.addObject(bank);
        var profile = fixture.model.findToolProfile(fixture.head.getId(), nozzle.getId());
        profile.bodyFollowsNozzleZ = false;
        profile.zIsNozzleTip = true;
        fixture.model.certify("Measured manual pickup");
        DynamicSafeMotionSettings.setEnabled(true);
        DynamicSafeMotionSettings.setStrictEnabled(true);
        feeds = new AtomicInteger();
        GcodeDriver feederDriver = new GcodeDriver() {
            @Override public void actuate(Actuator actual, double value) {
                feeds.incrementAndGet();
                assertTrue(fixture.commands.size() >= 1, "Manual feed follows a checked approach");
                assertEquals(300, nozzle.getLocation().getX(), .001);
                assertTrue(nozzle.getLocation().getZ() > -13);
            }
        };
        fixture.machine.addDriver(feederDriver);
        var actuator = new ReferenceActuator();
        actuator.setName("manual-test-feeder");
        actuator.setValueType(Actuator.ActuatorValueType.Double);
        actuator.setDriver(feederDriver);
        fixture.machine.addActuator(actuator);
        feeder = new ReferenceAutoFeeder();
        feeder.setActuatorName(actuator.getName());
        feeder.setPart(part);
        feeder.setLocation(new Location(LengthUnit.Millimeters, 300, 100, -13, 0));
        var frame = mock(MainFrame.class);
        var controls = mock(MachineControlsPanel.class);
        when(frame.getMachineControls()).thenReturn(controls);
        when(controls.getSelectedNozzle()).thenReturn(nozzle);
        when(frame.getTabs()).thenReturn(new JTabbedPane());
        when(frame.getFeedersTab()).thenReturn(mock(FeedersPanel.class));
        frameField = MainFrame.class.getDeclaredField("mainFrame");
        frameField.setAccessible(true);
        previousFrame = frameField.get(null);
        frameField.set(null, frame);
    }

    @AfterEach void cleanup() throws Exception {
        if (frameField != null) { frameField.set(null, previousFrame); }
        if (fixture != null) { fixture.machine.close(); }
    }

    @Test void manualPickupUsesTheSamePrepositionAndVerticalDescentAsJob() throws Exception {
        fixture.machine.submit(() -> {
            FeedersPanel.pickFeeder(feeder);
            return null;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, feeds.get(), "Never feed again after preposition");
        assertSame(part, fixture.nozzle.getPart());
        assertEquals(3, fixture.commands.size(), "Approach, vertical pick, local retreat only");
        assertEquals(300, fixture.commands.get(1).getCoordinate(fixture.x), .001);
        assertEquals(-13, fixture.commands.get(1).getCoordinate(fixture.z), .001);
        assertTrue(fixture.nozzle.getLocation().getZ() < 0, "No forced full Safe-Z after manual pick");
    }

    @Test void oldManualPickRecordAllowsRepeatedPickupWithoutDiscardOrVacuumRelease() throws Exception {
        var setPart = org.openpnp.spi.base.AbstractNozzle.class.getDeclaredMethod("setPart", Part.class);
        setPart.setAccessible(true);
        setPart.invoke(fixture.nozzle, part);
        fixture.machine.submit(() -> {
            FeedersPanel.pickFeeder(feeder);
            FeedersPanel.pickFeeder(feeder);
            return null;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, feeds.get());
        assertSame(part, fixture.nozzle.getPart());
        assertSame(feeder, fixture.nozzle.getPartsFeeder());
        verify(fixture.nozzle.getVacuumActuator(), never()).actuate(false);
    }
}
