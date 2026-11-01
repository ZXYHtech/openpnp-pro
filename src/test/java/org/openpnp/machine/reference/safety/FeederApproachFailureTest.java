package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.*;
import org.openpnp.machine.reference.driver.*;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.*;
import org.openpnp.spi.*;

class FeederApproachFailureTest {
    @TempDir Path directory;

    @Test void failedApproachStopsEveryFeedRetryWithoutAdvancingOrDisablingTheFeeder() throws Exception {
        var f = new DynamicSafeMotionPlannerTest();
        f.temporary = directory;
        f.fixture();
        AtomicInteger advances = new AtomicInteger(), approaches = new AtomicInteger();
        var planner = new ReferenceAdvancedMotionPlanner() {
            @Override public void moveTo(HeadMountable tool, AxesLocation axes, double speed, Motion.MotionOption... options) {
                approaches.incrementAndGet();
                axes.setToCoordinates();
            }
            @Override public void waitForCompletion(HeadMountable tool, CompletionType type) throws Exception {
                if (type == CompletionType.CommandStillstand) { throw new Exception("Injected native curve failure"); }
            }
        };
        f.machine.setMotionPlanner(planner);
        f.nozzle.setNozzleTip(new ReferenceNozzleTip());
        var profile = f.model.findToolProfile(f.head.getId(), f.nozzle.getId());
        profile.zIsNozzleTip = true;
        profile.bodyFollowsNozzleZ = false;
        var bank = f.model.createObject(SafetyWorkbenchModel.ObjectType.FEEDER);
        bank.x = 250;
        bank.y = 70;
        bank.width = 100;
        bank.depth = 60;
        bank.z = -25;
        bank.height = 18;
        bank.sourceKey = "feeder-bank:front";
        f.model.addObject(bank);
        f.model.certify("Feeder failure fixture");
        SafetyWorkbenchStore.save(f.model);
        DynamicSafeMotionSettings.setEnabled(true);
        var feedDriver = new GcodeDriver() {
            @Override public void actuate(Actuator actuator, double value) { advances.incrementAndGet(); }
        };
        f.machine.addDriver(feedDriver);
        var actuator = new ReferenceActuator();
        actuator.setName("feed-test");
        actuator.setValueType(Actuator.ActuatorValueType.Double);
        actuator.setDriver(feedDriver);
        f.head.addActuator(actuator);
        var feeder = new ReferenceAutoFeeder();
        feeder.setActuatorName(actuator.getName());
        feeder.setEnabled(true);
        feeder.setFeedRetryCount(3);
        feeder.setLocation(new Location(LengthUnit.Millimeters, 300, 100, -13, 0));
        var part = new Part("0402");
        feeder.setPart(part);
        var placement = new Placement("C3");
        placement.setPart(part);
        var jobPlacement = new PnpJobProcessor.JobPlacement(new BoardLocation(new Board()), placement);
        var processor = new ReferencePnpJobProcessor();
        var machineField = ReferencePnpJobProcessor.class.getDeclaredField("machine");
        machineField.setAccessible(true);
        machineField.set(processor, f.machine);
        var pickType = Class.forName("org.openpnp.machine.reference.ReferencePnpJobProcessor$Pick");
        var constructor = pickType.getDeclaredConstructor(ReferencePnpJobProcessor.class, List.class);
        constructor.setAccessible(true);
        var pick = constructor.newInstance(processor, List.of());
        var method = pickType.getDeclaredMethod("feed", Feeder.class, Nozzle.class, PnpJobProcessor.JobPlacement.class);
        method.setAccessible(true);
        f.machine.submit(() -> {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(pick, feeder, f.nozzle, jobPlacement));
            assertTrue(failure.getCause() instanceof JobProcessor.JobProcessorException);
            var error = (JobProcessor.JobProcessorException) failure.getCause();
            assertTrue(error.isInterrupting());
            assertTrue(error.getMessage().contains("Injected native curve failure"));
            assertEquals(0, advances.get());
            assertEquals(1, approaches.get(), "Do not rerun an approach as a feed retry");
            assertTrue(feeder.isEnabled());
            return null;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
