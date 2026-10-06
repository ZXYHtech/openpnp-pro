package org.openpnp.corexy.precision;

import java.util.List;

/** Machine operations are always executed by the caller's machine task, never on the EDT. */
public interface CalibrationMachine {
    boolean isSimulation();
    String fingerprint() throws Exception;
    void prepare(CalibrationRequest request, CalibrationPlan plan) throws Exception;
    default void setCancellationGuard(java.util.function.BooleanSupplier cancelled) {
    }
    default List<PrecisionSample> previousObservations() {
        return java.util.Collections.emptyList();
    }
    default void validatePlan(CalibrationPlan plan) throws Exception {
    }
    double measureNoise() throws Exception;
    PrecisionSample observe(CalibrationPlan.Path path) throws Exception;
    void apply(PrecisionModel model, int settling) throws Exception;
    List<String> optimizeControllerAndTools(CalibrationRequest request) throws Exception;
    default void calibrateHomeState(PrecisionModel model) throws Exception {
    }
    void finish(boolean keep, PrecisionModel model, int settling) throws Exception;
}
