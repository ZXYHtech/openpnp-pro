package org.openpnp.corexy.distribution;

import java.nio.file.Paths;
import java.util.Locale;
import org.openpnp.corexy.precision.CalibrationController;
import org.openpnp.corexy.precision.CalibrationRequest;
import org.openpnp.corexy.precision.DigitalTwinMachine;
import org.openpnp.corexy.precision.PrecisionStore;

/** Headless deterministic end-to-end check included in the standalone distribution. */
public final class CoreXYOffline {
    private CoreXYOffline() {
    }
    public static void main(String[] arguments) throws Exception {
        CalibrationRequest request = new CalibrationRequest();
        request.optimizeShaping = true;
        request.calibrateTools = true;
        CalibrationController.Report report = new CalibrationController().run(new DigitalTwinMachine(20260910),
                request, (percent, message) -> { });
        if (report.chosen == null || !report.targetReached || report.enabled) {
            throw new IllegalStateException("Offline independent-plant validation failed.");
        }
        String directory = arguments.length == 0 ? "corexy-offline-reports" : arguments[0];
        java.nio.file.Path saved = new PrecisionStore(Paths.get(directory)).saveReport(report);
        System.out.println(String.format(Locale.ROOT,
                "SIMULATION ONLY: P95 %.6f -> %.6f mm; max %.6f -> %.6f mm; RMS %.6f -> %.6f mm",
                report.baselineFinal.p95, report.correctedFinal.p95,
                report.baselineFinal.maximum, report.correctedFinal.maximum,
                report.baselineFinal.rms, report.correctedFinal.rms));
        System.out.println("Selected: " + report.chosen.getLayers());
        System.out.println("Report: " + saved.toAbsolutePath());
    }
}
