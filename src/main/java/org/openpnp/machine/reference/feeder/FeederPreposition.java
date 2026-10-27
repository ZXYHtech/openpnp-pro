package org.openpnp.machine.reference.feeder;

import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.pmw.tinylog.Logger;

/** Overlap a queued, collision-checked approach with a separate feeder controller. */
public final class FeederPreposition {
    private static final ThreadLocal<Actuator> ACTIVE = new ThreadLocal<>();

    private FeederPreposition() { }

    public static boolean isActive(Actuator actuator) {
        return ACTIVE.get() == actuator;
    }

    public static boolean feed(Feeder feeder, Nozzle nozzle, Location placement) throws Exception {
        if ((feeder.getClass() != ReferenceAutoFeeder.class
                && feeder.getClass() != ReferenceSlotAutoFeeder.class)
                || nozzle.getClass() != ReferenceNozzle.class
                || !DynamicSafeMotionSettings.isEnabled()
                || !SafetyWorkbenchStore.get().isCertified()
                || nozzle.getNozzleTip() == null
                || nozzle.isPartOffEnabled(Nozzle.PartOffStep.BeforePick)) { return false; }
        ReferenceAutoFeeder automatic = (ReferenceAutoFeeder) feeder;
        if (automatic.isMoveBeforeFeed() || automatic.getFeedOptions() != ReferenceAutoFeeder.FeedOptions.Normal) { return false; }
        Machine machine = Configuration.get().getMachine();
        Actuator actuator = nozzle.getHead().getActuatorByName(automatic.getActuatorName());
        if (actuator == null) { actuator = machine.getActuatorByName(automatic.getActuatorName()); }
        if (actuator == null || actuator.getClass() != ReferenceActuator.class
                || actuator.getValueType() == Actuator.ActuatorValueType.Profile
                || !(actuator.getDriver() instanceof GcodeDriver)) { return false; }
        // Sharing the motion controller cannot overlap safely. Check all machine axes.
        for (org.openpnp.spi.Axis axis : machine.getAxes()) {
            if (axis instanceof org.openpnp.spi.ControllerAxis
                    && ((org.openpnp.spi.ControllerAxis) axis).getDriver() == actuator.getDriver()) { return false; }
        }
        if (!machine.isTask(Thread.currentThread())
                || !Configuration.get().getScripting().hasNoScript("Feeder.BeforeFeed")) { return false; }
        if (!Configuration.get().getScripting().hasNoScript("Feeder.AfterFeed")) {
            // First feed after launch has not cached AfterFeed yet. Inspect
            // matching files without running the hook ahead of its real event.
            var scripting = Configuration.get().getScripting();
            java.io.File events = scripting.getEventsDirectory();
            if (events != null) {
                java.io.File[] scripts = events.listFiles();
                if (scripts == null) { return false; }
                for (java.io.File script : scripts) {
                    String name = org.apache.commons.io.FilenameUtils.getBaseName(script.getName());
                    String extension = org.apache.commons.io.FilenameUtils.getExtension(script.getName());
                    if (script.isFile() && (name.equals("Feeder.AfterFeed") || name.startsWith("Feeder.AfterFeed."))
                            && java.util.Arrays.asList(scripting.getExtensions()).contains(extension)) { return false; }
                }
            }
        }
        // Complete the previous tip's retreat before feeding. Only the next safe
        // approach is allowed to overlap, never a pick/contact stroke.
        machine.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
        // Establish the final pick rotation/reference BEFORE moving. Recomputing
        // MinimalRotation after arriving changes the pose and can trigger Safe-Z again.
        nozzle.prepareForPickAndPlaceArticulation(feeder.getPickLocation(), placement);
        if (!DynamicSafeMotionPlanner.prepositionForFeed(nozzle, feeder)) { return false; }
        machine.getMotionPlanner().waitForCompletion(null, CompletionType.CommandStillstand);
        ACTIVE.set(actuator);
        try {
            feeder.feed(nozzle); // Synchronous feed completion remains the pickup barrier.
            Logger.info("Feeder preposition {} on {}: feed completed, pickup released",
                    nozzle.getName(), feeder.getName());
            return true;
        } finally {
            ACTIVE.remove();
        }
    }
}
