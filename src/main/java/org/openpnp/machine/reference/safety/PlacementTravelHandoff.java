package org.openpnp.machine.reference.safety;

import java.util.*;
import org.openpnp.model.AxesLocation;
import org.openpnp.spi.*;

/** A proven post-release retreat can join the next feeder approach in the same queue. */
public final class PlacementTravelHandoff {
    private static final Map<Head, Stamp> READY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Stamp {
        final AxesLocation axes;
        final SafetyWorkbenchModel model;
        final long revision;
        Stamp(Nozzle nozzle) {
            axes = xyz(nozzle);
            model = SafetyWorkbenchStore.get();
            revision = model.getRevision();
        }
    }
    private PlacementTravelHandoff() { }
    private static AxesLocation xyz(Nozzle nozzle) {
        return new AxesLocation(nozzle.getHead().getMachine()).byType(Axis.Type.X, Axis.Type.Y, Axis.Type.Z);
    }
    public static void record(Nozzle nozzle) { READY.put(nozzle.getHead(), new Stamp(nozzle)); }
    public static boolean consume(Nozzle nozzle) {
        Stamp stamp = READY.remove(nozzle.getHead());
        return stamp != null && stamp.model == SafetyWorkbenchStore.get() && stamp.revision == stamp.model.getRevision()
                && stamp.model.isCertified() && stamp.axes.matches(xyz(nozzle))
                && nozzle.getHead().getNozzles().stream().noneMatch(n -> n.getPart() != null);
    }
}
